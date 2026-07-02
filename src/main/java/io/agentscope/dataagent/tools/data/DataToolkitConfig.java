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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DataToolkitConfig 是数据分析工具集的装配车间——负责把三个零件
 * （DataSourceRegistry、ChartRenderer、JDBC DataSource）
 * 组装成最终的 DataAgentToolkit，并且每个零件都可以被管理员替换。
 */
@Configuration
public class DataToolkitConfig {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitConfig.class);

    // DataToolkitConfig 中的兜底 Bean
    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry inMemoryDataSourceRegistry() {
        log.info(
                "DataToolkitConfig: 未找到 DataSourceRegistry bean，使用空的"
                        + " InMemoryDataSourceRegistry");
        return new InMemoryDataSourceRegistry(List.of());  // 空！没有数据源
    }

    @Bean
    @ConditionalOnMissingBean(ChartRenderer.class)
    public ChartRenderer stubChartRenderer() {
        log.info("DataToolkitConfig: 未找到 ChartRenderer bean，使用 StubChartRenderer");
        return new StubChartRenderer();  // 空壳图表渲染器
    }

    /**
     * 创建 DataAgentToolkit，组装上面两个 + 可选 JdbcTemplate。
     *
     * <p>关键点：把 DataSource 包装成 JdbcTemplate 再传给 Toolkit，
     * 这样 Toolkit 内部就不用手写 try-with-resources 和 ResultSet 遍历了。
     *
     * <p>三种零件来源：
     * <ul>
     *   <li>registry：Spring 容器（可能空）</li>
     *   <li>chartRenderer：Spring 容器（可能空壳）</li>
     *   <li>analyticsDataSource：Spring 容器（可选，没有就 null → stub 模式）</li>
     * </ul>
     */
    @Bean
    @ConditionalOnMissingBean(DataAgentToolkit.class)
    public DataAgentToolkit dataAgentToolkit(
            DataSourceRegistry registry,
            ChartRenderer chartRenderer,
            @Qualifier("analyticsDataSource")
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            DataSource analyticsDataSource) {
        // 有 DataSource 就包装成 JdbcTemplate，没有就传 null（stub 模式）
        JdbcTemplate jdbcTemplate = analyticsDataSource != null
                ? new JdbcTemplate(analyticsDataSource)
                : null;
        DataAgentToolkit toolkit = new DataAgentToolkit(registry, chartRenderer, jdbcTemplate);
        log.info("DataAgentToolkit 已创建: registry={}, chartRenderer={}, jdbcTemplate={}",
                registry.getClass().getSimpleName(),
                chartRenderer.getClass().getSimpleName(),
                jdbcTemplate != null ? "present" : "absent");
        return toolkit;
    }
}
