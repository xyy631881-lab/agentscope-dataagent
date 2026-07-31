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
package io.agentscope.dataagent.config;
import io.agentscope.dataagent.security.infrastructure.IdentityLinkStore;
import io.agentscope.dataagent.workspace.application.WorkspaceScaffolder;

import io.agentscope.core.model.Model;
import io.opentelemetry.api.OpenTelemetry;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.AgentRuntimeConfigurer;
import io.agentscope.dataagent.tools.data.DataAgentToolkit;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.config.ModelConfig;
import io.agentscope.dataagent.config.properties.AgentProperties;
import io.agentscope.dataagent.config.properties.ApiModelProperties;
import io.agentscope.dataagent.config.properties.WorkspaceProperties;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 核心引导装配——运行时配置器、引导启动器、身份关联和通道。
 *
 * <p>这是从 {@link DataAgentConfig} 中拆出的"总装主入口"部分。
 * 流程：解析工作目录 → 确保脚手架配置存在 → 创建 Builder →
 * 设置模型 → 注册统一运行时配置器 → 构建 → 配置通道 → 启动。
 */
@Configuration
public class BootstrapConfig {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfig.class);

    @Bean
    public AgentRuntimeConfigurer agentRuntimeConfigurer(
            ApiModelProperties modelProps,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            Optional<AgentStateStore> sessionOpt,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard sandboxExecutionGuard,
            OpenTelemetry openTelemetry,
            DataAgentToolkit dataAgentToolkit) {
        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "未配置分布式 AgentStateStore bean; 兜底使用 InMemoryAgentStateStore"
                            + "（进程重启会丢状态）。多副本部署请启用 redis profile。");
        }
        String activeModelId = ModelConfig.resolveActiveId(modelProps);
        String fallbackModelId = ModelConfig.LONGCAT_MODEL_ID.equals(activeModelId)
                ? ModelConfig.LOCAL_MODEL_ID
                : null;
        // snapshotSpec / executionGuard 由 SandboxSnapshotConfig 统一装配。
        // 主 Agent 与子 Agent 都走框架托管的 DockerFilesystemSpec，避免应用侧维护第二套容器生命周期。
        return new AgentRuntimeConfigurer(
                stateStore,
                sandboxClient,
                activeModelId,
                fallbackModelId,
                snapshotSpec,
                sandboxExecutionGuard,
                dataAgentToolkit);
    }

    @Bean
    public DataAgentBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            AgentRuntimeConfigurer agentRuntimeConfigurer,
            io.agentscope.dataagent.agent.domain.GlobalAgentOverrideStore overrideStore,
            AgentProperties agentProps,
            WorkspaceProperties workspaceProps)
            throws IOException {
        Path cwd = resolveCwd(workspaceProps);
        ensureAgentscopeConfig(agentProps);

        DataAgentBootstrap.Builder builder =
                DataAgentBootstrap.builder()
                        .cwd(cwd)
                        .overrideStore(overrideStore);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "未配置 model。请检查 dataagent.ollama 配置或提供自定义 Model bean。"
                            + "在可用 model 之前，Agent 调用将失败。");
        }

        builder.configureAllAgents(agentRuntimeConfigurer);

        DataAgentBootstrap bootstrap = builder.build();

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

    @Bean
    public io.agentscope.dataagent.security.infrastructure.IdentityLinkStore identityLinkStore(
            DataAgentBootstrap bootstrap) {
        Path agentscopeDir = bootstrap.cwd().resolve(".agentscope");
        return new io.agentscope.dataagent.security.infrastructure.IdentityLinkStore(agentscopeDir);
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

    private Path resolveCwd(WorkspaceProperties props) {
        String root = props.getRoot();
        if (root != null && !root.isBlank()) {
            return Paths.get(root).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private void ensureAgentscopeConfig(AgentProperties agentProps) throws IOException {
        Path configFile = DataAgentBootstrap.DEFAULT_CONFIG_PATH;
        Path workspaceRoot = DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT;

        if (Files.exists(configFile)) {
            upgradeGeneratedWorkspacePrompt(
                    workspaceRoot.resolve("AGENTS.md"), resolvePrompt(agentProps.getSystemPrompt()));
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

        io.agentscope.dataagent.workspace.application.WorkspaceScaffolder.scaffold(
                workspaceRoot, "Data Agent", resolvePrompt(agentProps.getSystemPrompt()));
    }

    static void upgradeGeneratedWorkspacePrompt(Path agentsMd, String prompt)
            throws IOException {
        if (!Files.isRegularFile(agentsMd) || prompt == null || prompt.isBlank()) {
            return;
        }
        String current = Files.readString(agentsMd);
        String generatedIntro =
                "You are a Data Agent built with AgentScope. You help users explore, analyse, "
                        + "visualise and report on data. Prefer registered skills and sub-agents "
                        + "over ad-hoc reasoning; always cite the data source you used.";
        String marker = "\n## How this folder works";
        int markerIndex = current.indexOf(marker);
        String currentIntro = markerIndex >= 0
                ? current.substring(0, markerIndex).trim()
                : "";
        boolean generatedPrompt = current.contains(generatedIntro)
                || currentIntro.equals("# Data Agent\n\nclasspath:/prompts/system.md");
        if (!generatedPrompt || markerIndex < 0) {
            return;
        }
        String upgraded = "# Data Agent\n\n" + prompt.trim() + "\n" + current.substring(markerIndex);
        Files.writeString(agentsMd, upgraded);
        log.info("Upgraded generated Data Agent workspace prompt at {}", agentsMd);
    }

    static String resolvePrompt(String prompt) {
        if (prompt == null || !prompt.startsWith("classpath:")) {
            return prompt;
        }
        String resourcePath = prompt.substring("classpath:".length());
        String absoluteResource = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (var input = BootstrapConfig.class.getResourceAsStream(absoluteResource)) {
            if (input == null) {
                throw new IOException("Prompt resource not found: " + absoluteResource);
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Unable to resolve system prompt resource {}: {}", prompt, e.getMessage());
            return prompt;
        }
    }
}
