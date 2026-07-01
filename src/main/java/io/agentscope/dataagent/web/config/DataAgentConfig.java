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
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.marketplace.GitDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace;
import io.agentscope.dataagent.runtime.marketplace.NacosDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry.DataAgentMarketplaceFactoryRegistration;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
 * <p>通俗说：这就是整个 DataAgent 的"总装车间"。
 * Spring 启动时会来这里取各种"零件"（Bean），这个类负责把这些零件装配好：
 * 大脑（模型）、记忆（Memory）、工具权限、子代理、沙箱文件系统……
 * 最后用 {@link DataAgentBootstrap} 把整车点火启动。
 *
 * <p>读取的配置都来自 application.yml 里的 {@code dataagent.*} 前缀。
 */
@Configuration
public class DataAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentConfig.class);

    // ===== 从 application.yml 读进来的"配置旋钮" =====
    // 注意：model 的 stream / 超时等参数由 ModelRegistry 默认处理，不再手写 @Value。
    // 切换厂商只需改 model-name 为 "openai:gpt-4o" / "anthropic:claude-sonnet-4-5" 等，
    // 并设置对应环境变量（OPENAI_API_KEY / ANTHROPIC_API_KEY ...）。
    @Value("${dataagent.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dataagent.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    // 主模型失败重试 2 次仍不行时，自动切到这个备用模型（2.0 fallbackModel 能力）。
    // 留空字符串则不启用 fallback。默认 qwen-plus 比 qwen-max 更便宜更稳。
    @Value("${dataagent.dashscope.fallback-model-name:qwen-plus}")
    private String dashscopeFallbackModelName;

    @Value(
            "${dataagent.agent.system-prompt:You are a Data Agent built with AgentScope."
                    + " You help users explore, analyse, visualise and report on data.}")
    private String agentSysPrompt;

    @Value("${dataagent.agent.name:data-agent}")
    private String agentName;

    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    // ===== Redis 分布式状态后端 =====
    // 连接参数（host/port/password/database）由 spring-boot-starter-data-redis 自动
    // 配置，直接注入 RedisProperties 即可，不必再 @Value 一遍。
    // 这里只保留 AgentScope 业务相关的 key 前缀。
    @Value("${dataagent.session.redis.key-prefix:dataagent:session:}")
    private String redisKeyPrefix;

    // -----------------------------------------------------------------
    //  Model bean — 通过 ModelRegistry 解析字符串 id 创建。
    //  两种来源都能工作：
    //   1. yml 配了 dataagent.dashscope.api-key → 注册工厂用这个 key
    //   2. 没配 api-key → 依赖环境变量 DASHSCOPE_API_KEY（2.0 推荐方式）
    // -----------------------------------------------------------------

    /**
     * 装配"大脑"——通过 ModelRegistry 解析 model id 创建模型实例。
     *
     * <p>这是 AgentScope 2.0 推荐的方式：传字符串 id（如 "dashscope:qwen-max"），
     * 框架自动解析并读取对应环境变量。
     *
     * <p>兼容两种配置来源：
     * <ul>
     *   <li>yml 配了 {@code dataagent.dashscope.api-key} → 注册一个工厂用这个 key；
     *   <li>没配 api-key → 走 ModelRegistry 内置工厂，读 {@code DASHSCOPE_API_KEY} 环境变量。
     * </ul>
     *
     * <p>切换厂商：把 model-name 改成 "openai:gpt-4o" / "anthropic:claude-sonnet-4-5" 等，
     * 并设置对应环境变量。
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    public Model dashscopeModel() {
        // 如果 yml 配了 api-key，注册一个工厂让 ModelRegistry 用这个 key
        // （优先级高于内置工厂，所以会覆盖默认的环境变量读取逻辑）
        if (dashscopeApiKey != null && !dashscopeApiKey.isBlank()) {
            String apiKey = dashscopeApiKey;
            ModelRegistry.registerFactory(
                    "dashscope:(.+)",
                    id -> DashScopeChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(id.substring("dashscope:".length()))
                            .stream(true)
                            .build());
            // 兼容无前缀的 model-name（如 "qwen-max"）
            ModelRegistry.registerFactory(
                    "qwen.*",
                    id -> DashScopeChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(id)
                            .stream(true)
                            .build());
            log.info("注册 DashScope 工厂（使用 yml 中的 api-key）: model={}", dashscopeModelName);
        } else {
            log.info("未配置 yml api-key，ModelRegistry 将读 DASHSCOPE_API_KEY 环境变量: model={}", dashscopeModelName);
        }

        // model-name 如果没有 provider 前缀，补上 dashscope: 让 ModelRegistry 正确识别
        String modelId = dashscopeModelName.contains(":")
                ? dashscopeModelName
                : "dashscope:" + dashscopeModelName;
        return ModelRegistry.resolve(modelId);
    }

    /**
     * 装配"分布式记忆后端"——基于 Redis 的 AgentStateStore。
     *
     * <p>触发条件（两个都满足才会创建）：
     * <ul>
     *   <li>application.yml 里 {@code dataagent.session.redis.enabled=true}；
     *   <li>Spring 容器里还没有用户自定义的 {@link AgentStateStore} Bean（避免覆盖）。
     * </ul>
     *
     * <p>典型用法：启动时加 {@code --spring.profiles.active=dev,redis}，
     * application-redis.yml 会自动把 {@code dataagent.session.redis.enabled} 置为 true。
     *
     * <p>实现说明：连接参数（host/port/password/database）由 spring-boot-starter-data-redis
     * 自动配置，注入 RedisProperties即可拿到，不必再 @Value 一遍。这里基于
     * RedisProperties 拼一个 Lettuce {@link RedisClient} 传给 builder。Cluster 部署请改用
     * {@code RedisClusterClient} 并调用 {@code .lettuceClusterClient(...)}。
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

        // 用 spring.data.redis.* 拼 RedisURI：host/port/database 必填，password 可选
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
    //  ObjectMapper bean — Spring Boot WebFlux 不会自动配置
    //  ObjectMapper（与 WebMVC 不同）。多个 bean
    //  （MarketContributionService 等）需要它，因此我们在此提供默认实例。
    // -----------------------------------------------------------------

    /**
     * 装配 JSON 解析器 ObjectMapper。
     *
     * <p>为啥要手写一个？因为 Spring Boot WebFlux 不像 WebMVC 会自动塞一个进来，
     * 而 MarketContributionService 这些 bean 又离不开它，所以这里兜底给一个默认实例。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // -----------------------------------------------------------------
    //  核心 bootstrap — model 作为方法参数注入（无字段级别的
    //  @Autowired）以避免与上面的 dashscopeModel() 产生循环依赖。
    // -----------------------------------------------------------------

    /**
     * 总装主入口：搭出一个完整的 {@link DataAgentBootstrap} 并点火启动。
     */
    @Bean
    public DataAgentBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            UserSandboxRegistry userSandboxRegistry,
            Optional<AgentStateStore> sessionOpt)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig();

        DataAgentBootstrap.Builder builder = DataAgentBootstrap.builder().cwd(cwd).userSandboxRegistry(userSandboxRegistry);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "未配置 model。请设置环境变量 DASHSCOPE_API_KEY"
                            + " 或在 application.yml 中配置 dataagent.dashscope.api-key"
                            + " 或提供自定义 Model bean。在可用 model 之前，Agent 调用将失败。");
        }

        // sessionOpt 是注入进来的 Optional<AgentStateStore>。
        // 如果有人在 Spring 容器里注册了分布式存储 Bean（比如 Redis），就用那个；没有的话，兜底用一个纯内存的 InMemoryAgentStateStore。
        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "未配置分布式 AgentStateStore bean ({}); 兜底使用"
                            + " InMemoryAgentStateStore（进程重启会丢状态）。"
                            + " 多副本部署请启用 redis profile："
                            + " --spring.profiles.active=dev,redis，"
                            + " 或自行提供 AgentStateStore bean。",
                    AgentStateStore.class.getName());
        }

        // 把所有"配件"一个个挂上去，平台基础设施：工具事件中间件、状态存储、Docker 沙箱文件系统（按用户隔离），
        // #6 模型容错：失败自动重试 2 次
        // #5 Plan Mode：复杂任务先规划再执行，规划阶段只读
        // #3 记忆压缩：对话超 30 条触发压缩，保留最近 10 条 + 摘要
        // 长期记忆：每 10 分钟限流刷新一次，落到 MEMORY.md
        // #2 子代理：注册 code-reviewer（不暴露给用户）和 report-writer（暴露思考过程）
        // #9 权限系统：默认 ALLOW，但 run_sql_preview 这类敏感操作要 ASK 用户确认
        builder.configureAllAgents(
                b -> {
                    // ---- 平台基础设施 ----
                    b.middleware(new ToolNotificationMiddleware(toolEventBus));
                    b.stateStore(stateStore);
                    b.filesystem(
                            new DockerFilesystemSpec()
                                    .client(sandboxClient)
                                    .isolationScope(IsolationScope.USER));

                    // ---- #6 模型容错: 主模型失败重试 + fallback 自动切换 ----
                    // 主模型失败自动重试 2 次；仍不行则切到备用模型（默认 qwen-plus）。
                    // 链路：主模型 → retry 2 次 → fallback 模型。
                    b.maxRetries(2);
                    if (dashscopeFallbackModelName != null && !dashscopeFallbackModelName.isBlank()) {
                        String fallbackId = dashscopeFallbackModelName.contains(":")
                                ? dashscopeFallbackModelName
                                : "dashscope:" + dashscopeFallbackModelName;
                        b.fallbackModel(fallbackId);
                    }

                    // ---- #5 Plan Mode: 复杂分析任务先规划再执行 ----
                    // Agent 获得 plan_enter / plan_write / plan_exit 工具，
                    // 规划阶段只读，需用户确认后才执行。
                    b.enablePlanMode();

                    // ---- #3 内置记忆压缩  ----
                    // 当对话累积 30 条以上消息时触发压缩，保留最近 10 条 +
                    // 压缩摘要，防止 context window 溢出。
                    b.compaction(
                            CompactionConfig.builder()
                                    .triggerMessages(30)
                                    .keepMessages(10)
                                    .build());

                    // ---- 大工具结果卸载 ----
                    // 数据分析场景下 SQL 查询可能返回几万行数据，单条结果超 80K 字符时
                    // 自动落盘到工作区，context 里只留首尾 2K 字符 + read_file 路径提示。
                    // read_file/write_file/grep 等小结果工具默认排除。
                    b.toolResultEviction(ToolResultEvictionConfig.defaults());

                    // 长期记忆管道: 每 10 分钟限流刷新一次，
                    // 将对话要点合并到 MEMORY.md + memory/YYYY-MM-DD.md
                    b.memory(
                            MemoryConfig.builder()
                                    .flushTrigger(
                                            MemoryConfig.FlushTrigger.throttled(
                                                    Duration.ofMinutes(10)))
                                    .build());

                    // ---- #2 声明内置子代理 ----
                    // 每个子代理以 Markdown 文件声明在 workspace/subagents/ 下，
                    // 或以编程方式在此处声明。2.0 SubagentsMiddleware 自动提供
                    // agent_spawn / agent_send / agent_list 等工具。

                    // 代码审查子代理: agent_spawn agent_id="code-reviewer" task="..."
                    b.subagent(
                            SubagentDeclaration.builder()
                                    .name("code-reviewer")
                                    .description("Code review specialist. Reviews data-analysis scripts, SQL, and chart definitions. "
                                            + "Returns structured findings with severity levels.")
                                    .model("dashscope:qwen-max")
                                    .maxIters(5)
                                    .exposeToUser(false)
                                    .workspaceMode(WorkspaceMode.ISOLATED)
                                    .build());

                    // 研究报告子代理: agent_spawn agent_id="report-writer" task="... "
                    b.subagent(
                            SubagentDeclaration.builder()
                                    .name("report-writer")
                                    .description("Report writer. Composes data-analysis reports in Markdown. "
                                            + "Takes findings and chart descriptions, produces polished narrative.")
                                    .model("dashscope:qwen-max")
                                    .maxIters(8)
                                    .exposeToUser(true)  // 用户可看到子代理的思考过程
                                    .workspaceMode(WorkspaceMode.ISOLATED)
                                    .build());

                    // ---- 权限系统: 对敏感工具启用用户确认 ----
                    // 默认所有工具 ALLOW，对 SQL 执行类工具要求用户确认。
                    PermissionContextState permCtx =
                            PermissionContextState.builder()
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "list_data_sources",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "describe_table",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "render_chart",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "outbound_send",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "agent_spawn",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "agent_send",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "agent_list",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    // SQL 执行需要用户确认
                                    .addAskRule(
                                            "sql_execution",
                                            new PermissionRule(
                                                    "run_sql_preview",
                                                    null,
                                                    PermissionBehavior.ASK,
                                                    "sql_execution"))
                                    // 内存写入操作自动允许（2.0 内置工具）
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "memory_search",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .addAllowRule(
                                            "default",
                                            new PermissionRule(
                                                    "memory_get",
                                                    null,
                                                    PermissionBehavior.ALLOW,
                                                    "default"))
                                    .build();
                    b.permissionContext(permCtx);
                });

        DataAgentBootstrap bootstrap = builder.build();

        // 尝试从 agentscope.json 读取 channel 配置，
        // bootstrap.loadedConfig() 就是之前 build 阶段加载的 agentscope.json。如果 json 里写了 channels.chatui 配置块，就用它；没写就 null
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        // 构造 channel 的配置对象
        ChannelConfig chatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)  // 有配置 → 用 json 里的
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_PEER)  // DmScope.PER_PEER 是关键：每个登录用户（peer）获得独立的 Agent 会话和 workspace
                                .build();  // 没配置 → 默认每人一个独立会话
        // 创建通道实例
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);
        bootstrap.start(webChannel);  // 整车点火

        log.info(
                "DataAgentBootstrap 已初始化: cwd={}, chatui dmScope={}, bindings={}",
                cwd,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size());
        return bootstrap;
    }

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
     * 必须提供 serverAddr；其他鉴权字段（namespaceId/username/password/accessKey/secretKey）可选。
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

    /** 小工具：从 props Map 里安全地取一个字符串，没有就返回 null。 */
    private static String stringProp(java.util.Map<String, Object> props, String key) {
        if (props == null) return null;
        Object v = props.get(key);
        return v == null ? null : v.toString();
    }

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
     * 方便别处直接注入使用。拿不到就抛异常，说明前面启动有问题。
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
     * <p><b>2.0 风格说明</b>：这是"脚手架"，不是"必需品"。生成后用户可以：
     * <ul>
     *   <li>直接删掉这份 JSON，然后通过 Web 界面重新配置；</li>
     *   <li>手工编辑这份 JSON 调整 agent / channel；</li>
     *   <li>保留不动，开箱即用。</li>
     * </ul>
     * 已经存在则绝不覆盖。
     */
    private void ensureAgentscopeConfig() throws IOException {
        Path configFile = DataAgentBootstrap.DEFAULT_CONFIG_PATH;
        Path workspaceRoot = DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT;

        if (Files.exists(configFile)) {
            return;  // 已有配置，绝不覆盖
        }

        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workspaceRoot);

        // 生成的 JSON 带 _comment 说明字段，方便用户理解可改可删
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
     * 否则就当普通字符串原样返回。读资源失败也兜底返回原值，不让启动挂掉。
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
            return prompt; // fallback: return raw value
        }
    }
}