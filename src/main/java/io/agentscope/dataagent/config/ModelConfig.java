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
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.OllamaChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.dataagent.config.properties.ApiModelProperties;
import io.agentscope.dataagent.config.properties.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配——同时注册「本地 Ollama」与「LongCat API」两个模型，并按配置选择全局默认模型。
 *
 * <p>两个模型都会注册到 {@link ModelRegistry}（id 分别为 {@link #LOCAL_MODEL_ID} 与
 * {@link #LONGCAT_MODEL_ID}），因此可在管理台按 Agent 单独指定 {@code model} 字段来切换。
 * 全局默认模型由 {@code dataagent.model.active}（ollama / longcat）决定，对应这里返回的
 * 那个 {@link Model} Bean，被 {@code DataAgentBootstrap} 用作未显式指定模型的 Agent 的默认模型。
 */
@Configuration
public class ModelConfig {

    /** 本地 Ollama 模型在 ModelRegistry 中的 id。 */
    public static final String LOCAL_MODEL_ID = "local";

    /** LongCat API 模型在 ModelRegistry 中的 id。 */
    public static final String LONGCAT_MODEL_ID = "longcat";

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    @Bean
    public Model model(OllamaProperties ollamaProps, ApiModelProperties apiProps) {
        // 1) 本地 Ollama
        String ollamaName = ollamaProps.getModel().getChat();
        Model local =
                OllamaChatModel.builder()
                        .modelName(ollamaName)
                        .baseUrl(ollamaProps.getBaseUrl())
                        .build();
        ModelRegistry.register(LOCAL_MODEL_ID, local);
        log.info(
                "注册本地模型: id={}, model={}, baseUrl={}",
                LOCAL_MODEL_ID,
                ollamaName,
                ollamaProps.getBaseUrl());

        // 2) LongCat（OpenAI 兼容）
        ApiModelProperties.LongCat lc = apiProps.getLongcat();
        Model longcat =
                OpenAIChatModel.builder()
                        .modelName(lc.getModelName())
                        .apiKey(lc.getApiKey())
                        .baseUrl(lc.getBaseUrl())
                        .stream(true)
                        .build();
        ModelRegistry.register(LONGCAT_MODEL_ID, longcat);
        log.info(
                "注册 LongCat API 模型: id={}, model={}, baseUrl={}",
                LONGCAT_MODEL_ID,
                lc.getModelName(),
                lc.getBaseUrl());

        // 3) 默认（active）模型
        String activeId = resolveActiveId(apiProps);
        Model active = LOCAL_MODEL_ID.equals(activeId) ? local : longcat;
        log.info(
                "当前默认模型: dataagent.model.active={} -> 使用 {}（可在 application.yml 切换）",
                apiProps.getActive(),
                activeId);
        return active;
    }

    /** 根据配置解析当前默认模型在 ModelRegistry 中的 id。 */
    public static String resolveActiveId(ApiModelProperties props) {
        return "longcat".equalsIgnoreCase(props.getActive()) ? LONGCAT_MODEL_ID : LOCAL_MODEL_ID;
    }
}
