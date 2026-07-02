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

import java.util.List;
import java.util.Map;

/**
 * 把 SQL 查询结果（List&lt;Map&gt;）渲染成 markdown 表格的工具类。
 *
 * <p>抽取出来消除 {@link DataAgentToolkit} 里 describeTable 和 runSqlPreview 的重复渲染逻辑。
 * 没有任何框架能帮你"把 SQL 结果转成 markdown"，所以这部分必须手写，但只写一次就够了。
 */
public final class MarkdownTables {

    /** 单列最大宽度，超过截断显示 "..." */
    private static final int MAX_CELL_WIDTH = 35;

    /** 列名最小宽度，避免表头挤在一起 */
    private static final int MIN_HEADER_WIDTH = 8;

    private MarkdownTables() {}

    /**
     * 渲染 markdown 表格。
     * 从 List<List<String>> 渲染 markdown 表格。
     * @param headers 列名（顺序敏感）
     * @param rows 数据行，每行是一组字符串值（顺序与 headers 对应）
     * @param leadingTitle 表格前的标题行（如 "Query returned 3 row(s):"），null 则不输出
     * @return markdown 格式的表格字符串
     */
    public static String render(
            List<String> headers, List<List<String>> rows, String leadingTitle) {
        // 1. 算每列宽度
        int colCount = headers.size();
        int[] widths = new int[colCount];
        for (int i = 0; i < colCount; i++) {
            widths[i] = Math.max(headers.get(i).length(), MIN_HEADER_WIDTH);
        }
        for (List<String> row : rows) {
            for (int i = 0; i < colCount && i < row.size(); i++) {
                String val = row.get(i);
                widths[i] = Math.max(widths[i], Math.min(val.length(), MAX_CELL_WIDTH));
            }
        }

        StringBuilder sb = new StringBuilder();
        if (leadingTitle != null) {
            sb.append(leadingTitle).append("\n\n");
        }

        // 2. 表头
        sb.append("|");
        for (int i = 0; i < colCount; i++) {
            sb.append(" ").append(padRight(headers.get(i), widths[i])).append(" |");
        }
        sb.append("\n|");
        for (int i = 0; i < colCount; i++) {
            sb.append("-".repeat(widths[i] + 2)).append("|");
        }
        sb.append("\n");

        // 3. 数据行
        for (List<String> row : rows) {
            sb.append("|");
            for (int i = 0; i < colCount; i++) {
                String val = i < row.size() ? row.get(i) : "";
                if (val.length() > MAX_CELL_WIDTH) {
                    val = val.substring(0, MAX_CELL_WIDTH - 3) + "...";
                }
                sb.append(" ").append(padRight(val, widths[i])).append(" |");
            }
            sb.append("\n");
        }

        if (rows.isEmpty()) {
            sb.append("(empty result)\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 从 JdbcTemplate 的 List<Map<String,Object>> 直接渲染
     * 便捷重载：从 JdbcTemplate 返回的 {@code List<Map<String,Object>>} 直接渲染。
     * 列顺序取第一行的 key 顺序（LinkedHashMap 保证有序）。
     */
    public static String renderFromMaps(
            List<Map<String, Object>> rows, int rowLimit, String leadingTitle) {
        if (rows.isEmpty()) {
            return leadingTitle != null
                    ? leadingTitle + "\n\n(empty result)"
                    : "(empty result)";
        }
        // 列名从第一行取
        List<String> headers = new java.util.ArrayList<>(rows.get(0).keySet());

        // 转成 List<List<String>>
        List<List<String>> stringRows = new java.util.ArrayList<>();
        int count = 0;
        for (Map<String, Object> row : rows) {
            if (count >= rowLimit) break;
            List<String> vals = new java.util.ArrayList<>(headers.size());
            for (String h : headers) {
                Object v = row.get(h);
                String s = v == null ? "NULL" : v.toString();
                vals.add(s);
            }
            stringRows.add(vals);
            count++;
        }
        return render(headers, stringRows, leadingTitle);
    }

    private static String padRight(String s, int len) {
        if (s.length() >= len) return s;
        return s + " ".repeat(len - s.length());
    }
}
