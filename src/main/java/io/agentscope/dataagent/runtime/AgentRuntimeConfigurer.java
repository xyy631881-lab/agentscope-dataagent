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
package io.agentscope.dataagent.runtime;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Unified runtime configuration for every {@link HarnessAgent} built by this
 * platform. Both {@code DataAgentBootstrap} (for global agents) and
 * {@code AgentLifecycleService} (for user-custom agents) apply this configurer
 * so that all agents share the same capabilities: Plan Mode, Compaction,
 * Memory, Subagents, Permissions, Sandbox, State store, model retry/fallback.
 *
 * <p>This class replaces the inline lambda that was previously in
 * {@code DataAgentConfig.configureAllAgents()} and the manual partial
 * configuration in {@code AgentLifecycleService.buildAndRegisterUca()}.
 */
public final class AgentRuntimeConfigurer implements Consumer<HarnessAgent.Builder> {

    private final AgentStateStore stateStore;
    private final SandboxClient<DockerSandboxClientOptions> sandboxClient;
    private final String modelName;
    private final String fallbackModelName;

    public AgentRuntimeConfigurer(
            AgentStateStore stateStore,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            String modelName,
            String fallbackModelName) {
        this.stateStore = stateStore;
        this.sandboxClient = sandboxClient;
        this.modelName = modelName;
        this.fallbackModelName = fallbackModelName;
    }

    @Override
    public void accept(HarnessAgent.Builder b) {
        // ---- State store & Sandbox filesystem ----
        b.stateStore(stateStore);
        b.filesystem(
                new DockerFilesystemSpec()
                        .client(sandboxClient)
                        .isolationScope(IsolationScope.USER));

        // ---- Model retry & fallback ----
        b.maxRetries(2);
        if (fallbackModelName != null && !fallbackModelName.isBlank()) {
            b.fallbackModel("ollama:" + fallbackModelName);
        }

        // ---- Plan Mode ----
        b.enablePlanMode();

        // ---- Compaction & Memory ----
        b.compaction(
                CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build());
        b.toolResultEviction(ToolResultEvictionConfig.defaults());
        b.memory(
                MemoryConfig.builder()
                        .flushTrigger(
                                MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                        .build());

        // ---- Subagent declarations ----
        b.subagent(
                SubagentDeclaration.builder()
                        .name("code-reviewer")
                        .description(
                                "Code review specialist. Reviews data-analysis scripts, SQL,"
                                        + " and chart definitions. Returns structured findings"
                                        + " with severity levels.")
                        .model("ollama:" + modelName)
                        .maxIters(5)
                        .exposeToUser(false)
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build());

        b.subagent(
                SubagentDeclaration.builder()
                        .name("report-writer")
                        .description(
                                "Report writer. Composes data-analysis reports in Markdown."
                                        + " Takes findings and chart descriptions, produces"
                                        + " polished narrative.")
                        .model(modelName)
                        .maxIters(8)
                        .exposeToUser(true)
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build());

        // ---- Permissions ----
        b.permissionContext(buildPermissionContext());
    }

    private static PermissionContextState buildPermissionContext() {
        return PermissionContextState.builder()
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "list_data_sources", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "describe_table", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "render_chart", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "outbound_send", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "agent_spawn", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "agent_send", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "agent_list", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "memory_search", null, PermissionBehavior.ALLOW, "default"))
                .addAllowRule(
                        "default",
                        new PermissionRule(
                                "memory_get", null, PermissionBehavior.ALLOW, "default"))
                .addAskRule(
                        "sql_execution",
                        new PermissionRule(
                                "run_sql_preview", null, PermissionBehavior.ASK, "sql_execution"))
                .build();
    }
}
