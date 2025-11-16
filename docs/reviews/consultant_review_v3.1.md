# Consultant Review of FARRO Architecture Design v3.1

**Reviewer:** /consultant
**Date:** 2025-11-16

## 1. Summary & Verdict

**Objective:** The design specifies a file-system-based job queue in Clojure, prioritizing correctness and crash-robustness over high performance or availability.

**Verdict:** The v3.1 revision is **fundamentally sound** and a significant improvement over v3.0. The revised `load-job` transaction correctly uses atomic primitives (`mkdir`, `mv`) to solve the critical race conditions and state ambiguity issues identified in the previous review. The design is now a solid foundation for the stated goals.

However, the design's reliance on an external, decoupled lock directory introduces a new set of subtle but critical failure modes that are not addressed. The system's robustness now hinges on correctly managing these orphaned locks, which the current design fails to do.

## 2. Evaluation

### Red Flags / Critical Issues

**1. The Orphaned Lock Problem**

The core transaction model is critically flawed by its lock management strategy. The lock directory (`incoming/job-123.lock`) is decoupled from the job directory (`incoming/job-123/`) that it protects. The lock is only removed at the very end of the `commit-job!` transaction (Step 5), *after* the job has been moved to its terminal state.

-   **Failure Mode:** If a worker successfully processes a job and moves it to `completed/succeeded/`, but crashes before it can remove the lock directory from `incoming/`, you now have an **orphaned lock**.
-   **Consequence:** The `watchdogd` will eventually discover this orphaned lock. It will see the PID inside is dead and declare the job "stale." It will then attempt to recover the job by moving it to `jobs/stale/`. This will fail because the job directory is no longer in `incoming/` or `in-progress/`. The watchdog will get an error, and the orphaned lock file will remain indefinitely, causing repeated, failed recovery attempts. This is a memory leak on the filesystem that also creates significant operational noise.

-   **Remedy:** The lock must be **self-contained within the job directory**.
    1.  Modify the `load-job` transaction: Instead of creating `incoming/job-123.lock`, the worker should atomically `mkdir incoming/job-123/.lock`. The lock is now part of the directory it protects.
    2.  The atomic `mv` in Step 5 of `load-job` now carries the lock along with the rest of the job data to the `in-progress` directory.
    3.  The `commit-job!` transaction no longer needs a separate step to find and remove a lock from `incoming/`. The lock is inside the job directory it just moved to `completed/`. It can be safely removed from there, or even left as an audit marker.

This change makes the entire job a single, self-contained unit, eliminating the entire class of orphaned lock failures.

### Important Improvements

**1. Underspecified Daemon Coordination**

The system now has three daemons (`agentd`, `managerd`, `watchdogd`) operating on the same shared filesystem state. The design does not specify the locking protocol required to prevent them from interfering with each other.

-   **Risk:** The `watchdogd` is described as acquiring "its own lock," and the `managerd` is described as "scanning the `stale/` directory." What if the watchdog is in the middle of moving a job to `stale/` when the manager begins its scan?
-   **Remedy:** Add a new top-level section named **"Global Locking Protocol."** This section must state that *any* process that wishes to operate on a job directory (worker, watchdog, or manager) **must** first acquire the canonical lock for that job using the `mkdir .lock` pattern. This ensures all actors play by the same rules and prevents race conditions between the daemons.

**2. Ignoring Filesystem Dependencies and Failure Modes**

The design's elegance comes from its use of the filesystem, but it also inherits all of the filesystem's operational burdens. The document completely ignores these.

-   **Risks:** What happens if the disk partition holding `/jobs` fills up? Every single file operation (`mkdir`, `mv`, `write`) will begin to fail, and the behavior of the system will be undefined. What about inode exhaustion? What are the performance and atomicity guarantees of `mv` on the target filesystem (e.g., across different devices)?
-   **Remedy:** Add a section on **"Operational Assumptions & Dependencies."** This section must explicitly state:
    -   The system's correctness relies on the atomicity of `mkdir` and `mv` (within the same filesystem).
    -   Operators are responsible for monitoring disk space and inode usage for the partition hosting the job directories.
    -   The failure mode for a full disk is catastrophic, and external monitoring is the primary mitigation.

### Nice-to-Haves

**1. Process Supervision is Undefined**

The design introduces three long-running daemons but does not mention how they will be monitored, kept running, or gracefully updated. This is a significant operational gap. While a full solution may be out of scope, the design should acknowledge it.

-   **Suggestion:** Add a note in the "Operational Concerns" section acknowledging that a process supervision strategy (e.g., `systemd`, `supervisorctl`, or a container orchestrator like Kubernetes) is required to manage the lifecycle of the daemons themselves.

## 3. Top 3 Unanswered Questions to De-risk Implementation

1.  **Locking:** How will you modify the transaction logic to prevent orphaned lock files from being created when a worker crashes post-completion but pre-cleanup? (See "Red Flags").
2.  **Deployment & Supervision:** How will you ensure the `agentd`, `managerd`, and `watchdogd` processes are always running, and how will you deploy updates to them without halting the system or losing in-flight work?
3.  **Resource Exhaustion:** What is the precise, specified behavior of the `load-job` and `commit-job` functions when a filesystem operation fails due to a full disk or inode exhaustion? Will they retry? Will they crash the worker?