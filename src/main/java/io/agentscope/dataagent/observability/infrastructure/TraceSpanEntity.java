package io.agentscope.dataagent.observability.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 执行过程中的单个操作 Span（模型调用、工具调用等）. */
@Entity
@Table(
        name = "trace_span",
        indexes = {
            @Index(name = "ix_trace_span_trace_time", columnList = "trace_id, started_at_ms"),
            @Index(name = "ix_trace_span_span", columnList = "span_id", unique = true)
        })
public class TraceSpanEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "span_row_id")
    private Long spanRowId;
    @Column(name = "trace_id", length = 64, nullable = false)
    private String traceId;
    @Column(name = "span_id", length = 32, nullable = false)
    private String spanId;
    @Column(name = "parent_span_id", length = 32)
    private String parentSpanId;
    @Column(name = "operation_name", length = 512, nullable = false)
    private String operationName;
    @Column(name = "span_kind", length = 32, nullable = false)
    private String spanKind;
    @Column(name = "status", length = 24, nullable = false)
    private String status;
    @Column(name = "started_at_ms", nullable = false)
    private long startedAtMs;
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
    @Lob
    @Column(name = "attributes_json")
    private String attributesJson;

    protected TraceSpanEntity() {}

    public TraceSpanEntity(
            String traceId, String spanId, String parentSpanId, String operationName, String spanKind,
            String status, long startedAtMs, long durationMs, String attributesJson) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.operationName = operationName;
        this.spanKind = spanKind;
        this.status = status;
        this.startedAtMs = startedAtMs;
        this.durationMs = durationMs;
        this.attributesJson = attributesJson;
    }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getOperationName() { return operationName; }
    public String getSpanKind() { return spanKind; }
    public String getStatus() { return status; }
    public long getStartedAtMs() { return startedAtMs; }
    public long getDurationMs() { return durationMs; }
    public String getAttributesJson() { return attributesJson; }
}
