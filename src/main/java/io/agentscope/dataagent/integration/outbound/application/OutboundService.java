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
package io.agentscope.dataagent.integration.outbound.application;
import io.agentscope.dataagent.integration.outbound.api.OutboundController;
import io.agentscope.dataagent.integration.outbound.domain.OutboundRequest;
import io.agentscope.dataagent.integration.outbound.domain.OutboundTool;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 {@link OutboundRequest} 转换为 {@link OutboundAddress} + {@link Msg} 并通过
 * {@link ChannelManager} 推送。
 *
 * <p>由 {@link OutboundController}（HTTP）和 {@link OutboundTool}（Agent 工具）使用。
 * 错误以 {@link IllegalArgumentException}（错误请求）或 {@link IllegalStateException}
 * （无此 channel / channel 不健康）形式抛出。
 */
public final class OutboundService {

    private static final Logger log = LoggerFactory.getLogger(OutboundService.class);

    private final ChannelManager channelManager;
    private final ChannelRouter router = new ChannelRouter(null);

    public OutboundService(ChannelManager channelManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "channelManager");
    }

    /** 投递 {@code request} 描述的消息。 */
    public void send(OutboundRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.channelId() == null || request.channelId().isBlank()) {
            throw new IllegalArgumentException("channelId 是必填项");
        }
        if (request.peerId() == null || request.peerId().isBlank()) {
            throw new IllegalArgumentException("peerId 是必填项");
        }
        boolean hasText = request.text() != null && !request.text().isBlank();
        boolean hasMd = request.markdown() != null && !request.markdown().isBlank();
        if (!hasText && !hasMd) {
            throw new IllegalArgumentException("必须提供 text 或 markdown 之一");
        }

        PeerKind kind = parsePeerKind(request.peerKind());
        Peer peer = new Peer(kind, request.peerId().trim());

        Optional<Channel> ch = channelManager.getChannel(request.channelId());
        if (ch.isEmpty()) {
            throw new IllegalStateException(
                    "没有为 channelId='" + request.channelId() + "' 注册 channel");
        }

        if (request.agentId() != null && !request.agentId().isBlank()) {
            verifyAgentRouting(ch.get(), peer, request);
        }

        OutboundAddress address =
                new OutboundAddress(
                        request.channelId(),
                        emptyToNull(request.accountId()),
                        request.channelId() + ":" + peer.key(),
                        emptyToNull(request.threadId()));

        Msg msg = new AssistantMessage("outbound",
                hasMd ? request.markdown() : request.text());

        log.debug(
                "出站发送: channel={}, peer={}, account={}, thread={}, mode={}",
                request.channelId(),
                peer.key(),
                request.accountId(),
                request.threadId(),
                hasMd ? "markdown" : "text");

        channelManager.deliver(address, List.of(msg));
    }

    /**
     * 使用 {@code peer} 上的合成入站消息探测 channel 的路由器，
     * 当 channel 的绑定（或其 {@code defaultAgentId}）路由到与 {@code request.agentId()}
     * 不同的 Agent 时拒绝投递。探测将请求的 Agent 作为提示携带，
     * 以便没有任何绑定（且没有 channel 默认值）的 channel 接受调用方——
     * 显式绑定仍然优先。
     */
    private void verifyAgentRouting(Channel channel, Peer peer, OutboundRequest request) {
        String wanted = request.agentId().trim();
        InboundMessage probe =
                InboundMessage.builder(
                                request.channelId(),
                                peer,
                                List.of(
                                        new UserMessage("outbound_probe",
                                                "__outbound_probe__")))
                        .senderId(peer.id())
                        .accountId(emptyToNull(request.accountId()))
                        .preferredAgentId(wanted)
                        .build();
        RouteResult route = router.resolveRoute(channel.config(), probe);
        String resolved = route.agentId();
        if (!Objects.equals(wanted, resolved)) {
            throw new IllegalStateException(
                    "路由不匹配: channel '" + request.channelId()
                            + "' / peer '" + peer.key()
                            + "' 解析到 Agent '" + resolved
                            + "' (matchedBy=" + route.matchedBy()
                            + ") 但调用方声明了 Agent '" + wanted
                            + "'。请更新 channel 绑定或省略 agent_id 以覆盖。");
        }
    }

    private static PeerKind parsePeerKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return PeerKind.DIRECT;
        }
        try {
            return PeerKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知的 peerKind '" + raw + "'。允许的值: DIRECT | CHANNEL | GROUP | THREAD");
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}