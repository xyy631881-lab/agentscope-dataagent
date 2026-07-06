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
package io.agentscope.dataagent.agent.content;

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
import io.agentscope.dataagent.agent.activity.ActivityEvent;
import io.agentscope.dataagent.agent.activity.AgentActivityStore;
import io.agentscope.dataagent.agent.catalog.AgentCatalogService;
import io.agentscope.dataagent.agent.catalog.AgentDefinition;
import io.agentscope.dataagent.agent.catalog.AgentLifecycleService;
import io.agentscope.dataagent.agent.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.agent.sharing.AgentAccessGuard;
import io.agentscope.dataagent.agent.sharing.AgentAclService.Tier;
import io.agentscope.dataagent.infrastructure.workspace.WorkspaceManagerFactory;
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
import java.util.Optional;
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
import reactor.core.publisher.Mono;

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
    private final WorkspaceManagerFactory workspaceFactory;
    private final List<McpCatalogEntry> mcpCatalog;

    public AgentToolsController(
            AgentAccessGuard guard,
            AgentActivityStore activity,
            AgentCatalogService catalogService,
            WorkspaceManagerFactory workspaceFactory,
            AgentLifecycleService lifecycleService) {
        this.guard = guard;
        this.activity = activity;
        this.catalogService = catalogService;
        this.lifecycleService = lifecycleService;
        this.workspaceFactory = workspaceFactory;
        this.mcpCatalog = loadMcpCatalog();
    }

    // -----------------------------------------------------------------
    //  Live tool list
    // -----------------------------------------------------------------

    @GetMapping("/active")
    public Mono<ActiveToolsResponse> active(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return introspect(userId, agentId);
                });
    }

    private ActiveToolsResponse introspect(String userId, String agentId) {
        List<String> warnings = new ArrayList<>();
        WorkspaceManager wsm = resolveWorkspaceManager(userId, agentId);
        try {
            HarnessAgent agent =
                    HarnessAgent.builder()
                            .name("__tools_introspect__")
                            .model(new NoopModel())
                            .workspace(wsm.getWorkspace())
                            .abstractFilesystem(wsm.getFilesystem())
                            .build();
            List<ToolSchema> schemas = agent.getDelegate().getToolkit().getToolSchemas();
            List<ActiveTool> tools = new ArrayList<>();
            for (ToolSchema s : schemas) {
                String source = BUILTIN_NAMES.contains(s.getName()) ? "built-in" : "mcp";
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
    public Mono<ToolsConfig> getConfig(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    WorkspaceManager wsm = resolveWorkspaceManager(userId, agentId);
                    ToolsConfig cfg = readConfig(wsm);
                    return cfg != null ? cfg : new ToolsConfig();
                });
    }

    @PutMapping("/config")
    public Mono<ToolsConfig> putConfig(
            @PathVariable String agentId, @RequestBody ToolsConfig body, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (body == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Request body is required");
                    }
                    AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
                    validate(body);
                    WorkspaceManager wsm = resolveWorkspaceManager(userId, agentId);
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
                });
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

    private static void validate(ToolsConfig cfg) {
        if (cfg.getAllow() != null) {
            for (String n : cfg.getAllow()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "allow contains a blank entry");
                }
            }
        }
        if (cfg.getDeny() != null) {
            for (String n : cfg.getDeny()) {
                if (n == null || n.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "deny contains a blank entry");
                }
            }
        }
        if (cfg.getMcpServers() != null) {
            for (Map.Entry<String, McpServerConfig> e : cfg.getMcpServers().entrySet()) {
                String name = e.getKey();
                McpServerConfig s = e.getValue();
                if (name == null || name.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers contains a blank server name");
                }
                if (s == null) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "mcpServers." + name + " is null");
                }
                String t = s.getTransport();
                if (t == null || t.isBlank()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "mcpServers." + name + ": transport is required");
                }
                String tl = t.toLowerCase(Locale.ROOT);
                switch (tl) {
                    case "stdio" -> {
                        if (s.getCommand() == null || s.getCommand().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": stdio requires 'command'");
                        }
                    }
                    case "sse", "http", "streamable-http", "streamablehttp" -> {
                        if (s.getUrl() == null || s.getUrl().isBlank()) {
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers." + name + ": " + tl + " requires 'url'");
                        }
                    }
                    default ->
                            throw new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST,
                                    "mcpServers."
                                            + name
                                            + ": unsupported transport '"
                                            + t
                                            + "' (expected stdio, sse, http)");
                }
            }
        }
    }

    // -----------------------------------------------------------------
    //  Catalogs
    // -----------------------------------------------------------------

    @GetMapping("/catalog/builtins")
    public Mono<List<BuiltinToolInfo>> catalogBuiltins(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return BUILTIN_TOOLS;
                });
    }

    @GetMapping("/catalog/mcp-servers")
    public Mono<List<McpCatalogEntry>> catalogMcpServers(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    return mcpCatalog;
                });
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Builds the {@link WorkspaceManager} the agent would use at run time. For globals the user is
     * passed as both userId and agentId-owner — only runtime data routes are per-(userId, agentId);
     * the shared workspace content is read from the local disk root. For user-custom agents we look
     * up the owner via {@link AgentCatalogService#findOwnerOf} so a shared-in user's writes still
     * land in the original namespace (which is what the agent's HarnessGateway registration uses).
     */
    private WorkspaceManager resolveWorkspaceManager(String userId, String agentId) {
        if (catalogService.isGlobal(agentId)) {
            return workspaceFactory.forGlobalAgent(userId, agentId);
        }
        String ownerId = catalogService.findOwnerOf(agentId).orElse(userId);
        Optional<UserAgentDefinitionStore.StoredEntry> entry =
                catalogService.findStoredEntry(agentId);
        String workspacePath =
                entry.map(UserAgentDefinitionStore.StoredEntry::workspacePath).orElse(null);
        return workspaceFactory.forAgent(ownerId, agentId, workspacePath);
    }

    private static List<McpCatalogEntry> loadMcpCatalog() {
        ClassPathResource r = new ClassPathResource("catalog/mcp-servers.json");
        if (!r.exists()) {
            log.warn("catalog/mcp-servers.json not found on classpath; MCP catalog is empty.");
            return List.of();
        }
        try (InputStream in = r.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String json = new String(bytes, StandardCharsets.UTF_8);
            McpCatalogEntry[] arr = MAPPER.readValue(json, McpCatalogEntry[].class);
            return List.of(arr);
        } catch (Exception e) {
            log.warn("Failed to load catalog/mcp-servers.json: {}", e.getMessage());
            return List.of();
        }
    }

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
