---
name: sql-analysis
description: Execute a bounded, auditable MySQL analysis workflow with schema inspection, HITL-gated SQL, validation, and a concise result report.
---

# SQL Analysis Skill

Use this workflow for quantitative questions about configured business data.

## Workflow

1. Restate the metric, grouping, time window, and filters in one sentence. Ask one clarifying question and stop if a required dimension is ambiguous.
2. Call `list_data_sources`, choose the relevant MySQL source, then call `describe_table` only for the tables needed by the metric.
3. Determine data freshness before interpreting relative dates. If the requested calendar window is empty, use the latest available data window and disclose the substitution.
4. Draft explicit-column, read-only SQL. Always bound the time range; never use `SELECT *` and never invent a table or column.
5. Call `run_sql_preview` through HITL. Use no more than three SQL executions: one primary query, one sanity check, and one comparison query when needed.
6. Validate row count, null group keys, totals, and min/max dates. Do not report a surprising result until the SQL or data caveat is resolved.
7. Call `render_chart` only when a chart materially improves a trend comparison.

## Response

Return these sections:

- **Answer:** headline finding with the exact time window.
- **Result:** at most 15 rows or a concise summary.
- **SQL:** the exact primary query that was executed.
- **Validation:** source, tables, sanity check, freshness, and caveats.
- **Business interpretation:** two or three evidence-backed observations, without claiming causality that the data cannot support.

## Guardrails

- Every reported number must trace to an approved `run_sql_preview` result.
- Treat missing dates as zero only when business semantics justify it; otherwise label them as missing observations.
- Stop querying once the requested metric, one validation check, and the comparison needed for interpretation are available.
