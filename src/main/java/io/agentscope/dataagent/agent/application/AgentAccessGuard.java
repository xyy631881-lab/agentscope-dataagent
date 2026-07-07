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

import io.agentscope.dataagent.agent.domain.AgentAclService;
import io.agentscope.dataagent.agent.domain.AgentAclService.Tier;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 控制器的便捷守卫：按 ID 查找 Agent（跨越全局、自己的、共享进来的）并
 * 验证调用者至少具有所需的权限级别。将 {@code findVisible + can} 模式
 * 从每个控制器中移出，同时在调用点保持显式（无 AOP 魔法）。
 */
@Service
public class AgentAccessGuard {

    private final AgentCatalogService catalog;
    private final AgentAclService acl;

    public AgentAccessGuard(AgentCatalogService catalog, AgentAclService acl) {
        this.catalog = catalog;
        this.acl = acl;
    }

    /**
     * 这是整个系统的门卫——你能不能见这个 Agent？能见的话，能做什么级别的操作？门卫帮你查清楚，不合规的直接拦在门外。
     */
    public AgentDefinition require(String userId, String agentId, Tier required) {
        // 第一道门：你能不能"看到"这个 Agent？
        AgentDefinition def =
                catalog.findVisible(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        // 第二道门：你有没有足够的权限做这个操作？
        if (!acl.can(userId, def, required)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Required tier " + required.name() + " on agent " + agentId);
        }
        return def;
    }

    /** Get the agent without enforcing a tier (404 if invisible). Use for read-only descriptors. */
    public AgentDefinition load(String userId, String agentId) {
        return catalog.findVisible(userId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
    }
}
