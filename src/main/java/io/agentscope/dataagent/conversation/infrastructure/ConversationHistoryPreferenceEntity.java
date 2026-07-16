package io.agentscope.dataagent.conversation.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A user's saved-history cap for a single logical agent. */
@Entity
@Table(
        name = "conversation_history_preference",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_conversation_history_preference_user_agent",
                        columnNames = {"user_id", "agent_id"}))
public class ConversationHistoryPreferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "agent_id", nullable = false, length = 256)
    private String agentId;

    @Column(name = "max_sessions", nullable = false)
    private int maxSessions;

    public Long getRowId() {
        return rowId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }
}
