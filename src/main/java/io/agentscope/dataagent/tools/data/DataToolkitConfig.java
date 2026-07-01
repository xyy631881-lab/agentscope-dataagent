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
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DataAgent toolkit 默认值的 Spring 连线。暴露
 * {@link DataSourceRegistry}、{@link ChartRenderer} 和
 * {@link DataAgentToolkit} 三个 bean，以便操作员可以独立覆盖任意一个。
 *
 * <p>如果有 {@code analyticsDataSource} bean（由 {@link AnalyticsDataConfig} 提供），
 * 则自动注入到 toolkit 中，启用真实 SQL 执行。
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

    /**
     * 创建 DataAgentToolkit，自动检测是否有可用的分析 DataSource。
     * 如果有 {@code analyticsDataSource} bean，toolkit 的 SQL 工具就能真实执行。
     */
    @Bean
    @ConditionalOnMissingBean(DataAgentToolkit.class)
    public DataAgentToolkit dataAgentToolkit(
            DataSourceRegistry registry,
            ChartRenderer chartRenderer,
            @Qualifier("analyticsDataSource")
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            DataSource analyticsDataSource) {
        DataAgentToolkit toolkit = new DataAgentToolkit(registry, chartRenderer, analyticsDataSource);
        log.info("DataAgentToolkit 已创建: registry={}, chartRenderer={}, jdbcDataSource={}",
                registry.getClass().getSimpleName(),
                chartRenderer.getClass().getSimpleName(),
                analyticsDataSource != null ? "present" : "absent");
        return toolkit;
    }
}
