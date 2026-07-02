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
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 独立 H2 分析数据库配置，与项目 JPA 数据库隔离。
 *
 * <p>通过激活 profile {@code analytics-h2} 或设置
 * {@code dataagent.analytics.h2.enabled=true} 启用。
 * 启动时自动执行 {@code data-analytics.sql} 种子脚本。
 *
 * <p>创建两个 bean：
 * <ul>
 *   <li>{@code analyticsDataSource} — HikariCP 管理的 H2 DataSource (jdbc:h2:mem:analytics)</li>
 *   <li>{@link DataSourceRegistry} — 预配置数据源注册表，替代默认空实现</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "dataagent.analytics.h2.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsDataConfig {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsDataConfig.class);

    /**
     * 分析专用的 H2 DataSource（内存模式，测试用）。
     * <p>生产环境可改为文件模式: jdbc:h2:file:/path/to/analytics
     */
    @Bean(name = "analyticsDataSource")
    @Primary
    public DataSource analyticsDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:analytics"
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(3);
        config.setPoolName("AnalyticsH2");
        log.info("Analytics H2 DataSource 已配置 (内存模式)");

        HikariDataSource ds = new HikariDataSource(config);

        // 启动时执行种子 SQL
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("data-analytics.sql"));
            populator.setContinueOnError(true);
            populator.execute(ds);
            log.info("Analytics 种子数据已加载 (products, users, orders, daily_sales)");
        } catch (Exception e) {
            log.warn("Analytics 种子数据加载失败: {}", e.getMessage());
        }
        return ds;
    }

    /**
     * 数据源注册表 —— 向 Agent 暴露分析数据库中的数据源。
     * 当前默认只注册了一个数据源：电商业务分析 H2 数据库。
     */
    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry analyticsDataSourceRegistry() {
        io.agentscope.dataagent.tools.data.DataSource ds =
                new io.agentscope.dataagent.tools.data.DataSource(
                "analytics_db",
                "电商业务数据库",
                "包含 2024 年全年电商销售数据：products (15款)、users (20人)、"
                        + "orders (120+笔)、daily_sales (每日汇总)。"
                        + "覆盖电子产品、运动户外、食品饮料、家居办公、图书教育 5 大品类。",
                "h2",
                "jdbc:h2:mem:analytics",
                List.of("电商", "销售", "2024", "零售"),
                Map.of("dialect", "MySQL"));
        List<io.agentscope.dataagent.tools.data.DataSource> sources = List.of(ds);
        log.info("Analytics DataSourceRegistry 已注册: {} 个数据源", sources.size());
        return new InMemoryDataSourceRegistry(sources);
    }
}
