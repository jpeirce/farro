# FARRO Project Glossary

This glossary defines key terms and acronyms used throughout the FARRO project documentation.

*   **Agentd:** The daemon responsible for executing individual jobs.
*   **Atomic Move:** A file system operation (typically `mv` or `rename`) that guarantees either the entire operation completes successfully, or it has no effect, even in the event of a system crash. Essential for maintaining data integrity in distributed systems.
*   **FarroEnvelope:** The canonical in-memory data model representing a job within the FARRO system. It separates immutable (`:prompt`), mutable (`:lifecycle`), and transient (`:runtime`) state.
*   **Managerd:** The daemon responsible for orchestrating jobs, including queuing, requeuing stale/failed jobs, and potentially auto-planning.
*   **Mermaid:** A JavaScript-based diagramming tool that uses Markdown-inspired text definitions to create diagrams and flowcharts. Used for visualizing architectural concepts in FARRO documentation.
*   **Poison Pill Job:** A job that is fundamentally broken and, if repeatedly processed, will continuously fail, consuming system resources and creating log noise.
*   **Watchdogd:** The daemon responsible for monitoring in-progress jobs, identifying stale or abandoned jobs, and initiating recovery processes.
