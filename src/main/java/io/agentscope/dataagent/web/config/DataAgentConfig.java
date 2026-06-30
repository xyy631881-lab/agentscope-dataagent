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
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
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
 * <p>从工作目录中的 {@code .agentscope/agentscope.json} 组装 {@link DataAgentBootstrap}
 * （默认为 {@code dataagent.workspace}），然后注册一个 {@link ChatUiChannel}
 * 使用 {@link DmScope#PER_PEER}，以便每个已认证用户获得隔离的 Agent 会话和命名空间。
 *
 * <h2>属性前缀</h2>
 *
 * <p>所有配置键位于 {@code dataagent.*} 下。
 *
 * <h2>文件系统拓扑</h2>
 *
 * <p>DataAgent 是一个多租户可部署应用。每个 {@link io.agentscope.harness.agent.sandbox.SandboxContext}
 * 的 workspace 根目录是 {@code ~/.agentscope/dataagent/workspace/}，其下是完整结构。
 *
 * <p>每个 {@link io.agentscope.harness.agent.HarnessAgent} 针对由 {@link UserSandboxRegistry}
 * 拥有的每个 {@code (userId, agentId)} 实时 Docker Sandbox 运行：
 * 作为 {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox() external sandbox}
 * 附加到 {@link io.agentscope.core.agent.RuntimeContext}，以便 harness 走 Priority-1 获取路径，
 * Agent 通过浏览器 workspace 控制器使用的完全相同的容器读/写。
 *
 * <p>多副本部署必须在前端使用基于 {@code userId} 的粘性负载均衡——
 * 注册表仅在内存中，否则两个 Pod 会为同一用户启动独立的容器。
 *
 * <h2>Model 连线（优先级顺序）</h2>
 *
 * <ol>
 *   <li>如果 {@link Model} Spring Bean 已经存在（由另一个 {@code @Configuration} 提供），
 *       则按原样使用。
 *   <li>否则，如果设置了 {@code dataagent.dashscope.api-key}，则自动创建
 *       {@link DashScopeChatModel}。
 *   <li>如果两者都不可用，应用启动时不带 model（Agent 调用将失败，直到配置了 model）。
 * </ol>
 *
 * <p>注意：model 连线在 {@code @Bean} 方法中使用<em>方法参数</em>注入（而不是字段级别的
 * {@code @Autowired}），以避免与此类中定义的 {@code Model} bean 产生循环依赖。
 *
 * <h2>Agent 配置</h2>
 *
 * <p>如果 {@code ~/.agentscope/dataagent/agentscope.json} 不存在，则自动生成最小默认
 * Agent 配置，以便应用无需手动设置即可启动。
 */
@Configuration
public class DataAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentConfig.class);

    @Value("${dataagent.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dataagent.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${dataagent.dashscope.stream:true}")
    private boolean dashscopeStream;

    @Value(
            "${dataagent.agent.sys-prompt:You are a Data Agent built with AgentScope."
                    + " You help users explore, analyse, visualise and report on data.}")
    private String agentSysPrompt;

    @Value("${dataagent.agent.name:data-agent}")
    private String agentName;

    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    // -----------------------------------------------------------------
    //  Model bean — 仅在设置 api-key 且上下文中尚未存在其他
    //  Model bean 时创建。属性为空时跳过，以便 Optional<Model>
    //  注入点接收到 Optional.empty()。
    // -----------------------------------------------------------------

    /**
     * 当配置了 {@code dataagent.dashscope.api-key} 且不存在其他 {@link Model} bean 时
     * 创建 {@link DashScopeChatModel} bean。属性为空时完全跳过，
     * 以便 {@code Optional<Model>} 注入点收到 {@code Optional.empty()} 而非空值 bean。
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${dataagent.dashscope.api-key:}' != ''")
    public Model dashscopeModel() {
        log.info("构建 DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .build();
    }

    // -----------------------------------------------------------------
    //  ObjectMapper bean — Spring Boot WebFlux 不会自动配置
    //  ObjectMapper（与 WebMVC 不同）。多个 bean
    //  （MarketContributionService 等）需要它，因此我们在此提供默认实例。
    // -----------------------------------------------------------------

    /**
     * 提供默认的 {@link ObjectMapper} bean。
     *
     * <p>Spring Boot WebFlux 不会自动配置 {@link ObjectMapper}
     * （仅在使用 {@code spring-boot-starter-web} / {@code spring-boot-starter-json} 时发生）。
     * 此项目中的多个 bean（例如 {@code MarketContributionService}）依赖于它，
     * 因此我们在此注册一个普通的默认实例。
     *
     * @return 默认的 {@link ObjectMapper} 实例
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
     * 组装 {@link DataAgentBootstrap}，从 {@code agentscope.json} 加载 Agent 配置，
     * 并启动 {@link ChatUiChannel} 实现每个用户的隔离会话。
     *
     * <p>由 bootstrap 构建的每个 Agent 声明一个 {@link DockerFilesystemSpec}
     * （每个用户隔离范围），与 {@link UserSandboxRegistry} 使用的共享同一个
     * {@link SandboxClient}。每个轮次的实际容器由网关通过
     * {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()} 提供——
     *
     * <p><strong>2.0 升级特性（2026-06-29）:</strong>
     * <ul>
     *   <li><b>Plan Mode</b> — 复杂分析任务先规划再执行，需用户确认</li>
     *   <li><b>记忆压缩</b> — 30 条消息触发压缩，保留最近 10 条 + 摘要</li>
     *   <li><b>长期记忆管道</b> — 每 10 分钟限流刷新到 MEMORY.md + memory/</li>
     *   <li><b>模型容错</b> — 主模型失败时自动重试 2 次</li>
     *   <li><b>内置子代理</b> — code-reviewer / report-writer（2.0 SubagentsMiddleware）</li>
     *   <li><b>权限系统</b> — SQL 执行需用户确认，其他工具默认放行</li>
     * </ul>
     *
     * @param modelOpt 要使用的 {@link Model}，如果未配置则为空
     * @param toolEventBus 用于工具调用的实时 SSE 流的共享工具事件总线
     * @param sandboxClient 每个 {@link DockerFilesystemSpec} 使用的客户端
     *     （{@link UserSandboxRegistry} 使用的同一个实例，以便 spec 默认值和
     *     注册表管理的 sandbox 共享一个 Docker 存储）
     * @param userSandboxRegistry bootstrap 后附加到网关的注册表，
     *     以便每次调用的轮次接收正确的每个用户 sandbox
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
                    "未配置 model。请在 application.yml 中设置 dataagent.dashscope.api-key"
                            + " 或提供 Model bean。在可用 model 之前，Agent 调用将失败。");
        }

        // AgentStateStore 后端选择现在独立于 workspace 文件系统，
        // 因为 workspace 由 sandbox 支持：通过粘性负载均衡下的内存
        // UserSandboxRegistry 访问每个用户的 sandbox。操作员仍应为生产环境
        // 提供分布式 AgentStateStore bean，以便会话状态在 Pod 重启后存活。
        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "未配置分布式 AgentStateStore bean ({}); 使用"
                            + " InMemoryAgentStateStore。对于多副本部署，请提供"
                            + " DistributedStore 或分布式 AgentStateStore bean"
                            + "（例如来自 agentscope-extensions-redis）。",
                    AgentStateStore.class.getName());
        }

        builder.configureAllAgents(
                b -> {
                    // ---- 平台基础设施 ----
                    b.middleware(new ToolNotificationMiddleware(toolEventBus));
                    b.stateStore(stateStore);
                    b.filesystem(
                            new DockerFilesystemSpec()
                                    .client(sandboxClient)
                                    .isolationScope(IsolationScope.USER));

                    // ---- #6 模型容错: 主模型失败时自动重试 2 次 ----
                    b.maxRetries(2);

                    // ---- #5 Plan Mode: 复杂分析任务先规划再执行 ----
                    // Agent 获得 plan_enter / plan_write / plan_exit 工具，
                    // 规划阶段只读，需用户确认后才执行。
                    b.enablePlanMode();

                    // ---- #3 内置记忆压缩: 替代手工 session freshness 检查 ----
                    // 当对话累积 30 条以上消息时触发压缩，保留最近 10 条 +
                    // 压缩摘要，防止 context window 溢出。
                    b.compaction(
                            CompactionConfig.builder()
                                    .triggerMessages(30)
                                    .keepMessages(10)
                                    .build());

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

                    // ---- #9 权限系统: 对敏感工具启用用户确认 ----
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

        // sandbox 注入已移至 UserSandboxContextMiddleware（通过 builder.userSandboxRegistry() 注册），
        // 不再需要在网关上手动设置。

        // 使用文件配置的绑定和 dmScope（如果有）构建 chatui channel，
        // 以便 agentscope.json 中管理员编辑的绑定被尊重。当不存在 chatui 条目时
        // 回退到 PER_PEER。
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

    /**
     * 在 {@code "local"} 类型下注册 {@link LocalApprovalMarketplace} 工厂，
     * 以便 {@code UserMarketplaceRegistry} 可以水化由磁盘上已批准贡献支持的
     * 每个用户 marketplace。
     *
     * <p>工厂从 {@code ${dataagent.shared-root}/agents/data-agent/skills} 读取——
     * 内置 {@code data-agent} 的每个 Agent 切片，也是每个(user, data-agent) sandbox
     * 作为其较低层投影的同一个目录，因此批准的 Skill 立即可用于 {@code data-agent}
     * 的每个租户，无需额外连线。为其他 Agent 批准的 Skill 位于它们自己的
     * {@code shared/agents/<agentId>/skills/} 切片中，并通过那些 Agent 自己的
     * 覆盖层展示；此本地 marketplace 不会交叉列出它们。
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
     * 在 {@code "git"} 类型下注册 {@link GitDataAgentMarketplace} 工厂。每个
     * 每个用户的 marketplace 在
     * {@code ${dataagent.workspace}/.cache/marketplaces/{userId}/{marketplaceId}} 下
     * 获得自己的克隆目标，以便配置同一上游的不同用户不会在共享工作副本上冲突。
     *
     * <p>属性：{@code remoteUrl}（必填）、{@code branch}（可选）。
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
     * 在 {@code "nacos"} 类型下注册 {@link NacosDataAgentMarketplace} 工厂。
     *
     * <p>属性：{@code serverAddr}（必填）、{@code namespaceId}（可选，默认
     * {@code "public"}）、{@code username} / {@code password}、{@code accessKey} /
     * {@code secretKey}。
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

    private static String stringProp(java.util.Map<String, Object> props, String key) {
        if (props == null) return null;
        Object v = props.get(key);
        return v == null ? null : v.toString();
    }

    @Bean
    public io.agentscope.dataagent.web.identity.IdentityLinkStore identityLinkStore(
            DataAgentBootstrap bootstrap) {
        Path agentscopeDir = bootstrap.cwd().resolve(".agentscope");
        return new io.agentscope.dataagent.web.identity.IdentityLinkStore(agentscopeDir);
    }

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

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * 如果 {@code ~/.agentscope/dataagent/agentscope.json} 不存在，则自动生成最小的
     * 配置文件，以便应用无需手动设置即可启动。生成的配置定义了一个预连线了
     * {@code chatui} channel 的 GLOBAL {@code data-agent}，并让 bootstrap
     * 回退到 {@link DataAgentBootstrap#DEFAULT_WORKSPACE_ROOT} 作为 workspace 位置。
     *
     * <p>workspace 根目录是只读的共享种子（磁盘上提供的模板内容、默认的
     * {@code AGENTS.md} / {@code skills/} / {@code subagents/} / {@code knowledge/}）。
     * {@link UserSandboxRegistry} 将其投影到每个新容器中；用户可写文件保存在容器内部。
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
        log.info("自动生成的 DataAgent 配置位于 {}", configFile);

        io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder.scaffold(
                workspaceRoot, "Data Agent", agentSysPrompt);
    }
}