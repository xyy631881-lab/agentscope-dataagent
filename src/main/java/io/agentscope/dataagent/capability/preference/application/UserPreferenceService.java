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
package io.agentscope.dataagent.capability.preference.application;

import io.agentscope.dataagent.capability.preference.infrastructure.ChartUsageRepository;
import io.agentscope.dataagent.capability.preference.infrastructure.SqlHistoryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.agentscope.dataagent.capability.preference.infrastructure.ChartUsageRepository.ChartTypeProjection;
import io.agentscope.dataagent.capability.preference.infrastructure.SqlHistoryRepository.SqlPatternProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户偏好聚合服务。
 *
 * <p>从 {@link SqlHistoryRepository} 和 {@link ChartUsageRepository} 读取历史记录，
 * 聚合成一段 system prompt 片段，注入到 agent 的系统提示词中，让 agent 随用户习惯持续进化。
 *
 * <p>聚合策略：
 * <ul>
 *   <li>SQL 模式：按完整 SQL 文本精确分组，设置页只展示 Top 5，其余按页读取
 *   <li>图表偏好：按 chart_type 分组，取使用次数最多的类型作为"偏好类型"
 *   <li>查询习惯：从成功 SQL 中归纳筛选、聚合、排序和多表关联等行为
 *   <li>常查数据表：从成功 SQL 的 FROM/JOIN 子句中归纳表名
 * </ul>
 */
@Service
public class UserPreferenceService {

    /** 注入提示词中最多展示的 SQL 模式数量。 */
    private static final int MAX_SQL_PATTERNS = 5;
    private static final int MAX_TABLE_PREFERENCES = 5;
    private static final int MAX_QUERY_STYLES = 4;
    private static final Pattern TABLE_REFERENCE =
            Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([`\\\"\\w.]+)");

    private final SqlHistoryRepository sqlHistoryRepository;
    private final ChartUsageRepository chartUsageRepository;

    public UserPreferenceService(
            SqlHistoryRepository sqlHistoryRepository,
            ChartUsageRepository chartUsageRepository) {
        this.sqlHistoryRepository = sqlHistoryRepository;
        this.chartUsageRepository = chartUsageRepository;
    }

    /**
     * 构建指定用户 + agent 的偏好提示词片段。
     *
     * @return 偏好片段；若用户无任何历史记录，返回空字符串（不注入）
     */
    @Transactional(readOnly = true)
    public String buildPromptFragment(String userId, String agentId) {
        PreferenceSummary summary = getSummary(userId, agentId);
        List<SqlPattern> sqlPatterns = summary.sqlPatterns();
        List<ChartPreference> chartCounts = summary.chartPreferences();
        List<TablePreference> tablePreferences = summary.tablePreferences();
        List<QueryStylePreference> queryStyles = summary.queryStyles();

        if (sqlPatterns.isEmpty() && chartCounts.isEmpty() && tablePreferences.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 用户偏好（基于历史使用模式）\n");
        sb.append("以下是你根据用户历史使用行为总结的偏好，请在后续交互中参考这些偏好：\n");

        if (!sqlPatterns.isEmpty()) {
            sb.append("\n### 常用 SQL 模式\n");
            sb.append("用户最常执行的 SQL 查询（按使用频率降序），生成 SQL 时可参考这些模式：\n");
            int limit = Math.min(sqlPatterns.size(), MAX_SQL_PATTERNS);
            for (int i = 0; i < limit; i++) {
                SqlPattern p = sqlPatterns.get(i);
                sb.append(i + 1)
                        .append(". ")
                        .append(p.sqlPreview())
                        .append("（使用 ")
                        .append(p.useCount())
                        .append(" 次）\n");
            }
        }

        if (!chartCounts.isEmpty()) {
            sb.append("\n### 图表偏好\n");
            for (ChartPreference c : chartCounts) {
                sb.append("- ").append(c.chartType()).append(" 图表（").append(c.percentage()).append("%，")
                        .append(c.useCount()).append(" 次）\n");
            }
            sb.append("生成图表时优先使用用户偏好的图表类型。\n");
        }

        if (!tablePreferences.isEmpty()) {
            sb.append("\n### 常查数据表\n");
            for (TablePreference table : tablePreferences) {
                sb.append("- ")
                        .append(table.tableName())
                        .append("（使用 ")
                        .append(table.useCount())
                        .append(" 次）\n");
            }
            sb.append("优先从这些表推断分析上下文，但仍需先确认表结构。\n");
        }

        if (!queryStyles.isEmpty()) {
            sb.append("\n### 查询习惯\n");
            for (QueryStylePreference style : queryStyles) {
                sb.append("- ")
                        .append(style.label())
                        .append("（")
                        .append(style.percentage())
                        .append("%）\n");
            }
        }

        return sb.toString();
    }

    @Transactional(readOnly = true)
    public PreferenceSummary getSummary(String userId, String agentId) {
        List<SqlPatternProjection> sqlPatterns =
                sqlHistoryRepository.findTopSqlPatterns(userId, agentId);
        List<ChartTypeProjection> chartCounts =
                chartUsageRepository.findChartTypeCounts(userId, agentId);
        long chartTotal = chartCounts.stream().mapToLong(ChartTypeProjection::getUseCount).sum();
        return new PreferenceSummary(
                sqlPatterns.stream()
                        .limit(MAX_SQL_PATTERNS)
                        .map(UserPreferenceService::toSqlPattern)
                        .toList(),
                chartCounts.stream()
                        .map(
                                c ->
                                        new ChartPreference(
                                                c.getChartType(),
                                                c.getUseCount(),
                                                chartTotal == 0
                                                        ? 0
                                                        : c.getUseCount() * 100 / chartTotal))
                        .toList(),
                tablePreferences(sqlPatterns),
                queryStyles(sqlPatterns),
                sqlPatterns.size());
    }

    @Transactional(readOnly = true)
    public SqlPatternPage getSqlPatterns(String userId, String agentId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        Page<SqlPatternProjection> result =
                sqlHistoryRepository.findSqlPatterns(
                        userId, agentId, PageRequest.of(safePage, safeSize));
        return new SqlPatternPage(
                result.getContent().stream().map(UserPreferenceService::toSqlPattern).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
    }

    @Transactional
    public void clear(String userId, String agentId) {
        sqlHistoryRepository.deleteByUserIdAndAgentId(userId, agentId);
        chartUsageRepository.deleteByUserIdAndAgentId(userId, agentId);
    }

    public record SqlPattern(String sqlPreview, String sqlText, long useCount) {}

    public record ChartPreference(String chartType, long useCount, long percentage) {}

    public record TablePreference(String tableName, long useCount) {}

    public record QueryStylePreference(String label, long useCount, long percentage) {}

    public record PreferenceSummary(
            List<SqlPattern> sqlPatterns,
            List<ChartPreference> chartPreferences,
            List<TablePreference> tablePreferences,
            List<QueryStylePreference> queryStyles,
            long sqlPatternCount) {}

    public record SqlPatternPage(
            List<SqlPattern> items, int page, int size, long total) {}

    private static SqlPattern toSqlPattern(SqlPatternProjection pattern) {
        return new SqlPattern(
                previewSql(pattern.getSqlText()), pattern.getSqlText(), pattern.getUseCount());
    }

    private static List<TablePreference> tablePreferences(List<SqlPatternProjection> patterns) {
        Map<String, Long> counts = new HashMap<>();
        for (SqlPatternProjection pattern : patterns) {
            if (pattern.getSqlText() == null || pattern.getUseCount() == null) continue;
            Set<String> tablesInQuery = new HashSet<>();
            Matcher matcher = TABLE_REFERENCE.matcher(pattern.getSqlText());
            while (matcher.find()) {
                String table = matcher.group(1).replace("`", "").replace("\"", "").trim();
                if (!table.isBlank()) tablesInQuery.add(table);
            }
            for (String table : tablesInQuery) {
                counts.merge(table, pattern.getUseCount(), Long::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(MAX_TABLE_PREFERENCES)
                .map(entry -> new TablePreference(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<QueryStylePreference> queryStyles(List<SqlPatternProjection> patterns) {
        Map<String, Long> counts = new HashMap<>();
        long totalQueries = 0;
        for (SqlPatternProjection pattern : patterns) {
            if (pattern.getSqlText() == null || pattern.getUseCount() == null) continue;
            String sql = pattern.getSqlText().toUpperCase(java.util.Locale.ROOT);
            long useCount = pattern.getUseCount();
            totalQueries += useCount;
            boolean hasStyle = false;
            if (sql.matches("(?s).*\\bWHERE\\b.*")) {
                counts.merge("带筛选条件", useCount, Long::sum);
                hasStyle = true;
            }
            if (sql.matches("(?s).*\\b(GROUP\\s+BY|COUNT\\s*\\(|SUM\\s*\\(|AVG\\s*\\(|MIN\\s*\\(|MAX\\s*\\().*")) {
                counts.merge("聚合统计", useCount, Long::sum);
                hasStyle = true;
            }
            if (sql.matches("(?s).*\\bORDER\\s+BY\\b.*")) {
                counts.merge("排序结果", useCount, Long::sum);
                hasStyle = true;
            }
            if (sql.matches("(?s).*\\bJOIN\\b.*")) {
                counts.merge("多表关联", useCount, Long::sum);
                hasStyle = true;
            }
            if (!hasStyle) counts.merge("明细查询", useCount, Long::sum);
        }
        if (totalQueries == 0) return List.of();
        long totalQueryCount = totalQueries;
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
        return sorted.stream()
                .limit(MAX_QUERY_STYLES)
                .map(
                        entry ->
                                new QueryStylePreference(
                                        entry.getKey(),
                                        entry.getValue(),
                                        entry.getValue() * 100 / totalQueryCount))
                .toList();
    }

    /**
     * 生成 SQL 文本的单行预览。把多行 SQL 压成一行，超过 120 字符截断。
     */
    private static String previewSql(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 120) {
            return oneLine.substring(0, 120) + "...";
        }
        return oneLine;
    }
}
