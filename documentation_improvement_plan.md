# Documentation Improvement Plan for FARRO Project

**Date:** 2025-11-16

This plan outlines concrete actions to address the "Strong Suggestions" and "Nice-to-haves" identified in the `review_report_for_manager.md`, incorporating feedback from the `review_of_doc_improvement_plan.md`. The goal is to enhance the clarity, consistency, and maintainability of the FARRO project's documentation.

## 1. Strong Suggestions - Action Plan

### 1.1. Consolidate Agent Policies
*   **Action:** Create a central `AGENTS_POLICY.md` document and refine existing agent policy documents.
*   **Details:**
    *   Move the "Prime Directive" and "Global Security & Safety Rules" from `AGENTS.md` into a new `docs/AGENTS_POLICY.md` file. This document will serve as the single source of truth for overarching agent principles.
    *   Update `AGENTS.md` to become a high-level overview of the agent system, providing links to `docs/AGENTS_POLICY.md` and the role-specific `agents/<Role>/AGENTS-ROLE.md` files.
    *   Ensure all `agents/<Role>/AGENTS-ROLE.md` files are updated to refer to `docs/AGENTS_POLICY.md` for global rules and focus solely on role-specific responsibilities and constraints.
*   **Benefit:** Reduces redundancy, improves consistency, and simplifies the maintenance of agent policies across the project.

### 1.2. Version Control for Documentation
*   **Action:** Formalize and communicate the process for versioning and reviewing documentation changes.
*   **Details:**
    *   **Create `CONTRIBUTING.md` if it doesn't exist.** Add a new section to `CONTRIBUTING.md` (or create the file if it's missing) detailing the expected process for proposing, reviewing, and merging documentation changes. This should mirror the code review process (e.g., pull requests, peer review).
    *   Reinforce the use of a clear versioning scheme (e.g., `vX.Y.md` for major architectural documents) and explain when new versions should be created.
*   **Benefit:** Ensures documentation accuracy, reliability, and that it evolves synchronously with the codebase.

### 1.3. Actionable Items Tracking
*   **Action:** Implement a systematic approach to track and manage actionable items derived from review documents.
*   **Details:**
    *   For every `consultant_review_vX.Y.md` or `design_feedback.md` that contains actionable recommendations, create corresponding issues in **GitHub Issues**.
    *   Each issue should clearly reference the specific suggestion in the review document.
    *   Assign ownership and target completion dates to these issues to ensure follow-through.
*   **Benefit:** Prevents valuable feedback from being overlooked and ensures that architectural and design improvements are systematically addressed.

## 2. Nice-to-Haves - Action Plan

### 2.1. Diagrams/Visualizations
*   **Action:** Identify key architectural concepts and create visual representations to enhance understanding.
*   **Details:**
    *   Prioritize creating diagrams for the job status state machine and the interaction flow between `agentd`, `managerd`, and `watchdogd`.
    *   Utilize **Mermaid** as the standardized text-based diagramming tool.
    *   Integrate these diagrams into the relevant architectural design documents (e.g., `docs/architecture_design_vX.Y.md`).
*   **Benefit:** Significantly improves the clarity and accessibility of complex system behaviors and interactions for all stakeholders.

### 2.2. Glossary
*   **Action:** Create a `docs/GLOSSARY.md` file to define key project terms.
*   **Details:**
    *   Compile a list of all domain-specific terms and acronyms used across the FARRO project documentation (e.g., FarroEnvelope, agentd, managerd, watchdogd, atomic move, poison pill job, etc.).
    *   Provide concise and consistent definitions for each term.
    *   Add links to `docs/GLOSSARY.md` from relevant top-level documents (e.g., `README.md`, `AGENTS.md`, `docs/architecture_design.md`).
    *   **Linking Strategy:** Terms should be linked on their first occurrence in a document, or when their definition is critical for understanding the immediate context.
*   **Benefit:** Facilitates quicker onboarding for new team members and ensures a consistent understanding of project terminology across the team.

---
This plan provides a roadmap for enhancing the FARRO project's documentation, making it more robust, accessible, and aligned with the project's evolving architecture.