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
import io.agentscope.dataagent.capability.marketplace.application.MarketplaceConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.config.properties.AgentProperties;
import io.agentscope.dataagent.config.properties.ApiModelProperties;
import io.agentscope.dataagent.config.properties.ConversationHistoryProperties;
import io.agentscope.dataagent.config.properties.OllamaProperties;
import io.agentscope.dataagent.config.properties.RuntimeRedisProperties;
import io.agentscope.dataagent.config.properties.WorkspaceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * agentscope-dataagent web 模块的 Spring Boot 配置入口。
 *
 * <p>这个类现在只负责两件事：
 * <ol>
 *   <li>注册 @ConfigurationProperties 类（分散在各 Config 类中使用的配置属性）
 *   <li>提供 ObjectMapper 兜底 Bean
 * </ol>
 *
 * <p>原来的 @Bean 方法已按职责拆分到：
 * <ul>
 *   <li>{@link ModelConfig} — Ollama 模型
 *   <li>{@link StateStoreConfig} — Redis AgentStateStore
 *   <li>{@link BootstrapConfig} — 运行时配置器、引导启动器、身份关联、通道
 *   <li>{@link MarketplaceConfig} — 市场工厂注册
 * </ul>
 *
 * <p>读取的配置都来自 application.yml 里的 {@code dataagent.*} 前缀。
 * 属性绑定通过 {@link OllamaProperties}、{@link AgentProperties}、
 * {@link WorkspaceProperties}、{@link RuntimeRedisProperties} 完成。
 */
@Configuration
@EnableConfigurationProperties({
    OllamaProperties.class,
    ApiModelProperties.class,
    AgentProperties.class,
    ConversationHistoryProperties.class,
    WorkspaceProperties.class,
    RuntimeRedisProperties.class
})
public class DataAgentConfig {

    /**
     * 兜底 ObjectMapper——Spring MVC 通常会自动配置一个，
     * 但某些场景（如自定义序列化）可能需要覆盖，这里给一个默认实例。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
