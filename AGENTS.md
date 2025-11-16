# FARRO Agent System Overview

This document provides a high-level overview of the FARRO agent system. For detailed global policies governing all agents, refer to the [FARRO Global Agent Policy](docs/AGENTS_POLICY.md).

## Agent Roles and Policies

The FARRO orchestrator utilizes various specialized AI agents, each with a defined role and set of responsibilities. Each agent operates under the [FARRO Global Agent Policy](docs/AGENTS_POLICY.md) and its own role-specific policy.

### Role Policy Locations (Convention)
While the final agentd executable will have these paths hardcoded, these pointers are for human and bootstrapping agents to understand the project structure.

*   [Architect Policy](agents/Architect/AGENTS-ROLE.md)
*   [Senior Engineer Policy](agents/SeniorEngineer/AGENTS-ROLE.md)
*   Code Reviewer: agents/CodeReviewer/AGENTS-ROLE.md
*   Manager: agents/Manager/AGENTS-ROLE.md
*   Doc Writer: agents/DocWriter/AGENTS-ROLE.md
*   Junior Engineer: agents/JuniorEngineer/AGENTS-ROLE.md
