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
package io.agentscope.dataagent.runtime.outbound;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 由 Agent 发起的出站消息请求。
 *
 * <p>请求描述了消息应发送到<em>哪里</em>（channel + peer kind + peer id，
 * 加上可选的 account/thread 上下文）以及消息的<em>内容</em>（文本或 markdown）。
 * {@link OutboundService} 将其转换为 {@link io.agentscope.harness.agent.gateway.channel.OutboundAddress}
 * 并委托给匹配的 channel。
 *
 * @param channelId 目标 channel ID（必须匹配已注册的
 *     {@link io.agentscope.harness.agent.gateway.channel.Channel}）；必填
 * @param peerKind {@code DIRECT}、{@code CHANNEL}、{@code GROUP}、{@code THREAD} 之一；必填
 * @param peerId 提供商特定的对等 ID（用户 ID、群组/会话 ID 等）；必填
 * @param accountId 可选的多账号维度（企业 ID、应用实例）；可为 null
 * @param threadId 可选的线程锚点，用于线程回复；可为 null
 * @param text 纯文本消息体；{@code text} 或 {@code markdown} 之一必须设置
 * @param markdown Markdown 消息体；{@code text} 或 {@code markdown} 之一必须设置
 * @param agentId 可选的调用方提供的 Agent ID。设置时，服务验证 channel 对 {@code peerId}
 *     的路由解析到同一个 Agent，否则拒绝投递。用于防止一个 Agent 向绑定到另一个 Agent
 *     的 channel/peer 发送消息。当 {@code null} 或空白时跳过。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboundRequest(
        String channelId,
        String peerKind,
        String peerId,
        String accountId,
        String threadId,
        String text,
        String markdown,
        String agentId) {

    /** 不带 {@code agentId} 的便捷构造函数（无调用方侧路由检查）。 */
    public OutboundRequest(
            String channelId,
            String peerKind,
            String peerId,
            String accountId,
            String threadId,
            String text,
            String markdown) {
        this(channelId, peerKind, peerId, accountId, threadId, text, markdown, null);
    }
}
