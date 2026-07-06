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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用 webhook channel 的 HTTP 端点：
 *
 * <ul>
 *   <li>{@code POST /api/webhook/{channelId}/inbound} — 验证 HMAC 签名和 IP 白名单，然后
 *       通过 {@link WebhookChannel#ingest(WebhookInboundRequest)} 分发。
 *   <li>{@code GET /api/webhook/{channelId}/outbound/{inboundId}} — 对前一个 {@code replyMode=poll}
 *       入站请求的回复进行长轮询。轮询超时时返回 {@code 204 No Content}，允许客户端重新轮询。
 * </ul>
 *
 * <p>两个端点都是公开的——认证由每个 channel 的共享密钥提供，而非全局的 Spring Security 过滤器。
 * {@code SecurityConfig} 放行 {@code /api/webhook/**} 路径。
 *
 * <p>从 WebFlux 迁移至 Spring MVC：{@code ServerHttpRequest} → {@code HttpServletRequest}，
 * {@code Mono<ResponseEntity>} → {@code ResponseEntity}，
 * {@code ch.ingest(body).map(...)} → {@code ch.ingest(body).block()} 同步获取结果。
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WebhookCallbackController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataAgentBootstrap bootstrap;

    public WebhookCallbackController(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @PostMapping(
            value = "/{channelId}/inbound",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> inbound(
            @PathVariable("channelId") String channelId,
            @RequestHeader(value = "X-DataAgent-Sig", required = false) String signature,
            @RequestBody byte[] rawBody,
            HttpServletRequest request) {
        WebhookChannel ch = resolveChannel(channelId);
        if (ch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "error", "unknown channel"));
        }
        WebhookChannelProperties props = ch.properties();

        if (props.hasIpAllowList()) {
            String remote = clientIp(request);
            if (remote == null || !props.allowedIps().contains(remote)) {
                log.warn(
                        "Webhook channel '{}' 拒绝了来自不允许的 IP '{}' 的入站请求",
                        channelId,
                        remote);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("status", "error", "error", "ip not allowed"));
            }
        }

        if (props.requiresSignature()) {
            String expected = WebhookSignature.hmacHex(props.sharedSecret(), rawBody);
            if (!WebhookSignature.constantTimeEquals(expected, signature)) {
                log.warn("Webhook channel '{}' 入站请求签名不匹配", channelId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("status", "error", "error", "bad signature"));
            }
        }

        WebhookInboundRequest body;
        try {
            body = MAPPER.readValue(rawBody, WebhookInboundRequest.class);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(
                            Map.of(
                                    "status",
                                    "error",
                                    "error",
                                    "invalid json: " + e.getMessage()));
        }

        WebhookChannel.WebhookDispatchResult result = ch.ingest(body).block();
        return ResponseEntity.accepted()
                .body(
                        Map.<String, Object>of(
                                "status", result.status(),
                                "inboundId", result.inboundId(),
                                "replyMode", result.replyMode()));
    }

    @GetMapping(
            value = "/{channelId}/outbound/{inboundId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> outbound(
            @PathVariable("channelId") String channelId,
            @PathVariable("inboundId") String inboundId) {
        WebhookChannel ch = resolveChannel(channelId);
        if (ch == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "error", "unknown channel"));
        }
        String reply = ch.awaitReply(inboundId).block();
        if (reply == null) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return ResponseEntity.ok(
                Map.<String, Object>of(
                        "status", "ok",
                        "inboundId", inboundId,
                        "reply", reply));
    }

    private WebhookChannel resolveChannel(String channelId) {
        return bootstrap
                .channelManager()
                .getChannel(channelId)
                .filter(c -> c instanceof WebhookChannel)
                .map(c -> (WebhookChannel) c)
                .orElse(null);
    }

    private String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isEmpty()) {
            int comma = fwd.indexOf(',');
            return comma >= 0 ? fwd.substring(0, comma).trim() : fwd.trim();
        }
        return request.getRemoteAddr();
    }
}
