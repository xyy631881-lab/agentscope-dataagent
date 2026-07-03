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
import org.springframework.beans.factory.annotation.Value;
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
 * 独立分析数据库配置，与项目主业务库（JPA）隔离。
 *
 * <p>这是一个独立的 MySQL 数据库（或 schema），专门用于给 Agent 调用 SQL 工具时查询演示数据。
 * 主业务库（用户/Agent/会话等 JPA 实体）走 Spring 主 DataSource，本类创建的 analyticsDataSource
 * 只用于 {@link DataAgentToolkit} 的 {@code list_data_sources}/{@code run_sql_preview} 等工具。
 *
 * <h2>配置项（在 application.yml 里覆盖）</h2>
 * <pre>
 * dataagent:
 *   analytics:
 *     enabled: true                                          # 总开关，默认 true
 *     jdbc-url: jdbc:mysql://localhost:3306/dataagent_analytics?...
 *     username: root
 *     password: ${MYSQL_PASSWORD:root}
 *     maximum-pool-size: 3
 *     init-script: data-analytics-mysql.sql                  # 启动时执行的种子脚本
 * </pre>
 *
 * <h2>数据源隔离设计</h2>
 * <ul>
 *   <li>{@code primaryDataSource} — HikariCP 管理的主业务库 DataSource（@Primary，被 JPA / Hibernate 使用，走 spring.datasource.* 配置）</li>
 *   <li>{@code analyticsDataSource} — HikariCP 管理的分析库 DataSource（被 DataToolkitConfig 通过 @Qualifier 注入 JdbcTemplate，仅供 Agent 查询演示数据）</li>
 *   <li>{@link DataSourceRegistry} — 向 Agent 暴露的"数据源目录"，Agent 通过 list_data_sources 工具能看到</li>
 * </ul>
 *
 * <h2>为什么独立数据库而不是用主业务库？</h2>
 * <ol>
 *   <li><b>安全隔离</b>：Agent 生成 SQL 只能查演示数据，不能碰到用户/会话/权限表</li>
 *   <li><b>可替换</b>：生产环境把这里的演示数据换成真实业务库即可，主业务库不受影响</li>
 *   <li><b>清晰边界</b>：数据分析能力是"只读消费业务数据"，不是"管理平台元数据"</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(name = "dataagent.analytics.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsDataConfig {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsDataConfig.class);

    /**
     * 主业务库 DataSource（JPA / Hibernate 用）。
     *
     * <p>当容器内存在多个 DataSource bean 时，Spring Boot JPA 自动配置会因无法判定主数据源而退出，
     * 导致 JPA 复用唯一的 {@code analyticsDataSource}（业务表错误地建到分析库里）。
     * 此处显式声明主数据源并标记 {@code @Primary}，确保 JPA 走 {@code spring.datasource.*} 配置，
     * 与 {@code analyticsDataSource}（演示数据）物理隔离。
     *
     * <p>配置项来自 {@code application-mysql.yml} 的 {@code spring.datasource.*}：
     * <ul>
     *   <li>{@code spring.datasource.url} — 主业务库 JDBC URL（默认 agentscope_dataagent）</li>
     *   <li>{@code spring.datasource.username} / {@code spring.datasource.password}</li>
     *   <li>{@code spring.datasource.hikari.maximum-pool-size}</li>
     * </ul>
     *
     * @return HikariCP 管理的主业务库 DataSource（@Primary，被 JPA / Hibernate 使用）
     */
    @Bean(name = "primaryDataSource")
    @Primary
    @ConditionalOnMissingBean(name = "primaryDataSource")
    public DataSource primaryDataSource(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:root}") String username,
            @Value("${spring.datasource.password:root}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:20}") int maxPoolSize,
            @Value("${spring.datasource.hikari.pool-name:DataAgentPool}") String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName(poolName);
        // MySQL 8 驱动自动探测，无需显式指定 driver-class-name
        log.info("Primary MySQL DataSource 已配置: {}", jdbcUrl);
        return new HikariDataSource(config);
    }

    /**
     * 分析专用的 MySQL DataSource。
     *
     * <p>通过 {@code dataagent.analytics.*} 配置项驱动，默认指向本地 MySQL 的
     * {@code dataagent_analytics} 数据库。启动时自动执行 {@code data-analytics-mysql.sql}
     * 种子脚本（幂等：DROP TABLE IF EXISTS + CREATE + INSERT），保证演示数据存在。
     *
     * @return HikariCP 管理的 MySQL DataSource
     */
    @Bean(name = "analyticsDataSource")
    public DataSource analyticsDataSource(
            @Value("${dataagent.analytics.jdbc-url:jdbc:mysql://localhost:3306/dataagent_analytics?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true}") String jdbcUrl,
            @Value("${dataagent.analytics.username:${spring.datasource.username:root}}") String username,
            @Value("${dataagent.analytics.password:${spring.datasource.password:root}}") String password,
            @Value("${dataagent.analytics.maximum-pool-size:3}") int maxPoolSize,
            @Value("${dataagent.analytics.init-script:data-analytics-mysql.sql}") String initScript) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName("AnalyticsMySQL");
        // MySQL 8 驱动自动探测，无需显式指定 driver-class-name
        log.info("Analytics MySQL DataSource 已配置: {}", jdbcUrl);

        HikariDataSource ds = new HikariDataSource(config);

        // 启动时执行种子 SQL（幂等脚本，重复执行无副作用）
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource(initScript));
            populator.setContinueOnError(true);
            populator.setSeparator(";");  // MySQL 标准分隔符
            populator.execute(ds);
            log.info("Analytics 种子数据已加载 ({}) — products, users, orders, daily_sales", initScript);
        } catch (Exception e) {
            log.warn("Analytics 种子数据加载失败: {}", e.getMessage());
        }
        return ds;
    }

    /**
     * 数据源注册表 —— 向 Agent 暴露分析数据库中的数据源。
     *
     * <p>Agent 调用 {@code list_data_sources} 工具时返回这里注册的数据源列表。
     * 当前默认只注册了一个：电商业务分析数据库。
     *
     * <p>注意：这里的 {@code jdbcUrl} 字段仅用于"展示给 Agent 看"，Agent 实际查询时
     * 用的是 {@link DataAgentToolkit} 注入的 JdbcTemplate（底层是 {@code analyticsDataSource}），
     * 不会重新建立连接。所以 jdbcUrl 这里填什么不影响实际查询。
     *
     * @return 数据源注册表
     */
    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry analyticsDataSourceRegistry(
            @Value("${dataagent.analytics.jdbc-url:jdbc:mysql://localhost:3306/dataagent_analytics}") String jdbcUrl) {
        io.agentscope.dataagent.tools.data.DataSource ds =
                new io.agentscope.dataagent.tools.data.DataSource(
                "analytics_db",
                "电商业务数据库",
                "包含 2024 年全年电商销售数据：products (15款)、users (20人)、"
                        + "orders (120+笔)、daily_sales (每日汇总)。"
                        + "覆盖电子产品、运动户外、食品饮料、家居办公、图书教育 5 大品类。",
                "mysql",
                jdbcUrl,
                List.of("电商", "销售", "2024", "零售"),
                Map.of("dialect", "MySQL"));
        List<io.agentscope.dataagent.tools.data.DataSource> sources = List.of(ds);
        log.info("Analytics DataSourceRegistry 已注册: {} 个数据源", sources.size());
        return new InMemoryDataSourceRegistry(sources);
    }
}
