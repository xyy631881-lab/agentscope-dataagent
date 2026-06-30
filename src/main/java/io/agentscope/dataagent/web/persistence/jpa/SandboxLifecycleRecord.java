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
package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 沙箱容器生命周期记录。
 *
 * <p>跟踪每个用户沙箱容器的创建、活跃、心跳和回收状态。
 * 使得应用重启后能识别孤儿容器并进行精确清理（而非无脑扫全部）。
 *
 * <p>多副本部署时，此表存储在共享数据库（MySQL/PostgreSQL）中，
 * 配合 {@code last_heartbeat} 字段实现跨实例的容器协调。
 */
@Entity
@Table(name = "sandbox_lifecycle", indexes = {
    @Index(name = "idx_sandbox_user_active", columnList = "user_id, status, last_heartbeat"),
    @Index(name = "idx_sandbox_container", columnList = "container_id", unique = true)
})
public class SandboxLifecycleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID。同一用户的多个会话共享同一个沙箱容器。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** Agent ID。 */
    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    /** Docker 容器 ID。 */
    @Column(name = "container_id", nullable = false, length = 128)
    private String containerId;

    /** Docker 容器名称。 */
    @Column(name = "container_name", length = 255)
    private String containerName;

    /** 容器镜像。 */
    @Column(name = "image", length = 128)
    private String image;

    /** 容器状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** 最近一次心跳时间。由容器内 sidecar 或调用方定期更新。 */
    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** TTL（分钟）：超过此时间未收到心跳则视为过期。 */
    @Column(name = "ttl_minutes", nullable = false)
    private int ttlMinutes = 30;

    /** 沙箱状态。 */
    public enum Status {
        /** 正在创建中。 */
        CREATING,
        /** 活跃运行中。 */
        ACTIVE,
        /** 正在停止中。 */
        STOPPING,
        /** 已正常关闭。 */
        CLOSED,
        /** 被回收器标记为过期并清理。 */
        REAPED
    }

    // ---- getters / setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getTtlMinutes() { return ttlMinutes; }
    public void setTtlMinutes(int ttlMinutes) { this.ttlMinutes = ttlMinutes; }
}
