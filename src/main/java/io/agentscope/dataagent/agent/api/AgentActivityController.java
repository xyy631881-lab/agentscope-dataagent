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
package io.agentscope.dataagent.agent.api;

import io.agentscope.dataagent.agent.domain.ActivityEvent;
import io.agentscope.dataagent.agent.application.AgentActivityStore;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.agent.application.AgentAccessGuard;
import io.agentscope.dataagent.agent.application.AgentAclService;
import io.agentscope.dataagent.agent.application.AgentAclService.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent 的只读活动日志。
 *
 * <p>{@code GET /api/agents/{id}/activity?since={ms}&limit={n}} — 至少需要 RUN 权限。影响
 * 共享或绑定配置的事件（{@code GRANT_SHARE}、{@code REVOKE_SHARE}、
 * {@code BIND_CHANNEL}、{@code UNBIND_CHANNEL}、{@code EDIT_BINDING}）对
 * EDIT 级别的调用者返回完整信息，对较低级别返回摘要版本（"所有者更新了共享" / "所有者更新了
 * channel 绑定"）。所有者可以看到所有信息。
 */
@RestController
@RequestMapping("/api/agents/{id}/activity")
public class AgentActivityController {
    private static final Set<String> REDACTED_SHARE_ACTIONS =
            Set.of(ActivityEvent.Action.GRANT_SHARE, ActivityEvent.Action.REVOKE_SHARE);

    private static final Set<String> REDACTED_BINDING_ACTIONS =
            Set.of(
                    ActivityEvent.Action.BIND_CHANNEL,
                    ActivityEvent.Action.UNBIND_CHANNEL,
                    ActivityEvent.Action.EDIT_BINDING);

    private final AgentAccessGuard guard;
    private final AgentAclService acl;
    private final AgentActivityStore store;

    public AgentActivityController(
            AgentAccessGuard guard, AgentAclService acl, AgentActivityStore store) {
        this.guard = guard;
        this.acl = acl;
        this.store = store;
    }

    @GetMapping
    public List<ActivityEvent> list(
            @PathVariable("id") String agentId,
            @RequestParam(name = "since", required = false) Long since,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
                    if (!AgentDefinition.SCOPE_USER.equals(def.scope())
                            || def.ownerId() == null) {
                        // Globals have no per-agent activity log (their workspace is
                        // read-only and
                        // not namespaced through the per-user store).
                        return List.<ActivityEvent>of();
                    }
                    String ownerId = def.ownerId();
                    Tier held = acl.tierFor(userId, def);
                    boolean fullDetail = held != null && held.implies(Tier.EDIT);
                    List<ActivityEvent> raw = store.list(ownerId, agentId, since, limit);
                    if (fullDetail) {
                        return raw;
                    }
                    List<ActivityEvent> out = new ArrayList<>(raw.size());
                    for (ActivityEvent ev : raw) {
                        out.add(redactFor(ev));
                    }
                    return out;
    }

    private static ActivityEvent redactFor(ActivityEvent ev) {
        if (ev == null || ev.action() == null) return ev;
        if (REDACTED_SHARE_ACTIONS.contains(ev.action())) {
            return new ActivityEvent(
                    ev.id(),
                    ev.timestampMs(),
                    ev.actorUserId(),
                    ev.actorUsername(),
                    ev.action(),
                    null,
                    null);
        }
        if (REDACTED_BINDING_ACTIONS.contains(ev.action())) {
            return new ActivityEvent(
                    ev.id(),
                    ev.timestampMs(),
                    ev.actorUserId(),
                    ev.actorUsername(),
                    ev.action(),
                    null,
                    null);
        }
        return ev;
    }
}
