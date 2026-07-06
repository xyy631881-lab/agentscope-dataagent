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
package io.agentscope.dataagent.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.AgentRuntimeConfigurer;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.marketplace.GitDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace;
import io.agentscope.dataagent.runtime.marketplace.NacosDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry.DataAgentMarketplaceFactoryRegistration;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agentscope-dataagent web 模块的 Spring Boot 配置。
 *
 * <p>这是整个 DataAgent 的"总装车间"。Spring 启动时来这里取各种"零件"（Bean），
 * 这个类负责装配：大脑（模型）、记忆后端、运行时配置器、引导启动器、市场工厂等。
 *
 * <p>运行时能力配置（Plan Mode、Compaction、Memory、Subagents、Permissions、Sandbox）
 * 已抽取到 {@link AgentRuntimeConfigurer}，全局 Agent 和用户自定义 Agent 共用同一套。
 *
 * <p>读取的配置都来自 application.yml 里的 {@code dataagent.*} 前缀。
 */
@Configuration
public class DataAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentConfig.class);

    // ===== 从 application.yml 读进来的"配置旋钮" =====
    @Value("${dataagent.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${dataagent.ollama.model-name:qwen2.5:1.5b}")
    private String ollamaModelName;

    @Value("${dataagent.ollama.fallback-model-name:}")
    private String ollamaFallbackModelName;

    @Value(
            "${dataagent.agent.system-prompt:You are a Data Agent built with AgentScope."
                    + " You help users explore, analyse, visualise and report on data.}")
    private String agentSysPrompt;

    @Value("${dataagent.agent.name:data-agent}")
    private String agentName;

    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    @Value("${dataagent.session.redis.key-prefix:dataagent:session:}")
    private String redisKeyPrefix;

    // -----------------------------------------------------------------
    //  Model bean
    // -----------------------------------------------------------------

    /**
     * 装配"大脑"——使用本地 Ollama 服务中的模型。
     *
     * <p>Ollama 是本地推理引擎，无需 API Key，可运行各种开源模型。
     * 默认连接 http://localhost:11434，可通过 dataagent.ollama.base-url 配置。
     * 备用模型(fallback)默认关闭；如有需要可通过 dataagent.ollama.fallback-model-name 启用。
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    public Model ollamaModel() {
        log.info("初始化 Ollama 本地模型: model={}, baseUrl={}", ollamaModelName, ollamaBaseUrl);
        return OllamaChatModel.builder()
                .modelName(ollamaModelName)
                .baseUrl(ollamaBaseUrl)
                .build();
    }

    // -----------------------------------------------------------------
    //  StateStore bean (Redis 可选)
    // -----------------------------------------------------------------

    /**
     * 装配"分布式记忆后端"——基于 Redis 的 AgentStateStore。
     *
     * <p>触发条件：application.yml 里 {@code dataagent.session.redis.enabled=true}
     * 且 Spring 容器里还没有自定义的 {@link AgentStateStore} Bean。
     *
     * <p>典型用法：启动时加 {@code --spring.profiles.active=dev,redis}，
     * application-redis.yml 会自动把 {@code dataagent.session.redis.enabled} 置为 true。
     * Cluster 部署请改用 {@code RedisClusterClient} 并调用 {@code .lettuceClusterClient(...)}。
     */
    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    @ConditionalOnExpression("'${dataagent.session.redis.enabled:false}' == 'true'")
    public AgentStateStore redisAgentStateStore(
            org.springframework.boot.autoconfigure.data.redis.RedisProperties redisProps) {
        log.info(
                "构建 RedisAgentStateStore: redis={}:{}, db={}, keyPrefix={}",
                redisProps.getHost(),
                redisProps.getPort(),
                redisProps.getDatabase(),
                redisKeyPrefix);

        RedisURI.Builder uriBuilder =
                RedisURI.builder()
                        .redis(redisProps.getHost(), redisProps.getPort())
                        .withDatabase(redisProps.getDatabase());
        if (redisProps.getPassword() != null && !redisProps.getPassword().isEmpty()) {
            uriBuilder.withPassword(redisProps.getPassword().toCharArray());
        }

        RedisClient client = RedisClient.create(uriBuilder.build());
        return RedisAgentStateStore.builder()
                .lettuceClient(client)
                .keyPrefix(redisKeyPrefix)
                .build();
    }

    // -----------------------------------------------------------------
    //  ObjectMapper bean — WebFlux 不会自动配置
    // -----------------------------------------------------------------

    /**
     * 装配 JSON 解析器 ObjectMapper。WebFlux 不像 WebMVC 会自动塞一个进来，
     * 多个 bean 需要它，这里兜底给一个默认实例。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // -----------------------------------------------------------------
    //  统一运行时配置器 — 全局 Agent 和用户自定义 Agent 共用
    // -----------------------------------------------------------------

    /**
     * 装配统一运行时配置器。所有 Agent（全局 + 用户自定义）共用同一套能力配置：
     * Plan Mode、Compaction、Memory、Subagents、Permissions、Sandbox 文件系统、
     * State store、模型重试/fallback。
     *
     * <p>由 {@link DataAgentBootstrap.Builder#configureAllAgents} 和
     * {@link io.agentscope.dataagent.agent.catalog.AgentLifecycleService} 分别在
     * 全局 Agent 和用户自定义 Agent 的构建路径中调用。
     */
    @Bean
    public AgentRuntimeConfigurer agentRuntimeConfigurer(
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            Optional<AgentStateStore> sessionOpt) {
        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "未配置分布式 AgentStateStore bean; 兜底使用 InMemoryAgentStateStore"
                            + "（进程重启会丢状态）。多副本部署请启用 redis profile。");
        }
        return new AgentRuntimeConfigurer(
                stateStore, sandboxClient, ollamaModelName, ollamaFallbackModelName);
    }

    // -----------------------------------------------------------------
    //  核心 bootstrap — 总装主入口
    // -----------------------------------------------------------------

    /**
     * 总装主入口：搭出一个完整的 {@link DataAgentBootstrap} 并点火启动。
     *
     * <p>流程：解析工作目录 → 确保脚手架配置存在 → 创建 Builder →
     * 设置模型 → 注册统一运行时配置器 → 构建 → 配置通道 → 启动。
     */
    @Bean
    public DataAgentBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            AgentRuntimeConfigurer agentRuntimeConfigurer,
            UserSandboxRegistry userSandboxRegistry)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig();

        DataAgentBootstrap.Builder builder =
                DataAgentBootstrap.builder().cwd(cwd).userSandboxRegistry(userSandboxRegistry);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "未配置 model。请检查 dataagent.ollama 配置或提供自定义 Model bean。"
                            + "在可用 model 之前，Agent 调用将失败。");
        }

        // 关键：注册统一运行时配置器，所有 Agent 构建时都会应用这套配置
        builder.configureAllAgents(agentRuntimeConfigurer);

        DataAgentBootstrap bootstrap = builder.build();

        // 从 agentscope.json 读取 channel 配置，没有就用默认
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_PEER)
                                .build();
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);
        bootstrap.start(webChannel);

        log.info(
                "DataAgentBootstrap 已初始化: cwd={}, chatui dmScope={}, bindings={}",
                cwd,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size());
        return bootstrap;
    }

    // -----------------------------------------------------------------
    //  市场工厂注册
    // -----------------------------------------------------------------

    /**
     * 注册"本地市场"工厂——从工作目录的 shared/.../skills 拉取 skill。
     * 适合自己开发、自己用，不联网。
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration localMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path sharedSkills =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("skills");
        return new DataAgentMarketplaceFactoryRegistration(
                LocalApprovalMarketplace.TYPE,
                (userId, id, props, wsf) -> new LocalApprovalMarketplace(id, sharedSkills));
    }

    /**
     * 注册"Git 市场"工厂——从远端 git 仓库 clone 下来再读 skill。
     * 必须提供 remoteUrl，可选 branch；clone 结果缓存在 .cache/marketplaces 下。
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration gitMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path cacheRoot = bootstrap.cwd().resolve(".cache").resolve("marketplaces");
        return new DataAgentMarketplaceFactoryRegistration(
                GitDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String remoteUrl = stringProp(props, "remoteUrl");
                    if (remoteUrl == null || remoteUrl.isBlank()) {
                        throw new IllegalArgumentException(
                                "git marketplace '" + id + "' 需要属性 'remoteUrl'");
                    }
                    String branch = stringProp(props, "branch");
                    Path clone = cacheRoot.resolve(userId).resolve(id);
                    return new GitDataAgentMarketplace(id, remoteUrl, branch, clone);
                });
    }

    /**
     * 注册"Nacos 市场"工厂——从 Nacos 配置中心拉 skill 定义。
     * 必须提供 serverAddr；其他鉴权字段可选。
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration nacosMarketplaceFactory() {
        return new DataAgentMarketplaceFactoryRegistration(
                NacosDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String serverAddr = stringProp(props, "serverAddr");
                    if (serverAddr == null || serverAddr.isBlank()) {
                        throw new IllegalArgumentException(
                                "nacos marketplace '" + id + "' 需要属性 'serverAddr'");
                    }
                    return new NacosDataAgentMarketplace(
                            id,
                            serverAddr,
                            stringProp(props, "namespaceId"),
                            stringProp(props, "username"),
                            stringProp(props, "password"),
                            stringProp(props, "accessKey"),
                            stringProp(props, "secretKey"));
                });
    }

    // -----------------------------------------------------------------
    //  其他 Bean
    // -----------------------------------------------------------------

    /**
     * 装配"身份关联库"——把外部用户身份和 DataAgent 内部身份关联起来，
     * 持久化在工作目录下的 .agentscope 目录里。
     */
    @Bean
    public io.agentscope.dataagent.web.identity.IdentityLinkStore identityLinkStore(
            DataAgentBootstrap bootstrap) {
        Path agentscopeDir = bootstrap.cwd().resolve(".agentscope");
        return new io.agentscope.dataagent.web.identity.IdentityLinkStore(agentscopeDir);
    }

    /**
     * 从 bootstrap 的 ChannelManager 里把 chatui 通道拿出来暴露成 Bean，
     * 方便别处直接注入使用。
     */
    @Bean
    public ChatUiChannel chatUiChannel(DataAgentBootstrap bootstrap) {
        return (ChatUiChannel)
                bootstrap
                        .channelManager()
                        .getChannel(ChatUiChannel.CHANNEL_ID)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ChatUiChannel 未在 ChannelManager 中注册"));
    }

    // -----------------------------------------------------------------
    //  内部辅助方法
    // -----------------------------------------------------------------

    /** 决定工作目录：yml 里配了 workspace 就用它，否则回退到 JVM 启动目录 user.dir。 */
    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * 首次启动时生成一份默认的 agentscope.json 脚手架。
     *
     * <p>这是"脚手架"，不是"必需品"。生成后用户可以：
     * 直接删掉然后通过 Web 界面重新配置、手工编辑、或保留不动开箱即用。
     * 已经存在则绝不覆盖。
     */
    private void ensureAgentscopeConfig() throws IOException {
        Path configFile = DataAgentBootstrap.DEFAULT_CONFIG_PATH;
        Path workspaceRoot = DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT;

        if (Files.exists(configFile)) {
            return;
        }

        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workspaceRoot);

        String agentsJson =
                """
                {
                  "_comment": "脚手架配置，首次启动自动生成。可直接删除或按需修改。",
                  "main": "data-agent",
                  "agents": {
                    "data-agent": {
                      "name": "Data Agent",
                      "description": "Tenant-isolated data-analysis assistant. Connects to internal SQL sources, drafts queries, validates results, and renders charts.",
                      "maxIters": 20
                    }
                  },
                  "channels": {
                    "chatui": {
                      "defaultAgentId": "data-agent",
                      "dmScope": "MAIN"
                    }
                  }
                }
                """;

        Files.writeString(configFile, agentsJson);
        log.info(
                "首次启动：已生成默认 agentscope.json 脚手架于 {}。"
                        + "这是脚手架而非必需品——可通过 Web 界面修改或直接删除重建。",
                configFile);

        io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder.scaffold(
                workspaceRoot, "Data Agent", resolvePrompt(agentSysPrompt));
    }

    /**
     * 解析系统提示词：如果值以 "classpath:" 开头，就从 classpath 资源里读全文；
     * 否则就当普通字符串原样返回。读资源失败也兜底返回原值。
     */
    private static String resolvePrompt(String prompt) {
        if (prompt == null || !prompt.startsWith("classpath:")) {
            return prompt;
        }
        String resourcePath = prompt.substring("classpath:".length());
        try {
            return new String(
                    ClassLoader.getSystemResourceAsStream(
                            resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath)
                            .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return prompt;
        }
    }

    /** 从 props Map 里安全地取一个字符串，没有就返回 null。 */
    private static String stringProp(java.util.Map<String, Object> props, String key) {
        if (props == null) return null;
        Object v = props.get(key);
        return v == null ? null : v.toString();
    }
}
