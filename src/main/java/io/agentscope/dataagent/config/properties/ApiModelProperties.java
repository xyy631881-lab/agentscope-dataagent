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
 * 模型选择配置属性。
 *
 * <p>对应 {@code application.yml} 中的 {@code dataagent.model} 前缀。
 * 通过 {@code active} 在「本地 Ollama」与「LongCat API」之间切换全局默认模型；
 * 两个模型都会注册到 {@link io.agentscope.core.model.ModelRegistry}，
 * 也可在管理台按 Agent 单独选择（{@code local} / {@code longcat}）。
 */
@ConfigurationProperties(prefix = "dataagent.model")
public class ApiModelProperties {

    /** 当前默认模型：{@code ollama}(本地) 或 {@code longcat}(API)。默认本地。 */
    private String active = "ollama";

    private LongCat longcat = new LongCat();

    public String getActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public LongCat getLongcat() {
        return longcat;
    }

    public void setLongcat(LongCat longcat) {
        this.longcat = longcat;
    }

    /** LongCat（OpenAI 兼容）API 配置。 */
    public static class LongCat {
        /** API Key，建议通过环境变量 {@code LONGCAT_API_KEY} 注入。 */
        private String apiKey = "";

        /** 模型名，LongCat 目前为 {@code LongCat-2.0}。 */
        private String modelName = "LongCat-2.0";

        /** OpenAI 兼容接入端点（不含 /v1，框架会自动拼接为 /v1/chat/completions）。 */
        private String baseUrl = "https://api.longcat.chat/openai";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
