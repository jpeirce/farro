# V2 Review of FARRO M2+ Architecture Design

**To:** FARRO Project Lead, CEO
**From:** External Consultant (Clojure Architect)
**Date:** 2025-11-16
**Subject:** V2 Review of FARRO M2+ Architecture Design & Implementation Readiness

This document is my second and more detailed review of the proposed architecture for FARRO, based on `architecture_design.md` (v2) and our preceding review cycle. My objective is to stress-test the design for coherence, robustness, and implementation readiness from an expert Clojure perspective.

### 1. High-Level Verdict

The v2 design is a significant improvement and demonstrates a strong grasp of the core problem. The decision to refactor around a central `FarroEnvelope` and a `job` module is absolutely correct.

However, the design is **not yet "good enough to ship"** as a foundation. While the major structural elements are in place, critical details regarding state management, idempotency, and concurrency remain ambiguous. These are not cosmetic issues; they are potential landmines that will surface during implementation or, worse, in production.

**My confidence level is high in the architectural *direction*, but low in its current *specification*.** With the refinements outlined below, this can become a world-class foundation.

### 2. Major Strengths

The architect has correctly identified the primary source of future complexity and addressed it head-on.

1.  **Central `FarroEnvelope` Abstraction:** This is the single best decision in the design. By creating a canonical, in-memory representation of a job, the design decouples the messy reality of the filesystem from the core business logic. This makes the daemons dramatically simpler and, more importantly, highly testable.
2.  **Clear Module Boundaries:** The proposed separation of `fs.clj` (primitives), `job.clj` (lifecycle), and `agentd.clj`/`managerd.clj` (business logic) is excellent. This is idiomatic Clojure: small modules with clear responsibilities. This structure will age well because it allows each part of the system to be replaced or upgraded independently.
3.  **Incremental Refactoring Plan:** The phased approach—building the canonical model first (Phase 1), then adding resilience (Phase 2), then features (Phase 3)—is pragmatic and correct. It prioritizes paying down architectural debt before building new features on a shaky foundation.

### 3. Risks and Missing Pieces (by Category)

#### **Data Model & Schema**

*   **Issue:** The `FarroEnvelope` still intermixes immutable prompt data (e.g., `:rubric`, `:allowed-paths`) with mutable lifecycle data (e.g., `:status`, `:attempt`). The design implies the entire `:persisted` map is written back to `job.json` on every commit.
*   **Why it's a problem:** This is inefficient and risks corrupting the original, immutable prompt data. `job.json` should only contain data related to the job's *lifecycle*, not the definition of the job itself.
*   **Severity:** **Annoying.** It will lead to data duplication and potential integrity issues.
*   **Recommendation:** Refine the `FarroEnvelope` to have three distinct top-level keys:
    *   `:prompt` (immutable data loaded from `prompt.json`).
    *   `:lifecycle` (mutable state that maps directly to `job.json`).
    *   `:runtime` (transient, in-memory state like lock handles).
    `commit-job!` should then *only* write the `:lifecycle` map to `job.json`.

#### **Concurrency & Process Model**

*   **Issue:** The idempotency and recovery logic for `job/load-job` is not fully specified. The v2 design states the watchdog will clean up stale locks in `incoming/`, but this is insufficient.
*   **Why it's a problem:** A slow-but-healthy worker could have its job stolen by a watchdog that misinterprets a long-running claim operation as a crash. The `load-job` function itself must be robust against race conditions during the "lock -> move" transition.
*   **Severity:** **Landmine.** This can lead to jobs being processed twice or dropped entirely in a high-load or slow-filesystem environment.
*   **Recommendation:** The `load-job` transaction must be more atomic. A safer pattern is: **1. Create lock -> 2. Write a `:status :in-progress` update to `job.json` -> 3. Move the directory.** This way, even if the process crashes before the move, the job is already marked as claimed, and the watchdog has a reliable on-disk signal to inspect.

*   **Issue:** The design constrains the manager to a single instance for auto-planning but doesn't offer a path forward.
*   **Why it's a problem:** This is a significant single point of failure (SPOF) and a performance bottleneck. While acceptable for an MVP, the architecture should not make it difficult to solve later.
*   **Severity:** **Annoying** (for now), but becomes a major architectural constraint later.
*   **Recommendation:** Acknowledge this limitation explicitly. The `job/create-job!` function should use a mechanism that is safe for concurrent callers, even if only one is used initially. For example, creating the job in a temporary, uniquely named directory first and then atomically moving it into the `jobs/` and then `incoming/` queue.

#### **Error Handling, Retries, and Backpressure**

*   **Issue:** The job status state machine is defined in a table, but there is no proposal for how to *enforce* it in code. A series of `if`/`case` statements in the `job.clj` module would be brittle.
*   **Why it's a problem:** Unenforced state machines lead to invalid states. An engineer could easily add a new transition that violates the intended logic, leading to stuck or corrupted jobs.
*   **Severity:** **Landmine.** This is a classic source of difficult-to-debug production issues.
*   **Recommendation:** The design should mandate a data-driven enforcement mechanism. See the Clojure-specific critique below.

### 4. Clojure-Specific Critique

The design is generally aligned with Clojure principles, but we can make it more idiomatic and robust.

*   **Data-Driven State Machine:** Instead of prose, the state machine should be represented as data. This is a perfect use case for a map that defines legal transitions:
    ```clojure
    (def legal-transitions
      {;; :from-status -> #{:to-status-1, :to-status-2}
       :queued       #{:in-progress}
       :in-progress  #{:succeeded :failed :queued :stale}
       :stale        #{:queued :killed}})
    ```
    The `commit-job!` function would then consult this map before writing a new status, throwing an error on an illegal transition. This is testable, explicit, and easy to update. For more complex logic based on the actor, consider `clojure.spec` or a multimethod: `(defmulti transition (fn [envelope actor] [(:status envelope) actor]))`.

*   **Component Lifecycle Management:** The design relies on JVM shutdown hooks (`.addShutdownHook`) to stop daemons. This is a weak pattern in a componentized system. It's hard to test and doesn't compose well.
    *   **Recommendation:** The design should recommend using a proper component lifecycle library like **`integrant`** or **`mount`**. This would allow the entire system (daemons, audit writers, etc.) to be started, stopped, and reset from the REPL, dramatically improving developer ergonomics and testability. The `-main` function would simply be responsible for starting the system defined by the library.

*   **REPL Friendliness:** The current design is getting better, but the proposed `job.clj` module is the key to a great REPL experience. An engineer should be able to `(def my-job (job/load-job "path/to/job"))` at the REPL, inspect the envelope, manually transform it `(assoc-in my-job [:persisted :status] :succeeded)`, and then `(job/commit-job! my-job)` to see the result on disk. The design correctly enables this, which is a major strength.

### 5. Implementation Readiness

**No, an engineer cannot reasonably start Phase 1 yet.** The ambiguities around the data model and idempotency are significant enough that they would be forced to make architectural decisions on the fly.

**Top 3 Decisions to Nail Down Before Coding:**

1.  **Finalize the `FarroEnvelope` structure:** Decide on the `{:prompt, :lifecycle, :runtime}` structure. This impacts the entire `job.clj` module.
2.  **Formalize the State Machine Enforcement:** Decide *how* the state machine will be enforced in code (data map, spec, multimethod).
3.  **Define the `load-job` and `commit-job!` Transactions:** Specify the exact, ordered, atomic (via temp files) steps for `load-job` and `commit-job!` to guarantee idempotency.

The milestone scoping (Phase 1/2/3) is sensible, but only *after* the core `job.clj` design is solidified.

### 6. Prioritized Recommendations

*   **P0 (Blocking): Refine `FarroEnvelope` Data Model.**
    *   **Change:** Update the design doc to split the envelope into three parts: `:prompt` (immutable), `:lifecycle` (persisted to `job.json`), and `:runtime` (transient).
    *   **Why:** Prevents data corruption and clarifies the source of truth for job state vs. job definition.

*   **P0 (Blocking): Define Idempotent `job.clj` Transactions.**
    *   **Change:** Add a sub-section under "Idempotency and Crash Recovery" that specifies the exact, ordered, atomic (via temp files) steps for `load-job` and `commit-job!`.
    *   **Why:** This is the core robustness guarantee of the entire system. It cannot be left ambiguous.

*   **P1 (High Priority): Mandate a Data-Driven State Machine.**
    *   **Change:** Update the "Job Status State Machine" section to require that the transition logic be enforced using a data structure (like a map) within `job.clj`.
    *   **Why:** Prevents invalid state transitions and makes the lifecycle logic explicit and testable.

*   **P1 (High Priority): Recommend a Component Lifecycle Library.**
    *   **Change:** Add a note in the "Architecture" section recommending `integrant` or `mount` to manage daemon lifecycles instead of shutdown hooks.
    *   **Why:** Dramatically improves testability, REPL-driven development, and compositional reasoning.

*   **P2 (Medium Priority): Formalize `job.json` Schema Definition.**
    *   **Change:** Add a small section defining the fields and types for `job.json` v1.0.0, explicitly including `:schema-version`.
    *   **Why:** Provides clarity and a foundation for future schema evolution.

### 7. Optional: Alternative Architectures

The proposed architecture is sound. The core idea of separating concerns via a canonical data model is the correct path. No major alternative is warranted. The focus should be on refining and implementing the current design with the rigor outlined above.
