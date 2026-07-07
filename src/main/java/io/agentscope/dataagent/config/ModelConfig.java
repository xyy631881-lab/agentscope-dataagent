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

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.dataagent.config.properties.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配——使用本地 Ollama 服务中的模型。
 *
 * <p>Ollama 是本地推理引擎，无需 API Key，可运行各种开源模型。
 * 默认连接 http://localhost:11434，可通过 {@code dataagent.ollama.base-url} 配置。
 */
@Configuration
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    @Bean
    @ConditionalOnMissingBean(Model.class)
    public Model ollamaModel(OllamaProperties props) {
        String modelName = props.getModel().getChat();
        log.info("初始化 Ollama 本地模型: model={}, baseUrl={}", modelName, props.getBaseUrl());
        return OllamaChatModel.builder()
                .modelName(modelName)
                .baseUrl(props.getBaseUrl())
                .build();
    }
}
