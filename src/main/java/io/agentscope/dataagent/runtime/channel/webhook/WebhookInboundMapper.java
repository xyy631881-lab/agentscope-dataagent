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
package io.agentscope.dataagent.runtime.channel.webhook;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.List;
import java.util.Optional;

/**
 * 将经过认证的 {@link WebhookInboundRequest} 转换为框架的 {@link InboundMessage}。
 *
 * <p>对等映射：
 * <ul>
 *   <li>{@code externalSessionId} 存在时 → {@link PeerKind#DIRECT}，id 为
 *       {@code "<externalUserId>::<externalSessionId>"}，这样同一用户的并发子线程不会共享 session。
 *   <li>{@code externalSessionId} 不存在时 → {@link PeerKind#DIRECT}，id 为 {@code externalUserId}。
 * </ul>
 *
 * <p>{@code senderId} 始终为原始的 {@code externalUserId}，以确保每个用户的 workspace
 * 命名空间保持稳定，无论消息来自哪个子 session。
 */
public final class WebhookInboundMapper {

    private final String channelId;

    public WebhookInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    public Optional<InboundMessage> map(WebhookInboundRequest req) {
        if (req == null) return Optional.empty();
        if (req.externalUserId() == null || req.externalUserId().isBlank()) {
            return Optional.empty();
        }
        if (req.message() == null || req.message().isBlank()) {
            return Optional.empty();
        }
        String userId = req.externalUserId().strip();
        String peerId =
                (req.externalSessionId() == null || req.externalSessionId().isBlank())
                        ? userId
                        : userId + "::" + req.externalSessionId().strip();
        Msg msg = new UserMessage(userId, req.message().strip());
        InboundMessage.Builder b =
                InboundMessage.builder(channelId, new Peer(PeerKind.DIRECT, peerId), List.of(msg))
                        .senderId(userId);
        if (req.preferredAgentId() != null && !req.preferredAgentId().isBlank()) {
            b.preferredAgentId(req.preferredAgentId().strip());
        }
        return Optional.of(b.build());
    }
}
