# Consultant Review of FARRO Architecture Design v3.2

**Reviewer:** /consultant
**Date:** 2025-11-16

## 1. Summary & Verdict

**Objective:** The design specifies a file-system-based job queue in Clojure, prioritizing correctness and crash-robustness.

**Verdict:** **Approved for implementation.** The v3.2 design is sound. The architect has successfully integrated the feedback from previous reviews, and the result is a robust, logical, and defensible plan. The self-contained locking model resolves the last critical design flaw.

The document is now a solid blueprint. The remaining risks are not in the design itself, but in its implementation and operationalization. My feedback will focus on guiding the engineering team to mitigate these risks as they build.

## 2. Evaluation

### Red Flags / Critical Issues

None. The design is approved.

### Important Implementation Risks to Mitigate

The design is sound, but the implementation team must be aware of the following challenges inherent to this architecture.

**1. Risk: Thundering Herd**

The `load-job` transaction begins with "Scan the `incoming/` directory." In a system with many idle workers, this can lead to a "thundering herd" problem, where dozens or hundreds of processes simultaneously scan the same directory and contend for the same locks, creating unnecessary load on the filesystem.

-   **Mitigation:** The implementation of the worker's main loop should include a simple mechanism to de-synchronize workers. Before re-scanning the `incoming/` directory, each worker should sleep for a short, randomized interval (e.g., 100-500ms). This is a trivial-to-implement and effective way to prevent herd behavior at a moderate scale.

**2. Risk: Poison Pill Jobs**

The design relies on a `managerd` to requeue failed jobs. If a job is fundamentally broken (a "poison pill"), it can enter an infinite loop: `fail -> recover -> requeue -> fail`. This consumes system resources and creates significant log noise.

-   **Mitigation:** The `managerd`'s retry policy is a critical component, not an optional one. The implementation must include:
    1.  A `:retry-count` field in the `job.json` schema.
    2.  Logic in the `managerd` to increment this count every time it processes a job from the `stale/` directory.
    3.  A configurable `max-retries` threshold. When `retry-count` exceeds this threshold, the manager **must** transition the job to `:killed` instead of `:queued`.

**3. Risk: Configuration Sprawl**

The system specifies multiple daemons, each of which will require configuration (e.g., file paths, scan intervals, agent IDs, retry policies). This is not part of the architecture spec, but it's a critical implementation detail.

-   **Mitigation:** Before writing significant code for the daemons, the engineering team should define a simple, unified configuration strategy. A single EDN or JSON configuration file passed to each daemon on startup is a standard and effective approach. This avoids scattering configuration across environment variables or command-line arguments, which quickly becomes unmanageable.

## 3. Top 3 Questions for the Implementation Team

Answering these questions will de-risk the project's next phase.

1.  **Throttling:** What is your specific strategy for staggering worker scans of the `incoming/` directory to prevent a "thundering herd" problem as the number of workers scales?
2.  **Poison Pills:** How will you concretely implement the `max-retries` policy in the `managerd` to prevent a broken job from causing an infinite recovery loop? What will be the default retry limit?
3.  **Configuration:** How will the daemons be configured in a production environment? Will you use a central configuration file, environment variables, or another mechanism?

---
This design is ready. Good work.
