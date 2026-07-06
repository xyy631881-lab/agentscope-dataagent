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
package io.agentscope.dataagent.web.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 身份与提示词配置属性。
 *
 * <p>对应 {@code application.yml} 中的 {@code dataagent.agent} 前缀。
 */
@ConfigurationProperties(prefix = "dataagent.agent")
public class AgentProperties {

    private String name = "data-agent";
    private String systemPrompt =
            "You are a Data Agent built with AgentScope."
                    + " You help users explore, analyse, visualise and report on data.";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
