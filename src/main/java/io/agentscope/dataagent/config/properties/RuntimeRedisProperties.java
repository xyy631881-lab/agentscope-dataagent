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
package io.agentscope.dataagent.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis backend for AgentScope runtime state.
 *
 * <p>This switch is intentionally runtime-scoped, not session-scoped. The same Redis connection
 * enables the framework {@code AgentStateStore}, sandbox snapshots, and sandbox execution guard.
 * Business session metadata remains in MySQL.
 */
@ConfigurationProperties(prefix = "dataagent.runtime.redis")
public class RuntimeRedisProperties {

    private boolean enabled = false;
    private String keyPrefix = "dataagent:runtime:";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
