# FARRO Architecture Design v2: M2 and Beyond

**Version:** 2.0
**Status:** Final
**Author:** Architect

This document incorporates feedback from the initial design review and external consultants. It provides a concrete and actionable plan for evolving the FARRO system from its M1 state to a production-ready orchestrator.

### **1. Core Principle & Architectural Vision**

The foundational principle of this design is the **separation of concerns**. The M1 implementation mixed business logic with filesystem I/O and state management. To build a robust, testable, and scalable system, we will refactor around a central, canonical data structure: the `FarroEnvelope`.

Daemons (`agentd`, `managerd`) will no longer interact directly with the filesystem for job lifecycle operations. Instead, they will delegate to a new, dedicated `job` module, making the daemons themselves stateless orchestrators that operate on pure data.

---

### **2. Architecture and Module Boundaries**

The module structure is updated to enforce the new separation of concerns.

*   `app/src/agents/orchestrator/`
    *   `core.clj`: (Unchanged) Provides low-level, stateless helpers.
    *   `fs.clj`: (Unchanged) Owns primitive filesystem operations (atomic moves, file locking).
    *   **`job.clj` (New Module):** The new heart of the system.
        *   **Responsibility:** Manages the entire lifecycle of a job on disk. It is the sole authority on loading, validating, canonicalizing, and committing job state. It understands the relationship between `prompt.json`, `job.json`, and the file-based queues.
        *   **Public API:**
            *   `load-job(job_dir_path) -> FarroEnvelope`
            *   `commit-job!(envelope) -> nil`
            *   `create-job!(prompt_data) -> FarroEnvelope`
    *   `agentd.clj` / `managerd.clj`: (Refactored) These modules become simpler. They are responsible for the core business logic of their respective roles and operate exclusively on the `FarroEnvelope` data structure provided by the `job` module.
    *   `cli.clj`: (Refactored) The entry point, responsible for parsing arguments and dispatching to the appropriate daemon.

**New Data Flow:**

`Daemon` -> `job/load-job` (Handles lock, move, read, validate) -> `Daemon` processes `FarroEnvelope` -> `job/commit-job!` (Handles write results, update `job.json`, move to next queue, release lock).

---

### **3. The FARRO Data Model**

#### **3.1. The `FarroEnvelope` (In-Memory Representation)**

The `FarroEnvelope` is a Clojure map representing a fully loaded, validated job. It is explicitly divided into persisted and runtime-only data.

```clojure
;; The FarroEnvelope is the canonical IN-MEMORY representation of a job.
{
  ;; --- Persisted State ---
  ;; This nested map is the source of truth for what is written to job.json.
  :persisted
  {
    :schema-version "1.0.0" ; REQUIRED, SemVer string.
    :job-id "job-20251116-120301-1234" ; REQUIRED
    :role :SeniorEngineer ; REQUIRED, Keyword
    :status :in-progress ; REQUIRED, Keyword
    :attempt 1 ; REQUIRED, Integer
    :created-at "2025-11-16T12:03:01Z" ; REQUIRED, ISO 8601 String
    :updated-at "2025-11-16T12:04:10Z" ; REQUIRED, ISO 8601 String
    :finalized-at nil ; OPTIONAL, ISO 8601 String
    :routing {:mode :role :next :CodeReviewer} ; REQUIRED, Map
    ;; The following are sourced from prompt.json and are immutable after creation.
    :rubric "Implement the change..." ; REQUIRED, String
    :allowed-paths ["src/" "docs/"] ; REQUIRED, Vector of strings
    :success-criteria "Tests pass." ; REQUIRED, String
  }

  ;; --- Payloads (Redaction-Ready) ---
  ;; These are held in memory and written to disk by commit-job!
  :result-payload "..." ; OPTIONAL, String
  :error-payload "..." ; OPTIONAL, String

  ;; --- Runtime-Only State (NEVER persisted) ---
  :job-path "/path/to/repo/agents/SeniorEngineer/in-progress/job-..." ; REQUIRED, String
  :lock-handle {:channel <FileChannel> ...} ; REQUIRED, Opaque handle from fs.clj
  :config-snapshot {:version "1.0.0" ...} ; REQUIRED, Immutable config for this run.
}
```

#### **3.2. `job.json` (On-Disk Representation)**

This file is the canonical source of truth for a job's lifecycle state.

*   **Schema:** The `job.json` file will contain all keys defined within the `:persisted` map of the `FarroEnvelope`.
*   **Versioning:** It **MUST** contain a `:schema-version` key (e.g., `"1.0.0"`). The `job/load-job` function will be responsible for handling different schema versions in the future.
*   **Creation/Merging:**
    *   On initial job creation (`job/create-job!`), `job.json` is created from the initial prompt data.
    *   On subsequent loads (`job/load-job`), `job.json` is the authoritative source for state. `prompt.json` is treated as an immutable artifact of the original request.

#### **3.3. Job Status State Machine**

The `:status` field follows a strict lifecycle.

| Current Status  | Allowed Next Status | Actor Triggering Change | Notes                               |
| :-------------- | :------------------ | :---------------------- | :---------------------------------- |
| (new)           | `:queued`           | `job/create-job!`       | Initial state upon creation.        |
| `:queued`       | `:in-progress`      | `job/load-job`          | When a worker claims the job.       |
| `:in-progress`  | `:succeeded`        | `job/commit-job!`       | Worker completes successfully.      |
| `:in-progress`  | `:failed`           | `job/commit-job!`       | Worker fails after all retries.     |
| `:in-progress`  | `:queued`           | `managerd` (Watchdog)   | Job is stale and requeued.          |
| `:in-progress`  | `:stale`            | `managerd` (Watchdog)   | Job is stale (intermediate state).  |
| `:stale`        | `:queued`           | `managerd` (Watchdog)   | Watchdog requeues a stale job.      |
| `:stale`        | `:killed`           | `managerd` (Watchdog)   | Watchdog kills an abandoned job.    |
| *any*           | `:killed`           | `agent-cli`             | Manual user intervention.           |

**Terminal States:** `:succeeded`, `:failed`, `:killed`. No transitions are allowed out of these states.

---

### **4. Idempotency and Crash Recovery**

The `job.clj` module MUST be resilient to crashes.

*   **`load-job` Idempotency:**
    *   The `load-job` function is a transactional sequence: **1. Lock -> 2. Move -> 3. Read/Snapshot**.
    *   If a crash occurs after (1) but before (2), the lock file will exist in the `incoming/` directory. On restart, another worker will fail to acquire the lock and skip the job. The watchdog will eventually find this job (as it never moves to `in-progress`) and can clean up the lock.
    *   If a crash occurs after (2), the job is in `in-progress/`. On daemon restart, a worker will find the job, successfully load it (as `job.json` still shows `:queued` or a non-terminal status), and proceed.
*   **`commit-job!` Idempotency:**
    *   The `commit-job!` function is a transactional sequence: **1. Write results -> 2. Update `job.json` -> 3. Move -> 4. Release Lock**.
    *   Writes to `result.md`/`error.md` and `job.json` MUST be atomic (write to a `.tmp` file then rename).
    *   If a crash occurs, the state on disk dictates recovery. For example, if `job.json` shows `:succeeded` but the job is still in `in-progress/`, the next `managerd` loop will see this and correctly route it to `completed/`.
*   **Orphaned Job Invariant:** A job in an `in-progress/` directory with a `status` of `:in-progress` and an `updated-at` timestamp older than the `stale_after` threshold (defined in `agents-config.json`) is considered **orphaned** and is eligible for recovery by the `managerd` watchdog.

---

### **5. Milestone Breakdown & Implementation Plan**

**Phase 1: The Canonical Envelope & Core Refactor (M2 Foundation)**

*   **Goal:** Establish the new architecture. This is the highest priority.
*   **Implementation Checklist:**
    1.  `[X]` **Create `app/src/agents/orchestrator/job.clj`:**
        *   `[ ]` Define the `FarroEnvelope` structure using `clojure.spec` for validation.
        *   `[ ]` Implement `load-job`, `commit-job!`, and `create-job!` according to the idempotency and state machine rules defined above.
        *   `[ ]` Ensure all JSON parsing and writing within this module conforms to the project's hardened JSON spec.
    2.  `[X]` **Refactor `agentd.clj` and `managerd.clj`:**
        *   `[ ]` Simplify their main loops to only call `job/load-job` and `job/commit-job!`. All business logic must operate on the `FarroEnvelope`.
    3.  `[X]` **Update Tests (`m1_test.clj`):**
        *   `[ ]` Refactor the test to align with the new, simpler daemon logic. The test should focus on setting up an initial state and verifying the final state, trusting the `job` module to handle the internals.

**Phase 2: Resilience & Watchdog (M2)**

*   **Implementation Checklist:**
    1.  `[X]` **Implement Watchdog in `managerd.clj`:**
        *   `[ ]` Add a loop to scan `in-progress` directories based on a configurable interval from `agents-config.json`.
        *   `[ ]` Use the "Orphaned Job Invariant" to detect and recover stale jobs.
    2.  `[X]` **Implement Retry Logic in `agentd.clj`:**
        *   `[ ]` When calling external providers, wrap the calls in a retry mechanism that uses the backoff policy from `agents-config.json`.
        *   `[ ]` On each failed attempt, update the envelope's `:persisted :attempt` count before calling `commit-job!`.

**Phase 3: Manager Auto-Plan & Backpressure (M4)**

*   **Implementation Checklist:**
    1.  `[X]` **Implement Auto-Plan in `managerd.clj`:**
        *   `[ ]` Add logic to the manager loop, gated by the `--auto-plan` flag.
        *   `[ ]` Queue depths and backpressure thresholds (`pause_at_percent`, `resume_at_percent`) MUST be loaded from `agents-config.json`.
        *   `[ ]` **Constraint:** The design will initially only support a single `managerd` process to avoid race conditions in auto-planning. This should be documented.
        *   `[ ]` When creating a job, call `job/create-job!` and include an `:origin :auto-plan` field in the initial prompt data.

---

### **6. Testing Strategy**

*   **Unit Tests (`job_test.clj`):** This is critical. Must test the `job` module's functions against all defined invariants, failure modes (e.g., malformed JSON), and crash-recovery scenarios.
*   **Integration Tests (`m1_test.clj`):** Evolve this into a full round-trip test using golden fixtures from a `test/fixtures/` directory.
*   **Snapshot Tests:** Use snapshot testing on the `:persisted` portion of the `FarroEnvelope` after loading from a fixture to prevent unintended schema drift.

---

### **7. Follow-up Work**

*   **For Engineers:**
    *   Implement the plan, starting with Phase 1.
    *   Ensure all new configuration values (thresholds, intervals) are added to `agents-config.json` and its schema.
*   **For Doc Writers:**
    *   Update `farro_spec.md` to reflect the formal definition of `job.json` and the job status state machine.
    *   Update `README.md` with any new CLI options.
    *   Formalize the `FarroEnvelope` as the canonical in-memory data structure in the spec.