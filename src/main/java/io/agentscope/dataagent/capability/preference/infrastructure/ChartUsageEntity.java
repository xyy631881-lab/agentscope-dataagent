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
import jakarta.persistence.Table;

/**
 * 用户图表渲染使用记录。每次 {@code render_chart} 工具调用成功时写入一行，
 * 作为"图表偏好"学习的数据源。
 *
 * <p>按 {@code chartType} 分组统计即可得到用户偏好的图表类型分布。
 */
@Entity
@Table(
        name = "dataagent_chart_usage",
        indexes = {
            @Index(name = "ix_chart_usage_user_agent", columnList = "user_id, agent_id"),
            @Index(name = "ix_chart_usage_created_at", columnList = "created_at")
        })
public class ChartUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    /** 图表类型：{@code line} / {@code bar} / {@code area} / {@code scatter}。 */
    @Column(name = "chart_type", length = 32, nullable = false)
    private String chartType;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public ChartUsageEntity() {}

    public ChartUsageEntity(String userId, String agentId, String chartType, long createdAt) {
        this.userId = userId;
        this.agentId = agentId;
        this.chartType = chartType;
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

    public String getChartType() {
        return chartType;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
