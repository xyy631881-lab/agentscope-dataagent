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
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 通用 webhook channel — "侧工具"形式：一个 HTTP 入 / HTTP 出的适配器，允许外部系统
 * （IM 机器人、工单系统、CI）调用 DataAgent 而无需打开主 Chat UI。
 *
 * <p>入站：{@link WebhookCallbackController} 处理 {@code POST /api/webhook/{channelId}
 * /inbound}，验证 HMAC 签名，然后调用 {@link #ingest(WebhookInboundRequest)}，
 * 该方法执行去重、应用 bot-loop 防护、通过 {@link ChannelRouter} 路由、
 * 并分发到 {@link Gateway}。
 *
 * <p>出站：每个请求有两种模式 — {@code callback} 通过 {@link WebhookOutboundClient}
 * 将回复发布到调用方的 {@code callbackUrl}，而 {@code poll} 将回复暂存在
 * {@link #parkedReplies} 中供长轮询端点使用。
 */
public final class WebhookChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    /** 在 {@code agentscope.json} 和 {@link
     * io.agentscope.dataagent.runtime.config.ChannelTypeRegistry} 中使用的 {@code type} 值。
     */
    public static final String TYPE = "webhook";

    private final String channelId;
    private final ChannelConfig config;
    private final WebhookChannelProperties properties;
    private final WebhookInboundMapper mapper;
    private final WebhookOutboundClient outboundClient;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;

    /** 每个 inboundId 的 {@code replyMode=poll} 暂存槽。通过 FIFO 淘汰进行容量控制。 */
    private final ConcurrentHashMap<String, Sinks.One<String>> parkedReplies =
            new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> parkOrder = new ConcurrentLinkedDeque<>();

    private volatile Gateway gateway;

    private WebhookChannel(
            String channelId,
            ChannelConfig config,
            WebhookChannelProperties properties,
            WebhookInboundMapper mapper,
            WebhookOutboundClient outboundClient,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
    }

    /** 由 {@link io.agentscope.dataagent.runtime.config.ChannelTypeRegistry} 使用的工厂方法。 */
    public static WebhookChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        WebhookChannelProperties props = WebhookChannelProperties.from(channelId, rawProperties);
        return new WebhookChannel(
                channelId,
                routing,
                props,
                new WebhookInboundMapper(channelId),
                new WebhookOutboundClient(channelId, props.sharedSecret()),
                new IdempotencyStore(),
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()));
    }

    // -----------------------------------------------------------------
    //  Channel 生命周期
    // -----------------------------------------------------------------

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    @Override
    public void start() {
        log.info(
                "Webhook channel '{}' 已启动: requiresSignature={}, allowedIps={}",
                channelId,
                properties.requiresSignature(),
                properties.allowedIps());
    }

    @Override
    public void stop() {
        parkedReplies.forEach((id, sink) -> sink.tryEmitEmpty());
        parkedReplies.clear();
        parkOrder.clear();
        log.info("Webhook channel '{}' 已停止", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("WebhookChannel '" + channelId + "' 没有 gateway"));
        }
        RouteResult route = router.resolveRoute(config, message);
        return g.run(route.context(), message.messages(), route.outboundAddress());
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        // 主动的 subagent 通知——webhook 没有持久化的对等连接，
        // 因此我们丢弃这些消息。需要通知投递的调用方应使用
        // chatui channel 或真正的 IM channel。
        if (messages == null || messages.isEmpty()) return;
        log.debug(
                "Webhook channel '{}' 丢弃 {} 条主动消息（无持久化对等连接）",
                channelId,
                messages.size());
    }

    // -----------------------------------------------------------------
    //  Webhook 入口点（由 WebhookCallbackController 调用）
    // -----------------------------------------------------------------

    WebhookChannelProperties properties() {
        return properties;
    }

    /**
     * 摄取一个刚刚通过认证的入站请求。返回要发送给调用方的响应负载
     * （包含分配的 {@code inboundId}，以及在 poll 模式下包含实际的回复内容）。
     */
    public Mono<WebhookDispatchResult> ingest(WebhookInboundRequest req) {
        Objects.requireNonNull(req, "req");
        String inboundId =
                (req.inboundId() != null && !req.inboundId().isBlank())
                        ? req.inboundId().strip()
                        : UUID.randomUUID().toString();
        if (!idempotency.firstSeen(channelId + "|" + inboundId)) {
            log.debug(
                    "Webhook channel '{}' 分发: 重复的 inboundId={}", channelId, inboundId);
            return Mono.just(
                    new WebhookDispatchResult(
                            inboundId, "duplicate", null, req.effectiveReplyMode()));
        }
        return mapper.map(req)
                .map(
                        inbound -> {
                            if (!botLoopGuard.allow(inbound.peer().key())) {
                                log.warn(
                                        "Webhook channel '{}' bot-loop 防护已触发，peer='{}'",
                                        channelId,
                                        inbound.peer().key());
                                return Mono.just(
                                        new WebhookDispatchResult(
                                                inboundId,
                                                "bot-loop-blocked",
                                                null,
                                                req.effectiveReplyMode()));
                            }
                            return dispatchAndDeliver(req, inbound, inboundId);
                        })
                .orElseGet(
                        () ->
                                Mono.just(
                                        new WebhookDispatchResult(
                                                inboundId,
                                                "invalid-payload",
                                                null,
                                                req.effectiveReplyMode())));
    }

    private Mono<WebhookDispatchResult> dispatchAndDeliver(
            WebhookInboundRequest req, InboundMessage inbound, String inboundId) {
        String mode = req.effectiveReplyMode();
        Mono<Msg> dispatch = dispatch(inbound);

        if (WebhookInboundRequest.REPLY_MODE_CALLBACK.equals(mode)) {
            String cbUrl = req.callbackUrl();
            if (cbUrl == null || cbUrl.isBlank()) {
                return Mono.just(
                        new WebhookDispatchResult(inboundId, "callback-url-required", null, mode));
            }
            dispatch.map(reply -> WebhookOutboundClient.renderReplyText(List.of(reply)))
                    .flatMap(text -> outboundClient.deliver(cbUrl, inboundId, text))
                    .doOnError(
                            err ->
                                    log.warn(
                                            "Webhook channel '{}' 分发失败（callback）: {}",
                                            channelId,
                                            err.getMessage()))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
            return Mono.just(new WebhookDispatchResult(inboundId, "accepted", null, mode));
        }

        // Poll 模式：暂存回复 sink，在后台等待分发完成。
        Sinks.One<String> sink = Sinks.one();
        parkReply(inboundId, sink);
        dispatch.map(reply -> WebhookOutboundClient.renderReplyText(List.of(reply)))
                .doOnSuccess(text -> sink.tryEmitValue(text == null ? "" : text))
                .doOnError(
                        err -> {
                            log.warn(
                                    "Webhook channel '{}' 分发失败（poll）: {}",
                                    channelId,
                                    err.getMessage());
                            sink.tryEmitValue("");
                        })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
        return Mono.just(new WebhookDispatchResult(inboundId, "parked", null, mode));
    }

    /**
     * 获取之前暂存的回复的长轮询方法。当轮询超时而没有回复到达时返回空；
     * 调用方应重新轮询。
     */
    public Mono<String> awaitReply(String inboundId) {
        if (inboundId == null) return Mono.empty();
        Sinks.One<String> sink = parkedReplies.get(inboundId);
        if (sink == null) return Mono.empty();
        return sink.asMono()
                .timeout(
                        java.time.Duration.ofMillis(properties.longPollTimeoutMillis()),
                        Mono.empty())
                .doFinally(sig -> parkedReplies.remove(inboundId));
    }

    private void parkReply(String inboundId, Sinks.One<String> sink) {
        parkedReplies.put(inboundId, sink);
        parkOrder.addLast(inboundId);
        while (parkedReplies.size() > properties.outboundParkCapacity()) {
            String evict = parkOrder.pollFirst();
            if (evict == null) break;
            Sinks.One<String> dropped = parkedReplies.remove(evict);
            if (dropped != null) dropped.tryEmitEmpty();
        }
    }

    /** 从 {@link #ingest} 返回的响应信封。 */
    public record WebhookDispatchResult(
            String inboundId, String status, String reply, String replyMode) {}
}
