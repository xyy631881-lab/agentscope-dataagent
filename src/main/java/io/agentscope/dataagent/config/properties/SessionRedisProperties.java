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
 * Redis 会话存储配置属性。
 *
 * <p>对应 {@code application.yml} 中的 {@code dataagent.session.redis} 前缀。
 */
@ConfigurationProperties(prefix = "dataagent.session.redis")
public class SessionRedisProperties {

    private boolean enabled = false;
    private String keyPrefix = "dataagent:session:";

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
