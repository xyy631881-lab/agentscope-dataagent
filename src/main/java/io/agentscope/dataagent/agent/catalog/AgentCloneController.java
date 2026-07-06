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
package io.agentscope.dataagent.agent.catalog;

import io.agentscope.dataagent.agent.activity.ActivityEvent;
import io.agentscope.dataagent.agent.activity.AgentActivityStore;
import io.agentscope.dataagent.agent.catalog.AgentCatalogService;
import io.agentscope.dataagent.agent.catalog.AgentCatalogService.StoredEntryAndDefinition;
import io.agentscope.dataagent.agent.catalog.AgentDefinition;
import io.agentscope.dataagent.agent.sharing.AgentAccessGuard;
import io.agentscope.dataagent.agent.sharing.AgentAclService.Tier;
import io.agentscope.dataagent.web.util.WorkspaceCopier;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 克隆端点：生成调用者至少具有 CLONE 级别权限的 Agent 的所有者私有副本。
 *
 * <p>克隆复制设置 + workspace 文件，但以无共享、无会话、无
 * channel 绑定的状态开始（计划第 5 节）。文件通过 {@code AbstractFilesystem}
 * 层复制，因此操作针对任何后端实现（目前：每个 {@code (userId, agentId)}
 * Docker 沙箱视图）都能相同工作。
 *
 * <p>v1 中不支持克隆全局 Agent（返回 409）：全局 Agent 存在于
 * {@code agentscope.json} 中，不在任何用户命名空间中，且其 workspace 布局
 * 尚未通过每个用户的 {@link WorkspaceManagerFactory} 连接。
 */
@RestController
@RequestMapping("/api/agents/{id}/clone")
public class AgentCloneController {

    private static final Logger log = LoggerFactory.getLogger(AgentCloneController.class);

    private final AgentCatalogService catalog;
    private final AgentAccessGuard guard;
    private final WorkspaceManagerFactory workspaceFactory;
    private final AgentActivityStore activity;

    public AgentCloneController(
            AgentCatalogService catalog,
            AgentAccessGuard guard,
            WorkspaceManagerFactory workspaceFactory,
            AgentActivityStore activity) {
        this.catalog = catalog;
        this.guard = guard;
        this.workspaceFactory = workspaceFactory;
        this.activity = activity;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AgentDefinition> clone(
            @PathVariable("id") String sourceAgentId,
            @RequestBody(required = false) CloneRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition src = guard.require(userId, sourceAgentId, Tier.CLONE);
                    if (!AgentDefinition.SCOPE_USER.equals(src.scope()) || src.ownerId() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cloning global agents is not supported yet; "
                                        + "edit agentscope.json or fork the project instead.");
                    }
                    String srcOwnerId = src.ownerId();
                    String newId = req != null ? req.newAgentId() : null;
                    String newName = req != null ? req.name() : null;

                    StoredEntryAndDefinition out =
                            catalog.prepareClone(srcOwnerId, sourceAgentId, userId, newId, newName);

                    int copied =
                            WorkspaceCopier.copy(
                                    workspaceFactory,
                                    srcOwnerId,
                                    sourceAgentId,
                                    src.workspacePath(),
                                    userId,
                                    out.entry().id(),
                                    out.entry().workspacePath());
                    log.info(
                            "Clone {}/{} -> {}/{}: {} files copied",
                            srcOwnerId,
                            sourceAgentId,
                            userId,
                            out.entry().id(),
                            copied);

                    AgentActivityStore.ActorRef actor = activity.actor(userId);
                    activity.record(
                            userId,
                            out.entry().id(),
                            actor,
                            ActivityEvent.Action.CLONE_TO,
                            srcOwnerId + "/" + sourceAgentId,
                            Map.of("files", copied));
                    activity.record(
                            srcOwnerId,
                            sourceAgentId,
                            actor,
                            ActivityEvent.Action.CLONE_FROM,
                            userId + "/" + out.entry().id(),
                            Map.of("files", copied));
                    return out.definition();
                });
    }

    public record CloneRequest(String newAgentId, String name) {}
}
