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
import io.agentscope.dataagent.integration.webhook.application.WebhookChannel;

import io.agentscope.dataagent.integration.webhook.application.WebhookChannel;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 以 {@code type} 为键的 {@link ChannelFactory} 实现注册表。
 * 当从 {@code agentscope.json} 自动实例化 channel 时，由
 * {@link io.agentscope.dataagent.runtime.DataAgentBootstrap} 查找。
 *
 * <p>DataAgent v1 中的内置类型：{@code chatui}（始终开启，主要 UX）、{@code dingtalk}
 * （可选的 IM 桥接）、{@code webhook}（通用 HTTP 侧信道，供 IM/工单/CI 系统调用
 * DataAgent 并接收结果）。Feishu / WeCom / GitHub / GitLab 适配器有意未在 v1 中捆绑；
 * 调用方可以在 {@link io.agentscope.dataagent.runtime.DataAgentBootstrap.Builder#build()}
 * 运行前通过 {@link #register(String, ChannelFactory) register} 注册额外类型。
 */
public final class ChannelTypeRegistry {

    private static final ConcurrentHashMap<String, ChannelFactory> FACTORIES =
            new ConcurrentHashMap<>();

    static {
        register(
                ChatUiChannel.CHANNEL_ID,
                (channelId, routing, properties) -> ChatUiChannel.create(routing));
        register(DingTalkChannel.TYPE, DingTalkChannel::fromProperties);
        register(WebhookChannel.TYPE, WebhookChannel::fromProperties);
    }

    private ChannelTypeRegistry() {}

    /**
     * 在 {@code typeId} 下注册（或替换）一个 factory。返回之前注册的 factory（如果有）。
     */
    public static ChannelFactory register(String typeId, ChannelFactory factory) {
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(factory, "factory");
        return FACTORIES.put(typeId, factory);
    }

    /** 返回为 {@code typeId} 注册的 factory（如果有）。 */
    public static Optional<ChannelFactory> get(String typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(FACTORIES.get(typeId));
    }

    /** 返回已注册 type ID 的不可变快照。 */
    public static Set<String> registeredTypes() {
        return Map.copyOf(FACTORIES).keySet();
    }
}