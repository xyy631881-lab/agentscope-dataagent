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
import io.agentscope.dataagent.infrastructure.workspace.UserSandboxRegistry;
import io.agentscope.dataagent.runtime.outbound.OutboundTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
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
 */
public final class DataAgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DataAgentBootstrap.class);

    /**
     * DEFAULT_WORKSPACE_ROOT	Agent 的工作目录，存模板、AGENTS.md、skills、subagents
     * DEFAULT_CONFIG_PATH	agentscope.json 配置文件位置
     * 放在 ~/.agentscope/dataagent/ 而不是项目 cwd，
     * 是为了避免"启动目录里有别的 harness app 留下的旧配置"串味儿。DataAgentConfig 首次启动时会用这两个常量自动建目录、写默认配置。
     */
    public static final Path DEFAULT_WORKSPACE_ROOT =
            Paths.get(System.getProperty("user.home"), ".agentscope", "dataagent", "workspace");

    public static final Path DEFAULT_CONFIG_PATH =
            Paths.get(
                    System.getProperty("user.home"), ".agentscope", "dataagent", "agentscope.json");

    // -----------------------------------------------------------------
    //  实例状态 — 由 Builder.build() 填充
    // -----------------------------------------------------------------

    private final Path cwd;
    private final Path configPath;
    private final Map<String, HarnessAgent> agents;    // 装好的所有 agent
    private final AgentscopeConfig loadedConfig;     // agentscope.json 解析结果
    private final List<Channel> registeredChannels;  // 通道列表
    private final HarnessGateway gateway;    // 网关（消息路由器）
    private final ChannelManager channelManager;    // 通道管理器

    private DataAgentBootstrap(
            Path cwd,
            Path configPath,
            Map<String, HarnessAgent> agents,
            AgentscopeConfig loadedConfig,
            List<Channel> registeredChannels,
            HarnessGateway gateway,
            ChannelManager channelManager) {
        this.cwd = Objects.requireNonNull(cwd, "cwd");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.loadedConfig = loadedConfig != null ? loadedConfig : new AgentscopeConfig();
        this.registeredChannels =
                registeredChannels != null ? List.copyOf(registeredChannels) : List.of();
        this.gateway = gateway;
        this.channelManager = channelManager;
    }

    // -----------------------------------------------------------------
    //  静态工厂 / 工具方法
    // -----------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    // 把 JSON 文件解析成 AgentscopeConfig 对象。两个细节：
    // 1. 文件不存在 → 返回空配置（不抛异常）
    // FAIL_ON_UNKNOWN_PROPERTIES=false → JSON 里多写了不认识的字段也不报错（前向兼容，老代码读新配置不会挂）
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
    //    干啥：装配完之后调一次这个方法，整车点火。流程：
    //
    //    拿到 gateway（消息路由器）
    //    把传入的 channels 注册到 ChannelManager
    //    initAll 让每个 channel 知道"有消息就往这个 gateway 投"
    //    startAll 让每个 channel 开始监听外部请求
    // -----------------------------------------------------------------

    public void start(Channel... channels) {
        Objects.requireNonNull(channels, "channels");
        Gateway g = resolveGateway();
        if (channelManager != null) {
            for (Channel channel : channels) {
                if (channel != null) {
                    channelManager.register(channel);
                }
            }
            channelManager.initAll(g);  // 让通道知道消息往哪个 gateway 投
            channelManager.startAll();  // 通道开始监听，前端可以发消息了
        } else {
            // 降级路径：没 ChannelManager 就手动一个一个 init/start
            for (Channel channel : channels) {
                if (channel != null) {
                    channel.init(g);
                    channel.start();
                }
            }
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

    public Path configPath() {
        return configPath;
    }

    public HarnessGateway gateway() {
        return gateway;
    }

    /** 用于 channel 生命周期管理和出站消息投递的 channel manager。 */
    public ChannelManager channelManager() {
        return channelManager;
    }

    // -----------------------------------------------------------------
    //  内部辅助方法
    // -----------------------------------------------------------------

    //拿 gateway，没有就抛异常（说明主 Agent 把 subagent 关了，gateway 就没了）。
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
        /**
         * 代码里用 Builder.channel(...) 注册的（编程式）
         * agentscope.json 里 channels 块声明的（声明式）
         * 合并规则：
         * 文件里 disabled=true → 从代码注册的里删掉
         * 代码里已注册 → 文件里的同 id 跳过（代码优先）
         * 文件独有的 → 按 type 字段找工厂创建
         * type 为空 → 向后兼容：id 叫 chatui 就当 chatui 处理
         * 找不到工厂 → 跳过并 warn
         */
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

    static void applyFileEntry(
            Path cwd, String agentId, AgentConfigEntry e, HarnessAgent.Builder b) {
        /**
         * 核心方法：把 JSON 里一个 agent 条目的字段挨个塞到 HarnessAgent.Builder：
         * name / description / sysPrompt / workspace / maxIters / model
         * skillRepositories（skill 仓库）
         * identity.name 优先级最高（覆盖前面设的 name）
         * 没配 workspace → 用 DEFAULT_WORKSPACE_ROOT。entry 为 null → 至少设个默认 workspace。
         */
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
        private Model model;
        private final List<Consumer<HarnessAgent.Builder>> globalConfigurators =
                new java.util.ArrayList<>();  //横切配置器列表——给每个 Agent 都加一遍的配置（中间件、权限、记忆…）
        private final Map<String, Channel> channels = new LinkedHashMap<>();

        /** 每个用户的 sandbox 注册表；非空时为每个 Agent 注册 UserSandboxContextMiddleware */
        private UserSandboxRegistry userSandboxRegistry;

        private Builder() {}

        public Builder cwd(Path cwd) {
            this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
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
         * 从 {@code agentscope.json} 加载 Agent 配置，组装 Gateway + Channel + Session 基础设施，
         * 返回完全初始化的 {@link DataAgentBootstrap}。
         */
        public DataAgentBootstrap build() throws IOException {
            AgentscopeConfig fileConfig = loadConfigFile(DEFAULT_CONFIG_PATH);

            Map<String, AgentConfigEntry> fileAgents =
                    fileConfig.getAgents() != null ? fileConfig.getAgents() : Map.of();

            Set<String> ids = new LinkedHashSet<>(fileAgents.keySet());
            if (ids.isEmpty()) {
                throw new IllegalStateException(
                        "未定义任何 Agent：请向 " + DEFAULT_CONFIG_PATH + " 添加条目");
            }

            String main =
                    fileConfig.getMain() != null && !fileConfig.getMain().isBlank()
                            ? fileConfig.getMain().trim()
                            : ids.iterator().next();

            // ---- 阶段 1：session 基础设施已迁移到 ConversationService（JPA） ----

            ChannelManager channelMgr = new ChannelManager();
            HarnessGateway gateway = HarnessGateway.create(channelMgr);
            OutboundTool outboundTool = new OutboundTool(channelMgr);

            // ---- 阶段 2：构建 Agent（2.0 内置 SubagentsMiddleware 接管子代理） ----
            Map<String, HarnessAgent> built = new LinkedHashMap<>();

            for (String id : ids) {
                AgentConfigEntry entry = fileAgents.get(id);
                HarnessAgent.Builder b = HarnessAgent.builder();
                applyFileEntry(cwd, id, entry, b);  //把 JSON 字段塞进 Builder

                if (model != null) {
                    b.model(model);
                }

                // 为每个 Agent 注册 sandbox 注入 middleware
                if (userSandboxRegistry != null) {
                    b.middleware(
                            new io.agentscope.dataagent.runtime.middleware
                                    .UserSandboxContextMiddleware(
                                    userSandboxRegistry, id));
                }

                // 预填充出站发送工具
                Toolkit agentKit = new Toolkit();
                agentKit.registerTool(outboundTool);
                b.toolkit(agentKit);
                //执行所有 globalConfigurators——这是关键！DataAgentConfig 里那一大坨 configureAllAgents(b -> {...})
                // 就是在这里被批量应用
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
            gateway.bindMainAgent(built.get(main));  // 标记主 agent

            List<Channel> resolvedChannels = resolveChannels(channels, fileConfig);  // 合并通道配置

            log.info(
                    "AgentBootstrap: cwd={}, config={}, main={}, agents={}, channels={}",
                    cwd,
                    DEFAULT_CONFIG_PATH,
                    main,
                    built.keySet(),
                    resolvedChannels.stream().map(Channel::channelId).toList());
            // 整车交付——返回一个完全初始化的 DataAgentBootstrap 实例，包含所有 Agent、通道、会话管理器、网关等组件
            return new DataAgentBootstrap(
                    cwd,
                    DEFAULT_CONFIG_PATH,
                    Map.copyOf(built),
                    fileConfig,
                    resolvedChannels,
                    gateway,
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