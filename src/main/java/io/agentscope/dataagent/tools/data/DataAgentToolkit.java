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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 面向 Agent 的数据分析原语工具包：列出已配置的数据源、描述表、预览 SQL 查询、渲染图表。
 * <p>支持两种模式：
 * <ul>
 *   <li><b>独立模式</b>（无 JdbcTemplate）：仅 list_data_sources 有完整实现，
 *       describe_table 和 run_sql_preview 返回 "not implemented"。</li>
 *   <li><b>连接模式</b>（注入 JdbcTemplate）：所有工具通过 Spring JdbcTemplate 真实执行，
 *       describe_table 返回表结构 + 采样数据，run_sql_preview 返回 markdown 表格。</li>
 * </ul>
 *
 * <p>优化历史：从原生 JDBC 改为 Spring JdbcTemplate，消除了 try-with-resources 样板代码
 * 和手动 ResultSet 遍历；markdown 表格渲染抽到 {@link MarkdownTables} 工具类。
 */
public final class DataAgentToolkit {

    private static final Logger log = LoggerFactory.getLogger(DataAgentToolkit.class);
    private static final int DEFAULT_ROW_LIMIT = 100;
    private static final int MAX_ROW_LIMIT = 500;

    private final DataSourceRegistry registry;
    private final ChartRenderer chartRenderer;
    // 用 JdbcTemplate 替代原生 DataSource——它封装了连接管理和 ResultSet 遍历
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param registry 数据源注册表（必填）
     * @param chartRenderer 图表渲染器（必填）
     * @param jdbcTemplate 可选 JDBC 模板；为 null 时 SQL 工具返回 stub
     */
    public DataAgentToolkit(
            DataSourceRegistry registry,
            ChartRenderer chartRenderer,
            JdbcTemplate jdbcTemplate) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.chartRenderer = Objects.requireNonNull(chartRenderer, "chartRenderer");
        this.jdbcTemplate = jdbcTemplate;
        if (jdbcTemplate != null) {
            log.info("DataAgentToolkit: JdbcTemplate 已注入，describe_table / run_sql_preview 可用");
        } else {
            log.info("DataAgentToolkit: 无 JdbcTemplate，SQL 工具为 stub 模式");
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

    /**
     * 校验 source_id → 校验 table → 检查 JdbcTemplate → 执行 describeTableJdbc
     * 返回列信息 + 采样数据（前 5 行）。
     */
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
        if (jdbcTemplate == null) {
            return "not implemented: describe_table requires a connector module (see DataAgent docs)";
        }
        return describeTableJdbc(table);
    }

    /**
     * 三层防护（像银行三道门）：
     * ① 白名单：只让 SELECT/WITH 开头（查询客户才进门）
     * ② 黑名单：搜身查 DDL/DML 关键词（带危险品不让进）
     * ③ 额度限制：setMaxRows 限制行数（取款限额）
     *
     * <p>注意：LIMIT 不再拼接字符串（避免方言不兼容 Oracle），改用 JDBC 的 setMaxRows。
     */
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
        // ① 白名单：只允许 SELECT / WITH 开头
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            return "error: only SELECT / WITH statements are allowed";
        }
        // ② 黑名单：禁止 DDL/DML 关键词（防止 DROP TABLE 等注入）
        List<String> forbidden = List.of(
                "insert ", "update ", "delete ", "drop ", "alter ",
                "create ", "truncate ", "merge ", "replace ");
        for (String kw : forbidden) {
            if (lower.contains(kw)) {
                return "error: forbidden keyword '" + kw.trim()
                        + "' detected; only SELECT allowed";
            }
        }
        if (jdbcTemplate == null) {
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
    //  JdbcTemplate 实现
    // -----------------------------------------------------------------

    /**
     * 描述表结构：列信息（用 DatabaseMetaData）+ 采样数据（SELECT * LIMIT 5）。
     *
     * <p>JdbcTemplate 的 {@code extract(DatabaseMetaData)} 回调专门用于拿元数据，
     * 避免手动管理 Connection。
     */
    private String describeTableJdbc(String tableName) {
        try {
            // 1) 列信息：用 JdbcTemplate 的 Connection 回调拿 DatabaseMetaData
            StringBuilder sb = new StringBuilder();
            sb.append("Table: ").append(tableName).append("\nColumns:\n");

            List<String[]> columns = jdbcTemplate.execute(
                    (ConnectionCallback<List<String[]>>) conn -> {
                        List<String[]> cols = new ArrayList<>();
                        java.sql.DatabaseMetaData meta = conn.getMetaData();
                        try (java.sql.ResultSet rs = meta.getColumns(
                                null, null, tableName.toUpperCase(), null)) {
                            while (rs.next()) {
                                cols.add(new String[]{
                                        rs.getString("COLUMN_NAME"),
                                        rs.getString("TYPE_NAME"),
                                        String.valueOf(rs.getInt("COLUMN_SIZE")),
                                        "YES".equals(rs.getString("IS_NULLABLE"))
                                                ? "NULL" : "NOT NULL"
                                });
                            }
                        }
                        return cols;
                    });

            if (columns.isEmpty()) {
                sb.append("  (no columns found; check table name)\n");
            } else {
                for (String[] c : columns) {
                    sb.append(String.format("  %-25s %-15s (%s) %s%n",
                            c[0], c[1], c[2], c[3]));
                }
            }

            // 2) 采样数据（前 5 行）——用 setMaxRows 限制，不拼 LIMIT 字符串
            sb.append("\nSample rows (LIMIT 5):\n");
            String safeTable = sanitizeIdentifier(tableName);
            List<Map<String, Object>> sampleRows = jdbcTemplate.execute(
                    (ConnectionCallback<List<Map<String, Object>>>) conn -> {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        try (java.sql.Statement stmt = conn.createStatement()) {
                            stmt.setMaxRows(5);  // 用 JDBC API 限制行数，避免 LIMIT 方言差异
                            try (java.sql.ResultSet rs = stmt.executeQuery(
                                    "SELECT * FROM " + safeTable)) {
                                java.sql.ResultSetMetaData rsmd = rs.getMetaData();
                                int colCount = rsmd.getColumnCount();
                                while (rs.next()) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    for (int i = 1; i <= colCount; i++) {
                                        row.put(rsmd.getColumnName(i), rs.getString(i));
                                    }
                                    rows.add(row);
                                }
                            }
                        }
                        return rows;
                    });

            List<String> headers = sampleRows.isEmpty()
                    ? List.of()
                    : new ArrayList<>(sampleRows.get(0).keySet());
            List<List<String>> stringRows = new ArrayList<>();
            for (Map<String, Object> row : sampleRows) {
                List<String> vals = new ArrayList<>();
                for (String h : headers) {
                    Object v = row.get(h);
                    vals.add(v == null ? "NULL" : v.toString());
                }
                stringRows.add(vals);
            }
            if (sampleRows.isEmpty()) {
                sb.append("(empty table)\n");
            } else {
                sb.append(MarkdownTables.render(headers, stringRows, null));
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("describeTable failed: table={}, error={}", tableName, e.getMessage());
            return "error: describe_table failed — " + e.getMessage();
        }
    }

    /**
     * 执行只读 SQL，返回 markdown 表格。
     *
     * <p>关键优化：
     * <ul>
     *   <li>用 {@code setMaxRows(rowLimit)} 限制行数，不拼接 LIMIT 字符串
     *       —— 解决 Oracle 等不支持 LIMIT 的方言问题</li>
     *   <li>用 JdbcTemplate 的 {@code query(StatementCallback)} 自定义 Statement 创建
     *       —— 在创建时调 setMaxRows</li>
     * </ul>
     */
    private String runSqlPreviewJdbc(String sql, int rowLimit) {
        try {
            // 用 ConnectionCallback 拿 Connection 自己建 Statement，
            // 在建完 Statement 后立刻调 setMaxRows——这样无论什么数据库方言都生效
            List<Map<String, Object>> rows = jdbcTemplate.execute(
                    (ConnectionCallback<List<Map<String, Object>>>) conn -> {
                        List<Map<String, Object>> result = new ArrayList<>();
                        try (java.sql.Statement stmt = conn.createStatement()) {
                            stmt.setMaxRows(rowLimit);
                            try (java.sql.ResultSet rs = stmt.executeQuery(sql)) {
                                java.sql.ResultSetMetaData rsmd = rs.getMetaData();
                                int colCount = rsmd.getColumnCount();
                                while (rs.next()) {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    for (int i = 1; i <= colCount; i++) {
                                        row.put(rsmd.getColumnName(i), rs.getString(i));
                                    }
                                    result.add(row);
                                }
                            }
                        }
                        return result;
                    });

            String title = "Query returned " + rows.size() + " row(s):";
            return MarkdownTables.renderFromMaps(rows, rowLimit, title);
        } catch (Exception e) {
            log.error("run_sql_preview failed: sql={}, error={}", sql, e.getMessage());
            return "error: SQL execution failed — " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------
    //  辅助方法
    // -----------------------------------------------------------------

    /** 只允许字母数字和下划线，防止表名注入。 */
    private static String sanitizeIdentifier(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "");
    }
}
