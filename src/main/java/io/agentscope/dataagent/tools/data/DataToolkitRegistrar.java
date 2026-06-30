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
 * 在启动时将单例 {@link DataAgentToolkit} 连接到内置主 Agent 的 toolkit 上，
 * 以便 Agent 可以调用 {@code list_data_sources}、{@code describe_table}、
 * {@code run_sql_preview} 和 {@code render_chart}。
 *
 * <p>镜像 {@code ContributionToolRegistrar}：在 {@link DataAgentBootstrap} 构建了所有
 * Agent 后运行，错误时软失败，以便缺失的工具插槽不会阻止应用程序启动。
 */
@Component
public class DataToolkitRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitRegistrar.class);

    private final DataAgentBootstrap bootstrap;
    private final DataSourceRegistry registry;
    private final ChartRenderer chartRenderer;

    public DataToolkitRegistrar(
            DataAgentBootstrap bootstrap,
            DataSourceRegistry registry,
            ChartRenderer chartRenderer) {
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.chartRenderer = chartRenderer;
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
            main.getDelegate()
                    .getToolkit()
                    .registerTool(new DataAgentToolkit(registry, chartRenderer));
            log.info("已向主 Agent '{}' 注册 DataAgent toolkit", main.getName());
        } catch (RuntimeException e) {
            log.warn("向主 Agent 注册 DataAgent toolkit 失败: {}", e.getMessage());
        }
    }
}
