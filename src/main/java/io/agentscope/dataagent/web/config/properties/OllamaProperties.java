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
 * Ollama 本地模型配置属性。
 *
 * <p>对应 {@code application.yml} 中的 {@code dataagent.ollama} 前缀。
 * 修复了原来 {@code @Value("${dataagent.ollama.model-name}")} 读不到 YAML 中
 * {@code dataagent.ollama.model.chat} 的问题——现在通过嵌套 {@link Model} 类正确绑定。
 */
@ConfigurationProperties(prefix = "dataagent.ollama")
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";
    private Model model = new Model();
    private String fallbackModelName = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public String getFallbackModelName() {
        return fallbackModelName;
    }

    public void setFallbackModelName(String fallbackModelName) {
        this.fallbackModelName = fallbackModelName;
    }

    /** Ollama 模型配置，对应 YAML 中 {@code dataagent.ollama.model} 下的字段。 */
    public static class Model {
        private String chat = "qwen2.5:1.5b";

        public String getChat() {
            return chat;
        }

        public void setChat(String chat) {
            this.chat = chat;
        }
    }
}
