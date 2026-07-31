package io.agentscope.dataagent.observability.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.model.application.TenantModelService;
import io.agentscope.dataagent.observability.infrastructure.TraceRunEntity;
import io.agentscope.dataagent.observability.infrastructure.TraceRunRepository;
import io.agentscope.dataagent.observability.infrastructure.TraceSpanEntity;
import io.agentscope.dataagent.observability.infrastructure.TraceSpanRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** B它是 OpenTelemetry Span 到 MySQL 业务记录的桥梁层。
 * AgentScope 框架通过 OtelTracingMiddleware 生成原始 Span，
 * TraceRunService 负责把这些 Span 转成用户可查询的结构化记录.
 *
 * AgentScope 中间件  →  OpenTelemetry SDK  →  JpaTraceSpanExporter  →  TraceRunService  →  MySQL
 *   (OtelTracing)        (Span生成)              (批量异步导出)            (过滤+持久化)      (trace_run + trace_span)
 *
 *   -- trace_run: 按用户+时间查询最近运行记录
 *   -- trace_span: 按 trace 查询所有 Span，按 span_id 去重
 * */
@Service
public class TraceRunService {

    private static final Set<String> EXPORTED_ATTRIBUTES = Set.of(
            "gen_ai.operation.name",       // 操作类型（chat/completion/embedding）
            "gen_ai.request.model",        // 模型名称
            "gen_ai.request.messages.count", // 请求消息数
            "gen_ai.request.tools.count",    // 工具数量
            "gen_ai.usage.input_tokens",     // 输入 Token → 成本分析
            "gen_ai.usage.output_tokens",    // 输出 Token → 成本分析
            "gen_ai.tool.name",             // 工具名称 → 异常定位
            "gen_ai.tool.call.count",       // 工具调用次数
            "gen_ai.tool.call.id",          // 工具调用 ID
            "agentscope.agent.reply_id",    // Agent 回复 ID
            "error.type"                    // 错误类型 → 异常定位
    );

    private final TraceRunRepository runs;
    private final TraceSpanRepository spans;
    private final ObjectMapper objectMapper;

    public TraceRunService(
            TraceRunRepository runs, TraceSpanRepository spans, ObjectMapper objectMapper) {
        this.runs = runs;
        this.spans = spans;
        this.objectMapper = objectMapper;
    }

    /** 开始一个 Agent 执行记录，记录用户 ID、Agent ID、会话键、模型 ID 等信息。 */
    @Transactional
    public TraceScope start(
            Span rootSpan, String userId, String agentId, String sessionKey, String modelId) {
        String traceId = rootSpan.getSpanContext().getTraceId();
        runs.save(
                new TraceRunEntity(
                        traceId,
                        rootSpan.getSpanContext().getSpanId(),
                        TenantModelService.tenantForUser(userId),
                        userId,
                        agentId,
                        sessionKey,
                        modelId,
                        System.currentTimeMillis()));
        return new TraceScope(rootSpan, rootSpan.storeInContext(Context.current()), traceId);
    }

    @Transactional
    public void complete(TraceScope scope, String outcome, Throwable error) {
        // ① 更新 trace_run 状态：记录完成时间、错误信息（如果有）和耗时
        runs.findByTraceId(scope.traceId()).ifPresent(run ->
                run.finish(outcome, conciseError(error), System.currentTimeMillis()));
        // ② 设置 OTel Span 状态：根据 outcome 设置 SUCCESS 或 ERROR，CANCELLED 用于手动取消
        if ("SUCCESS".equals(outcome)) {
            scope.rootSpan().setStatus(StatusCode.OK);
        } else {
            scope.rootSpan().setStatus(StatusCode.ERROR, outcome.toLowerCase());
        }
        scope.rootSpan().end();
    }

    @Transactional
    public void appendSpan(SpanData span) {
        // ① 去重：span_id 已存在就跳过
        if (span.getSpanContext().getTraceId().isBlank() || spans.existsBySpanId(span.getSpanId())) return;
        // ② 属性过滤：只保留 AI 相关的关键属性
        Map<String, Object> attributes = new LinkedHashMap<>();
        span.getAttributes().asMap().forEach((key, value) -> {
            if (EXPORTED_ATTRIBUTES.contains(key.getKey())) attributes.put(key.getKey(), value);
        });
        // ③ 计算耗时
        long durationMs = Math.max(0L, (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000L);
        // ④ 写入 trace_span 表
        spans.save(
                new TraceSpanEntity(
                        span.getTraceId(),
                        span.getSpanId(),
                        span.getParentSpanId(),
                        span.getName(),
                        span.getKind().name(),
                        span.getStatus().getStatusCode().name(),
                        span.getStartEpochNanos() / 1_000_000L,
                        durationMs,
                        json(attributes)));
    }

    public List<RunView> recentForUser(String userId, int requestedLimit) {
        // ① 查 trace_run（按时间倒序，最多 100 条）
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return runs.findByUserIdOrderByStartedAtMsDesc(userId, PageRequest.of(0, limit)).stream()
                .map(this::view)
                .toList();
    }

    private RunView view(TraceRunEntity run) {
        // ③ 查该 trace 的所有 Span（按时间升序）
        List<SpanView> details = spans.findByTraceIdOrderByStartedAtMsAsc(run.getTraceId()).stream()
                .map(span -> new SpanView(
                        span.getSpanId(),
                        span.getParentSpanId(),
                        span.getOperationName(),
                        span.getSpanKind(),
                        span.getStatus(),
                        span.getStartedAtMs(),
                        span.getDurationMs(),
                        map(span.getAttributesJson())))
                .toList();
        // ④ 计算总耗时
        long end = run.getEndedAtMs() == null ? System.currentTimeMillis() : run.getEndedAtMs();
        return new RunView(
                run.getTraceId(),
                run.getRootSpanId(),
                run.getAgentId(),
                run.getSessionKey(),
                run.getModelId(),
                run.getStatus(),
                run.getErrorMessage(),
                run.getStartedAtMs(),
                Math.max(0L, end - run.getStartedAtMs()),
                details);
    }

    private String json(Map<String, Object> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private Map<String, Object> map(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private static String conciseError(Throwable error) {
        if (error == null || error.getMessage() == null) return null;
        String message = error.getMessage().replaceAll("\\s+", " ").trim();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    public record TraceScope(Span rootSpan, Context otelContext, String traceId) {}
    public record RunView(
            String traceId,
            String rootSpanId,
            String agentId,
            String sessionKey,
            String modelId,
            String status,
            String errorMessage,
            long startedAtMs,
            long durationMs,
            List<SpanView> spans) {}
    public record SpanView(
            String spanId,
            String parentSpanId,
            String operationName,
            String spanKind,
            String status,
            long startedAtMs,
            long durationMs,
            Map<String, Object> attributes) {}
}
