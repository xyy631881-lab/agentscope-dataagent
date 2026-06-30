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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.runtime.config.AgentConfigEntry;
import io.agentscope.dataagent.runtime.config.AgentscopeConfig;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.config.ChannelTypeRegistry;
import io.agentscope.dataagent.runtime.config.SkillRepositorySupport;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.dataagent.runtime.outbound.OutboundTool;
import io.agentscope.dataagent.runtime.session.AgentManagerConfig;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionMaintenanceConfig;
import io.agentscope.dataagent.runtime.session.SessionStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于组装、配置和运行 agentscope harness 的单一 API 类。
 *
 * <h2>构建阶段 — {@link #builder()}</h2>
 *
 * 加载 {@code ~/.agentscope/dataagent/agentscope.json}（每个 app 独立的 home 目录，与其他
 * harness app 和 JVM 启动时的 cwd 隔离），将基于文件的 Agent 定义与程序化的 {@link Builder} 配置
 * 合并，生成配备 AgentScope 2.0 内置 SubagentsMiddleware 和共享的
 * {@link SessionAgentManager} + {@link HarnessGateway} 的 {@link HarnessAgent} 实例。
 *
 * <p>Phase 3 (2026-06-29): 子代理管理已从自研 SessionsTool 迁移到 AgentScope 2.0 内置的
 * SubagentsMiddleware。Agent 现在使用 agent_spawn / agent_send / agent_list 等标准工具。
 * SessionAgentManager 保留用于 web 层 MAIN session 状态管理。
 *
 * <h2>运行时阶段 — 两个入口点</h2>
 *
 * <ol>
 *   <li>{@link #chatUiChannel()} — 获取 {@link ChatUiChannel} 用于直接程序化交互
 *       （嵌入式 UI、CLI、测试）。立即可用，无需调用 {@link #start()}。
 *   <li>{@link #start()} — 初始化并启动所有预注册的 Channel 适配器。
 * </ol>
 */
public final class DataAgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DataAgentBootstrap.class);
    private static final String DEFAULT_MAIN_ID = "default";

    /**
     * 每个 data-agent 实例的默认 workspace 根目录。位于项目树之外，以便共享的 workspace 根目录
     * （模板、默认的 {@code AGENTS.md} / skills / subagents）和每个租户的远程命名空间不会污染
     * app 启动时的 cwd，并且两个不同的 harness app 不会冲突使用同一个 {@code .agentscope/workspace/} 目录。
     */
    public static final Path DEFAULT_WORKSPACE_ROOT =
            Paths.get(System.getProperty("user.home"), ".agentscope", "dataagent", "workspace");

    /**
     * {@code agentscope.json} 配置文件的默认位置。固定在每个 app 的 home 目录
     * （{@code ~/.agentscope/dataagent/}），以确保 dataagent web app 不会捡到由其他
     * harness app（如 builder、codingagent）留在启动 cwd 中的过期配置。
     * {@link io.agentscope.dataagent.web.config.DataAgentConfig} 在首次启动时若文件不存在会自动生成此文件。
     */
    public static final Path DEFAULT_CONFIG_PATH =
            Paths.get(
                    System.getProperty("user.home"), ".agentscope", "dataagent", "agentscope.json");

    // -----------------------------------------------------------------
    //  实例状态 — 由 Builder.build() 填充
    // -----------------------------------------------------------------

    private final Path cwd;
    private final Path configPath;
    private final String mainAgentId;
    private final Map<String, HarnessAgent> agents;
    private final AgentscopeConfig loadedConfig;
    private final List<Channel> registeredChannels;
    private final HarnessGateway gateway;
    private final SessionAgentManager sessionAgentManager;
    private final ChannelManager channelManager;

    private DataAgentBootstrap(
            Path cwd,
            Path configPath,
            String mainAgentId,
            Map<String, HarnessAgent> agents,
            AgentscopeConfig loadedConfig,
            List<Channel> registeredChannels,
            HarnessGateway gateway,
            SessionAgentManager sessionAgentManager,
            ChannelManager channelManager) {
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.mainAgentId = Objects.requireNonNull(mainAgentId, "mainAgentId");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.loadedConfig = loadedConfig != null ? loadedConfig : new AgentscopeConfig();
        this.registeredChannels =
                registeredChannels != null ? List.copyOf(registeredChannels) : List.of();
        this.gateway = gateway;
        this.sessionAgentManager = sessionAgentManager;
        this.channelManager = channelManager;
    }

    // -----------------------------------------------------------------
    //  静态工厂 / 工具方法
    // -----------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static AgentscopeConfig loadConfig() throws IOException {
        return loadConfigFile(DEFAULT_CONFIG_PATH);
    }

    public static AgentscopeConfig loadConfigFile(Path configPath) throws IOException {
        if (!Files.isRegularFile(configPath)) {
            return new AgentscopeConfig();
        }
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(configPath.toFile(), AgentscopeConfig.class);
    }

    // -----------------------------------------------------------------
    //  公开运行时 API
    // -----------------------------------------------------------------

    public ChatUiChannel chatUiChannel() {
        return ChatUiChannel.create(resolveGateway());
    }

    public ChatUiChannel chatUiChannel(ChannelConfig config) {
        return ChatUiChannel.create(resolveGateway(), Objects.requireNonNull(config, "config"));
    }

    public DataAgentBootstrap start() {
        start(registeredChannels.toArray(new Channel[0]));
        return this;
    }

    public void start(Channel... channels) {
        Objects.requireNonNull(channels, "channels");
        Gateway g = resolveGateway();
        if (channelManager != null) {
            for (Channel channel : channels) {
                if (channel != null) {
                    channelManager.register(channel);
                }
            }
            channelManager.initAll(g);
            channelManager.startAll();
        } else {
            for (Channel channel : channels) {
                if (channel != null) {
                    channel.init(g);
                    channel.start();
                }
            }
        }
    }

    /** 停止 channel manager 管理的所有 channel。 */
    public void stop() {
        if (channelManager != null) {
            channelManager.stopAll();
        }
    }

    // -----------------------------------------------------------------
    //  公开 / 包级访问器
    // -----------------------------------------------------------------

    /** 此 bootstrap 使用的工作目录（相对配置路径的基础）。 */
    public Path cwd() {
        return cwd;
    }

    /** 从 {@code agentscope.json} 加载的 {@link AgentscopeConfig}（若未找到则为空）。 */
    public AgentscopeConfig loadedConfig() {
        return loadedConfig;
    }

    /** 所有已注册的 Agent 实例，以 agentId 为键。 */
    public Map<String, HarnessAgent> agents() {
        return agents;
    }

    String mainAgentId() {
        return mainAgentId;
    }

    HarnessAgent mainAgent() {
        HarnessAgent a = agents.get(mainAgentId);
        if (a == null) {
            throw new IllegalStateException("未注册主 Agent: " + mainAgentId);
        }
        return a;
    }

    public Path configPath() {
        return configPath;
    }

    List<Channel> registeredChannels() {
        return registeredChannels;
    }

    public HarnessGateway gateway() {
        return gateway;
    }

    /** 构建时使用的 session agent manager（供控制器获取 session 信息）。 */
    public SessionAgentManager sessionAgentManager() {
        return sessionAgentManager;
    }

    /** 用于 channel 生命周期管理和出站消息投递的 channel manager。 */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * 使用与构建阶段相同的逻辑，解析指定 Agent ID 对应的 workspace {@link Path}。
     *
     * <p>如果在加载的配置中未找到 {@code agentId}（或没有显式 workspace），则返回默认的
     * workspace 路径（{@link #DEFAULT_WORKSPACE_ROOT}）。
     */
    public Path resolveWorkspace(String agentId) {
        AgentscopeConfig cfg = loadedConfig;
        AgentConfigEntry entry = cfg.getAgents() != null ? cfg.getAgents().get(agentId) : null;
        if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
            return cwd.resolve(entry.getWorkspace()).normalize();
        }
        return DEFAULT_WORKSPACE_ROOT;
    }

    // -----------------------------------------------------------------
    //  内部辅助方法
    // -----------------------------------------------------------------

    private Gateway resolveGateway() {
        if (gateway != null) {
            return gateway;
        }
        throw new IllegalStateException(
                "没有可用的 gateway：主 Agent 已禁用 subagent。"
                        + " 请启用 subagent（默认启用）以便创建 gateway。");
    }

    private static List<Channel> resolveChannels(
            Map<String, Channel> builderChannels, AgentscopeConfig fileConfig) {
        Map<String, ChannelConfigEntry> fileChannels =
                fileConfig.getChannels() != null ? fileConfig.getChannels() : Map.of();

        Map<String, Channel> merged = new LinkedHashMap<>(builderChannels);

        for (Map.Entry<String, ChannelConfigEntry> entry : fileChannels.entrySet()) {
            String channelId = entry.getKey();
            ChannelConfigEntry ce = entry.getValue();
            if (Boolean.TRUE.equals(ce.getDisabled())) {
                merged.remove(channelId);
                continue;
            }
            if (merged.containsKey(channelId)) {
                continue;
            }
            String type = ce.getType();
            if (type == null || type.isBlank()) {
                // 向后兼容：没有 `type` 字段的旧版条目视为 chatui——这是唯一使用隐式类型的内置 channel。
                type = ChatUiChannel.CHANNEL_ID.equals(channelId) ? ChatUiChannel.CHANNEL_ID : null;
            }
            if (type == null) {
                log.warn(
                        "Channel '{}' 没有 'type' 字段，也不是已知的内置 channel；跳过。",
                        channelId);
                continue;
            }
            ChannelFactory factory = ChannelTypeRegistry.get(type).orElse(null);
            if (factory == null) {
                log.warn(
                        "Channel '{}' 声明了未知类型 '{}'；跳过自动创建。"
                                + " 已注册的类型: {}",
                        channelId,
                        type,
                        ChannelTypeRegistry.registeredTypes());
                continue;
            }
            try {
                merged.put(
                        channelId,
                        factory.create(
                                channelId, ce.toChannelConfig(channelId), ce.getProperties()));
            } catch (RuntimeException ex) {
                log.warn(
                        "实例化 channel '{}'（类型 '{}'）失败: {}",
                        channelId,
                        type,
                        ex.getMessage(),
                        ex);
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * 将 {@code agentscope.json} 中可选的 {@code session.maintenance} 配置块转换为运行时
     * {@link AgentManagerConfig}。当该配置块不存在时回退到 {@link AgentManagerConfig#defaults()}。
     */
    private static AgentManagerConfig resolveAgentManagerConfig(AgentscopeConfig fileConfig) {
        var sessionCfg = fileConfig != null ? fileConfig.getSession() : null;
        if (sessionCfg == null || sessionCfg.getMaintenance() == null) {
            return AgentManagerConfig.defaults();
        }
        var m = sessionCfg.getMaintenance();
        String mode = m.getMode();
        if (mode == null || mode.isBlank() || "off".equalsIgnoreCase(mode)) {
            return AgentManagerConfig.defaults();
        }
        long pruneAfterMs = m.pruneAfterMs();
        int maxEntries = m.getMaxEntries() != null ? m.getMaxEntries() : 0;
        SessionMaintenanceConfig sm =
                SessionMaintenanceConfig.enabled(pruneAfterMs, maxEntries);
        return new AgentManagerConfig(sm);
    }

    static void applyFileEntry(
            Path cwd, String agentId, AgentConfigEntry e, HarnessAgent.Builder b) {
        String name =
                (e != null && e.getName() != null && !e.getName().isBlank())
                        ? e.getName()
                        : agentId;
        b.name(name);

        if (e != null) {
            if (e.getDescription() != null) {
                b.description(e.getDescription());
            }
            if (e.getSysPrompt() != null) {
                b.sysPrompt(e.getSysPrompt());
            }
            Path workspace =
                    e.getWorkspace() != null && !e.getWorkspace().isBlank()
                            ? cwd.resolve(e.getWorkspace()).normalize()
                            : DEFAULT_WORKSPACE_ROOT;
            b.workspace(workspace);

            if (e.getMaxIters() != null) {
                b.maxIters(e.getMaxIters());
            }
            if (e.getEnvironmentMemory() != null) {
                b.environmentMemory(e.getEnvironmentMemory());
            }
            if (e.getModel() != null && !e.getModel().isBlank()) {
                b.model(e.getModel());
            }
            var repos = SkillRepositorySupport.createAll(cwd, e.effectiveSkillRepositories());
            if (!repos.isEmpty()) {
                b.skillRepositories(repos);
            }
            // 如果设置了 identity.name，则覆盖显示名称
            if (e.getIdentity() != null && e.getIdentity().getName() != null) {
                b.name(e.getIdentity().getName());
            }
        } else {
            b.workspace(DEFAULT_WORKSPACE_ROOT);
        }
    }

    // -----------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------

    public static final class Builder {

        private Path cwd = Paths.get(System.getProperty("user.dir"));
        private Path configPath;
        private boolean skipConfigFile;
        private Model model;
        private String mainAgentId;
        private final Map<String, HarnessAgent> prebuilt = new LinkedHashMap<>();
        private final Map<String, Consumer<HarnessAgent.Builder>> configurators =
                new LinkedHashMap<>();
        private final List<Consumer<HarnessAgent.Builder>> globalConfigurators =
                new java.util.ArrayList<>();
        private final Map<String, Channel> channels = new LinkedHashMap<>();

        /** 每个用户的 sandbox 注册表；非空时为每个 Agent 注册 UserSandboxContextMiddleware */
        private UserSandboxRegistry userSandboxRegistry;

        private Builder() {}

        public Builder cwd(Path cwd) {
            this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            return this;
        }

        public Builder configPath(Path configPath) {
            this.configPath = Objects.requireNonNull(configPath, "configPath");
            return this;
        }

        public Builder skipConfigFile(boolean skip) {
            this.skipConfigFile = skip;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder mainAgent(String agentId) {
            this.mainAgentId = agentId;
            return this;
        }

        public Builder agent(String agentId, HarnessAgent agent) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(agent, "agent");
            this.prebuilt.put(agentId, agent);
            return this;
        }

        public Builder configureAgent(String agentId, Consumer<HarnessAgent.Builder> customizer) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(customizer, "customizer");
            this.configurators.put(agentId, customizer);
            return this;
        }

        /**
         * 注册一个定制器，该定制器将应用于<em>每一个</em> Agent builder，
         * 作为每个 Agent 专有定制器的补充。适用于注入横切关注点（如 hooks）。
         */
        public Builder configureAllAgents(Consumer<HarnessAgent.Builder> customizer) {
            Objects.requireNonNull(customizer, "customizer");
            this.globalConfigurators.add(customizer);
            return this;
        }

        public Builder userSandboxRegistry(UserSandboxRegistry registry) {
            this.userSandboxRegistry = registry;
            return this;
        }

        public Builder channel(Channel... channels) {
            Objects.requireNonNull(channels, "channels");
            for (Channel c : channels) {
                if (c != null) {
                    this.channels.put(c.channelId(), c);
                }
            }
            return this;
        }

        /**
         * 组装所有 Agent 和 channel，连接内部 gateway，并返回一个完全初始化的
         * {@link DataAgentBootstrap}。
         *
         * <h4>连接顺序</h4>
         * <ol>
         *   <li>从文件配置 + 程序化注册中解析 Agent ID</li>
 *   <li>对于主 Agent：提取 workspace 路径，构建共享的 {@link
 *       WorkspaceManager} + {@link SessionAgentManager} + {@link HarnessGateway}</li>
         *   <li>2.0 内置 SubagentsMiddleware 接管子代理 spawn/send/list 工具，
         *       通过 DataAgentConfig 中的 SubagentDeclaration 声明子代理</li>
         *   <li>构建每个 Agent，注入 OutboundTool + DataAgentConfig 的全局配置
         *       (Plan Mode, Memory, Compaction, Permission, 模型容错)</li>
         *   <li>在 gateway 中注册所有 Agent 以实现多 Agent 路由</li>
         * </ol>
         */
        public DataAgentBootstrap build() throws IOException {
            Path resolvedConfig =
                    skipConfigFile ? null : (configPath != null ? configPath : DEFAULT_CONFIG_PATH);
            AgentscopeConfig fileConfig =
                    skipConfigFile
                            ? new AgentscopeConfig()
                            : loadConfigFile(
                                    resolvedConfig != null ? resolvedConfig : DEFAULT_CONFIG_PATH);

            Map<String, AgentConfigEntry> fileAgents =
                    fileConfig.getAgents() != null ? fileConfig.getAgents() : Map.of();

            Set<String> ids = new LinkedHashSet<>();
            ids.addAll(fileAgents.keySet());
            ids.addAll(prebuilt.keySet());
            ids.addAll(configurators.keySet());

            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "未定义任何 Agent：请向 ~/.agentscope/dataagent/agentscope.json 添加条目"
                                + " 或使用 AgentBootstrap.builder().agent(id, ...) /"
                                + " configureAgent(...)");
            }

            String main =
                    mainAgentId != null
                            ? mainAgentId
                            : (fileConfig.getMain() != null && !fileConfig.getMain().isBlank()
                                    ? fileConfig.getMain().trim()
                                    : (fileAgents.containsKey(DEFAULT_MAIN_ID)
                                            ? DEFAULT_MAIN_ID
                                            : ids.iterator().next()));

            if (!ids.contains(main)) {
                throw new IllegalStateException(
                        "主 Agent ID '" + main + "' 不在已配置的 Agent 中: " + ids);
            }

            // ---- 阶段 1：构建共享的 session 基础设施 ----

            // 配置主 Agent 的临时 builder 以提取 subagent 条目。
            Path mainWorkspace = resolveAgentWorkspace(cwd, fileAgents.get(main));
            HarnessAgent.Builder mainEntryBuilder = HarnessAgent.builder();
            applyFileEntry(cwd, main, fileAgents.get(main), mainEntryBuilder);
            if (model != null) {
                mainEntryBuilder.model(model);
            }
            Consumer<HarnessAgent.Builder> mainCustomizer = configurators.get(main);
            if (mainCustomizer != null) {
                mainCustomizer.accept(mainEntryBuilder);
            }
            for (Consumer<HarnessAgent.Builder> gc : globalConfigurators) {
                gc.accept(mainEntryBuilder);
            }

            WorkspaceManager wsManager = new WorkspaceManager(mainWorkspace);

            Path storeFile = mainWorkspace.resolve("sessions.json");
            SessionStore sessionStore = new SessionStore(storeFile);
            sessionStore.load();

            AgentManagerConfig amCfg = resolveAgentManagerConfig(fileConfig);
            SessionAgentManager sam =
                    new SessionAgentManager(wsManager, amCfg, sessionStore);

            ChannelManager channelMgr = new ChannelManager();
            HarnessGateway gateway = HarnessGateway.create(channelMgr);
            // Subagent lifecycle (spawn / execute / completion) is now handled by AgentScope 2.0's
            // built-in SubagentsMiddleware. SessionsTool and AnnounceDispatcher have been removed
            // in Phase 3. SessionAgentManager is retained for MAIN-session state used by the web
            // layer (ChatController, SessionController, SessionLifecycleScheduler).
            OutboundTool outboundTool = new OutboundTool(channelMgr);

            // ---- 阶段 2：构建 Agent（2.0 内置 SubagentsMiddleware 接管子代理） ----

            Map<String, HarnessAgent> built = new LinkedHashMap<>();

            for (String id : ids) {
                if (prebuilt.containsKey(id)) {
                    built.put(id, prebuilt.get(id));
                    continue;
                }

                AgentConfigEntry entry = fileAgents.get(id);
                if (entry == null && !configurators.containsKey(id)) {
                    continue;
                }

                HarnessAgent.Builder b = HarnessAgent.builder();
                applyFileEntry(cwd, id, entry, b);

                if (model != null) {
                    b.model(model);
                }
                // Subagent spawning is now handled by AgentScope 2.0's built-in
                // SubagentsMiddleware (via SubagentDeclaration entries registered in
                // DataAgentConfig). The old SessionsTool-based externalSubagentTool is removed.

                // 为每个 Agent 注册 sandbox 注入 middleware（替代原 gateway.attachUserSandboxContext）
                if (userSandboxRegistry != null) {
                    b.middleware(
                            new io.agentscope.dataagent.runtime.middleware
                                    .UserSandboxContextMiddleware(
                                    userSandboxRegistry, id));
                }

                // 预填充此 Agent 的工具包（toolkit）中的出站发送工具，以便 Agent 能够
                // 主动向任何已注册的 IM channel 推送消息。在定制器之前完成，以便调用者
                // 如果确实需要仍然可以替换 toolkit。
                Toolkit agentToolkit = new Toolkit();
                agentToolkit.registerTool(outboundTool);
                b.toolkit(agentToolkit);

                Consumer<HarnessAgent.Builder> c = configurators.get(id);
                if (c != null) {
                    c.accept(b);
                }
                for (Consumer<HarnessAgent.Builder> gc : globalConfigurators) {
                    gc.accept(b);
                }

                built.put(id, b.build());
            }

            if (!built.containsKey(main)) {
                throw new IllegalStateException(
                        "主 Agent ID '" + main + "' 未构建。已构建的 ID: " + built.keySet());
            }

            // ---- 阶段 3：将所有 Agent 连接到 gateway ----

            for (Map.Entry<String, HarnessAgent> e : built.entrySet()) {
                gateway.registerAgent(e.getKey(), e.getValue());
            }
            gateway.bindMainAgent(built.get(main));

            List<Channel> resolvedChannels = resolveChannels(channels, fileConfig);

            log.info(
                    "AgentBootstrap: cwd={}, config={}, main={}, agents={}, channels={}",
                    cwd,
                    skipConfigFile ? "(skipConfigFile)" : resolvedConfig,
                    main,
                    built.keySet(),
                    resolvedChannels.stream().map(Channel::channelId).toList());

            return new DataAgentBootstrap(
                    cwd,
                    resolvedConfig != null ? resolvedConfig : DEFAULT_CONFIG_PATH,
                    main,
                    Map.copyOf(built),
                    fileConfig,
                    resolvedChannels,
                    gateway,
                    sam,
                    channelMgr);
        }

        private static Path resolveAgentWorkspace(Path cwd, AgentConfigEntry entry) {
            if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
                return cwd.resolve(entry.getWorkspace()).normalize();
            }
            return cwd.resolve(".agentscope").resolve("workspace").normalize();
        }
    }
}