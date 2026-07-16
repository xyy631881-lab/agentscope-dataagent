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

/** Bridges OpenTelemetry's spans to a compact, tenant-safe runtime-records read model. */
@Service
public class TraceRunService {

    private static final Set<String> EXPORTED_ATTRIBUTES = Set.of(
            "gen_ai.operation.name",
            "gen_ai.request.model",
            "gen_ai.request.messages.count",
            "gen_ai.request.tools.count",
            "gen_ai.usage.input_tokens",
            "gen_ai.usage.output_tokens",
            "gen_ai.tool.name",
            "gen_ai.tool.call.count",
            "gen_ai.tool.call.id",
            "agentscope.agent.reply_id",
            "error.type");

    private final TraceRunRepository runs;
    private final TraceSpanRepository spans;
    private final ObjectMapper objectMapper;

    public TraceRunService(
            TraceRunRepository runs, TraceSpanRepository spans, ObjectMapper objectMapper) {
        this.runs = runs;
        this.spans = spans;
        this.objectMapper = objectMapper;
    }

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
        runs.findByTraceId(scope.traceId()).ifPresent(run ->
                run.finish(outcome, conciseError(error), System.currentTimeMillis()));
        if ("SUCCESS".equals(outcome)) {
            scope.rootSpan().setStatus(StatusCode.OK);
        } else {
            scope.rootSpan().setStatus(StatusCode.ERROR, outcome.toLowerCase());
        }
        scope.rootSpan().end();
    }

    @Transactional
    public void appendSpan(SpanData span) {
        if (span.getSpanContext().getTraceId().isBlank() || spans.existsBySpanId(span.getSpanId())) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        span.getAttributes().asMap().forEach((key, value) -> {
            if (EXPORTED_ATTRIBUTES.contains(key.getKey())) attributes.put(key.getKey(), value);
        });
        long durationMs = Math.max(0L, (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000L);
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
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return runs.findByUserIdOrderByStartedAtMsDesc(userId, PageRequest.of(0, limit)).stream()
                .map(this::view)
                .toList();
    }

    private RunView view(TraceRunEntity run) {
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
