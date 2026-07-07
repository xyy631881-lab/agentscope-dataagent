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
package io.agentscope.dataagent.agent.application;
import io.agentscope.dataagent.agent.api.AgentSkillsController;
import io.agentscope.dataagent.agent.api.AgentToolsController;
import io.agentscope.dataagent.agent.api.AgentWorkspaceController;

import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.workspace.infrastructure.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工作空间解析服务——统一封装 AgentWorkspaceController、AgentSkillsController、
 * AgentToolsController 中重复的"根据 userId+agentId 解析 WorkspaceManager"逻辑。
 *
 * <p>核心流程：
 * <ol>
 *   <li>通过 {@link AgentCatalogService#findVisible} 查找用户可见的 Agent 定义
 *   <li>根据 scope 分支：
 *     <ul>
 *       <li>SCOPE_USER → {@link WorkspaceManagerFactory#forAgent}（ownerId + workspacePath）
 *       <li>全局 → {@link WorkspaceManagerFactory#forGlobalAgent}
 *     </ul>
 * </ol>
 *
 * <p>使用 findVisible 统一替代原来各 Controller 中分散的 isGlobal + findOwnerOf + findStoredEntry
 * 三次调用，同时增加了可见性校验（安全提升）。
 */
@Service
public class WorkspaceResolutionService {

    private final AgentCatalogService catalogService;
    private final WorkspaceManagerFactory workspaceFactory;

    public WorkspaceResolutionService(
            AgentCatalogService catalogService, WorkspaceManagerFactory workspaceFactory) {
        this.catalogService = catalogService;
        this.workspaceFactory = workspaceFactory;
    }

    /**
     * 解析工作空间上下文（包含 WorkspaceManager、工作目录路径、ownerId）。
     *
     * <p>这是写操作和需要完整上下文的场景使用的主入口。
     * 会执行可见性校验——用户无法访问不可见的 Agent 的工作空间。
     *
     * @param userId  当前用户 ID
     * @param agentId URL 路径中的 Agent ID
     * @return 解析后的工作空间上下文
     * @throws ResponseStatusException 404 如果 Agent 不存在或用户无权访问
     */
    public ResolvedWorkspace resolve(String userId, String agentId) {
        AgentDefinition def =
                catalogService
                        .findVisible(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found or not accessible: "
                                                        + agentId));
        if (AgentDefinition.SCOPE_USER.equals(def.scope())) {
            String ownerId = def.ownerId() != null ? def.ownerId() : userId;
            WorkspaceManager wm =
                    workspaceFactory.forAgent(ownerId, agentId, def.workspacePath());
            return new ResolvedWorkspace(wm, ownerId);
        }
        WorkspaceManager wm =
                workspaceFactory.forGlobalAgent(userId, agentId, def.workspacePath());
        return new ResolvedWorkspace(wm, userId);
    }

    /** 只获取 WorkspaceManager（不抛 404，内部使用）。 */
    public WorkspaceManager resolveManager(String userId, String agentId) {
        return resolve(userId, agentId).manager();
    }

    /** 只获取 AbstractFilesystem（只读浏览场景）。 */
    public AbstractFilesystem resolveFilesystem(String userId, String agentId) {
        return resolve(userId, agentId).filesystem();
    }

    /**
     * 解析后的工作空间上下文。
     *
     * @param manager  WorkspaceManager 实例
     * @param ownerId  工作空间所有者 ID（SCOPE_USER 时是 Agent 的 ownerId，全局时是当前 userId）
     */
    public record ResolvedWorkspace(WorkspaceManager manager, String ownerId) {
        /** 工作空间在容器内的归一化路径。 */
        public Path workspace() {
            return manager.getWorkspace().normalize();
        }

        /** 工作空间的文件系统接口（用于 ls/read/write 等操作）。 */
        public AbstractFilesystem filesystem() {
            return manager.getFilesystem();
        }
    }
}