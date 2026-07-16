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

import io.agentscope.dataagent.config.properties.RuntimeRedisProperties;
import io.agentscope.extensions.redis.sandbox.RedisSandboxExecutionGuard;
import io.agentscope.extensions.redis.snapshot.RedisSnapshotSpec;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Framework sandbox snapshot and execution-guard configuration.
 *
 * <p>Redis is used for framework state, workspace snapshots, and the framework's distributed
 * execution lease. No application-specific sandbox lock or lifecycle manager is installed.
 */
@Configuration
public class SandboxSnapshotConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxSnapshotConfig.class);
    private static final String SNAPSHOT_KEY_PREFIX = "dataagent:sandbox:snapshots:";

    @Bean
    @ConditionalOnMissingBean(SandboxSnapshotSpec.class)
    @ConditionalOnProperty(prefix = "dataagent.runtime.redis", name = "enabled", havingValue = "true")
    public SandboxSnapshotSpec redisSandboxSnapshotSpec(
            RuntimeRedisProperties props, RedisProperties redisProps) {
        String prefix =
                props.getKeyPrefix() != null && !props.getKeyPrefix().isBlank()
                        ? props.getKeyPrefix() + "snapshot:"
                        : SNAPSHOT_KEY_PREFIX;
        log.info(
                "Sandbox snapshot backend=Redis: redis={}:{}, prefix={}",
                redisProps.getHost(),
                redisProps.getPort(),
                prefix);
        return new RedisSnapshotSpec(buildJedis(redisProps), prefix, null);
    }

    @Bean
    @ConditionalOnMissingBean(SandboxSnapshotSpec.class)
    public SandboxSnapshotSpec noopSandboxSnapshotSpec() {
        log.info("Sandbox snapshot backend=Noop");
        return new NoopSnapshotSpec();
    }

    @Bean
    @ConditionalOnMissingBean(SandboxExecutionGuard.class)
    @ConditionalOnProperty(prefix = "dataagent.runtime.redis", name = "enabled", havingValue = "true")
    public SandboxExecutionGuard redisSandboxExecutionGuard(
            RuntimeRedisProperties props, RedisProperties redisProps) {
        String prefix =
                props.getKeyPrefix() != null && !props.getKeyPrefix().isBlank()
                        ? props.getKeyPrefix() + "guard:"
                        : "dataagent:sandbox:guard:";
        return RedisSandboxExecutionGuard.builder(buildJedis(redisProps))
                .keyPrefix(prefix)
                .leaseTtl(Duration.ofMinutes(30))
                .retryInterval(Duration.ofMillis(500))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(SandboxExecutionGuard.class)
    public SandboxExecutionGuard noopSandboxExecutionGuard() {
        return SandboxExecutionGuard.noop();
    }

    private static UnifiedJedis buildJedis(RedisProperties redisProps) {
        String host = redisProps.getHost() != null ? redisProps.getHost() : "localhost";
        StringBuilder uri = new StringBuilder("redis://");
        String password = redisProps.getPassword();
        if (password != null && !password.isEmpty()) {
            uri.append(':').append(password).append('@');
        }
        uri.append(host).append(':').append(redisProps.getPort());
        if (redisProps.getDatabase() != 0) {
            uri.append('/').append(redisProps.getDatabase());
        }
        return new JedisPooled(uri.toString());
    }
}
