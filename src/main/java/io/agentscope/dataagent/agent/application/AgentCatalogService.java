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
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.agent.domain.AgentShareGrant;
import io.agentscope.dataagent.agent.domain.UserAgentDefinitionStore;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.dataagent.security.domain.UserStore;
import io.agentscope.dataagent.security.domain.UserStore.UserRecord;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Query and visibility logic for the agent catalog: merges global agent definitions (loaded from
 * {@code agentscope.json}) with per-user custom agent definitions, and dynamically instantiates
 * user-custom agents on demand.
 *
 * <p>Mutation operations (create, update, delete, clone, share grants) live in
 * {@link AgentMutationService}.
 *
 * <h2>Visibility rules</h2>
 *
 * <ul>
 *   <li><b>Global agents</b> ({@code scope = "global"}): defined in {@code agentscope.json},
 *       registered in {@link HarnessGateway} at startup. All users can list and converse with
 *       them; each user's conversation is isolated via a separate session keyed by
 *       {@code (userId, agentId)}.
 *   <li><b>User-custom agents</b> ({@code scope = "user"}): stored per-user in
 *       {@code .agentscope/users/{userId}/agents.json}. Only the owning user can see,
 *       create, update, or delete them. On first use they are dynamically built and registered
 *       in the gateway under the namespace key {@code uca-{userId}-{agentId}}.
 * </ul>
 */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);

    private final DataAgentBootstrap builderBootstrap;
    private final UserAgentDefinitionStore store;
    private final AgentLifecycleService lifecycleService;
    private final UserStore userStore;
    private final AgentAclService aclService;

    public AgentCatalogService(
            DataAgentBootstrap builderBootstrap,
            UserAgentDefinitionStore store,
            AgentLifecycleService lifecycleService,
            UserStore userStore,
            AgentAclService aclService) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.lifecycleService = lifecycleService;
        this.userStore = userStore;
        this.aclService = aclService;
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /**
     * Lists all agent definitions visible to the given user: global agents, the user's own
     * custom agents, and any user-custom agents shared with the user via a {@link
     * io.agentscope.dataagent.agent.domain.AgentShareGrant} (USER or WORKSPACE grantee).
     *
     * <p>Globals always appear first; user-custom agents follow in owner-insertion order.
     * Duplicates by id are collapsed (the first match wins, normally the user's own copy).
     */
    public List<AgentDefinition> listVisible(String userId) {
        List<AgentDefinition> result = new ArrayList<>(lifecycleService.globalDefinitions());
        Map<String, AgentDefinition> visibleUserAgents = new LinkedHashMap<>();
        // The user's own agents first so they win id collisions over shared-in ones.
        for (AgentDefinition def : userDefinitions(userId)) {
            visibleUserAgents.put(def.id(), def);
        }
        // Then everyone else's, filtered by ACL.
        for (UserRecord owner : userStore.listAll()) {
            if (owner.userId().equals(userId)) continue;
            for (UserAgentDefinitionStore.StoredEntry e : store.list(owner.userId())) {
                AgentDefinition def = e.toDefinition(owner.userId());
                if (aclService.tierFor(userId, def) != null) {
                    visibleUserAgents.putIfAbsent(def.id(), def);
                }
            }
        }
        result.addAll(visibleUserAgents.values());
        return result;
    }

    /**
     * Finds a single visible agent definition by id.
     */
    public Optional<AgentDefinition> findVisible(String userId, String agentId) {
        return listVisible(userId).stream().filter(d -> d.id().equals(agentId)).findFirst();
    }

    /**
     * Returns the owner of a user-custom agent, or {@link Optional#empty()} for globals / unknown
     * ids. Used by share, clone, and EDIT-delegated-mutation flows to resolve the storage
     * namespace.
     */
    public Optional<String> findOwnerOf(String agentId) {
        if (isGlobal(agentId)) return Optional.empty();
        for (UserRecord owner : userStore.listAll()) {
            if (store.findById(owner.userId(), agentId).isPresent()) {
                return Optional.of(owner.userId());
            }
        }
        return Optional.empty();
    }

    /** Look up the on-disk store entry for a user-custom agent by id, across all owners. */
    public Optional<UserAgentDefinitionStore.StoredEntry> findStoredEntry(String agentId) {
        for (UserRecord owner : userStore.listAll()) {
            Optional<UserAgentDefinitionStore.StoredEntry> e =
                    store.findById(owner.userId(), agentId);
            if (e.isPresent()) return e;
        }
        return Optional.empty();
    }

    /** Returns true if the agent id corresponds to a global (bootstrap-registered) agent. */
    public boolean isGlobal(String agentId) {
        return builderBootstrap.agents().containsKey(agentId);
    }

    /**
     * Returns the running {@link HarnessAgent} for {@code (userId, agentId)}.
     * For globals, returns the bootstrap-registered instance directly.
     * For user-custom agents, delegates to {@link AgentLifecycleService} which
     * resolves (and caches) the gateway ID. Returns {@code null} if the agent
     * is not found.
     *
     * <p>Note: visibility/permission checks are the caller's responsibility.
     */
    public HarnessAgent getRunningAgent(String userId, String agentId) {
        return lifecycleService.getRunningAgent(userId, agentId);
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private List<AgentDefinition> userDefinitions(String userId) {
        return store.list(userId).stream().map(e -> e.toDefinition(userId)).toList();
    }
}