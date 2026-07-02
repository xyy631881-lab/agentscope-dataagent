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
package io.agentscope.dataagent.web.persistence.jpa;

import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * JpaPersistenceConfig 是"数据库持久化层的总开关"——它通过 3 个注解激活 Spring Data JPA 的全套能力，
 * 并创建 2 个核心 Bean（用户存储 + Agent 定义存储），是连接"业务接口"和"数据库表"的桥梁。
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = JpaPersistenceConfig.class)
@EntityScan(basePackageClasses = JpaPersistenceConfig.class)
@EnableTransactionManagement
public class JpaPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(JpaPersistenceConfig.class);

    // ← 创建 UserStore 实现
    @Bean
    public UserStore jpaUserStore(UserEntityRepository repository) {
        log.info("Persistence: user store backed by JPA");
        return new JpaUserStore(repository);
    }

    // ← 创建 Agent 定义存储实现
    @Bean
    public UserAgentDefinitionStore jpaUserAgentDefinitionStore(AgentEntityRepository repository) {
        log.info("Persistence: agent definition store backed by JPA");
        return new JpaUserAgentDefinitionStore(repository);
    }
}
