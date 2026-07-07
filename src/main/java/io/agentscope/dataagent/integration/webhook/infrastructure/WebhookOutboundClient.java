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
package io.agentscope.dataagent.integration.webhook.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 将 Agent 回复发布到调用方提供的 {@code callbackUrl}，用于以 {@code replyMode=callback}
 * 模式运行的 webhook channel。
 *
 * <p>每个请求体为 JSON 对象
 * <pre>{"channelId":"...","inboundId":"...","reply":"..."}</pre>
 * 并使用与入站侧相同的共享密钥签名——签名在 {@code X-DataAgent-Sig} 中传递，
 * 为 HMAC-SHA256 的原始体小写十六进制摘要。
 */
public final class WebhookOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String channelId;
    private final String sharedSecret;
    private final HttpClient http;

    public WebhookOutboundClient(String channelId, String sharedSecret) {
        this.channelId = channelId;
        this.sharedSecret = sharedSecret;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * 将给定的回复文本发布到回调 URL。成功时返回空的 Mono；记录并吞掉失败，
     * 这样下游的回调故障不会导致分发管道失败。
     */
    public Mono<Void> deliver(String callbackUrl, String inboundId, String replyText) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", channelId);
        body.put("inboundId", inboundId);
        body.put("reply", replyText == null ? "" : replyText);

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("序列化回调体失败", e));
        }

        HttpRequest.Builder rb =
                HttpRequest.newBuilder(URI.create(callbackUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            rb.header("X-DataAgent-Sig", WebhookSignature.hmacHex(sharedSecret, payload));
        }
        HttpRequest req = rb.build();

        return Mono.fromCallable(() -> http.send(req, HttpResponse.BodyHandlers.discarding()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(
                        resp -> {
                            if (resp.statusCode() >= 300) {
                                log.warn(
                                        "Webhook channel '{}' 回调到 {} 返回状态码 {}",
                                        channelId,
                                        callbackUrl,
                                        resp.statusCode());
                            }
                        })
                .doOnError(
                        err ->
                                log.warn(
                                        "Webhook channel '{}' 回调到 {} 失败: {}",
                                        channelId,
                                        callbackUrl,
                                        err.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** 从 Agent 回复列表中提取用户可见文本，以换行符连接。 */
    public static String renderReplyText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            String t = m.getTextContent();
            if (t == null || t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }
}
