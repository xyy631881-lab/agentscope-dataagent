package io.agentscope.dataagent.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.agent.infrastructure.BindingPersistence;
import io.agentscope.dataagent.config.properties.ApiModelProperties;
import io.agentscope.dataagent.config.properties.OllamaProperties;
import io.agentscope.dataagent.conversation.application.ConversationService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentscopeConfig;
import io.agentscope.dataagent.runtime.config.BindingConfigEntry;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.security.infrastructure.IdentityLinkStore;
import io.agentscope.dataagent.debug.AdminLogBuffer;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Compatibility API for the bundled administration console. */
@RestController
@RequestMapping("/api/admin")
public class AdminConsoleController {
    private final DataAgentBootstrap bootstrap;
    private final BindingPersistence persistence;
    private final IdentityLinkStore identityLinks;
    private final ConversationService conversations;
    private final ObjectMapper objectMapper;
    private final ApiModelProperties modelProperties;
    private final OllamaProperties ollamaProperties;
    private final AdminLogBuffer logBuffer;
    private final Instant startedAt = Instant.now();

    public AdminConsoleController(
            DataAgentBootstrap bootstrap,
            BindingPersistence persistence,
            IdentityLinkStore identityLinks,
            ConversationService conversations,
            ObjectMapper objectMapper,
            ApiModelProperties modelProperties,
            OllamaProperties ollamaProperties,
            AdminLogBuffer logBuffer) {
        this.bootstrap = bootstrap;
        this.persistence = persistence;
        this.identityLinks = identityLinks;
        this.conversations = conversations;
        this.objectMapper = objectMapper;
        this.modelProperties = modelProperties;
        this.ollamaProperties = ollamaProperties;
        this.logBuffer = logBuffer;
    }

    @GetMapping("/runtime/channels")
    public List<ChannelView> channels(Authentication auth) {
        requireAdmin(auth);
        return channelViews();
    }

    @GetMapping("/runtime/channels/{channelId}")
    public ChannelView channel(@PathVariable String channelId, Authentication auth) {
        requireAdmin(auth);
        return channelViews().stream()
                .filter(view -> view.channelId().equals(channelId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found: " + channelId));
    }

    @GetMapping("/channels/{channelId}/detail")
    public ChannelDetailView channelDetail(@PathVariable String channelId, Authentication auth) {
        requireAdmin(auth);
        ChannelView channel = channel(channelId, auth);
        List<ChannelSessionRef> sessions = conversations.recentSessions(500).stream()
                .filter(session -> session.getAgentId() != null)
                .map(session -> new ChannelSessionRef(
                        session.getSessionKey(), session.getAgentId(), session.getUserId(),
                        session.getKind(), session.getLastActivityMs(),
                        Math.max(0L, System.currentTimeMillis() - session.getLastActivityMs())))
                .toList();
        List<String> users = sessions.stream().map(ChannelSessionRef::userId)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        return new ChannelDetailView(
                channel,
                new ArrayList<>(bootstrap.agents().keySet()),
                sessions,
                users);
    }

    @GetMapping("/channels/{channelId}/bindings")
    public List<EditableBinding> bindings(@PathVariable String channelId, Authentication auth) {
        requireAdmin(auth);
        ChannelConfigEntry channel = config().getChannels().get(channelId);
        if (channel == null || channel.getBindings() == null) return List.of();
        List<EditableBinding> result = new ArrayList<>();
        for (int i = 0; i < channel.getBindings().size(); i++) {
            BindingConfigEntry binding = channel.getBindings().get(i);
            if (binding != null) result.add(EditableBinding.from(channelId, i, binding));
        }
        return result;
    }

    @PostMapping("/channels/{channelId}/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public BindingMutationResult addBinding(
            @PathVariable String channelId,
            @RequestBody BindingMutationRequest request,
            Authentication auth) {
        requireAdmin(auth);
        if (request == null || request.agentId() == null || request.agentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        int index = persistence.mutate(
                channels -> {
                    ChannelConfigEntry channel = persistence.orCreate(channels, channelId);
                    List<BindingConfigEntry> bindings = persistence.mutableBindings(channel);
                    bindings.add(request.toEntry());
                    return bindings.size() - 1;
                },
                List.of(channelId));
        return new BindingMutationResult(index, "Binding saved to agentscope.json");
    }

    @PutMapping("/channels/{channelId}/bindings/{index}")
    public BindingMutationResult updateBinding(
            @PathVariable String channelId,
            @PathVariable int index,
            @RequestBody BindingMutationRequest request,
            Authentication auth) {
        requireAdmin(auth);
        if (request == null || request.agentId() == null || request.agentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        persistence.mutate(
                channels -> {
                    ChannelConfigEntry channel = channels.get(channelId);
                    if (channel == null || channel.getBindings() == null
                            || index < 0 || index >= channel.getBindings().size()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Binding not found");
                    }
                    persistence.mutableBindings(channel).set(index, request.toEntry());
                    return null;
                },
                List.of(channelId));
        return new BindingMutationResult(index, "Binding updated in agentscope.json");
    }

    @DeleteMapping("/channels/{channelId}/bindings/{index}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @PathVariable String channelId, @PathVariable int index, Authentication auth) {
        requireAdmin(auth);
        persistence.mutate(
                channels -> {
                    ChannelConfigEntry channel = channels.get(channelId);
                    if (channel == null || channel.getBindings() == null
                            || index < 0 || index >= channel.getBindings().size()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Binding not found");
                    }
                    persistence.mutableBindings(channel).remove(index);
                    return null;
                },
                List.of(channelId));
    }

    @GetMapping("/identity-links")
    public Map<String, Map<String, String>> identityLinks(Authentication auth) {
        requireAdmin(auth);
        return identityLinks.snapshot();
    }

    @GetMapping("/config/agentscope")
    public AgentscopeConfig agentscopeConfig(Authentication auth) {
        requireAdmin(auth);
        return config();
    }

    @PutMapping("/config/agentscope")
    public SaveResult saveAgentscopeConfig(@RequestBody JsonNode body, Authentication auth) {
        requireAdmin(auth);
        try {
            AgentscopeConfig parsed = objectMapper.treeToValue(body, AgentscopeConfig.class);
            Path target = bootstrap.configPath();
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), parsed);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new SaveResult(true, "agentscope.json 已保存；重启后完整配置生效");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentscope.json 无效: " + e.getMessage());
        }
    }

    @GetMapping("/config/runtime")
    public Map<String, Object> runtimeConfig(Authentication auth) {
        requireAdmin(auth);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server.port", System.getProperty("server.port", "8080"));
        values.put("dataagent.model.active", modelProperties.getActive());
        values.put("dataagent.model.longcat.model-name", modelProperties.getLongcat().getModelName());
        values.put("dataagent.ollama.base-url", ollamaProperties.getBaseUrl());
        values.put("dataagent.ollama.model.chat", ollamaProperties.getModel().getChat());
        values.put("workspace.root", bootstrap.cwd().toString());
        values.put("agentscope.config", bootstrap.configPath().toString());
        values.put("channel.count", channelViews().size());
        values.put("session.count", conversations.sessionCount());
        return values;
    }

    @PutMapping("/config/runtime")
    public SaveResult saveRuntimeConfig(@RequestBody Map<String, Object> ignored, Authentication auth) {
        requireAdmin(auth);
        return new SaveResult(false, "运行时参数为启动配置，请修改环境变量或 application.yml 后重启");
    }

    @GetMapping("/debug/info")
    public DebugInfo debugInfo(Authentication auth) {
        requireAdmin(auth);
        String model = "longcat".equalsIgnoreCase(modelProperties.getActive())
                ? modelProperties.getLongcat().getModelName()
                : ollamaProperties.getModel().getChat();
        return new DebugInfo(
                "agentscope-dataagent",
                startedAt.toString(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown"),
                model,
                modelProperties.getLongcat().getApiKey() != null
                        && !modelProperties.getLongcat().getApiKey().isBlank(),
                logBuffer.isAttached());
    }

    @GetMapping("/debug/logs")
    public List<String> recentLogs(Authentication auth) {
        requireAdmin(auth);
        return logBuffer.recent();
    }

    @GetMapping(value = "/runtime/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logStream(Authentication auth) {
        requireAdmin(auth);
        return logBuffer.openStream();
    }

    private AgentscopeConfig config() {
        try {
            return DataAgentBootstrap.loadConfigFile(bootstrap.configPath());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 agentscope.json 失败");
        }
    }

    private List<ChannelView> channelViews() {
        AgentscopeConfig config = config();
        Map<String, ChannelConfigEntry> configured = config.getChannels();
        Map<String, ChannelView> views = new LinkedHashMap<>();
        for (Channel channel : bootstrap.channelManager().getAllChannels()) {
            ChannelConfig runtime = channel.config();
            ChannelConfigEntry disk = configured.get(channel.channelId());
            views.put(channel.channelId(), toView(channel.channelId(), runtime, disk, true));
        }
        configured.forEach((id, disk) -> views.putIfAbsent(
                id, toView(id, null, disk, false)));
        return new ArrayList<>(views.values());
    }

    private static ChannelView toView(
            String id, ChannelConfig runtime, ChannelConfigEntry disk, boolean started) {
        String dmScope = runtime != null && runtime.dmScope() != null
                ? runtime.dmScope().name()
                : disk != null ? disk.getDmScope() : "MAIN";
        String defaultAgent = runtime != null && runtime.defaultAgentId() != null
                ? runtime.defaultAgentId()
                : disk != null ? disk.getDefaultAgentId() : null;
        List<BindingView> bindings = new ArrayList<>();
        if (disk != null && disk.getBindings() != null) {
            for (BindingConfigEntry binding : disk.getBindings()) {
                if (binding != null) bindings.add(BindingView.from(binding));
            }
        }
        return new ChannelView(id, dmScope, defaultAgent, started, 0, bindings.size(), bindings);
    }

    private static void requireAdmin(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null
                || auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .noneMatch("ROLE_ADMIN"::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    public record ChannelView(
            String channelId,
            String dmScope,
            String defaultAgentId,
            boolean started,
            int outboundQueueSize,
            int bindingCount,
            List<BindingView> bindings) {}

    public record ChannelDetailView(
            ChannelView channel,
            List<String> agents,
            List<ChannelSessionRef> sessions,
            List<String> users) {}

    public record ChannelSessionRef(
            String sessionKey,
            String agentId,
            String userId,
            String kind,
            long lastActivityMs,
            long idleMs) {}

    public record BindingView(
            String agentId,
            String peerId,
            String guildId,
            String roomId,
            String sessionScope) {
        static BindingView from(BindingConfigEntry binding) {
            return new BindingView(
                    binding.getAgentId(), binding.getPeer(), binding.getGuild(),
                    binding.getChannel(), binding.getSessionScope());
        }
    }

    public record EditableBinding(
            int index,
            String agentId,
            String peer,
            String parentPeer,
            String guild,
            List<String> roles,
            String team,
            String account,
            String channel,
            String sessionScope,
            String tier) {
        static EditableBinding from(String channelId, int index, BindingConfigEntry binding) {
            return new EditableBinding(index, binding.getAgentId(), binding.getPeer(),
                    binding.getParentPeer(), binding.getGuild(), binding.getRoles(),
                    binding.getTeam(), binding.getAccount(), binding.getChannel(),
                    binding.getSessionScope(), AgentBindingController.deriveTier(binding));
        }
    }

    public record BindingMutationRequest(
            String agentId,
            String peer,
            String parentPeer,
            String guild,
            List<String> roles,
            String team,
            String account,
            String channel,
            String sessionScope) {
        BindingConfigEntry toEntry() {
            BindingConfigEntry entry = new BindingConfigEntry();
            entry.setAgentId(agentId);
            entry.setPeer(blank(peer));
            entry.setParentPeer(blank(parentPeer));
            entry.setGuild(blank(guild));
            entry.setRoles(roles == null || roles.isEmpty() ? null : List.copyOf(roles));
            entry.setTeam(blank(team));
            entry.setAccount(blank(account));
            entry.setChannel(blank(channel));
            entry.setSessionScope(blank(sessionScope));
            return entry;
        }

        private static String blank(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }

    public record BindingMutationResult(int index, String message) {}
    public record SaveResult(boolean success, String message) {}
    public record DebugInfo(
            String application,
            String startedAt,
            String javaVersion,
            String osName,
            String modelName,
            boolean apiKeyConfigured,
            boolean logAppenderAttached) {}
}
