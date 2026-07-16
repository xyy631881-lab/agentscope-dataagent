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
import io.agentscope.dataagent.agent.application.WorkspaceResolutionService;
import io.agentscope.dataagent.agent.application.AgentAclService;
import io.agentscope.dataagent.workspace.infrastructure.WorkspaceManagerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.dataagent.agent.domain.ActivityEvent;
import io.agentscope.dataagent.agent.application.AgentActivityStore;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.agent.application.AgentAccessGuard;
import io.agentscope.dataagent.agent.application.AgentAclService.Tier;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Agent 在平台多租户护栏下的工具管理端点。镜像
 * claw 的 {@code AgentToolsController} 1:1 的 URL/负载形状，但每个入口点都由
 * {@link AgentAccessGuard} 守卫，跨用户访问返回 403，所有 workspace I/O
 * 都通过 {@link WorkspaceManagerFactory} 进行，以便写入进入每个（所有者，Agent）
 * 实时的 Docker 沙箱（与 Agent 运行时使用的容器相同）。
 *
 * <p>端点：
 *
 * <ul>
 *   <li>{@code GET /active} — 实时工具列表（RUN）。使用无操作模型构建一个瞬态
 *       {@link HarnessAgent}，其 workspace + 文件系统来自
 *       {@link WorkspaceManagerFactory#forAgent}（或全局的 {@code forGlobalAgent}），
 *       以便在 {@code tools.json} 中配置的 MCP 服务器通过它们将运行的同一命名空间进行检查。
 *   <li>{@code GET /config} — 读取 {@code workspace/tools.json}（RUN）。
 *   <li>{@code PUT /config} — 覆盖 {@code workspace/tools.json}（EDIT）。记录一个
 *       {@link ActivityEvent.Action#EDIT_SETTINGS} 条目并驱逐 UCA 注册缓存，
 *       以便下一次聊天调用重建 Agent。
 *   <li>{@code GET /catalog/builtins} — 镜像 harness 内置工具的静态列表（RUN）。
 *   <li>{@code GET /catalog/mcp-servers} — 来自 {@code classpath:catalog/mcp-servers.json} 的
 *       捆绑 MCP 服务器模板（RUN）。
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
public class AgentToolsController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsController.class);

    /**
     * Harness 内置工具的规范列表。镜像 {@code HarnessAgent.Builder.build()} 中执行的注册；
     * 用于 {@code /active} 上的来源归属，也作为 {@code /catalog/builtins} 响应。
     */
    private static final List<BuiltinToolInfo> BUILTIN_TOOLS =
            List.of(
                    new BuiltinToolInfo(
                            "read_file", "Read a file from the workspace.", "filesystem"),
                    new BuiltinToolInfo(
                            "write_file",
                            "Write or overwrite a file in the workspace.",
                            "filesystem"),
                    new BuiltinToolInfo(
                            "edit_file", "Apply a diff to an existing file.", "filesystem"),
                    new BuiltinToolInfo(
                            "list_files", "List files under a directory.", "filesystem"),
                    new BuiltinToolInfo(
                            "grep_files", "Search file contents with a regex.", "filesystem"),
                    new BuiltinToolInfo(
                            "glob_files", "Find files matching a glob pattern.", "filesystem"),
                    new BuiltinToolInfo(
                            "memory_search",
                            "Semantic search across the agent's long-term memory.",
                            "memory"),
                    new BuiltinToolInfo(
                            "memory_get", "Fetch a specific memory entry by id.", "memory"),
                    new BuiltinToolInfo(
                            "session_search",
                            "Search prior session transcripts for context.",
                            "memory"),
                    new BuiltinToolInfo(
                            "execute",
                            "Execute a shell command (sandbox / local-shell modes only).",
                            "shell"));

    private static final Set<String> BUILTIN_NAMES;
    private static final Set<String> DATA_AGENT_TOOL_NAMES =
            Set.of("list_data_sources", "describe_table", "run_sql_preview", "render_chart");

    static {
        BUILTIN_NAMES = new HashSet<>();
        for (BuiltinToolInfo b : BUILTIN_TOOLS) BUILTIN_NAMES.add(b.id());
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;
    private final AgentCatalogService catalogService;
    private final AgentLifecycleService lifecycleService;
    private final WorkspaceResolutionService resolutionService;
    private final List<McpCatalogEntry> mcpCatalog;

    public AgentToolsController(
            AgentAccessGuard guard,
            AgentActivityStore activity,
            AgentCatalogService catalogService,
            AgentLifecycleService lifecycleService,
            WorkspaceResolutionService resolutionService) {
        this.guard = guard;
        this.activity = activity;
        this.catalogService = catalogService;
        this.lifecycleService = lifecycleService;
        this.resolutionService = resolutionService;
        this.mcpCatalog = AgentToolsSupport.loadMcpCatalog();
    }

    // -----------------------------------------------------------------
    //  Live tool list
    // -----------------------------------------------------------------

    @GetMapping("/active")
    public ActiveToolsResponse active(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    guard.require(userId, agentId, Tier.RUN);
                    return introspect(userId, agentId);
    }

    private ActiveToolsResponse introspect(String userId, String agentId) {
        List<String> warnings = new ArrayList<>();
        WorkspaceManager wsm = resolutionService.resolveManager(userId, agentId);
        try {
            HarnessAgent agent = lifecycleService.getRunningAgent(userId, agentId);
            if (agent == null) {
                agent =
                        HarnessAgent.builder()
                                .name("__tools_introspect__")
                                .model(new NoopModel())
                                .workspace(wsm.getWorkspace())
                                .abstractFilesystem(wsm.getFilesystem())
                                .build();
                warnings.add("Agent has not run yet; showing the base harness tool set.");
            }
            List<ToolSchema> schemas = agent.getDelegate().getToolkit().getToolSchemas();
            List<ActiveTool> tools = new ArrayList<>();
            for (ToolSchema s : schemas) {
                String source = toolSource(s.getName());
                tools.add(new ActiveTool(s.getName(), s.getDescription(), source));
            }
            return new ActiveToolsResponse(tools, warnings);
        } catch (Exception e) {
            log.warn("Transient agent build failed for {}/{}: {}", userId, agentId, e.getMessage());
            warnings.add(
                    "Live introspection failed ("
                            + e.getMessage()
                            + "). Showing config-only view.");
            return configOnlyView(wsm, warnings);
        }
    }

    private static String toolSource(String name) {
        if (BUILTIN_NAMES.contains(name)) return "built-in";
        if (DATA_AGENT_TOOL_NAMES.contains(name)) return "data-agent";
        return "mcp";
    }

    private ActiveToolsResponse configOnlyView(WorkspaceManager wsm, List<String> warnings) {
        ToolsConfig cfg = readConfig(wsm);
        List<ActiveTool> tools = new ArrayList<>();
        Set<String> deny =
                cfg != null && cfg.getDeny() != null ? new HashSet<>(cfg.getDeny()) : Set.of();
        Set<String> allow =
                cfg != null && cfg.getAllow() != null && !cfg.getAllow().isEmpty()
                        ? new HashSet<>(cfg.getAllow())
                        : null;
        for (BuiltinToolInfo b : BUILTIN_TOOLS) {
            if (deny.contains(b.id())) continue;
            if (allow != null && !allow.contains(b.id())) continue;
            tools.add(new ActiveTool(b.id(), b.description(), "built-in"));
        }
        if (cfg != null && cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                tools.add(
                        new ActiveTool(
                                e.getKey(),
                                "MCP server (" + e.getValue().getTransport() + ")",
                                "mcp"));
            }
        }
        return new ActiveToolsResponse(tools, warnings);
    }

    // -----------------------------------------------------------------
    //  tools.json read/write
    // -----------------------------------------------------------------

    @GetMapping("/config")
    public ToolsConfig getConfig(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    guard.require(userId, agentId, Tier.RUN);
                    WorkspaceManager wsm = resolutionService.resolveManager(userId, agentId);
                    ToolsConfig cfg = readConfig(wsm);
                    return cfg != null ? cfg : new ToolsConfig();
    }

    @PutMapping("/config")
    public ToolsConfig putConfig(
            @PathVariable String agentId, @RequestBody ToolsConfig body, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    if (body == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Request body is required");
                    }
                    AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
                    AgentToolsSupport.validate(body);
                    WorkspaceManager wsm = resolutionService.resolveManager(userId, agentId);
                    String json;
                    try {
                        json = MAPPER.writeValueAsString(body);
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to serialize tools.json: " + e.getMessage());
                    }
                    wsm.writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(), "tools.json", json + "\n");
                    String ownerId =
                            def.ownerId() != null
                                    ? def.ownerId()
                                    : catalogService.findOwnerOf(agentId).orElse(userId);
                    activity.record(
                            ownerId,
                            agentId,
                            activity.actor(userId),
                            ActivityEvent.Action.EDIT_SETTINGS,
                            "tools.json",
                            Map.of(
                                    "allowCount",
                                            body.getAllow() != null ? body.getAllow().size() : 0,
                                    "denyCount", body.getDeny() != null ? body.getDeny().size() : 0,
                                    "mcpCount",
                                            body.getMcpServers() != null
                                                    ? body.getMcpServers().size()
                                                    : 0));
                    lifecycleService.invalidateUca(ownerId, agentId);
                    return body;
    }

    private ToolsConfig readConfig(WorkspaceManager wsm) {
        try {
            String raw = wsm.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), "tools.json");
            if (raw == null || raw.isBlank()) return null;
            return MAPPER.readValue(raw, ToolsConfig.class);
        } catch (Exception e) {
            log.debug("tools.json missing or unreadable: {}", e.getMessage());
            return null;
        }
    }



    // -----------------------------------------------------------------
    //  Catalogs
    // -----------------------------------------------------------------

    @GetMapping("/catalog/builtins")
    public List<BuiltinToolInfo> catalogBuiltins(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    guard.require(userId, agentId, Tier.RUN);
                    return BUILTIN_TOOLS;
    }

    @GetMapping("/catalog/mcp-servers")
    public List<McpCatalogEntry> catalogMcpServers(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();

                    guard.require(userId, agentId, Tier.RUN);
                    return mcpCatalog;
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------



    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveTool(String name, String description, String source) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ActiveToolsResponse(List<ActiveTool> tools, List<String> warnings) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BuiltinToolInfo(String id, String description, String group) {}

    /**
     * Bundled MCP server template loaded from {@code classpath:catalog/mcp-servers.json}. Mirrors
     * {@link McpServerConfig} plus a small bit of UI metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record McpCatalogEntry(
            String id,
            String name,
            String description,
            String transport,
            String url,
            String command,
            List<String> args,
            Map<String, String> env,
            Map<String, String> headers,
            Map<String, String> queryParams,
            List<String> requiredEnv,
            String docsUrl) {}

    /** No-op model used only for tool-schema introspection — {@link #stream} is never invoked. */
    private static final class NoopModel implements Model {
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "noop";
        }
    }
}
