# Design Feedback & Action Plan for FARRO M2+ Architecture

**To:** Architect
**From:** Project Manager/CEO
**Date:** 2025-11-16
**Subject:** Feedback and Action Plan on M2+ Architecture Design

First, great work on the initial `architecture_design.md`. The core strategy of introducing the `FarroEnvelope` and the `job.clj` module is absolutely the right direction. It provides a strong foundation for building out the remaining milestones.

We've had external consultants review the plan, and their feedback is both positive and highly valuable. They agree with the core architectural approach. Their recommendations are focused on "tightening the screws" by making definitions more precise, state transitions more explicit, and failure modes more predictable. This is a great outcome, as it validates our direction while helping us de-risk the implementation.

This document outlines their key feedback and the action plan for incorporating it.

### **Consultant Feedback Summary**

The review highlighted the following areas for improvement:

1.  **`FarroEnvelope` Structure:** They recommend explicitly separating persisted fields (what goes into `job.json`) from runtime-only fields (`:lock-handle`, `:job-path`, etc.) to prevent serialization errors.
2.  **State Transition Logic:** The design needs a formal state machine for job statuses (`:status`). We need to define which transitions are legal (e.g., `:in-progress` -> `:succeeded`) and which actor (agent, manager, watchdog) is permitted to make them.
3.  **Idempotency & Crash Recovery:** The design should explicitly define how the system recovers if a crash occurs mid-operation within `job/load-job` or `job/commit-job!`.
4.  **`job.json` Schema:** The schema for `job.json` should be formally defined from the start, including a `:schema-version` field to manage future migrations.
5.  **Configuration Details:** Magic numbers like backpressure thresholds ("80%") should be made configurable in `agents-config.json`. The design should also clarify the concurrency strategy for the manager's auto-planner (e.g., singleton process).
6.  **Spec Alignment:** The design should explicitly state that the `job.clj` module is the enforcement point for the project's hardened JSON principles.

### **Viability and Path Forward**

The design is viable, and the consultant feedback is essential. We will incorporate their recommendations before the engineering team begins implementation. Addressing these points now is far more efficient than discovering them as bugs or architectural dead-ends later.

### **Action Plan for Architect**

Please create a revised version of `docs/architecture_design.md` that incorporates the feedback above. Specifically, the updated document should include:

1.  **[ ] Refine the `FarroEnvelope` Data Model:** Restructure it to clearly distinguish between persisted and runtime fields. A nested `:persisted` map is a good approach.
2.  **[ ] Add a State Machine Definition:** Include a table or diagram that clearly defines the job status lifecycle, its legal transitions, and the actors responsible for each change.
3.  **[ ] Specify Idempotency and Recovery Rules:** Add a section detailing the recovery process for the `job` module. For example, define what happens on restart if a lock file exists but the job directory is still in the `incoming` queue.
4.  **[ ] Formalize the `job.json` Schema:** Add a section defining the initial `job.json` structure and mandate the inclusion of a `:schema-version` field.
5.  **[ ] Make Configuration Explicit:** Update the design to note that operational values (like backpressure thresholds) will be sourced from `agents-config.json`. Add a constraint note regarding the manager's concurrency model for auto-planning.

Once the design document is updated with these clarifications, the engineering team will be green-lit to begin Phase 1. This will ensure they have a clear, unambiguous, and robust plan to execute.

Let me know if you have any questions. This is a solid plan, and with these refinements, it will be exceptional.
