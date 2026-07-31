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
package io.agentscope.dataagent.capability.preference.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 用户 SQL 执行历史记录。每次 {@code run_sql_preview} 工具调用结束时写入一行，
 * 作为"常用 SQL 模式"偏好学习的数据源。
 *
 * <p>保存执行 SQL 全文，供用户在偏好页审阅。分组时按完整文本做精确匹配。
 */
@Entity
@Table(
        name = "dataagent_sql_history",
        indexes = {
            @Index(name = "ix_sql_history_user_agent", columnList = "user_id, agent_id"),
            @Index(name = "ix_sql_history_created_at", columnList = "created_at")
        })
public class SqlHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    /** SQL 全文。 */
    @Lob
    @Column(name = "sql_text", nullable = false)
    private String sqlText;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public SqlHistoryEntity() {}

    public SqlHistoryEntity(
            String userId, String agentId, String sqlText, boolean success, long createdAt) {
        this.userId = userId;
        this.agentId = agentId;
        this.sqlText = sqlText;
        this.success = success;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getSqlText() {
        return sqlText;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
