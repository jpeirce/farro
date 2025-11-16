# FARRO Architecture Design v2.0

**Version:** 2.0
**Status:** Proposed
**Author:** /architect
**Date:** 2025-11-16

## 1. Overview

This document specifies the architecture for the FARRO agent orchestration system (M2+). It refines the v1 design based on feedback from the v2 consultant review, focusing on creating a robust and unambiguous foundation for implementation.

The core of the system is a file-system-based job queue. Agents and managers are stateless daemons that operate on these jobs. The central architectural challenge is ensuring job processing is robust, idempotent, and resilient to crashes, especially given the reliance on the filesystem as the source of truth.

This design achieves that robustness through three key concepts:
1.  A **Canonical In-Memory Data Model** (`FarroEnvelope`) that separates immutable, mutable, and transient state.
2.  **Idempotent, Atomic Transactions** for loading and committing job state changes.
3.  A **Data-Driven State Machine** that enforces a valid job lifecycle.

## 2. Key Requirements & Goals

*   **Robustness:** The system must not lose or duplicate jobs, even if daemons crash at any point in the lifecycle.
*   **Clarity:** The responsibilities of each module and the structure of key data models must be explicit and unambiguous.
*   **Testability:** The core logic, especially job lifecycle management, must be highly testable in isolation, without requiring a full system environment.
*   **Evolvability:** The design must provide a stable foundation that can be extended with new features in the future.

### Non-Goals (for this version)

*   **High-Availability Manager:** This design does not solve the single-point-of-failure (SPOF) for the manager daemon. That is a future scalability concern.
*   **Performance Optimization:** The focus is on correctness and robustness, not high-throughput performance.
*   **Specific Library Choices:** While recommendations are made (e.g., for a component lifecycle library), this document specifies the *what*, not the *how* of every implementation detail.

## 3. Core Components & Modules

The system is composed of several Clojure modules, each with a distinct responsibility.

*   `fs.clj`: Contains low-level, stateless filesystem primitives (e.g., `list-dirs`, `read-json`, `write-json`, `atomic-move`). It has no knowledge of jobs or agents.
*   `job.clj`: The heart of the system. It defines the `FarroEnvelope` data model and the core lifecycle functions (`load-job`, `commit-job!`, `create-job!`). This module is responsible for the transactional integrity of a job.
*   `agentd.clj`: The main business logic for the agent daemon. It uses `job.clj` to acquire a job, executes the work, and uses `job.clj` to commit the result.
*   `managerd.clj`: The main business logic for the manager daemon. It monitors the system and creates new jobs using `job.clj`.

## 4. Data Model: The FarroEnvelope

To eliminate ambiguity between immutable and mutable data, the in-memory representation of a job, the `FarroEnvelope`, **must** be a map with three distinct, top-level keys.

```clojure
;; In-memory representation of a job
{:prompt    {...}  ; Immutable data from prompt.json
 :lifecycle {...}  ; Mutable data, maps to job.json
 :runtime   {...}} ; Transient, in-memory-only state
```

*   **:prompt (Immutable):** This map contains the original, unchanging definition of the job. It is loaded once from `prompt.json` when the job is first created or loaded. **It is never written back to disk.**
    *   Example fields: `:prompt-text`, `:allowed-paths`, `:rubric`.

*   **:lifecycle (Mutable, Persisted):** This map contains all the mutable state of a job that is persisted to disk. It is the **only** part of the envelope that is read from and written to `job.json`.
    *   Example fields: `:job-id`, `:status`, `:attempts`, `:created-at`, `:updated-at`, `:schema-version`.

*   **:runtime (Transient):** This map contains temporary, in-memory state required for processing, such as file locks or database handles. It is created when a job is loaded and discarded when it is released. **It is never written to disk.**
    *   Example fields: `:lock-handle`, `:job-dir-path`.

This structure ensures a clean separation of concerns and prevents accidental corruption of the original job prompt. The `commit-job!` function will be responsible for writing *only* the `:lifecycle` map to `job.json`.

## 5. Job Status State Machine

The job lifecycle is governed by a state machine. To ensure this is not just a convention but is enforced by the system, the `job.clj` module **must** implement a data-driven validation mechanism.

### 5.1. Job Statuses

*   `:queued`: The job is in the `incoming/` directory, ready to be processed.
*   `:in-progress`: The job has been locked by a worker and moved to its `in-progress/` directory.
*   `:succeeded`: The worker completed the job successfully.
*   `:failed`: The worker encountered a non-recoverable error.
*   `:stale`: A watchdog determined the job was abandoned while `:in-progress`.
*   `:killed`: The job was manually terminated.

### 5.2. State Transition Enforcement

A map **must** be used to define the set of legal transitions. The `commit-job!` function will consult this map before persisting a status change, throwing an exception if the transition is illegal.

```clojure
;; In job.clj
(def legal-transitions
  {;; :from-status -> #{:to-status-1, :to-status-2}
   :queued       #{:in-progress}
   :in-progress  #{:succeeded :failed :queued :stale}
   :stale        #{:queued :killed}
   :succeeded    #{} ;; Terminal state
   :failed       #{:queued :killed}
   :killed       #{}}) ;; Terminal state
```

This makes the state machine logic explicit, testable, and easy to modify safely.

## 6. Idempotent Transactions

The `job.clj` functions for loading and committing jobs are the most critical points for ensuring robustness. They **must** be implemented as ordered, atomic transactions to prevent race conditions and data loss.

### 6.1. `load-job` Transaction

The `load-job` function's purpose is to find a `:queued` job, lock it, mark it as `:in-progress`, and return a `FarroEnvelope` to the caller.

**Sequence of Operations:**

1.  **Scan `incoming/`:** Get a list of job directories.
2.  **Acquire Lock:** For a candidate job, attempt to acquire an exclusive file lock (e.g., by creating a `.lock` file using `java.nio.channels.FileChannel`). If the lock fails, move to the next candidate.
3.  **Read `job.json`:** Once the lock is held, read the `job.json` file. Verify its status is `:queued`.
4.  **Update Status to `:in-progress`:** Write the status change *back to the `job.json` file in the `incoming/` directory*. This is the critical step. If the process crashes after this, the watchdog will see an `:in-progress` job in the `incoming/` queue and know it needs recovery.
5.  **Atomic Move:** Atomically move the entire job directory from `incoming/` to the worker-specific `in-progress/{agent-id}/{job-id}` directory.
6.  **Construct Envelope:** Read `prompt.json`, construct the full `FarroEnvelope` with `:prompt`, `:lifecycle`, and `:runtime` (including the lock handle), and return it.

### 6.2. `commit-job!` Transaction

The `commit-job!` function's purpose is to persist the results of processing.

**Sequence of Operations:**

1.  **Validate State Transition:** Using the `legal-transitions` map, verify the requested status change (e.g., from `:in-progress` to `:succeeded`) is valid. Throw an exception if not.
2.  **Write to Temp File:** Serialize the `:lifecycle` portion of the `FarroEnvelope` to a temporary file (e.g., `job.json.tmp`) in the job's `in-progress` directory.
3.  **Atomic Move:** Atomically move/rename the temp file to `job.json`. This ensures `job.json` is never left in a partially written, corrupt state.
4.  **Move to Final Directory:** Atomically move the entire job directory from `in-progress/{agent-id}/{job-id}` to its terminal location (e.g., `completed/succeeded/` or `completed/failed/`).
5.  **Release Lock:** Close the file handle for the `.lock` file, releasing the lock.

## 7. Implementation Roadmap & Handoffs

### Phase 1: Solidify the Core (`job.clj`)

This phase must be completed before any daemon/worker logic is built.

*   **Ticket 1 (/engineersr): Implement the `FarroEnvelope` and `job.json` Schema.**
    *   Define the `job.json` schema v1.0.0, including `:schema-version`.
    *   Create helper functions in `job.clj` for creating an envelope, but do not implement `load-job` or `commit-job!` yet.
*   **Ticket 2 (/engineersr): Implement Data-Driven State Machine.**
    *   Add the `legal-transitions` map to `job.clj`.
    *   Implement the validation logic that will be used by `commit-job!`.
*   **Ticket 3 (/engineersr): Implement Idempotent `load-job` and `commit-job!`.**
    *   Implement the full, transactional logic for both functions as specified in Section 6.
    *   This ticket requires extensive testing of edge cases (crashes, race conditions).

### Phase 2: Build the Daemons

*   **Ticket 4 (/engineerjr): Implement `agentd.clj`.**
    *   Build the main loop for the agent daemon, using the now-stable `job.clj` to load and commit jobs.
*   **Ticket 5 (/engineerjr): Implement `managerd.clj`.**
    *   Build the main loop for the manager, using `job.clj` to create new jobs.

### Phase 3: System Integration & Hardening

*   **Ticket 6 (/architect, /engineersr): Implement Component Lifecycle.**
    *   Integrate a library like `integrant` or `mount` to manage the lifecycle of all system components (daemons, loggers, etc.). This will replace any temporary startup/shutdown logic.
*   **Ticket 7 (/review): Code Review and Stress Testing.**
    *   Perform a holistic review of the implemented `job.clj` and daemon logic.
    *   Develop a test suite that simulates crash conditions to verify the system's robustness.

## 8. Appendix: Feedback Not Implemented

All P0 and P1 feedback from the consultant's review have been incorporated into this design. The P2 recommendation to formalize the `job.json` schema has also been included in the implementation roadmap. No feedback was rejected.
