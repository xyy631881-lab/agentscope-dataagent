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
package io.agentscope.dataagent.tools.data;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 在启动时将 {@link DataAgentToolkit} 单例注册到主 Agent 的 toolkit 上。
 *
 * <p>toolkit 已由 {@link DataToolkitConfig} 创建为 Spring bean（含可选的 JDBC DataSource）。
 * 本类负责将其注册到 AgentScope HarnessAgent 的工具集中。
 */
@Component
public class DataToolkitRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitRegistrar.class);

    private final DataAgentBootstrap bootstrap;
    private final DataAgentToolkit toolkit;

    public DataToolkitRegistrar(DataAgentBootstrap bootstrap, DataAgentToolkit toolkit) {
        this.bootstrap = bootstrap;
        this.toolkit = toolkit;
    }

    @PostConstruct
    public void registerDataToolkit() {
        HarnessAgent main = bootstrap.agents().get(bootstrap.loadedConfig().getMain());
        if (main == null) {
            main =
                    bootstrap.agents().values().stream()
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "没有可用于注册 data toolkit 的 Agent"));
        }
        try {
            main.getDelegate().getToolkit().registerTool(toolkit);
            log.info("已向主 Agent '{}' 注册 DataAgent toolkit (含工具: {})",
                    main.getName(),
                    toolkit.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.warn("向主 Agent 注册 DataAgent toolkit 失败: {}", e.getMessage());
        }
    }
}
