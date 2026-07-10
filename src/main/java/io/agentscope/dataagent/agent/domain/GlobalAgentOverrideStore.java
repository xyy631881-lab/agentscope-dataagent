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
package io.agentscope.dataagent.agent.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for <em>admin overrides</em> on global (bootstrap-registered) agents.
 *
 * <p>A global agent such as {@code data-agent} is defined in {@code agentscope.json} and registered
 * at startup by the AgentScope framework. Its definition is therefore not editable through the
 * per-user custom-agent store. This store holds the delta an administrator applies through the web
 * UI (name, system prompt, model, tool/skill allow-deny lists, identity, sandbox settings, and
 * share grants). At read time the override is layered on top of the bootstrap definition so the
 * catalog reflects the admin's edits; at startup the same override is folded back into the
 * runtime agent configuration so the running agent honours the edits after a restart.
 *
 * <p>Fields intentionally excluded from overrides: {@code workspacePath} (moving a global agent's
 * shared workspace is unsafe), {@code forkOf} (not meaningful for globals), and the effective
 * runtime {@code tools} list (resolved by the framework).
 *
 * <p>Implementations are expected to be thread-safe.
 */
public interface GlobalAgentOverrideStore {

    /** Returns the override for {@code agentId}, or empty if the admin never edited it. */
    Optional<GlobalOverride> findById(String agentId);

    /** Saves (creates or updates) the override for a global agent. */
    GlobalOverride save(GlobalOverride entry);

    /** Deletes the override, reverting the global agent to its bootstrap definition. */
    void delete(String agentId);

    // -----------------------------------------------------------------
    //  Stored data model — the admin-editable delta for a global agent
    // -----------------------------------------------------------------

    /**
     * JSON-serialisable override for a global agent. Every field is optional except {@code id}
     * (the global agent id). A {@code null} field means "keep the bootstrap default".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GlobalOverride(
            String id,
            String name,
            String description,
            String sysPrompt,
            String model,
            Integer maxIters,
            List<String> toolsAllow,
            List<String> toolsDeny,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            List<String> skillsAllow,
            List<String> skillsDeny,
            long createdAt,
            long updatedAt,
            List<AgentShareGrant> shares,
            String runAs,
            String sandboxMode,
            String sandboxScope) {

        /** Projects this override into an API {@link AgentDefinition} for the global scope. */
        public AgentDefinition toDefinition() {
            return new AgentDefinition(
                    id,
                    name != null ? name : id,
                    description,
                    sysPrompt,
                    model,
                    maxIters,
                    null, // effective tool list resolved at runtime
                    toolsAllow,
                    toolsDeny,
                    identityName,
                    identityEmoji,
                    groupChatMentionPatterns,
                    groupChatRequireMention,
                    skillsAllow,
                    skillsDeny,
                    AgentDefinition.SCOPE_GLOBAL,
                    null, // globals have no owner
                    createdAt,
                    updatedAt,
                    shares,
                    runAs != null ? runAs : AgentDefinition.RUN_AS_INVOKER,
                    null, // forkOf — not applicable to globals
                    null, // workspacePath — controlled by bootstrap, not editable
                    sandboxMode,
                    sandboxScope,
                    null); // tierForCurrentUser — populated by the controller
        }
    }
}
