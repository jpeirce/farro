# FARRO Architecture Design v3.2

**Version:** 3.2
**Status:** Final
**Author:** /architect
**Date:** 2025-11-16

> **Note on v3.2:** This is the final implementation-ready design. It incorporates critical feedback from the v3.1 consultant review. The transaction logic has been hardened to use a **self-contained lock**, eliminating the "orphaned lock" failure mode. This version also adds explicit sections on the global locking protocol and operational dependencies.

## 1. Overview

This document specifies the architecture for the FARRO agent orchestration system (M2+). It refines the v2 design based on multiple reviews, focusing on adding critical operational specifications to create a robust and unambiguous foundation for implementation.

The core of the system is a file-system-based job queue. Agents and managers are stateless daemons that operate on these jobs. This design ensures robustness through three key concepts:
1.  A **Canonical In-Memory Data Model** (`FarroEnvelope`) that separates immutable, mutable, and transient state.
2.  **Idempotent, Atomic Transactions** for loading and committing job state changes.
3.  A **Data-Driven State Machine** that enforces a valid job lifecycle.

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
(Unchanged from v3.1)

## 5. Job Status State Machine
(Unchanged from v3.1)

## 6. Idempotent Transactions

The `job.clj` functions for loading and committing jobs are implemented as ordered, atomic transactions. The lock is a `.lock` directory created *inside* the job directory itself, making the job a self-contained unit.

### 6.1. `load-job` Transaction (Revised for v3.2)

1.  **Scan for Jobs:** Scan the `incoming/` directory for job directories (e.g., `job-123/`).
2.  **Atomically Acquire Lock:** For a candidate job, attempt to create a self-contained lock directory: `incoming/job-123/.lock`. This `mkdir` operation is atomic.
    -   If `mkdir` succeeds, the worker has acquired the lock.
    -   If `mkdir` fails (directory already exists), another actor has the lock. The worker **must** abort and try another job.
3.  **Write Lock Metadata:** Write a file inside the lock directory (e.g., `pid`) containing the worker's Process ID.
4.  **Verify Job State:** Read `incoming/job-123/job.json` and verify its `:status` is `:queued`.
    -   If not, the state is inconsistent. The worker **must** release its lock (by removing the `.lock` directory) and log a critical error.
5.  **Atomically Move Job (Ownership Commit):** Atomically move the entire job directory from `incoming/job-123/` to `in-progress/{agent-id}/{job-id}/`. The `.lock` directory moves with it. This is the primary commit point.
6.  **Update Status:** After the move, update `job.json` (now in the `in-progress` directory) to set `:status` to `:in-progress`.
7.  **Construct Envelope:** Construct and return the `FarroEnvelope`.

### 6.2. `commit-job!` Transaction (Revised for v3.2)

1.  Validate the state transition against `legal-transitions`.
2.  Write the updated `:lifecycle` map to a temp file (`job.json.tmp`) in the job's `in-progress` directory.
3.  Atomically move the temp file to `job.json`.
4.  Atomically move the job directory from `in-progress/{agent-id}/{job-id}/` to its terminal location (e.g., `completed/succeeded/`).
5.  **Release Lock:** Remove the `.lock` directory from the job's new, terminal location. This is the final step.

## 7. Operational Concerns

### 7.1. Watchdog and Recovery Logic (Revised for v3.2)

*   **Scope:** The watchdog periodically scans all `in-progress/{agent-id}/` and `stale/` directories. It looks for job directories containing a `.lock` directory.
*   **Stale Job Identification:** A job is "stale" if it contains a `.lock` directory and the PID within that lock does not correspond to a running process.
*   **Recovery Process:**
    1.  When the watchdog finds a stale job, it **must** first acquire the job's lock itself (see Global Locking Protocol, 7.4).
    2.  It validates the job status is `:in-progress` or `:stale`.
    3.  It updates the job's status to `:stale` in `job.json`.
    4.  It moves the job directory to `jobs/stale/` and releases its lock.
*   **Manager Responsibility for Stale Jobs:** The `managerd` periodically scans `jobs/stale/`. To process a job, it must first acquire its lock. It can then requeue (move to `incoming/`, set status to `:queued`) or kill (move to `completed/killed/`, set status to `:killed`) based on policy.

### 7.2. Observability
(Unchanged from v3.1)

### 7.3. Schema Versioning Policy
(Unchanged from v3.1)

### 7.4. Global Locking Protocol (New in v3.2)

To prevent race conditions between daemons, system-wide adherence to a single locking protocol is mandatory.

**Any process or daemon (`agentd`, `watchdogd`, `managerd`) that intends to read or modify a job directory MUST first acquire the canonical lock for that job.**

The lock is always the `.lock` directory located directly inside the job directory being accessed. Lock acquisition is performed via an atomic `mkdir`.

### 7.5. Operational Assumptions and Dependencies (New in v3.2)

This system's correctness relies on specific behaviors of the underlying filesystem and operating environment.

*   **Filesystem Primitives:** The design's atomicity guarantees depend entirely on the atomicity of `mkdir` and `mv` (rename) system calls on a single filesystem. Moving directories across different filesystems is not atomic and is not supported.
*   **Resource Limits:** The design assumes sufficient disk space and inodes are available. If the disk fills up, filesystem operations will fail, and the system's behavior will be unpredictable. **Operators are responsible for monitoring disk space and inode usage.**
*   **Process Supervision:** The design specifies three long-running daemons. It assumes an external process supervisor (e.g., `systemd`, `supervisorctl`, Kubernetes) is in place to ensure these daemons are running and are restarted on failure.

## 8. Implementation Roadmap & Handoffs (Final)

### Phase 1: Solidify the Core (`job.clj`)
*   **Ticket 1 (/engineersr):** Implement `FarroEnvelope`, `job.json` schema v1.0.0, and state machine logic.
*   **Ticket 2 (/engineersr):** Implement the final, atomic `load-job` and `commit-job!` transactions using the **self-contained `.lock` directory** as specified in v3.2.

### Phase 2: Build the Daemons
*   **Ticket 3 (/engineerjr):** Implement `agentd.clj` main loop, adhering to the Global Locking Protocol.
*   **Ticket 4 (/engineerjr):** Implement `managerd.clj` main loop, including logic for processing the `stale/` directory, adhering to the Global Locking Protocol.
*   **Ticket 5 (/engineersr):** Implement `watchdogd.clj` daemon, adhering to the Global Locking Protocol.

### Phase 3: System Integration & Hardening
*   **Ticket 6 (/engineersr):** Implement a shared logging library enforcing the structured JSON policy.
*   **Ticket 7 (/architect, /engineersr):** Implement a component lifecycle library (e.g., `integrant`).
*   **Ticket 8 (/review):** Holistic code review and development of a crash-simulation test suite targeting the failure modes identified in all reviews.

## 9. Appendix: Feedback Not Implemented

All blocking and strong suggestions from the v3.0 and v3.1 reviews have been incorporated into this final design.
