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
 * DataToolkitRegistrar 是一个工具注册员——应用启动时，把 DataAgentToolkit（数据分析工具箱）挂载到主 Agent 身上，
 * 让 Agent 能用"查数据源、看表结构、跑 SQL、画图表"这四个工具。
 */
@Component
public class DataToolkitRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitRegistrar.class);

    private final DataAgentBootstrap bootstrap;  // 应用启动时的引导器
    private final DataAgentToolkit toolkit;  //数据分析工具箱

    public DataToolkitRegistrar(DataAgentBootstrap bootstrap, DataAgentToolkit toolkit) {
        this.bootstrap = bootstrap;
        this.toolkit = toolkit;
    }

    @PostConstruct
    public void registerDataToolkit() {
        // 把"向主 Agent 挂载 DataAgent toolkit"封装成安装器：启动期立即挂到当前主 Agent，
        // 并记录下来，供全局 Agent 热重建时重新应用。
        bootstrap.registerMainAgentToolInstaller(
                main -> {
                    try {
                        main.getDelegate().getToolkit().registerTool(toolkit);
                        log.info(
                                "已向主 Agent '{}' 注册 DataAgent toolkit (含工具: {})",
                                main.getName(),
                                toolkit.getClass().getSimpleName());
                    } catch (RuntimeException e) {
                        log.warn("向主 Agent 注册 DataAgent toolkit 失败: {}", e.getMessage());
                    }
                });
    }
}
