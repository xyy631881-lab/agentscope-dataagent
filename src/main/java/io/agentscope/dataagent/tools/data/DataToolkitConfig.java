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

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DataAgent toolkit 默认值的 Spring 连线。暴露一个 {@link DataSourceRegistry}
 * （空的 {@link InMemoryDataSourceRegistry}）和 {@link ChartRenderer}
 * （{@link StubChartRenderer}），以便操作员可以独立覆盖任意一个—
 * 例如使用 Spring profile 连接 JDBC 支持的注册表或服务端 PNG 渲染器。
 *
 * <p>toolkit 实际注册到主 Agent 的工具包的操作在 {@link DataToolkitRegistrar} 中，
 * 这样 {@code @PostConstruct} 不会与在此定义的 {@code @Bean} 方法的自注入冲突。
 */
@Configuration
public class DataToolkitConfig {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitConfig.class);

    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry inMemoryDataSourceRegistry() {
        log.info(
                "DataToolkitConfig: 未找到 DataSourceRegistry bean，使用空的"
                        + " InMemoryDataSourceRegistry");
        return new InMemoryDataSourceRegistry(List.of());
    }

    @Bean
    @ConditionalOnMissingBean(ChartRenderer.class)
    public ChartRenderer stubChartRenderer() {
        log.info("DataToolkitConfig: 未找到 ChartRenderer bean，使用 StubChartRenderer");
        return new StubChartRenderer();
    }
}
