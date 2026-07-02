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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code ${cwd}/.agentscope/agentscope.json} 的根文档。
 *
 * <p>形状有意与 OpenClaw 的顶级配置相似：一个 {@code main} 条目 ID，一个以 Agent ID 为键的
 * {@code agents} 映射，以及一个可选的以 channel ID 为键的 {@code channels} 映射。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentscopeConfig {

    /** 供编辑器使用的可选 JSON Schema 引用。 */
    @JsonProperty("$schema")
    private String schema;

    /**
     * 默认入口点的 Agent ID。程序化的 {@link
     * DataAgentBootstrap.Builder 设置时会覆盖此值。
     */
    @JsonProperty("main")
    private String main;

    @JsonProperty("agents")
    private Map<String, AgentConfigEntry> agents = new LinkedHashMap<>();

    /**
     * 以 channel ID 为键的可选 channel 配置（例如 {@code "chatui"}, {@code
     * "slack"}）。内置的 {@code chatui} channel 在无程序化注册覆盖相同 ID 时会
     * 自动从其条目创建。对于其他 channel 类型，条目提供在 bootstrap 时应用的
     * 路由配置（{@link ChannelConfigEntry#toChannelConfig}）。
     */
    @JsonProperty("channels")
    private Map<String, ChannelConfigEntry> channels = new LinkedHashMap<>();

    /**
     * 可选的 session 生命周期配置（自动重置、维护）。映射到运行时
     * {@link io.agentscope.dataagent.runtime.session.SessionMaintenanceConfig}
     * 和用于每日/空闲重置的内部调度器。
     */
    @JsonProperty("session")
    private SessionLifecycleConfig session;

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getMain() {
        return main;
    }

    public void setMain(String main) {
        this.main = main;
    }

    public Map<String, AgentConfigEntry> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, AgentConfigEntry> agents) {
        this.agents = agents != null ? agents : new LinkedHashMap<>();
    }

    public Map<String, ChannelConfigEntry> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, ChannelConfigEntry> channels) {
        this.channels = Objects.requireNonNullElseGet(channels, LinkedHashMap::new);
    }

    public SessionLifecycleConfig getSession() {
        return session;
    }

    public void setSession(SessionLifecycleConfig session) {
        this.session = session;
    }
}
