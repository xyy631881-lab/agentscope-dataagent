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
import io.agentscope.dataagent.tools.data.DataAgentToolkit;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tracing.OtelTracingMiddleware;
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
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
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
     * 沙箱工作区快照后端。主 Agent、子代理和浏览器工作区都走框架托管的
     * {@code DockerFilesystemSpec}/{@code SandboxManager}，因此这里必须统一注入同一个
     * snapshot backend（Redis / Noop / future JDBC）。
     */
    private final SandboxSnapshotSpec snapshotSpec;
    /**
     * 沙箱并发守卫。生产多副本下由 Redis/JDBC guard 串行化同一隔离槽的执行；单机开发
     * 可使用 noop guard。
     */
    private final SandboxExecutionGuard executionGuard;
    private final DataAgentToolkit dataAgentToolkit;

    public AgentRuntimeConfigurer(
            AgentStateStore stateStore,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            String modelId,
            String fallbackModelId,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard,
            DataAgentToolkit dataAgentToolkit) {
        this.stateStore = stateStore;
        this.sandboxClient = sandboxClient;
        this.modelId = modelId;
        this.fallbackModelId = fallbackModelId;
        this.snapshotSpec = Objects.requireNonNull(snapshotSpec, "snapshotSpec");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
        this.dataAgentToolkit = Objects.requireNonNull(dataAgentToolkit, "dataAgentToolkit");
    }

    @Override
    public void accept(HarnessAgent.Builder b) {
        // ---- State store & Sandbox filesystem ----
        b.stateStore(stateStore);
        // 就这一行！框架自动生成 agent、model、tool 三个层面的 Span
        // 项目的职责只是 exporter（JpaTraceSpanExporter）和读模型（TraceRunService）
        // Span 生命周期和 Reactor 上下文传播全部由 AgentScope 框架自动处理
        b.middleware(new OtelTracingMiddleware());
        b.filesystem(
                new DockerFilesystemSpec()
                        .client(sandboxClient)
                        // DockerSandbox.doExec always passes this value to `docker exec -w`.
                        // Leaving it null only fails later on the asynchronous session mirror
                        // thread as a ProcessBuilder NPE.
                        .workspaceRoot("/workspace")
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
        defaultSubagentDeclarations().forEach(b::subagent);
        // The framework uses SubagentDeclaration.tools only to filter tools inherited from the
        // parent toolkit. It subsequently adds its default filesystem tools, so the declaration
        // alone cannot confine a data-explorer. Registering the same name last makes this factory
        // authoritative while retaining the declaration for agent_spawn metadata and UI display.
        b.subagentFactory("data-explorer", ignored -> buildRestrictedDataExplorer());

        // ---- Permissions ----
        b.permissionContext(buildPermissionContext());
    }

    /** Returns the built-in subagents exposed by every platform-managed agent. */
    public List<SubagentDeclaration> defaultSubagentDeclarations() {
        return List.of(
                SubagentDeclaration.builder()
                        .name("data-explorer")
                        .description(
                                "Data source discovery specialist. Use when the correct table,"
                                        + " columns, or join path is not yet known. Returns one"
                                        + " canonical source and a sample query.")
                        .model(modelId)
                        .maxIters(12)
                        .tools(List.of("list_data_sources", "describe_table"))
                        .exposeToUser(false)
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build(),
                SubagentDeclaration.builder()
                        .name("report-writer")
                        .description(
                                "Report writing specialist. Use after the main agent has prepared"
                                        + " verified figures, query context, and chart paths."
                                        + " Returns a ready-to-use Markdown report.")
                        .model(modelId)
                        .maxIters(25)
                        .tools(List.of("write_file"))
                        .exposeToUser(true)
                        // The explorer owns isolated scratch state; the writer must publish the
                        // final markdown into the main workspace so the parent and UI can read it.
                        .workspaceMode(WorkspaceMode.SHARED)
                        .build());
    }

    private HarnessAgent buildRestrictedDataExplorer() {
        Toolkit toolkit = restrictedDataExplorerToolkit(dataAgentToolkit);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("data-explorer")
                .description("Data source discovery specialist with read-only metadata access.")
                .sysPrompt(
                        "You are the data-explorer subagent. Use only list_data_sources and "
                                + "describe_table to identify a source, schema, date column, and "
                                + "a sample SQL shape. Never infer from memory, inspect files, run "
                                + "shell commands, delegate work, or execute SQL.")
                .model(modelId)
                .maxIters(12)
                .toolkit(toolkit)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableWorkspaceContext()
                .disableDefaultWorkspaceSkills()
                .disableDynamicSubagents()
                .disableSubagents();
        if (fallbackModelId != null && !fallbackModelId.isBlank()) {
            builder.fallbackModel(fallbackModelId);
        }
        return builder.build();
    }

    static Toolkit restrictedDataExplorerToolkit(DataAgentToolkit dataAgentToolkit) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(dataAgentToolkit);
        // DataAgentToolkit also contains query execution and chart rendering. The explorer may
        // only inspect source metadata, so remove those methods after reflection registration.
        toolkit.removeTool("run_sql_preview");
        toolkit.removeTool("render_chart");
        return toolkit;
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
