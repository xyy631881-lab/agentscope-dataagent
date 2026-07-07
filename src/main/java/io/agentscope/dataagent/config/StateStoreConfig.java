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
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.dataagent.config.properties.SessionRedisProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分布式记忆后端装配——基于 Redis 的 AgentStateStore。
 *
 * <p>触发条件：{@code dataagent.session.redis.enabled=true}
 * 且 Spring 容器中还没有自定义的 {@link AgentStateStore} Bean。
 *
 * <p>典型用法：启动时加 {@code --spring.profiles.active=dev,mysql,redis}，
 * application-redis.yml 会自动把 {@code dataagent.session.redis.enabled} 置为 true。
 */
@Configuration
public class StateStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StateStoreConfig.class);

    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    @ConditionalOnProperty(prefix = "dataagent.session.redis", name = "enabled", havingValue = "true")
    public AgentStateStore redisAgentStateStore(
            SessionRedisProperties props,
            org.springframework.boot.autoconfigure.data.redis.RedisProperties redisProps) {
        log.info(
                "构建 RedisAgentStateStore: redis={}:{}, db={}, keyPrefix={}",
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
}
