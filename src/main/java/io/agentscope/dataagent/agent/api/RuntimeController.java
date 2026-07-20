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

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.conversation.application.ConversationService;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntity;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;

/**
 * 运行时概览端点（供管理后台 Overview / Instances 页面调用）。
 *
 * <pre>
 * GET /api/admin/runtime/overview   → KPI 汇总（Agent 数、会话数、用户数等）
 * GET /api/admin/runtime/instances → 已注册 Agent 实例列表
 * GET /api/admin/runtime/sessions  → 活跃会话列表
 * </pre>
 */
@RestController
@RequestMapping({"/api/admin/runtime", "/api/admin"})
public class RuntimeController {

    private final DataAgentBootstrap bootstrap;
    private final AgentLifecycleService lifecycleService;
    private final ConversationService conversationService;
    private final AgentCatalogService catalogService;

    public RuntimeController(
            DataAgentBootstrap bootstrap,
            AgentLifecycleService lifecycleService,
            ConversationService conversationService,
            AgentCatalogService catalogService) {
        this.bootstrap = bootstrap;
        this.lifecycleService = lifecycleService;
        this.conversationService = conversationService;
        this.catalogService = catalogService;
    }

    @GetMapping("/overview")
    public OverviewDto overview() {
        Map<String, ?> agents = bootstrap.agents();
        long sessionCount = conversationService.sessionCount();
        long userCount = conversationService.userCount();
        // TODO: registeredChannelCount / recentActivity 后续可从 BindingStore + ActivityStore 聚合
        return new OverviewDto(
                (int) sessionCount,
                (int) userCount,
                agents.size(),
                0,       // channel count placeholder
                List.of() // recent activity placeholder
        );
    }

    @GetMapping("/instances")
    public List<InstanceDto> instances() {
        Map<String, io.agentscope.harness.agent.HarnessAgent> agents = bootstrap.agents();
        return agents.entrySet().stream()
                .map(e -> new InstanceDto(e.getKey(), e.getValue().getClass().getName()))
                .toList();
    }

    @GetMapping("/sessions")
    public List<SessionDto> sessions(@RequestParam(defaultValue = "100") int limit) {
        return conversationService.recentSessions(limit).stream()
                .map(e -> new SessionDto(
                        e.getSessionKey(),
                        e.getAgentId(),
                        e.getUserId(),
                        e.getKind(),
                        e.getLastActivityMs(),
                        System.currentTimeMillis() - e.getLastActivityMs()))
                .toList();
    }

    @GetMapping("/sessions/{sessionKey}/tree")
    public SessionTreeDto sessionTree(@PathVariable String sessionKey) {
        return conversationService
                .sessionTree(sessionKey)
                .map(RuntimeController::toTreeDto)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Session not found: " + sessionKey));
    }

    private static SessionTreeDto toTreeDto(ConversationService.SessionTreeNode node) {
        io.agentscope.dataagent.conversation.domain.SessionEntry session = node.session();
        long now = System.currentTimeMillis();
        SessionDetailDto detail =
                new SessionDetailDto(
                        session.sessionKey(),
                        session.agentId(),
                        session.sessionId(),
                        session.label(),
                        session.kind().getValue(),
                        session.spawnedBy(),
                        session.spawnDepth(),
                        session.userId(),
                        session.gateKey(),
                        session.sessionFilePath(),
                        session.createdAtMs(),
                        session.lastActivityMs(),
                        Math.max(0L, now - session.lastActivityMs()));
        return new SessionTreeDto(
                detail, node.children().stream().map(RuntimeController::toTreeDto).toList());
    }

    // ── DTOs ──────────────────────────────────────────────

    public record OverviewDto(
            int activeSessionCount,
            int totalUserCount,
            int registeredAgentCount,
            int registeredChannelCount,
            List<?> recentActivity) {}

    public record InstanceDto(String agentId, String className) {}

    public record SessionDto(
            String sessionKey,
            String agentId,
            String userId,
            String kind,
            long lastActivityMs,
            long idleMs) {}

    public record SessionDetailDto(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            String kind,
            String spawnedBy,
            int spawnDepth,
            String userId,
            String gateKey,
            String sessionFilePath,
            long createdAtMs,
            long lastActivityMs,
            long idleMs) {}

    public record SessionTreeDto(SessionDetailDto session, List<SessionTreeDto> children) {}
}
