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
package io.agentscope.dataagent.integration.webhook.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /api/webhook/{channelId}/inbound} 请求体。
 *
 * <p>支持两种 {@link #replyMode} 风格：
 * <ul>
 *   <li>{@code "callback"}（默认）— channel 将回复发布到 {@link #callbackUrl}，
 *       并使用 channel 的共享密钥签名。原始 POST 返回 202 及 {@code inboundId}。
 *   <li>{@code "poll"} — 回复暂存在以 {@code inboundId} 为键的内存环形缓冲区中，
 *       通过 {@code GET /api/webhook/{channelId}/outbound/{inboundId}}（长轮询）获取。
 * </ul>
 *
 * @param externalUserId 调用系统的发送方标识（映射到 {@code senderId}）。必填。
 * @param externalSessionId 可选的子会话键 — 存在时成为对等 ID 的一部分，
 *     以便同一用户的并发线程不共享 session。不存在时对等 ID 等于 {@code externalUserId}。
 * @param message 用户可见的文本负载，转发给 Agent。必填。
 * @param replyMode {@code "callback"} 或 {@code "poll"}。省略时默认为 {@code "poll"}。
 * @param callbackUrl 回调模式的回调 URL。callback 模式下必填。
 * @param preferredAgentId 可选的显式 Agent 覆盖；在绑定评估之前作为 0 级短路，
 *     类似于 {@code InboundMessage.preferredAgentId}。
 * @param inboundId 可选的客户端提供的幂等键。省略时由服务器生成（在响应中返回）。
 *     在 channel 的幂等 TTL 内使用相同 {@code inboundId} 的重复 POST 将被丢弃。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookInboundRequest(
        String externalUserId,
        String externalSessionId,
        String message,
        String replyMode,
        String callbackUrl,
        String preferredAgentId,
        String inboundId) {

    public static final String REPLY_MODE_CALLBACK = "callback";
    public static final String REPLY_MODE_POLL = "poll";

    /** 返回有效的回复模式，默认 {@link #REPLY_MODE_POLL}。 */
    public String effectiveReplyMode() {
        if (replyMode == null || replyMode.isBlank()) {
            return REPLY_MODE_POLL;
        }
        return replyMode.toLowerCase();
    }
}
