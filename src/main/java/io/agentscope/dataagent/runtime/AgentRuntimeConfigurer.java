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
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.config.DataAgentConfig;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.time.Duration;
import java.util.Objects;
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
    /** 默认模型在 ModelRegistry 中的 id（{@code local} 或 {@code longcat}）。 */
    private final String modelId;
    /** 兜底模型 id，为空则不设置兜底。 */
    private final String fallbackModelId;
    /**
     * 沙箱工作区快照后端。本平台主 Agent 走 {@code externalSandbox}（由 UserSandboxPool
     * 注入，其 {@code SandboxContext} 已带快照），故主路径不受此字段影响。
     * 但<b>兜底路径</b>（borrow 失败、框架回退到 agent 默认 SandboxContext）与
     * <b>子代理（ISOLATED）</b>创建独立沙箱时，会用到这个挂在本 agent 上的
     * {@code DockerFilesystemSpec}——若不带上快照，容器回收后子代理产出文件（如
     * report-writer 的报告）将无快照可恢复。故此处必须与 UserSandboxPool 共用同一
     * 后端（Redis / Noop），由 {@code SandboxSnapshotConfig} 统一装配。
     */
    private final SandboxSnapshotSpec snapshotSpec;
    /**
     * 沙箱并发守卫。仅对框架托管的沙箱（Priority 3/4、子代理）生效；主 Agent 走
     * externalSandbox（Priority 1，守卫被绕过），真实并发由 {@code SandboxLock}
     * 在回合边界串行化。此处仍注入，使子代理路径也具备分布式串行能力。
     */
    private final SandboxExecutionGuard executionGuard;

    public AgentRuntimeConfigurer(
            AgentStateStore stateStore,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            String modelId,
            String fallbackModelId,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard) {
        this.stateStore = stateStore;
        this.sandboxClient = sandboxClient;
        this.modelId = modelId;
        this.fallbackModelId = fallbackModelId;
        this.snapshotSpec = Objects.requireNonNull(snapshotSpec, "snapshotSpec");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
    }

    @Override
    public void accept(HarnessAgent.Builder b) {
        // ---- State store & Sandbox filesystem ----
        b.stateStore(stateStore);
        // 主 Agent 经 UserSandboxContextMiddleware 注入 externalSandbox（带快照），
        // 故此处 spec 主要用于：①borrow 失败时的回退 SandboxContext；②子代理（ISOLATED）
        // 独立沙箱。两者都必须带上 snapshotSpec + executionGuard，否则子代理产出文件
        // 在容器回收后无快照可恢复（P0）、且多副本下并发无串行（P1 子代理路径）。
        b.filesystem(
                new DockerFilesystemSpec()
                        .client(sandboxClient)
                        .isolationScope(IsolationScope.USER)
                        .snapshotSpec(snapshotSpec)
                        .executionGuard(executionGuard));

        // ---- Model retry & fallback ----
        b.maxRetries(2);
        if (fallbackModelId != null && !fallbackModelId.isBlank()) {
            b.fallbackModel(fallbackModelId);
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
                        .model(modelId)
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
                        .model(modelId)
                        .maxIters(8)
                        .exposeToUser(true)
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build());

        // ---- Permissions ----
        b.permissionContext(buildPermissionContext());
    }

    /**
     * Builds the permission context for the (trusted, local) data-agent.
     *
     * <p>IMPORTANT: the first argument of {@code addAllowRule}/{@code addAskRule} is the rule table
     * KEY — PermissionEngine looks rules up via {@code table.get(tool.getName())}, so it
     * MUST equal the real tool name (NOT a logical group like "default"). A wrong key silently drops
     * the rule and the tool falls through to the default ASK behaviour, which surfaces as a
     * human-in-the-loop pause in the chat link.
     *
     * <p>This platform runs in {@link PermissionMode#BYPASS} with a single {@code run_sql_preview}
     * ASK rule. BYPASS does NOT mean "allow everything unconditionally" — it is the *fallback
     * default* (ALLOW) applied only when no Deny/Ask/Allow rule matches. The permission engine
     * evaluates Ask rules BEFORE the BYPASS fallback, so this one ASK rule still pauses the agent
     * for human confirmation (the {@code ChatController} HITL confirm-back channel surfaces it as a
     * UI card), while every other tool auto-runs without enumeration. We deliberately do NOT use
     * {@link PermissionMode#DEFAULT}: its fallback is ASK, which would pause every tool lacking an
     * explicit Allow rule and force a long, fragile allow-list (a single mis-keyed rule silently
     * re-introduces the stuck-in-ASKING bug).
     */
    private static PermissionContextState buildPermissionContext() {
        return PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                // The chat link now implements the HITL confirm-back channel (see ChatController),
                // so specific tools can be gated behind human approval. BYPASS lets every other tool
                // run autonomously; the single ASK rule below intercepts run_sql_preview (a data
                // surface) and pauses the agent until the user approves/rejects in the UI. The
                // permission engine evaluates askRules BEFORE the BYPASS fallback, so this one rule
                // is enough — no need to enumerate every other tool name.
                .addAskRule(
                        "run_sql_preview",
                        new PermissionRule(
                                "run_sql_preview", null, PermissionBehavior.ASK, "sql_execution"))
                .build();
    }
}