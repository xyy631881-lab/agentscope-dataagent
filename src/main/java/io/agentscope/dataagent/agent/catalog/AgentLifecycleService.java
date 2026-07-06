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

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentConfigEntry;
import io.agentscope.dataagent.runtime.config.SkillRepositorySupport;
import io.agentscope.dataagent.runtime.outbound.OutboundTool;
import io.agentscope.dataagent.runtime.AgentRuntimeConfigurer;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the lifecycle of dynamically-instantiated user-custom agents (UCAs):
 * building, registering, caching, and invalidating running agent instances in the
 * {@link HarnessGateway}.
 *
 * <p>This service is responsible for:
 * <ul>
 *   <li>Resolving logical agent IDs to physical gateway agent IDs.</li>
 *   <li>Building and registering user-custom agents on first access.</li>
 *   <li>Caching gateway registrations to avoid redundant rebuilds.</li>
 *   <li>Invalidating cached registrations when agent definitions change.</li>
 *   <li>Providing the global agent definitions loaded from {@code agentscope.json}.</li>
 * </ul>
 *
 * <p>This service has no dependency on {@link AgentCatalogService} to avoid circular
 * references. Permission and visibility checks are the caller's responsibility.
 */
@Service
public class AgentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycleService.class);

    /** Prefix for user-custom agent IDs when registered in the gateway. */
    public static final String UCA_PREFIX = "uca-";

    private final DataAgentBootstrap builderBootstrap;
    private final UserAgentDefinitionStore store;
    private final Model model;
    private final AgentRuntimeConfigurer runtimeConfigurer;
    private final io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory workspaceManagerFactory;

    /**
     * In-flight cache of dynamically-registered gateway agent IDs. Key: {@code {userId}/{agentId}},
     * Value: the gateway agent ID (e.g. {@code uca-{userId}-{agentId}}).
     */
    private final ConcurrentHashMap<String, String> registeredUcaIds = new ConcurrentHashMap<>();

    public AgentLifecycleService(
            DataAgentBootstrap builderBootstrap,
            UserAgentDefinitionStore store,
            Optional<Model> modelOpt,
            AgentRuntimeConfigurer runtimeConfigurer,
            io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory workspaceManagerFactory) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.runtimeConfigurer = runtimeConfigurer;
        this.workspaceManagerFactory = workspaceManagerFactory;
    }

    // -----------------------------------------------------------------
    //  Gateway routing support
    // -----------------------------------------------------------------

    /**
     * Resolves the gateway agent ID for the given user and agent.
     * For global agents, returns the agent ID directly.
     * For user-custom agents, looks up the stored definition, and builds/registers
     * the UCA on first access (cached for subsequent calls).
     *
     * @throws ResponseStatusException 404 if the agent is not found for the user
     */
    public String resolveGatewayAgentId(String userId, String agentId) {
        if (builderBootstrap.agents().containsKey(agentId)) {
            return agentId;
        }
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found or not accessible: " + agentId));
        String cacheKey = ucaCacheKey(userId, agentId);
        return registeredUcaIds.computeIfAbsent(cacheKey, k -> buildAndRegisterUca(userId, entry));
    }

    /**
     * Returns the gateway agent ID without triggering a build or database lookup.
     * Useful for read-only scenarios (audit logs, session filtering).
     */
    public String peekGatewayAgentId(String userId, String agentId) {
        if (agentId == null) return null;
        if (builderBootstrap.agents().containsKey(agentId)) return agentId;
        return UCA_PREFIX + userId + "-" + agentId;
    }

    /**
     * Returns the running {@link HarnessAgent} for the given user and agent.
     * For globals, returns the bootstrap-registered instance directly.
     * For user-custom agents, resolves (and caches) the gateway ID, then looks it up.
     *
     * <p>Note: this method does NOT perform visibility/permission checks. The caller
     * is responsible for ensuring the user has access to the agent.
     */
    public HarnessAgent getRunningAgent(String userId, String agentId) {
        if (agentId == null) return null;
        if (builderBootstrap.agents().containsKey(agentId)) {
            return builderBootstrap.agents().get(agentId);
        }
        String gatewayId = resolveGatewayAgentId(userId, agentId);
        return builderBootstrap.gateway().findAgent(gatewayId);
    }

    // -----------------------------------------------------------------
    //  Cache invalidation
    // -----------------------------------------------------------------

    /**
     * Drops the cached UCA registration for {@code (userId, agentId)} so the next chat call
     * rebuilds the {@link HarnessAgent} from the current {@link UserAgentDefinitionStore} entry.
     */
    public void invalidateUca(String userId, String agentId) {
        if (userId == null || agentId == null) return;
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));
    }

    /**
     * Drops all cached UCA registrations for the given agent ID across all users.
     * Useful when an agent definition changes globally (e.g., hot-reload of config).
     */
    public void invalidateAllUca(String agentId) {
        if (agentId == null) return;
        String suffix = "/" + agentId;
        registeredUcaIds.keySet().removeIf(key -> key.endsWith(suffix));
    }

    // -----------------------------------------------------------------
    //  Global definitions
    // -----------------------------------------------------------------

    /**
     * Returns the list of global agent definitions loaded from {@code agentscope.json}
     * and the bootstrap agent registry.
     */
    public List<AgentDefinition> globalDefinitions() {
        Map<String, AgentConfigEntry> fileAgents = builderBootstrap.loadedConfig().getAgents();
        List<AgentDefinition> result = new ArrayList<>();
        for (Map.Entry<String, HarnessAgent> e : builderBootstrap.agents().entrySet()) {
            String id = e.getKey();
            AgentConfigEntry cfg = fileAgents != null ? fileAgents.get(id) : null;
            String name = cfg != null && cfg.getName() != null ? cfg.getName() : id;
            String desc = cfg != null ? cfg.getDescription() : null;

            // HarnessAgent does not expose a public getToolkit(); report standard built-in tools.
            List<String> toolNames =
                    List.of(
                            "filesystem",
                            "shell_execute",
                            "memory_search",
                            "memory_get",
                            "session_search");

            AgentConfigEntry.ToolsConfig tc = cfg != null ? cfg.getTools() : null;
            AgentConfigEntry.IdentityConfig ic = cfg != null ? cfg.getIdentity() : null;
            AgentConfigEntry.GroupChatConfig gc = cfg != null ? cfg.getGroupChat() : null;
            AgentConfigEntry.SkillsConfig sk = cfg != null ? cfg.getSkills() : null;

            result.add(
                    new AgentDefinition(
                            id,
                            name,
                            desc,
                            null, // don't expose sysPrompt in global catalog
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolNames,
                            tc != null ? tc.getAllow() : null,
                            tc != null ? tc.getDeny() : null,
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            sk != null ? sk.getAllow() : null,
                            sk != null ? sk.getDeny() : null,
                            AgentDefinition.SCOPE_GLOBAL,
                            null,
                            0L,
                            0L,
                            null, // shares — globals are never shared individually
                            AgentDefinition.RUN_AS_INVOKER,
                            null, // forkOf
                            cfg != null ? cfg.getWorkspace() : null, // mirror runtime workspace
                            null, // sandboxMode — globals follow the platform default
                            null, // sandboxScope
                            null)); // tierForCurrentUser — populated by the controller
        }
        return result;
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    /**
     * Builds a user-custom agent from its stored definition and registers it in the gateway.
     */
    String buildAndRegisterUca(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        String gatewayAgentId = UCA_PREFIX + userId + "-" + entry.id();
        Path workspace = userWorkspacePath(userId, entry);

        HarnessAgent.Builder b = HarnessAgent.builder();

        String name = entry.name() != null ? entry.name() : entry.id();
        b.name(name);

        if (entry.description() != null) {
            b.description(entry.description());
        }
        if (entry.sysPrompt() != null) {
            b.sysPrompt(entry.sysPrompt());
        }
        if (entry.maxIters() != null) {
            b.maxIters(entry.maxIters());
        }
        // Model: prefer per-agent override, fall back to bootstrap-level model.
        if (entry.model() != null && !entry.model().isBlank()) {
            b.model(entry.model());
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);

        // Layered skill repositories: workspace overlay is implicit; explicit entries from the
        // user's saved definition are appended in order so earlier entries win on name clashes.
        if (entry.skillRepositories() != null && !entry.skillRepositories().isEmpty()) {
            var repos = SkillRepositorySupport.createAll(workspace, entry.skillRepositories());
            if (!repos.isEmpty()) {
                b.skillRepositories(repos);
            }
        }

        // Pre-populate this user-custom agent's toolkit with the outbound-send tool so the agent
        // can proactively push messages into any registered IM channel (subject to per-agent
        // tier ACL enforced at OutboundController + channel-routing check in OutboundService).
        Toolkit ucaToolkit = new Toolkit();
        ucaToolkit.registerTool(new OutboundTool(builderBootstrap.channelManager()));
        b.toolkit(ucaToolkit);

        // Apply unified runtime config (Plan Mode, Compaction, Memory, Subagents, Permissions, Sandbox, etc.)
        runtimeConfigurer.accept(b);

        HarnessAgent agent = b.build();
        HarnessGateway gateway = builderBootstrap.gateway();
        gateway.registerAgent(gatewayAgentId, agent);

        log.info(
                "Registered user-custom agent in gateway: userId={}, agentId={}, gatewayId={}",
                userId,
                entry.id(),
                gatewayAgentId);

        return gatewayAgentId;
    }

    private Path userWorkspacePath(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        return workspaceManagerFactory.resolveAgentDataPath(entry.workspacePath(), entry.id());
    }

    private static String ucaCacheKey(String userId, String agentId) {
        return userId + "/" + agentId;
    }
}
