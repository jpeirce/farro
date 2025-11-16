### V3 Review of FARRO Architecture Design

**To:** FARRO Architecture Lead
**From:** External Consultant
**Date:** 2025-11-16
**Subject:** Final Review of Architecture Design v2.0

This document is my final review of the `architecture_design_v2.0.md`. The design's goal is to create a robust, implementable specification for the core job lifecycle by resolving the critical ambiguities identified in my previous review.

**High-Level Verdict: This is an excellent design document. It is a significant improvement that directly and correctly addresses all previous P0 feedback. The architecture is now sound, and the specification is clear enough to proceed with implementation. My confidence level in this design is high.**

The following feedback is intended to refine the final operational details and de-risk the implementation phase, not to block it.

### Evaluation by Dimension

*   **Problem Framing & Requirements: Excellent.** The document correctly restates the problem and focuses on the highest-risk areas: the data model and transactional integrity. The requirements and non-goals are clear and appropriate.
*   **Architecture & Data Model: Excellent.**
    *   The `FarroEnvelope` split into `:prompt`, `:lifecycle`, and `:runtime` is exactly the right model. It provides a clean, compile-time separation of concerns that will prevent a whole class of bugs.
    *   The data-driven state machine (`legal-transitions` map) is simple, explicit, and robust. It's the correct, idiomatic Clojure approach.
    *   The transactional sequences for `load-job` and `commit-job!` are well-specified and correctly handle the most likely crash scenarios.
*   **Operational Concerns: Good, but with gaps.** The design is robust, but its *observability* is underspecified. More importantly, it introduces a dependency on a "watchdog" process for recovery, but the logic of this critical component is not defined.
*   **Implementation Risk & Sequencing: Good.** The implementation roadmap correctly prioritizes building and testing the core `job.clj` module first. The highest remaining risk is not in the specified design, but in the *unspecified* recovery logic and the complexity of writing tests that can reliably simulate system crashes to prove the transactional logic holds.

### Prioritized Feedback

#### **Red Flags / Critical Issues**

None. The design is ready for implementation.

#### **Important Improvements**

1.  **The "Watchdog" is now the most critical unspecified component.** The entire recovery strategy depends on a "watchdog" process that can identify and requeue abandoned jobs. This logic is non-trivial and must be specified. An unreliable watchdog can be as dangerous as no watchdog at all (e.g., by incorrectly flagging a slow job as "stale").
    *   **Recommendation:** Add a new section to the design document titled "7. Watchdog and Recovery Logic" (and renumber subsequent sections). This section must define:
        *   **Stale Job Identification:** The precise mechanism for determining a lock is stale. A simple file lock is not enough. The best practice is to write the process ID (PID) of the worker into the `.lock` file. The watchdog can then check if that PID is still active on the host.
        *   **Recovery Action:** What happens when a stale job is found. The proposed action should be to move the job directory to a `stale/` queue, not directly back to `incoming/`. This prevents immediate re-processing and allows for manual inspection if a job is repeatedly failing. The manager can then decide to requeue it from `stale/`.
        *   **Scope:** Define which directories the watchdog scans (e.g., `incoming/` for jobs locked but never moved, and all `in-progress/{agent-id}/` directories).

2.  **Observability is an architectural concern, not an afterthought.** The system will be nearly impossible to debug and operate in production without structured, queryable logs.
    *   **Recommendation:** Add a subsection on "Observability" that mandates structured logging (e.g., JSON logs written to stdout). Every log entry related to a job's lifecycle **must** contain a consistent set of fields: `{ "job_id": "...", "actor": "agentd/worker-1", "event": "commit_start", "from_status": "in-progress", "to_status": "succeeded" }`. This is non-negotiable for a production-grade distributed system.

3.  **The Schema Versioning Process is Incomplete.** The design correctly adds a `:schema-version` field to `job.json`. However, it does not specify what a worker should do if it encounters a version it doesn't understand.
    *   **Recommendation:** In the `job.json` schema definition, add a policy statement: "If a worker loads a `job.json` with a schema version it does not recognize, it **must not** attempt to process it. It should immediately release its lock and log a critical error." This fail-fast approach prevents data corruption from older workers trying to process newer job formats.

### Top 3 Questions the Team Must Answer

1.  **What is the precise, testable contract for the Watchdog?** (How does it acquire the PID? What happens if it can't read the PID? What OS dependencies does this introduce?)
2.  **Beyond logs, what are the key metrics required to monitor system health?** (e.g., `jobs.queued.count`, `jobs.succeeded.rate`, `watchdog.recoveries.total`). This will inform what needs to be instrumented during implementation.
3.  **What is the testing strategy for proving the crash-recovery logic?** (How will you reliably simulate a process crash between step 4 and step 5 of the `load-job` transaction in an automated test?)
