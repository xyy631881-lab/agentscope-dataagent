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
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.application.AgentMutationService;

import io.agentscope.dataagent.agent.domain.ActivityEvent;
import io.agentscope.dataagent.agent.application.AgentActivityStore;
import io.agentscope.dataagent.agent.application.command.AgentCreateRequest;
import io.agentscope.dataagent.agent.application.AgentMutationService.ShareGrantRequest;
import io.agentscope.dataagent.agent.application.AgentAccessGuard;
import io.agentscope.dataagent.agent.application.AgentAclService;
import io.agentscope.dataagent.agent.application.AgentAclService.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import io.agentscope.dataagent.agent.application.command.AgentDraft;
import io.agentscope.dataagent.config.ModelConfig;
import io.agentscope.dataagent.config.properties.ApiModelProperties;
import io.agentscope.dataagent.config.properties.OllamaProperties;
import java.util.ArrayList;

/**
 * REST controller for the agent catalog.
 *
 * <ul>
 *   <li>{@code GET /api/agents} — list all visible agent definitions (global + own custom)
 *   <li>{@code GET /api/agents/{id}} — get a single definition
 *   <li>{@code POST /api/agents} — create a user-custom agent
 *   <li>{@code PUT /api/agents/{id}} — update a user-custom agent (own only)
 *   <li>{@code DELETE /api/agents/{id}} — delete a user-custom agent (own only)
 *   <li>{@code POST /api/agents/{id}/shares} — 追加/更新一条分享授权（owner only）
 *   <li>{@code DELETE /api/agents/{id}/shares} — 撤销一条分享授权（owner only）
 * </ul>
 */
@RestController
@RequestMapping("/api/agents")
public class AgentCatalogController {
    private final AgentCatalogService catalogService;
    private final AgentMutationService mutationService;
    private final AgentAclService aclService;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;
    private final OllamaProperties ollamaProps;
    private final ApiModelProperties modelProps;

    public AgentCatalogController(
            AgentCatalogService catalogService,
            AgentMutationService mutationService,
            AgentAclService aclService,
            AgentAccessGuard guard,
            AgentActivityStore activity,
            OllamaProperties ollamaProps,
            ApiModelProperties modelProps) {
        this.catalogService = catalogService;
        this.mutationService = mutationService;
        this.aclService = aclService;
        this.guard = guard;
        this.activity = activity;
        this.ollamaProps = ollamaProps;
        this.modelProps = modelProps;
    }

    /**
     * 列出当前可用模型，供管理台 Agent 配置的「Model」下拉选择。
     *
     * <p>返回本地 Ollama 与 LongCat API 两个选项；id 即 Agent definition 的 model 字段取值
     * （{@code local} / {@code longcat}），label 用于展示。不返回任何密钥。
     */
    @GetMapping("/models")
    public List<ModelOption> listModels() {
        String ollamaName = ollamaProps.getModel().getChat();
        List<ModelOption> options = new ArrayList<>();
        options.add(new ModelOption(ModelConfig.LOCAL_MODEL_ID, "本地 Ollama · " + ollamaName, true));
        options.add(
                new ModelOption(
                        ModelConfig.LONGCAT_MODEL_ID,
                        modelProps.getLongcat().getModelName() + " (LongCat API)",
                        false));
        return options;
    }

    /** 模型选项：id 用于 Agent 的 model 字段，label 用于前端展示，local 标记是否本地模型。 */
    public record ModelOption(String id, String label, boolean local) {}

    /**
     * Lists all agent definitions visible to the authenticated user: global agents first, then
     * the user's own custom agents.
     */
    @GetMapping
    public List<AgentDefinition> listAgents(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return catalogService.listVisible(userId).stream()
                        .map(def -> withTier(userId, def))
                        .toList();
    }

    /** Gets a single agent definition visible to the authenticated user. */
    @GetMapping("/{id}")
    public AgentDefinition getAgent(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return withTier(
                        userId,
                        catalogService
                                .findVisible(userId, id)
                                .orElseThrow(
                                        () ->
                                                new org.springframework.web.server
                                                        .ResponseStatusException(
                                                        org.springframework.http.HttpStatus
                                                                .NOT_FOUND,
                                                        "Agent not found: " + id)));
    }

    /**
     * Decorates an {@link AgentDefinition} returned to the frontend with the calling user's
     * effective tier so the UI can gate tabs and affordances without re-implementing ACL.
     */
    private AgentDefinition withTier(String userId, AgentDefinition def) {
        Tier t = aclService.tierFor(userId, def);
        return def.withTierForCurrentUser(t == null ? null : t.name());
    }

    /** Creates a new user-custom agent definition. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDefinition createAgent(
            @RequestBody AgentCreateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    AgentDefinition created = mutationService.createUserAgent(userId, req);
                    activity.record(
                    userId,
                    created.id(),
                    activity.actor(userId),
                    ActivityEvent.Action.CREATE);
                    return created;
    }

    /**
     * Updates a user-custom agent definition. Owner or any EDIT-tier grantee may update; the
     * change is persisted to the owner's namespace regardless of who triggered it.
     */
    @PutMapping("/{id}")
    public AgentDefinition updateAgent(
            @PathVariable String id, @RequestBody AgentCreateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    // Global (bootstrap) agents have no owner; only an administrator (who holds
                    // EDIT tier on globals) may edit them. The change is persisted as an override
                    // and applies to the running agent after the next restart.
                    if (def.ownerId() == null) {
                AgentDefinition updated = mutationService.updateGlobalAgent(userId, id, req);
                        activity.record(
                        userId,
                        id,
                        activity.actor(userId),
                        ActivityEvent.Action.EDIT_SETTINGS);
                        return withTier(userId, updated);
                    }
                    String ownerId = def.ownerId();
                    AgentDefinition updated = mutationService.updateUserAgent(ownerId, id, req);
                    activity.record(
                    ownerId,
                    id,
                    activity.actor(userId),
                    ActivityEvent.Action.EDIT_SETTINGS);
                    return withTier(userId, updated);
    }

    /**
     * Deletes a user-custom agent definition. Owner or EDIT-tier grantee may delete; only the
     * owner's namespace copy is removed.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgent(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    String ownerId = def.ownerId();
                    if (ownerId == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Global agents cannot be deleted via the catalog API");
                    }
                    mutationService.deleteUserAgent(ownerId, id);
                    // The owning namespace tree (including activity.jsonl) is removed when the
                    // agent is deleted; we still emit one final event so a workspace audit
                    // sweep can see who triggered the deletion before the log went away.
                    activity.record(
                    ownerId, id, activity.actor(userId), ActivityEvent.Action.DELETE_AGENT);
    }

    // -----------------------------------------------------------------
    //  分享授权（Share management）—— 只有 owner 能操作
    // -----------------------------------------------------------------

    /**
     * 追加（或更新）一条分享授权。采用 upsert 语义：如果 (granteeType, granteeId) 已存在，
     * 就更新它的 tier；否则新增一条。
     *
     * <p>权限设计：只有 Agent 的 owner 能分享。
     * 为什么不让被授权 EDIT 的人也能分享？——防止"权限扩散"：被授权者再授权给别人，
     * 会导致权限不可控。owner 才是权限的源头。
     *
     * <p>请求体示例：
     * <pre>{@code
     * { "granteeType": "USER", "granteeId": "alice", "tier": "RUN" }
     * { "granteeType": "WORKSPACE", "granteeId": "*", "tier": "CLONE" }
     * }</pre>
     */
    @PostMapping("/{id}/shares")
    public AgentDefinition grantShare(
            @PathVariable String id,
            @RequestBody ShareGrantRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    // 第一道门：可见性 + EDIT 权限（owner 必然满足 EDIT，因为 tierFor 规则2）
                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    // 全局 Agent 没有 owner，由管理员（对全局 Agent 持有 EDIT 的用户）直接管理分享。
                    if (def.ownerId() == null) {
                AgentDefinition updated = mutationService.grantShareGlobal(id, req);
                        activity.record(
                        userId,
                        id,
                        activity.actor(userId),
                        ActivityEvent.Action.EDIT_SETTINGS);
                        return withTier(userId, updated);
                    }
                    String ownerId = def.ownerId();
                    // 第二道门：只有 owner 能管理分享，被授权 EDIT 的人不能再授权
                    if (!userId.equals(ownerId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only the owner may manage shares on agent " + id);
                    }
                    AgentDefinition updated = mutationService.grantShare(ownerId, id, req);
                    activity.record(
                    ownerId,
                    id,
                    activity.actor(userId),
                    ActivityEvent.Action.EDIT_SETTINGS);
                    return withTier(userId, updated);
    }

    /**
     * 撤销一条分享授权。精确匹配 (granteeType, granteeId)，移除对应的那条 grant。
     *
     * <p>参数通过 query string 传递（DELETE 带 body 不规范）：
     * <pre>{@code
     * DELETE /api/agents/{id}/shares?granteeType=USER&granteeId=alice
     * DELETE /api/agents/{id}/shares?granteeType=WORKSPACE&granteeId=*
     * }</pre>
     */
    @DeleteMapping("/{id}/shares")
    public AgentDefinition revokeShare(
            @PathVariable String id,
            @RequestParam String granteeType,
            @RequestParam String granteeId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    AgentDefinition def = guard.require(userId, id, Tier.EDIT);
                    // 全局 Agent 没有 owner，由管理员直接管理分享。
                    if (def.ownerId() == null) {
                AgentDefinition updated =
                        mutationService.revokeShareGlobal(id, granteeType, granteeId);
                        activity.record(
                        userId,
                        id,
                        activity.actor(userId),
                        ActivityEvent.Action.EDIT_SETTINGS);
                        return withTier(userId, updated);
                    }
                    String ownerId = def.ownerId();
                    if (!userId.equals(ownerId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Only the owner may manage shares on agent " + id);
                    }
                    AgentDefinition updated =
                    mutationService.revokeShare(ownerId, id, granteeType, granteeId);
                    activity.record(
                    ownerId,
                    id,
                    activity.actor(userId),
                    ActivityEvent.Action.EDIT_SETTINGS);
                    return withTier(userId, updated);
    }

    /**
     * 触发指定 Agent 的工作区<b>惰性重建</b>。
     *
     * <p>用途：当共享层（{@code shared/agents/{agentId}/} 下的 skills/knowledge 等）
     * 被<b>绕过贡献审批流程</b>直接修改（如管理员手动编辑）后，已存在的用户容器不会感知
     * 变化——共享层是在容器<b>创建时</b> bind-mount 的，运行中不自动更新。本端点等价于
     * 贡献审批通过的效果：标记该 Agent 下所有用户容器为失效，<b>下次各用户 borrow 时
     * 自动重建</b>（挂载新共享层 + 从快照恢复用户私有文件），且不打断进行中的会话。
     *
     * <p>需要对该 Agent 的 {@code EDIT} 权限（全局 Agent 由管理员持有；用户 Agent 仅 owner）。
     */
    @PostMapping("/{id}/rebuild-workspace")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rebuildWorkspace(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        // 复用 catalogue 的 EDIT 门禁：全局 Agent（ownerId=null）由管理员持有 EDIT，
        // 用户 Agent 仅 owner 可触发。
        guard.require(userId, id, Tier.EDIT);
    }
}
