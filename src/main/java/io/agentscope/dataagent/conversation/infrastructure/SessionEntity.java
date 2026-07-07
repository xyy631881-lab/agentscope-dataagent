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
package io.agentscope.dataagent.conversation.infrastructure;

import io.agentscope.dataagent.conversation.domain.SessionEntry;
import io.agentscope.dataagent.conversation.domain.SessionKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 会话元数据的 JPA 持久化实体，替代原先的 sessions.json + 内存索引。
 *
 * <p>对应表 {@code conversation_session}，存储每条会话的元数据：谁在什么时候跟哪个 Agent
 * 聊了天、会话标识、网关路由键等。实际的对话内容（消息、工具调用）仍存储在框架管理的
 * JSONL 日志文件中，不在此表中。
 */
@Entity
@Table(
        name = "conversation_session",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_conversation_session_key",
                        columnNames = "session_key"),
        indexes = {
            @Index(name = "ix_conv_session_user", columnList = "user_id, agent_id"),
            @Index(name = "ix_conv_session_gate", columnList = "gate_key"),
            @Index(name = "ix_conv_session_activity", columnList = "user_id, last_activity_ms"),
            @Index(name = "ix_conv_session_activity_global", columnList = "last_activity_ms")
        })
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "session_key", length = 256, nullable = false)
    private String sessionKey;

    @Column(name = "agent_id", length = 256)
    private String agentId;

    @Column(name = "session_id", length = 256)
    private String sessionId;

    @Column(name = "label", length = 256)
    private String label;

    /** "main" or "subagent" */
    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    @Column(name = "spawned_by", length = 256)
    private String spawnedBy;

    @Column(name = "spawn_depth")
    private int spawnDepth;

    @Column(name = "created_at_ms")
    private long createdAtMs;

    @Column(name = "last_activity_ms")
    private long lastActivityMs;

    @Lob
    @Column(name = "session_file_path")
    private String sessionFilePath;

    @Column(name = "spawn_run_id", length = 256)
    private String spawnRunId;

    @Lob
    @Column(name = "gate_key")
    private String gateKey;

    @Column(name = "user_id", length = 128)
    private String userId;

    public SessionEntity() {}

    /** 从领域模型 SessionEntry 构造 JPA 实体。 */
    public static SessionEntity fromEntry(SessionEntry e) {
        SessionEntity entity = new SessionEntity();
        entity.sessionKey = e.sessionKey();
        entity.agentId = e.agentId();
        entity.sessionId = e.sessionId();
        entity.label = e.label();
        entity.kind = e.kind().getValue();
        entity.spawnedBy = e.spawnedBy();
        entity.spawnDepth = e.spawnDepth();
        entity.createdAtMs = e.createdAtMs();
        entity.lastActivityMs = e.lastActivityMs();
        entity.sessionFilePath = e.sessionFilePath();
        entity.spawnRunId = e.spawnRunId();
        entity.gateKey = e.gateKey();
        entity.userId = e.userId();
        return entity;
    }

    /** 转换为领域模型 SessionEntry。 */
    public SessionEntry toEntry() {
        return new SessionEntry(
                sessionKey,
                agentId,
                sessionId,
                label,
                "main".equals(kind) ? SessionKind.MAIN : SessionKind.SUBAGENT,
                spawnedBy,
                spawnDepth,
                createdAtMs,
                lastActivityMs,
                sessionFilePath,
                spawnRunId,
                gateKey,
                userId);
    }

    /** 用更新后的字段创建新的 SessionEntry（用于 reset 等场景）。 */
    public SessionEntity copyWithNewSessionId(String newSessionId, String newFilePath, long now) {
        SessionEntity copy = new SessionEntity();
        copy.rowId = this.rowId;
        copy.sessionKey = this.sessionKey;
        copy.agentId = this.agentId;
        copy.sessionId = newSessionId;
        copy.label = this.label;
        copy.kind = this.kind;
        copy.spawnedBy = this.spawnedBy;
        copy.spawnDepth = this.spawnDepth;
        copy.createdAtMs = now;
        copy.lastActivityMs = now;
        copy.sessionFilePath = newFilePath;
        copy.spawnRunId = this.spawnRunId;
        copy.gateKey = this.gateKey;
        copy.userId = this.userId;
        return copy;
    }

    // --- getters / setters ---

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSpawnedBy() {
        return spawnedBy;
    }

    public void setSpawnedBy(String spawnedBy) {
        this.spawnedBy = spawnedBy;
    }

    public int getSpawnDepth() {
        return spawnDepth;
    }

    public void setSpawnDepth(int spawnDepth) {
        this.spawnDepth = spawnDepth;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }

    public long getLastActivityMs() {
        return lastActivityMs;
    }

    public void setLastActivityMs(long lastActivityMs) {
        this.lastActivityMs = lastActivityMs;
    }

    public String getSessionFilePath() {
        return sessionFilePath;
    }

    public void setSessionFilePath(String sessionFilePath) {
        this.sessionFilePath = sessionFilePath;
    }

    public String getSpawnRunId() {
        return spawnRunId;
    }

    public void setSpawnRunId(String spawnRunId) {
        this.spawnRunId = spawnRunId;
    }

    public String getGateKey() {
        return gateKey;
    }

    public void setGateKey(String gateKey) {
        this.gateKey = gateKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
