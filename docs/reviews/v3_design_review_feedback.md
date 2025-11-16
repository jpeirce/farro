# Review of FARRO Architecture Design v3.0

**Reviewer:** /review
**Date:** 2025-11-16

## 1. Summary of Change

This architecture document (v3.0) formalizes the FARRO agent orchestration system. It builds upon previous versions by adding critical operational specifications to ensure robustness, observability, and safe evolution. The core proposal is a file-system-based job queue where stateless daemons perform idempotent, atomic operations on jobs.

Key additions in v3 are:
-   A `watchdogd` daemon for detecting and recovering stale/abandoned jobs.
-   A strict structured logging policy (JSON over stdout) for improved observability.
-   A fail-fast schema versioning policy to prevent data corruption during upgrades.

The design aims to create a clear, testable, and operationally sound foundation for the FARRO system.

## 2. Evaluation

The v3 design is a significant step forward. It demonstrates a strong focus on correctness and operational reality, addressing many of the difficult, non-functional requirements that are essential for a production system. The principles of a data-driven state machine, idempotent transactions, and a clear data model (`FarroEnvelope`) are excellent.

The feedback below is intended to further refine and remove ambiguity from this solid foundation.

### Blocking Issues

These issues introduce significant ambiguity or risk and should be resolved before implementation begins.

**1. Critical Ambiguity in `load-job` Transaction Logic**

The sequence described in Section 6.1 for `load-job` has two weaknesses that could compromise the system's robustness.

-   **Non-Atomic Lock Acquisition:** Step 2, "Create a `.lock` file containing the worker's Process ID (PID)," is not an atomic operation on most filesystems. This creates a race condition where two workers could attempt to create a lock file for the same job simultaneously, with both potentially believing they have acquired the lock.
    -   **Suggestion:** Specify the use of an atomic operation for lock acquisition. The most common and portable method is `mkdir`, as it is an atomic "test-and-set" operation across POSIX-compliant filesystems. The lock would be a directory (e.g., `job-123.lock/`), and its successful creation would signal lock acquisition.

-   **Confusing State on Mid-Transaction Crash:** Step 4 updates the job's status to `:in-progress` *before* the job directory is atomically moved out of the `incoming/` directory in Step 5. If a worker crashes between these two steps, it leaves a job in the `incoming/` directory that is marked as `:in-progress`. While the watchdog is designed to eventually clean this up, it violates the principle that the `incoming/` directory represents a queue of ready-to-run jobs. This makes state analysis and manual intervention more complex.
    -   **Suggestion:** Revise the `load-job` transaction to make the state clearer at every step.
        1.  Scan `incoming/`.
        2.  **Atomically acquire lock** on a job (e.g., `mkdir .../{job-id}.lock`). If it fails, another worker has it; abort.
        3.  Read `job.json` and verify its status is `:queued`. If not, release the lock and abort.
        4.  **Atomically move** the job directory from `incoming/` to `in-progress/{agent-id}/{job-id}`. This is the primary commit point for taking ownership.
        5.  *After* the move is complete, update the `job.json` file (now in the `in-progress` directory) to set the status to `:in-progress`.
        6.  Construct and return the `FarroEnvelope`.

### Strong Suggestions

These are important issues that should be addressed to improve the design's clarity and robustness, but they are not strictly blocking.

**1. Clarify Lifecycle for Stale Jobs**

The watchdog moves stale jobs to `jobs/stale/`, and the `managerd` is responsible for them (Section 7.1). However, the state transitions for this process are not defined. This leaves ambiguity in how the manager should operate.

-   **Suggestion:** Explicitly add the potential state transitions from `:stale` to the `legal-transitions` map in Section 5.2. This would likely include:
    -   `:stale -> #{:queued, :killed}`
-   The document should also briefly describe the manager's actions: to requeue, it updates the status to `:queued` and moves the job directory back to `incoming/`; to kill, it updates to `:killed` and moves it to a terminal directory.

**2. Improve Stale Job Identification Robustness**

The watchdog identifies stale jobs by checking if the PID in the `.lock` file is running (Section 7.1). This is a good start, but it is vulnerable to a classic race condition where a process dies and the OS recycles its PID for a new, unrelated process. This could cause the watchdog to ignore a genuinely stale job.

-   **Suggestion:** To make this more robust in the future, consider enriching the lock file. Instead of just the PID, store a combination of `hostname:pid:timestamp`. While the current PID-only check may be acceptable for the initial implementation (given the "no HA" non-goal), this limitation should be acknowledged, and the design should recommend this more robust approach for future hardening.

### Nice-to-Haves

These are suggestions for polish or future improvement.

**1. Introduce a Job Execution Correlation ID**

The structured logging policy (Section 7.2) is excellent. To make it even more powerful for debugging, consider adding a `correlation_id` to the standard log fields.

-   **Suggestion:** Generate a unique ID for each *execution attempt* of a job. This ID would be created by the `managerd` when it first queues a job and could be regenerated on every retry from a `:stale` or `:failed` state. Including this in every log message would make it trivial to trace the lifecycle of a single execution attempt, even across different daemons, and differentiate it from previous failed runs of the same `job_id`.

## 3. Test Coverage

The implementation roadmap (Section 8) rightly includes a "crash-simulation test suite." This is critical.

To ensure full confidence, this test suite should explicitly target the edge cases identified in this review:
-   **Test Concurrent `load-job`:** Simulate multiple workers attempting to acquire the same job at the same time to prove the locking mechanism is atomic and correct.
-   **Test Crash during `load-job`:** Systematically simulate a worker crash at every step of the `load-job` transaction (especially before and after the atomic move) and verify that the watchdog correctly recovers the job to the `:stale` state.
-   **Test Crash during `commit-job!`:** Simulate crashes during the `commit-job!` transaction to ensure the job is not lost or left in an inconsistent state.
-   **Test Stale Job Recovery:** Simulate a worker dying without releasing its lock and verify the watchdog identifies it, marks it `:stale`, and moves it to the `stale/` directory.
-   **Test Manager Requeue:** Test the `managerd`'s ability to correctly pick up a `:stale` job and requeue it.
-   **Test Schema Version Mismatch:** Create jobs with old/invalid schema versions and verify that workers fail fast, log an error, and do not attempt to process them.