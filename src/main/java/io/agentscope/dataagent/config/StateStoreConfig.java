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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.dataagent.config.properties.RuntimeRedisProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope runtime state 后端装配的唯一来源。两种实现二选一：
 *
 * <ul>
 *   <li><b>Redis 后端</b>（分布式）：当 {@code dataagent.runtime.redis.enabled=true} 且容器中没有
 *       其它 {@link AgentStateStore} Bean 时注册 {@code RedisAgentStateStore}。适用于多副本部署，
 *       让各 pod 共享沙箱隔离状态。
 *   <li><b>内存后端</b>（默认/单副本）：当上述 Redis Bean 未注册时，作为兜底注册
 *       {@code InMemoryAgentStateStore}。注意它是进程内的——多副本下需配合 sticky session，
 *       否则不同 pod 会各持一份状态。
 * </ul>
 *
 * <p>两个声明放在同一个 {@code @Configuration} 内，且 Redis 在前、内存在后：Spring 按声明顺序
 * 求值 {@code @ConditionalOnMissingBean}，Redis 先注册成功则内存兜底自动跳过。这样避免了分散在
 * 多个配置类时因处理顺序导致的"内存先注册、Redis 被跳过"的隐患。
 *
 * <p>典型用法：启动时加 {@code --spring.profiles.active=mysql,redis}，
 * application-redis.yml 会自动把 {@code dataagent.runtime.redis.enabled} 置为 true。
 */
@Configuration
public class StateStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StateStoreConfig.class);

    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    @ConditionalOnProperty(prefix = "dataagent.runtime.redis", name = "enabled", havingValue = "true")
    public AgentStateStore redisAgentStateStore(
            RuntimeRedisProperties props,
            org.springframework.boot.autoconfigure.data.redis.RedisProperties redisProps) {
        log.info(
                "构建 RedisAgentStateStore: redis={}:{}, db={}, runtimeKeyPrefix={}",
                redisProps.getHost(),
                redisProps.getPort(),
                redisProps.getDatabase(),
                props.getKeyPrefix());

        RedisURI.Builder uriBuilder =
                RedisURI.builder()
                        .redis(redisProps.getHost(), redisProps.getPort())
                        .withDatabase(redisProps.getDatabase());
        if (redisProps.getPassword() != null && !redisProps.getPassword().isEmpty()) {
            uriBuilder.withPassword(redisProps.getPassword().toCharArray());
        }

        RedisClient client = RedisClient.create(uriBuilder.build());
        return RedisAgentStateStore.builder()
                .lettuceClient(client)
                .keyPrefix(props.getKeyPrefix())
                .build();
    }

    /**
     * 默认兜底：进程内 {@link InMemoryAgentStateStore}。仅在 Redis 后端未启用时注册。
     *
     * <p>覆盖方式：在任意 {@code @Configuration} 中声明自己的 {@code AgentStateStore} Bean 即可
     * （本类的两个声明都带 {@code @ConditionalOnMissingBean}，会被你的 Bean 顶掉）。
     */
    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore inMemoryAgentStateStore() {
        log.info("AgentStateStore 未配置 Redis 后端，使用进程内 InMemoryAgentStateStore（单副本/粘性会话）");
        return new InMemoryAgentStateStore();
    }
}
