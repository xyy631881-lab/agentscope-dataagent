package io.agentscope.dataagent.observability.infrastructure;

import io.agentscope.dataagent.observability.application.TraceRunService;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 核心职责: 实现 OpenTelemetry 的 SpanExporter 接口，将 Span 写入数据库
 *
 * 最佳努力导出：即使数据库不可用也不影响 Agent 请求
 * 捕获异常仅记录警告，返回失败状态
 * */
public class JpaTraceSpanExporter implements SpanExporter {
    private static final Logger log = LoggerFactory.getLogger(JpaTraceSpanExporter.class);
    private final TraceRunService traceRuns;

    public JpaTraceSpanExporter(TraceRunService traceRuns) {
        this.traceRuns = traceRuns;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        try {
            spans.forEach(traceRuns::appendSpan);
            return CompletableResultCode.ofSuccess();
        } catch (RuntimeException exception) {
            log.warn("Unable to persist OpenTelemetry spans: {}", exception.getMessage());
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

    @Override
    public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
}
