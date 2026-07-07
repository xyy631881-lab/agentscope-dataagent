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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 会话已读状态的 JPA 持久化实体，替代原先的 session-read-state.json。
 *
 * <p>对应表 {@code conversation_read_state}，记录每个用户对每条会话的最后已读时间。
 * 通过比较 {@code lastReadAt} 与会话的 {@code lastActivityMs} 判断是否未读。
 */
@Entity
@Table(
        name = "conversation_read_state",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_conv_read_state",
                        columnNames = {"user_id", "session_key"}),
        indexes =
                @Index(
                        name = "ix_conv_read_user",
                        columnList = "user_id"))
public class SessionReadStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "session_key", length = 256, nullable = false)
    private String sessionKey;

    @Column(name = "last_read_at")
    private long lastReadAt;

    public SessionReadStateEntity() {}

    public SessionReadStateEntity(String userId, String sessionKey, long lastReadAt) {
        this.userId = userId;
        this.sessionKey = sessionKey;
        this.lastReadAt = lastReadAt;
    }

    // --- getters / setters ---

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public long getLastReadAt() {
        return lastReadAt;
    }

    public void setLastReadAt(long lastReadAt) {
        this.lastReadAt = lastReadAt;
    }
}
