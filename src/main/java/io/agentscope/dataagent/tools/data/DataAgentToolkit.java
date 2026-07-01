/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.tools.data;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 面向 Agent 的数据分析原语工具包：列出已配置的数据源、描述表、预览 SQL 查询、渲染图表。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li><b>独立模式</b>（无 DataSource）：仅 list_data_sources 有完整实现，
 *       describe_table 和 run_sql_preview 返回 "not implemented"。</li>
 *   <li><b>连接模式</b>（注入 DataSource）：所有工具通过 JDBC 真实执行，
 *       describe_table 返回表结构 + 采样数据，run_sql_preview 返回 markdown 表格。</li>
 * </ul>
 */
public final class DataAgentToolkit {

    private static final Logger log = LoggerFactory.getLogger(DataAgentToolkit.class);
    private static final int DEFAULT_ROW_LIMIT = 100;
    private static final int MAX_ROW_LIMIT = 500;

    private final DataSourceRegistry registry;
    private final ChartRenderer chartRenderer;
    private final DataSource dataSource;

    /**
     * @param registry 数据源注册表（必填）
     * @param chartRenderer 图表渲染器（必填）
     * @param dataSource 可选 JDBC DataSource；为 null 时 SQL 工具返回 stub
     */
    public DataAgentToolkit(
            DataSourceRegistry registry,
            ChartRenderer chartRenderer,
            DataSource dataSource) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.chartRenderer = Objects.requireNonNull(chartRenderer, "chartRenderer");
        this.dataSource = dataSource;
        if (dataSource != null) {
            log.info("DataAgentToolkit: JDBC DataSource 已注入，describe_table / run_sql_preview 可用");
        } else {
            log.info("DataAgentToolkit: 无 JDBC DataSource，SQL 工具为 stub 模式");
        }
    }

    @Tool(
            name = "list_data_sources",
            description =
                    """
                    List the data sources the admin has configured for this deployment. Each entry \
                    is reported as 'id | kind | label — description (tags)'. Call this before \
                    drafting any SQL so you pick a source the runtime actually knows about. \
                    Returns 'none' when no sources are configured.\
                    """)
    public String listDataSources() {
        List<io.agentscope.dataagent.tools.data.DataSource> all = registry.list();
        if (all.isEmpty()) {
            return "none: no data sources are configured. Ask the operator to seed"
                    + " dataagent.data.sources in agentscope.json.";
        }
        StringBuilder sb = new StringBuilder();
        for (io.agentscope.dataagent.tools.data.DataSource ds : all) {
            sb.append(ds.id()).append(" | ").append(ds.kind()).append(" | ").append(ds.label());
            if (ds.description() != null && !ds.description().isBlank()) {
                sb.append(" — ").append(ds.description());
            }
            if (!ds.tags().isEmpty()) {
                sb.append(" (").append(String.join(",", ds.tags())).append(")");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(
            name = "describe_table",
            description =
                    """
                    Return the column schema (name, type, nullable) and a short row sample for a \
                    table in a configured data source. Use after list_data_sources to confirm the \
                    columns you intend to project / filter / group by.\
                    """)
    public String describeTable(
            @ToolParam(name = "source_id", description = "Data source id from list_data_sources")
                    String sourceId,
            @ToolParam(
                            name = "table",
                            description = "Fully-qualified table name as understood by the source")
                    String table) {
        Optional<io.agentscope.dataagent.tools.data.DataSource> ds = registry.findById(sourceId);
        if (ds.isEmpty()) {
            return "error: unknown source_id '" + sourceId + "'";
        }
        if (table == null || table.isBlank()) {
            return "error: table must not be blank";
        }
        if (dataSource == null) {
            return "not implemented: describe_table requires a connector module (see DataAgent docs)";
        }
        return describeTableJdbc(table);
    }

    @Tool(
            name = "run_sql_preview",
            description =
                    """
                    Execute a read-only SQL query against a configured data source and return the \
                    first N rows as a small markdown table. Only SELECT and WITH statements are \
                    accepted. The connector enforces a row cap of 500 rows.\
                    """)
    public String runSqlPreview(
            @ToolParam(name = "source_id", description = "Data source id from list_data_sources")
                    String sourceId,
            @ToolParam(name = "sql", description = "SELECT-only SQL statement") String sql,
            @ToolParam(
                            name = "row_limit",
                            description = "Max rows to return; defaults to 100, hard cap 500",
                            required = false)
                    Integer rowLimit) {
        Optional<io.agentscope.dataagent.tools.data.DataSource> ds = registry.findById(sourceId);
        if (ds.isEmpty()) {
            return "error: unknown source_id '" + sourceId + "'";
        }
        if (sql == null || sql.isBlank()) {
            return "error: sql must not be blank";
        }
        String trimmed = sql.trim();
        String lower = trimmed.toLowerCase();
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            return "error: only SELECT / WITH statements are allowed";
        }
        // 安全检查：禁止 DDL/DML 关键词
        String[] forbidden = {"insert ", "update ", "delete ", "drop ", "alter ",
                "create ", "truncate ", "merge ", "replace "};
        for (String kw : forbidden) {
            if (lower.contains(kw)) {
                return "error: forbidden keyword '" + kw.trim() + "' detected; only SELECT allowed";
            }
        }
        if (dataSource == null) {
            return "not implemented: run_sql_preview requires a connector module (see DataAgent docs)";
        }
        int limit = rowLimit != null ? Math.min(rowLimit, MAX_ROW_LIMIT) : DEFAULT_ROW_LIMIT;
        return runSqlPreviewJdbc(trimmed, limit);
    }

    @Tool(
            name = "render_chart",
            description =
                    """
                    Render a chart from a Vega-Lite spec. chart_type is one of \
                    line | bar | area | scatter. The spec must include the data inline. Returns a \
                    short status string; the SPA renders the chart client-side from the same spec.\
                    """)
    public String renderChart(
            @ToolParam(name = "chart_type", description = "line | bar | area | scatter")
                    String chartType,
            @ToolParam(
                            name = "vega_lite_spec",
                            description = "Vega-Lite JSON spec including inline data")
                    String vegaLiteSpec) {
        return chartRenderer.render(chartType, vegaLiteSpec);
    }

    // -----------------------------------------------------------------
    //  JDBC 实现
    // -----------------------------------------------------------------

    private String describeTableJdbc(String tableName) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            StringBuilder sb = new StringBuilder();

            // 1) 列信息
            sb.append("Table: ").append(tableName).append("\nColumns:\n");
            try (ResultSet cols = meta.getColumns(null, null,
                    tableName.toUpperCase(), null)) {
                if (!cols.isBeforeFirst()) {
                    // 尝试小写
                    cols.close();
                }
            }
            try (ResultSet cols = meta.getColumns(null, null,
                    tableName.toUpperCase(), null)) {
                boolean hasColumns = false;
                while (cols.next()) {
                    hasColumns = true;
                    String colName = cols.getString("COLUMN_NAME");
                    String colType = cols.getString("TYPE_NAME");
                    int colSize = cols.getInt("COLUMN_SIZE");
                    String nullable = "YES".equals(cols.getString("IS_NULLABLE"))
                            ? "NULL" : "NOT NULL";
                    sb.append(String.format("  %-25s %-15s (%d) %s\n",
                            colName, colType, colSize, nullable));
                }
                if (!hasColumns) {
                    sb.append("  (no columns found; check table name)\n");
                }
            }

            // 2) 采样数据 (前 5 行)
            sb.append("\nSample rows (LIMIT 5):\n");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT * FROM " + sanitizeIdentifier(tableName) + " LIMIT 5")) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();

                // 表头
                List<String> headers = new ArrayList<>();
                List<Integer> widths = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    String h = rsmd.getColumnName(i);
                    headers.add(h);
                    widths.add(Math.max(h.length(), 10));
                }

                // 收集数据行
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i);
                        if (val == null) val = "NULL";
                        row.add(val);
                        widths.set(i - 1, Math.max(widths.get(i - 1),
                                Math.min(val.length(), 30)));
                    }
                    rows.add(row);
                }

                // 渲染表头
                sb.append("|");
                for (int i = 0; i < colCount; i++) {
                    sb.append(" ").append(padRight(headers.get(i), widths.get(i))).append(" |");
                }
                sb.append("\n|");
                for (int i = 0; i < colCount; i++) {
                    sb.append("-".repeat(widths.get(i) + 2)).append("|");
                }
                sb.append("\n");

                // 渲染数据
                for (List<String> row : rows) {
                    sb.append("|");
                    for (int i = 0; i < colCount; i++) {
                        String val = row.get(i);
                        if (val.length() > 30) val = val.substring(0, 27) + "...";
                        sb.append(" ").append(padRight(val, widths.get(i))).append(" |");
                    }
                    sb.append("\n");
                }
                if (rows.isEmpty()) {
                    sb.append("(empty table)\n");
                }
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("describeTable failed: table={}, error={}", tableName, e.getMessage());
            return "error: describe_table failed — " + e.getMessage();
        }
    }

    private String runSqlPreviewJdbc(String sql, int rowLimit) {
        // 强制追加 LIMIT（如果原 SQL 没有）
        String lowerSql = sql.toLowerCase();
        if (!lowerSql.contains("limit") && !lowerSql.contains("fetch")) {
            sql = sql + " LIMIT " + rowLimit;
        }
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(rowLimit);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int colCount = rsmd.getColumnCount();

                // 列名与宽度
                List<String> headers = new ArrayList<>();
                List<Integer> widths = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    String h = rsmd.getColumnName(i);
                    headers.add(h);
                    widths.add(Math.max(h.length(), 8));
                }

                // 收集数据
                List<List<String>> rows = new ArrayList<>();
                int rowCount = 0;
                while (rs.next() && rowCount < rowLimit) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        String val = rs.getString(i);
                        if (val == null) val = "NULL";
                        row.add(val);
                        widths.set(i - 1, Math.max(widths.get(i - 1),
                                Math.min(val.length(), 35)));
                    }
                    rows.add(row);
                    rowCount++;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Query returned ").append(rowCount).append(" row(s):\n\n");

                // Markdown 表格
                sb.append("|");
                for (int i = 0; i < colCount; i++) {
                    sb.append(" ").append(padRight(headers.get(i), widths.get(i))).append(" |");
                }
                sb.append("\n|");
                for (int i = 0; i < colCount; i++) {
                    sb.append("-".repeat(widths.get(i) + 2)).append("|");
                }
                sb.append("\n");
                for (List<String> row : rows) {
                    sb.append("|");
                    for (int i = 0; i < colCount; i++) {
                        String val = row.get(i);
                        if (val.length() > 35) val = val.substring(0, 32) + "...";
                        sb.append(" ").append(padRight(val, widths.get(i))).append(" |");
                    }
                    sb.append("\n");
                }
                return sb.toString().stripTrailing();
            }
        } catch (Exception e) {
            log.error("run_sql_preview failed: sql={}, error={}", sql, e.getMessage());
            return "error: SQL execution failed — " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------
    //  辅助方法
    // -----------------------------------------------------------------

    private static String sanitizeIdentifier(String name) {
        // 只允许字母数字和下划线
        return name.replaceAll("[^a-zA-Z0-9_]", "");
    }

    private static String padRight(String s, int len) {
        if (s.length() >= len) return s;
        return s + " ".repeat(len - s.length());
    }
}
