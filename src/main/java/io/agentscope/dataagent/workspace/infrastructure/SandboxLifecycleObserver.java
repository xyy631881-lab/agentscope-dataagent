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

import io.agentscope.dataagent.workspace.infrastructure.SandboxLifecycleRecord;
import io.agentscope.dataagent.workspace.infrastructure.SandboxLifecycleRecord.Status;
import io.agentscope.dataagent.workspace.infrastructure.SandboxLifecycleRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 沙箱生命周期观察者——将容器创建/关闭事件持久化到 DB。
 *
 * <p>这个类是从 {@link UserSandboxPool} 中提取出来的项目级审计逻辑。
 * Registry 只负责容器池管理（框架职责），所有 DB 记录的读写都委托给这里。
 *
 * <p>设计原则：观察者只接收原始字符串参数（userId、containerId 等），
 * 不依赖 {@code Sandbox} 或 {@code DockerSandboxState} 等框架类型，
 * 这样框架适配层和项目审计层之间的耦合降到最低。
 *
 * <p>所有 DB 操作都包裹在 try-catch 中，失败只 warn 不抛异常——
 * 审计日志写入失败不应影响容器池的正常运作。
 */
@Component
public class SandboxLifecycleObserver {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleObserver.class);

    private final SandboxLifecycleRepository repository;

    public SandboxLifecycleObserver(SandboxLifecycleRepository repository) {
        this.repository = repository;
    }

    /**
     * 容器创建成功时调用——在 DB 中记录一条 ACTIVE 生命周期。
     *
     * @param userId        用户 ID
     * @param agentId       Agent ID
     * @param containerId   Docker 容器 ID
     * @param containerName Docker 容器名称
     * @param image         容器镜像
     */
    public void onCreated(
            String userId,
            String agentId,
            String containerId,
            String containerName,
            String image) {
        try {
            SandboxLifecycleRecord record = new SandboxLifecycleRecord();
            record.setUserId(userId);
            record.setAgentId(agentId);
            record.setContainerId(containerId);
            record.setContainerName(containerName);
            record.setImage(image);
            record.setStatus(Status.ACTIVE);
            record.setLastHeartbeat(LocalDateTime.now());
            record.setCreatedAt(LocalDateTime.now());
            record.setTtlMinutes(30);
            repository.save(record);
            log.info(
                    "[sandbox-lifecycle] 已记录沙箱生命周期: userId={}, containerId={}",
                    userId,
                    containerId);
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] 记录生命周期失败: {}", e.getMessage());
        }
    }

    /**
     * 容器关闭时调用——将 DB 中对应的 ACTIVE 记录标记为 CLOSED。
     *
     * @param userId  用户 ID
     * @param agentId Agent ID（目前未用于查询，保留以备将来按 agent 维度精确匹配）
     */
    public void onClosed(String userId, String agentId) {
        try {
            repository
                    .findByUserIdAndStatus(userId, Status.ACTIVE)
                    .ifPresent(
                            record -> {
                                record.setStatus(Status.CLOSED);
                                repository.save(record);
                            });
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] 更新生命周期状态失败: {}", e.getMessage());
        }
    }
}
