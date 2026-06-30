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
package io.agentscope.dataagent.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code agentscope.json} 中 {@code channels.<channelId>} 下每个 channel 的配置节。
 *
 * <p>定义 channel 适配器的路由配置。如果不存在程序化的 {@link
 * DataAgentBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)}
 * 注册覆盖，内置的 {@code chatui} channel 会自动从此条目创建。对于其他 channel 类型，
 * 此条目提供在 bootstrap 时应用的 {@link ChannelConfig} 路由规则。
 *
 * <h2>示例</h2>
 *
 * <pre>{@code
 * "channels": {
 *   "chatui": {
 *     "defaultAgentId": "main",
 *     "dmScope": "PER_PEER"
 *   },
 *   "slack": {
 *     "defaultAgentId": "support",
 *     "dmScope": "PER_PEER"
 *   }
 * }
 * }</pre>
 *
 * @see AgentscopeConfig
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelConfigEntry {

    /**
     * Channel 类型标识符，由 {@link ChannelTypeRegistry} 查找以选择
     * 构建 channel 实例的 {@link ChannelFactory}。内置类型：{@code chatui}、
     * {@code dingtalk}、{@code wecom}、{@code feishu}、{@code github}、{@code gitlab}。
     * 当通过 {@link DataAgentBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)}
     * 程序化注册 channel 时可省略。
     */
    @JsonProperty("type")
    private String type;

    /**
     * 类型特定的供应商属性（例如凭据、端点、签名密钥）。按原样转发给
     * {@link ChannelFactory#create(String, ChannelConfig, Map)}。
     */
    @JsonProperty("properties")
    private Map<String, Object> properties;

    /**
     * 当没有绑定匹配时的回退 Agent ID。如果省略，回退到全局绑定的主 Agent。
     */
    @JsonProperty("defaultAgentId")
    private String defaultAgentId;

    /**
     * 控制 DM session 键的作用域。值为 {@code MAIN}、{@code PER_PEER}、{@code PER_CHANNEL_PEER}、
     * {@code PER_ACCOUNT_CHANNEL_PEER} 之一。省略时默认为 {@code MAIN}。
     *
     * @see DmScope
     */
    @JsonProperty("dmScope")
    private String dmScope;

    /**
     * 当 {@code true} 时，此 channel 条目在 bootstrap 时被忽略——不创建 channel 实例，
     * 且任何具有相同 ID 的程序化注册 channel 也不会启动。
     */
    @JsonProperty("disabled")
    private Boolean disabled;

    /**
     * 有序的 {@link io.agentscope.harness.agent.gateway.channel.ChannelBinding} 路由规则列表，
     * 由 {@link io.agentscope.harness.agent.gateway.channel.ChannelRouter} 按优先级层级评估。
     * 最高优先级层级中的第一个匹配绑定胜出。
     */
    @JsonProperty("bindings")
    private List<BindingConfigEntry> bindings;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getProperties() {
        return properties != null ? properties : Map.of();
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? null : new LinkedHashMap<>(properties);
    }

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public String getDmScope() {
        return dmScope;
    }

    public void setDmScope(String dmScope) {
        this.dmScope = dmScope;
    }

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    public List<BindingConfigEntry> getBindings() {
        return bindings;
    }

    public void setBindings(List<BindingConfigEntry> bindings) {
        this.bindings = bindings;
    }

    /** 将当前条目转换为给定 channel ID 的 {@link ChannelConfig}。 */
    public ChannelConfig toChannelConfig(String channelId) {
        DmScope scope = DmScope.MAIN;
        if (dmScope != null && !dmScope.isBlank()) {
            try {
                scope = DmScope.valueOf(dmScope.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 未知值 → 回退到 MAIN
            }
        }
        List<ChannelBinding> resolved = new ArrayList<>();
        if (bindings != null) {
            for (BindingConfigEntry e : bindings) {
                if (e == null) continue;
                try {
                    resolved.add(e.toBinding());
                } catch (IllegalArgumentException ignored) {
                    // 跳过格式错误的绑定（缺少 agentId）
                }
            }
        }
        return ChannelConfig.builder(channelId)
                .defaultAgentId(defaultAgentId)
                .dmScope(scope)
                .bindings(resolved)
                .build();
    }
}
