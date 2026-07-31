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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.capability.preference.infrastructure.ChartUsageEntity;
import io.agentscope.dataagent.capability.preference.infrastructure.ChartUsageRepository;
import io.agentscope.dataagent.capability.preference.infrastructure.SqlHistoryEntity;
import io.agentscope.dataagent.capability.preference.infrastructure.SqlHistoryRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 偏好学习的事件记录器。
 *
 * <p>由 {@code ChatController} 在工具调用结束时调用，异步写入 SQL 执行历史和图表渲染使用记录，
 * 不阻塞 SSE 流。所有写入都是 best-effort——失败只记日志，不影响主流程。
 *
 * <p>从工具入参 JSON 中提取 SQL 文本 / 图表类型：
 * <ul>
 *   <li>{@code run_sql_preview} 入参：{@code {"source_id":"...", "sql":"SELECT ...", "row_limit":N}}
 *   <li>{@code render_chart} 入参：{@code {"chart_type":"bar", "vega_lite_spec":"..."}}
 * </ul>
 */
@Service
public class PreferenceRecorder {

    private static final Logger log = LoggerFactory.getLogger(PreferenceRecorder.class);

    /** 防止异常长的工具参数无限制落库，同时覆盖正常分析 SQL 的完整文本。 */
    private static final int SQL_TEXT_MAX_LENGTH = 16 * 1024;

    private final SqlHistoryRepository sqlHistoryRepository;
    private final ChartUsageRepository chartUsageRepository;
    private final ObjectMapper objectMapper;
    private final AgentLifecycleService lifecycleService;

    /** 单线程 daemon executor，保证写入顺序且不阻塞 SSE 流。 */
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "preference-recorder");
                        t.setDaemon(true);
                        return t;
                    });

    public PreferenceRecorder(
            SqlHistoryRepository sqlHistoryRepository,
            ChartUsageRepository chartUsageRepository,
            ObjectMapper objectMapper,
            AgentLifecycleService lifecycleService) {
        this.sqlHistoryRepository = sqlHistoryRepository;
        this.chartUsageRepository = chartUsageRepository;
        this.objectMapper = objectMapper;
        this.lifecycleService = lifecycleService;
    }

    /**
     * 记录一次 SQL 执行。
     *
     * @param userId     执行用户
     * @param agentId    执行 agent
     * @param toolInput  {@code run_sql_preview} 工具入参 JSON（包含 {@code sql} 字段）
     * @param toolResult 工具返回结果（以 {@code "error"} 开头表示失败）
     */
    public CompletableFuture<Void> recordSqlExecution(
            String userId, String agentId, String toolInput, String toolResult) {
        if (userId == null || agentId == null) return CompletableFuture.completedFuture(null);
        String sql = extractJsonField(toolInput, "sql");
        if (sql == null || sql.isBlank()) return CompletableFuture.completedFuture(null);
        String truncated = sql.length() > SQL_TEXT_MAX_LENGTH
                ? sql.substring(0, SQL_TEXT_MAX_LENGTH)
                : sql;
        boolean success = !isErrorResult(toolResult);
        long now = System.currentTimeMillis();
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        sqlHistoryRepository.save(
                                new SqlHistoryEntity(userId, agentId, truncated, success, now));
                        lifecycleService.invalidateUca(userId, agentId);
                    } catch (Exception e) {
                        log.debug(
                                "Failed to record SQL history (non-fatal): userId={}, agentId={}, error={}",
                                userId,
                                agentId,
                                e.getMessage());
                    }
                },
                executor);
    }

    /**
     * 记录一次图表渲染。
     *
     * @param userId    执行用户
     * @param agentId   执行 agent
     * @param toolInput {@code render_chart} 工具入参 JSON（包含 {@code chart_type} 字段）
     */
    public CompletableFuture<Void> recordChartRender(
            String userId, String agentId, String toolInput) {
        if (userId == null || agentId == null) return CompletableFuture.completedFuture(null);
        String chartType = extractJsonField(toolInput, "chart_type");
        if (chartType == null || chartType.isBlank()) return CompletableFuture.completedFuture(null);
        long now = System.currentTimeMillis();
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        chartUsageRepository.save(
                                new ChartUsageEntity(userId, agentId, chartType, now));
                        lifecycleService.invalidateUca(userId, agentId);
                    } catch (Exception e) {
                        log.debug(
                                "Failed to record chart usage (non-fatal): userId={}, agentId={}, error={}",
                                userId,
                                agentId,
                                e.getMessage());
                    }
                },
                executor);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private String extractJsonField(String json, String fieldName) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode node = root.get(fieldName);
            return node != null ? node.asText(null) : null;
        } catch (Exception e) {
            // 工具入参可能不是合法 JSON（如框架直接拼的字符串），跳过即可
            return null;
        }
    }

    private static boolean isErrorResult(String toolResult) {
        if (toolResult == null) return true;
        return toolResult.trim().toLowerCase().startsWith("error");
    }
}
