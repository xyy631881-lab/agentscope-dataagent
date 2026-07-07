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
package io.agentscope.dataagent.workspace.infrastructure;

import io.agentscope.dataagent.workspace.infrastructure.SandboxLifecycleRecord.Status;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 沙箱容器生命周期记录的 JPA 仓库。
 */
public interface SandboxLifecycleRepository extends JpaRepository<SandboxLifecycleRecord, Long> {

    /** 查找指定用户的活跃沙箱。 */
    Optional<SandboxLifecycleRecord> findByUserIdAndStatus(String userId, Status status);

    /** 查找指定容器 ID 的记录。 */
    Optional<SandboxLifecycleRecord> findByContainerId(String containerId);

    /** 查找所有心跳超时的活跃沙箱（可能已变成孤儿）。 */
    List<SandboxLifecycleRecord> findByStatusAndLastHeartbeatBefore(Status status, LocalDateTime cutoff);

    /** 查找指定状态的所有记录。 */
    List<SandboxLifecycleRecord> findByStatus(Status status);

    /** 查找状态在给定集合内的所有记录（启动时清理多种状态用）。 */
    List<SandboxLifecycleRecord> findByStatusIn(List<Status> statuses);
}
