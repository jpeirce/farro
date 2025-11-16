# FARRO Architecture Design v3.0

**Version:** 3.0
**Status:** Approved
**Author:** /architect
**Date:** 2025-11-16

## 1. Overview

This document specifies the architecture for the FARRO agent orchestration system (M2+). It refines the v2 design based on the final consultant review, focusing on adding critical operational specifications to create a robust and unambiguous foundation for implementation.

The core of the system is a file-system-based job queue. Agents and managers are stateless daemons that operate on these jobs. This design ensures robustness through three key concepts:
1.  A **Canonical In-Memory Data Model** (`FarroEnvelope`) that separates immutable, mutable, and transient state.
2.  **Idempotent, Atomic Transactions** for loading and committing job state changes.
3.  A **Data-Driven State Machine** that enforces a valid job lifecycle.

This v3 document adds critical specifications for **Recovery, Observability, and Data Schema Evolution**.

## 2. Key Requirements & Goals

*   **Robustness:** The system must not lose or duplicate jobs, even if daemons crash at any point in the lifecycle.
*   **Clarity:** The responsibilities of each module and the structure of key data models must be explicit and unambiguous.
*   **Testability:** The core logic, especially job lifecycle management, must be highly testable in isolation.
*   **Operability:** The system must be observable and recoverable, allowing operators to debug issues and trust its automated recovery mechanisms.
*   **Evolvability:** The design must provide a stable foundation that can be extended with new features in the future.

### Non-Goals (for this version)

*   **High-Availability Manager:** This design does not solve the single-point-of-failure (SPOF) for the manager daemon.
*   **Performance Optimization:** The focus is on correctness and robustness, not high-throughput performance.
*   **Metrics and Alerting:** This design specifies logging, but a full metrics and alerting strategy is a future work item.

## 3. Core Components & Modules

The system is composed of several Clojure modules, each with a distinct responsibility.

*   `fs.clj`: Contains low-level, stateless filesystem primitives.
*   `job.clj`: The heart of the system. It defines the `FarroEnvelope` data model and the core lifecycle functions.
*   `agentd.clj`: The main business logic for the agent daemon.
*   `managerd.clj`: The main business logic for the manager daemon.
*   `watchdogd.clj`: A new daemon responsible for system recovery.

## 4. Data Model: The FarroEnvelope

The in-memory representation of a job, the `FarroEnvelope`, **must** be a map with three distinct, top-level keys.

```clojure
;; In-memory representation of a job
{:prompt    {...}  ; Immutable data from prompt.json
 :lifecycle {...}  ; Mutable data, maps to job.json
 :runtime   {...}} ; Transient, in-memory-only state
```

*   **:prompt (Immutable):** Loaded from `prompt.json`. Never written back.
*   **:lifecycle (Mutable, Persisted):** Read from and written to `job.json`.
*   **:runtime (Transient):** In-memory state, including lock handles and PIDs. Never written to disk.

## 5. Job Status State Machine

The job lifecycle is governed by a data-driven state machine to enforce valid state transitions.

### 5.1. Job Statuses

*   `:queued`, `:in-progress`, `:succeeded`, `:failed`, `:stale`, `:killed`

### 5.2. State Transition Enforcement

A map **must** be used to define the set of legal transitions. The `commit-job!` function will consult this map before persisting a status change.

```clojure
;; In job.clj
(def legal-transitions
  {;; :from-status -> #{:to-status-1, :to-status-2}
   :queued       #{:in-progress}
   :in-progress  #{:succeeded :failed :stale}
   :stale        #{:queued :killed}
   :succeeded    #{} ;; Terminal state
   :failed       #{:queued :killed}
   :killed       #{}}) ;; Terminal state
```

## 6. Idempotent Transactions

The `job.clj` functions for loading and committing jobs are implemented as ordered, atomic transactions.

### 6.1. `load-job` Transaction

The sequence for a worker to acquire a job:
1.  Scan `incoming/`.
2.  **Acquire Lock & Write PID:** Create a `.lock` file containing the worker's Process ID (PID).
3.  Read `job.json` and verify status is `:queued`.
4.  Update status to `:in-progress` in the `job.json` file within the `incoming/` directory.
5.  Atomically move the job directory to `in-progress/{agent-id}/{job-id}`.
6.  Construct and return the `FarroEnvelope`.

### 6.2. `commit-job!` Transaction

The sequence for a worker to commit a result:
1.  Validate the state transition against `legal-transitions`.
2.  Write the updated `:lifecycle` map to a temp file (`job.json.tmp`).
3.  Atomically move the temp file to `job.json`.
4.  Atomically move the job directory to its terminal location (e.g., `completed/succeeded/`).
5.  Release the file lock.

## 7. Operational Concerns

This section details the critical operational components required for a production-ready system.

### 7.1. Watchdog and Recovery Logic

A dedicated `watchdogd.clj` daemon is responsible for detecting and recovering abandoned jobs.

*   **Scope:** The watchdog periodically scans all `in-progress/{agent-id}/` directories and the central `incoming/` directory.
*   **Stale Job Identification:** A job is considered "stale" if its `.lock` file exists but the PID written inside it does not correspond to a running process on the host. This check prevents the watchdog from interfering with slow-but-healthy workers.
*   **Recovery Process:**
    1.  When the watchdog identifies a stale job, it first acquires its own lock on the job to prevent conflicts.
    2.  It validates that the job status is either `:in-progress` or `:queued`.
    3.  It updates the job's status to `:stale` by writing to `job.json`.
    4.  It moves the entire job directory to the `jobs/stale/` directory. This isolates the failed job for inspection and prevents immediate, potentially problematic, re-processing.
*   **Responsibility:** The `managerd` will be responsible for periodically scanning the `stale/` directory and deciding whether to requeue, kill, or ignore these jobs based on retry counts and other policies.

### 7.2. Observability

To ensure the system is debuggable and operable, it **must** implement structured logging.

*   **Format:** All log output **must** be a single JSON object per line, written to `stdout`.
*   **Standard Fields:** Every log entry related to a job's lifecycle **must** contain a standard set of fields to allow for effective filtering and correlation.
    ```json
    {
      "timestamp": "2025-11-16T22:45:10.123Z",
      "level": "info",
      "message": "Job status changed",
      "app": "farro",
      "actor": "agentd/worker-1",
      "job_id": "abc-123",
      "event": {
        "type": "state_transition",
        "from": "in-progress",
        "to": "succeeded"
      }
    }
    ```
*   **Policy:** This logging policy allows operators to easily answer questions like "Show me the full history of job abc-123" or "Show me all errors from agentd workers in the last hour."

### 7.3. Schema Versioning Policy

To prevent data corruption during software updates, a strict schema versioning policy is required.

*   **Schema Version Field:** The `:lifecycle` map (and corresponding `job.json`) **must** contain a `:schema-version` field (e.g., "1.0.0").
*   **Fail-Fast Policy:** When a worker loads a job using `load-job`, it **must** compare the job's `:schema-version` with the version it was built to understand. If the versions do not match, the worker **must not** attempt to process the job. It must immediately release its lock, log a critical error, and move on to the next job. This prevents newer workers from mishandling old job formats and vice-versa.

## 8. Implementation Roadmap & Handoffs

### Phase 1: Solidify the Core (`job.clj`)
*   **Ticket 1 (/engineersr):** Implement `FarroEnvelope`, `job.json` schema v1.0.0, and state machine logic.
*   **Ticket 2 (/engineersr):** Implement Idempotent `load-job` and `commit-job!` with PID-aware locking.

### Phase 2: Build the Daemons
*   **Ticket 3 (/engineerjr):** Implement `agentd.clj` main loop.
*   **Ticket 4 (/engineerjr):** Implement `managerd.clj` main loop.
*   **Ticket 5 (/engineersr):** Implement `watchdogd.clj` daemon with stale job recovery logic.

### Phase 3: System Integration & Hardening
*   **Ticket 6 (/engineersr):** Implement a shared logging library that enforces the structured JSON logging policy. Integrate it into all daemons.
*   **Ticket 7 (/architect, /engineersr):** Implement a component lifecycle library (e.g., `integrant`) to manage all daemons.
*   **Ticket 8 (/review):** Holistic code review and development of a crash-simulation test suite.

## 9. Appendix: Feedback Not Implemented

All P0, P1, and P2 feedback from the consultant's v2 and v3 reviews have been incorporated into this design. No feedback was rejected.
