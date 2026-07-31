---
description: Tenant-isolated data-analysis assistant. Connects to internal SQL sources, drafts queries, validates results, and renders charts.
workspace:
  mode: shared
model: longcat
maxIters: 20
tools: [filesystem, shell_execute, memory_search, memory_get, session_search]
---
