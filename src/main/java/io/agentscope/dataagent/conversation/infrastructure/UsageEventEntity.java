package io.agentscope.dataagent.conversation.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** One completed chat request, aggregated across all model calls in that request. */
@Entity
@Table(
        name = "usage_event",
        indexes = {
            @Index(name = "ix_usage_event_user_time", columnList = "user_id, recorded_at_ms"),
            @Index(name = "ix_usage_event_tenant_time", columnList = "tenant_id, recorded_at_ms"),
            @Index(name = "ix_usage_event_agent_time", columnList = "agent_id, recorded_at_ms"),
            @Index(name = "ix_usage_event_model_time", columnList = "model_id, recorded_at_ms")
        })
public class UsageEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "agent_id", length = 256, nullable = false)
    private String agentId;

    @Column(name = "session_key", length = 256)
    private String sessionKey;

    @Column(name = "model_id", length = 256, nullable = false)
    private String modelId;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cached_prompt_tokens", nullable = false)
    private long cachedPromptTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    /** Cost in micro USD. Keeping an integer avoids decimal rounding in reports. */
    @Column(name = "cost_microusd", nullable = false)
    private long costMicrousd;

    @Column(name = "outcome", length = 24, nullable = false)
    private String outcome;

    @Column(name = "recorded_at_ms", nullable = false)
    private long recordedAtMs;

    protected UsageEventEntity() {}

    public UsageEventEntity(
            String tenantId,
            String userId,
            String agentId,
            String sessionKey,
            String modelId,
            long inputTokens,
            long outputTokens,
            long cachedPromptTokens,
            long durationMs,
            long costMicrousd,
            String outcome,
            long recordedAtMs) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentId = agentId;
        this.sessionKey = sessionKey;
        this.modelId = modelId;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedPromptTokens = cachedPromptTokens;
        this.durationMs = durationMs;
        this.costMicrousd = costMicrousd;
        this.outcome = outcome;
        this.recordedAtMs = recordedAtMs;
    }

    public String getUserId() { return userId; }
    public String getAgentId() { return agentId; }
    public String getModelId() { return modelId; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCachedPromptTokens() { return cachedPromptTokens; }
    public long getDurationMs() { return durationMs; }
    public long getCostMicrousd() { return costMicrousd; }
    public long getRecordedAtMs() { return recordedAtMs; }
}
