package io.agentscope.dataagent.observability.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Query index for one user-visible agent execution. The detailed children are exported OTel spans. */
@Entity
@Table(
        name = "trace_run",
        indexes = {
            @Index(name = "ix_trace_run_user_time", columnList = "user_id, started_at_ms"),
            @Index(name = "ix_trace_run_session", columnList = "session_key")
        })
public class TraceRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "trace_id", length = 64, nullable = false, unique = true)
    private String traceId;
    @Column(name = "root_span_id", length = 32, nullable = false)
    private String rootSpanId;
    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;
    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;
    @Column(name = "agent_id", length = 256, nullable = false)
    private String agentId;
    @Column(name = "session_key", length = 256)
    private String sessionKey;
    @Column(name = "model_id", length = 256)
    private String modelId;
    @Column(name = "status", length = 24, nullable = false)
    private String status;
    @Column(name = "error_message", length = 1024)
    private String errorMessage;
    @Column(name = "started_at_ms", nullable = false)
    private long startedAtMs;
    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    protected TraceRunEntity() {}

    public TraceRunEntity(
            String traceId, String rootSpanId, String tenantId, String userId, String agentId,
            String sessionKey, String modelId, long startedAtMs) {
        this.traceId = traceId;
        this.rootSpanId = rootSpanId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentId = agentId;
        this.sessionKey = sessionKey;
        this.modelId = modelId;
        this.status = "RUNNING";
        this.startedAtMs = startedAtMs;
    }

    public String getTraceId() { return traceId; }
    public String getRootSpanId() { return rootSpanId; }
    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public String getSessionKey() { return sessionKey; }
    public String getModelId() { return modelId; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public long getStartedAtMs() { return startedAtMs; }
    public Long getEndedAtMs() { return endedAtMs; }
    public void finish(String status, String errorMessage, long endedAtMs) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.endedAtMs = endedAtMs;
    }
}
