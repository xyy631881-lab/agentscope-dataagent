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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.identity.IdentityLinkStore;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import io.agentscope.dataagent.web.usage.UsageStore;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 聊天端点，作用于特定的 Agent。
    是用户和 Agent 之间的唯一入口，相当于客服中心前台——用户打电话进来（HTTP 请求），前台把电话转给对应客服（Agent）。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatUiChannel chatUiChannel;
    private final SessionAgentManager sessionAgentManager;
    private final AgentCatalogService catalogService;
    private final IdentityLinkStore identityLinks;
    private final UsageStore usageStore;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    /**
     * 我们已经为其记录了 RUN_SESSION 事件的会话键。每个 (userId, agentId)
     * 对在每个进程生命周期中只记录一个条目，因此活动日志显示每个会话一行，
     * 而不是每轮对话一行。
     */
    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

    public ChatController(
            ChatUiChannel chatUiChannel,
            DataAgentBootstrap builderBootstrap,
            AgentCatalogService catalogService,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            AgentAccessGuard guard,
            AgentActivityStore activity) {
        this.chatUiChannel = chatUiChannel;
        this.sessionAgentManager = builderBootstrap.sessionAgentManager();
        this.catalogService = catalogService;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.guard = guard;
        this.activity = activity;
    }

    /**
     * 两个端点共享的请求体。
     * sessionKey是调用方提供的会话标识符，用于寻址同一 (userId, agentId)
     */
    public record ChatRequest(String message, String sessionKey) {}

    /** 同步端点的响应。 */
    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * currentSession会话的唯一标识符，前端拿着它去拉取历史消息
     * exists为 true；这个会话是否真实存在（用户是否至少发过一条消息）
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /**
     * SSE 流式端点，
     * agentId：跟哪个 Agent 聊天
     * auth：当前登录用户信息
     * req：请求体，包含 message（用户消息）和 sessionKey（会话标识）
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {


        /**
         * 通俗理解：前台接到电话，先确认"你是谁"、"有没有权限"，然后分配一个通话编号。
         * 从登录信息中取出用户ID
         * 检查这个用户有没有权限跟这个 Agent 聊天（Tier.RUN 权限）
         * 如果前端传了 sessionKey 就用它，没传就生成一个新的 UUID
         * 记录一条"会话开始"的审计日志
         */
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        String conversationId = normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);

        // 斜杠命令会短路 Agent，直接生成一条合成的单 token 回复。
        // 如果用户输入的是 /new、/reset 等斜杠命令，不走 Agent，直接返回一条合成消息就结束。
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            Map<String, Object> doneFrame = new LinkedHashMap<>();
            doneFrame.put("type", "done");
            // /new 会生成新会话；其他命令保留调用方当前的会话。
            doneFrame.put(
                    "sessionKey",
                    cmd.newSessionKey != null ? cmd.newSessionKey : resolvedConversationId);
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", doneFrame));
        }

        // 核心——把 Agent 事件流转换为 SSE 事件流
        // Agent 在执行过程中会不断产生事件，这个方法把每种事件翻译成前端能理解的 SSE 格式。
        final Map<String, ToolBuffer> buffers = new ConcurrentHashMap<>();

        return executeChatStream(userId, agentId, req.message(), resolvedConversationId)
                .mapNotNull(event -> {
                    if (event instanceof TextBlockDeltaEvent delta) {
                        return sse("token", Map.of("type", "token", "data", delta.getDelta()));
                    }
                    if (event instanceof ToolCallStartEvent tc) {
                        buffers.put(tc.getToolCallId(),
                                new ToolBuffer(tc.getToolCallName()));
                        return null;
                    }
                    if (event instanceof ToolCallDeltaEvent delta) {
                        ToolBuffer buf = buffers.get(delta.getToolCallId());
                        if (buf != null) buf.appendInput(delta.getDelta());
                        return null;
                    }
                    if (event instanceof ToolCallEndEvent end) {
                        ToolBuffer buf = buffers.remove(end.getToolCallId());
                        String toolName = (buf != null) ? buf.toolName : end.getToolCallName();
                        String toolInput = (buf != null) ? buf.input.toString() : "";
                        return sse("tool_call", Map.of(
                                "type", "tool_call",
                                "toolName", toolName,
                                "toolInput", toolInput));
                    }
                    if (event instanceof ToolResultStartEvent start) {
                        buffers.computeIfAbsent(start.getToolCallId(),
                                k -> new ToolBuffer(null));
                        return null;
                    }
                    if (event instanceof ToolResultTextDeltaEvent delta) {
                        ToolBuffer buf = buffers.get(delta.getToolCallId());
                        if (buf != null) buf.appendResult(delta.getDelta());
                        return null;
                    }
                    if (event instanceof ToolResultEndEvent end) {
                        ToolBuffer buf = buffers.remove(end.getToolCallId());
                        String toolResult = (buf != null) ? buf.result.toString()
                                : end.getState().name();
                        return sse("tool_result", Map.of(
                                "type", "tool_result",
                                "toolName", end.getToolCallName(),
                                "toolResult", toolResult));
                    }
                    if (event instanceof AgentEndEvent) {
                        return sse("done", Map.of(
                                "type", "done",
                                "sessionKey", resolvedConversationId));
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                // 如果 Agent 执行过程中出错了，不会让整个流崩掉，而是发一个 error 事件给前端，前端可以显示"出错了，请重试"。
                .onErrorResume(ex -> {
                    log.warn(
                            "Chat stream error: userId={}, agentId={}, error={}",
                            userId,
                            agentId,
                            ex.getMessage());
                    return Flux.just(
                            sse("error", Map.of("type", "error", "error", ex.getMessage())));
                });
    }

    /**
     * currentSession() 是前端的"会话探测仪"——页面加载时问后端"我有没有进行中的对话"，
     * 后端通过模拟网关路由算出 gateKey，再反查会话存储，告诉前端"有/没有"以及"会话 key 是什么"，
     * 前端据此决定是显示欢迎页还是加载历史消息。
     */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(
            @PathVariable String agentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            String sessionKey,
            Authentication auth) {
        // ① 取用户ID
        String userId = (String) auth.getPrincipal();
        // ② 权限校验：这个用户能不能跟这个 Agent 聊天？
        guard.require(userId, agentId, Tier.RUN);
        // ③ 标准化 sessionKey：空字符串 → null
        String conversationId = normalizedConversationId(sessionKey);
        // ④ 用 Mono.fromCallable 包裹同步逻辑，变成异步响应
        return Mono.fromCallable(
                () -> {
                    if (conversationId == null) {
                        // 情况1：前端没传 sessionKey，说明是新用户
                        return new CurrentSessionResponse(null, false);
                    }
                    // 情况2/3：前端传了 sessionKey → 查一下这个会话存不存在
                    String gateKey = resolveGateKey(userId, agentId, conversationId);
                    boolean exists = gateKey != null && findSessionKeyByGate(userId, gateKey) != null;
                    // exists=false：前端传了 sessionKey，但数据库里找不到对应会话，这个 key 对应的会话不存在了
                    // exists=true：前端传了 sessionKey，数据库里找到了对应会话，会话还在，去加载历史消息吧
                    return new CurrentSessionResponse(conversationId, exists);
                });
    }

    /** 同步聊天. */
    @PostMapping("/send")
    public Mono<ChatResponse> send(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        String conversationId = normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            return Mono.just(
                    new ChatResponse(
                            cmd.message,
                            cmd.newSessionKey != null
                                    ? cmd.newSessionKey
                                    : resolvedConversationId));
        }
        return executeChat(userId, agentId, req.message(), resolvedConversationId)
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            // 返回 conversationId，而非存储键——参见 stream() 方法。
                            return new ChatResponse(text, resolvedConversationId);
                        });
    }

    // -----------------------------------------------------------------
    //  内部辅助方法
    // -----------------------------------------------------------------

    /**
     * 在用户第一次跟某个 Agent 开始对话时，记一条审计日志，而且同一个会话只记一次，用于用量统计和审计追踪。
     */
    private void recordRunSession(
            AgentDefinition def, String agentId, String userId, String conversationId) {
        if (def == null || def.ownerId() == null) {
            // 全局 Agent 没有逐 Agent 的活动日志
            // 全局 Agent（系统内置的）没有 ownerId，不需要记录活动日志。
            // 只有用户自定义的 Agent 才需要记录——因为需要统计"哪个用户的 Agent 被用了多少次"。
            // 公共设施不需要记谁用了，但私人助理的每次服务都要记账。
            return;
        }
        // 用 resolveGateKey 算出一个唯一标识，作为去重依据。
        String dedupeKey = resolveGateKey(userId, agentId, conversationId);
        if (dedupeKey == null) return;
        // 如果不做去重，一个会话里发了 10 条消息，就会记 10 条 RUN_SESSION 日志，
        // 但实际只开了 1 个会话。去重后，活动日志里每个会话只有一行记录。
        if (!startedSessions.add(dedupeKey)) return;  // 去重——同一个会话只记一次
        activity.record(
                def.ownerId(),                        // Agent 的所有者（谁创建的这个 Agent）
                agentId,                              // Agent ID
                activity.actor(userId),               // 操作者（谁在用这个 Agent）
                ActivityEvent.Action.RUN_SESSION,     // 动作类型：开始会话
                dedupeKey,                            // 去重 key / 关联 ID
                null);                                // 附加信息（无）
    }

    /** Normalizes a request-supplied {@code sessionKey} into a conversationId, or {@code null}. */
    private static String normalizedConversationId(String key) {
        return (key != null && !key.isBlank()) ? key.trim() : null;
    }

    /**
     *  模拟网关路由，算出 gateKey
     *  精髓在于 previewRoute——它不真正发送消息，只是"预演"一下：如果我要发一条消息，网关会把它路由到哪个 key？
     */
    private String resolveGateKey(String userId, String agentId, String conversationId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            InboundMessage probe =
                    InboundMessage.builder(ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.of())
                            .preferredAgentId(gatewayAgentId)
                            .accountId(conversationId)
                            .build();
            return chatUiChannel.previewRoute(probe).context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * O(1) lookup
     * 通过 SessionAgentManager 内部的索引查一下：有没有一个会话已经注册在这个 gateKey 下了？
     * 找到了 → 说明用户之前已经跟这个 Agent 聊过天，会话还在 → exists = true
     * 找不到 → 说明从来没有创建过会话，或者会话已经被清理了 → exists = false
     */
    private String findSessionKeyByGate(String userId, String gateKey) {
        if (gateKey == null) return null;
        return sessionAgentManager.findByGateKey(gateKey, userId)
                .map(SessionEntry::sessionKey)
                .orElse(null);
    }

    /**
     * 如果 {@code message} 是已识别的斜杠命令，则执行副作用（例如重置此用户+Agent+会话元组的会话），
     * 并返回一条合成确认回复。普通消息返回 {@code null}。
     *
     * <p>{@code /new} 会生成一个新的 {@code sessionKey}（通过 {@link CommandResult#newSessionKey} 返回），
     * 使前端可以跳转到新的 ChatGPT 风格会话；之前的会话保持不变。{@code /reset} 会就地清空
     * <em>当前</em> 会话的对话历史。
     */
    private CommandResult handleSlashCommand(
            String userId, String agentId, String message, String conversationId) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String[] parts = m.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/new": {
                // 生成全新的会话键；前端从 SSE `done` 事件中取走，
                // 并跳转到新会话。
                String fresh = UUID.randomUUID().toString();
                return new CommandResult(
                        "Started a fresh conversation. Your next message opens a new chat.",
                        fresh);
            }
            case "/reset": {
                String gateKey = resolveGateKey(userId, agentId, conversationId);
                if (gateKey == null) {
                    return new CommandResult("No active session to reset.", null);
                }
                String sessionKey = findSessionKeyByGate(userId, gateKey);
                if (sessionKey == null) {
                    // 尚未注册任何会话——没有可清空的内容，从用户角度看相当于全新开始。
                    // 移除去重标记，使下一条真实消息记录一个新的 RUN_SESSION 事件。
                    startedSessions.remove(gateKey);
                    return new CommandResult(
                            "No active session yet — your next message will start a fresh"
                                    + " conversation.",
                            null);
                }
                boolean ok = sessionAgentManager.resetSession(sessionKey);
                startedSessions.remove(gateKey);
                return new CommandResult(
                        ok
                                ? "AgentStateStore reset. Conversation history cleared; the"
                                        + " next message starts a fresh turn."
                                : "No matching session found for reset.",
                        null);
            }
            case "/identity": {
                Map<String, String> links = identityLinks.linksFor(userId);
                if (links.isEmpty()) {
                    return new CommandResult(
                            "No identity links yet. Use `/dock_<channel> <externalId>` to add"
                                    + " one — e.g. `/dock_slack U7F9LZK1A`.",
                            null);
                }
                StringBuilder sb = new StringBuilder("Your identity links:\n");
                links.forEach(
                        (ch, id) ->
                                sb.append("  - ")
                                        .append(ch)
                                        .append(" -> ")
                                        .append(id)
                                        .append('\n'));
                return new CommandResult(sb.toString(), null);
            }
            default:
                if (cmd.startsWith("/dock_")) {
                    String channel = cmd.substring("/dock_".length());
                    if (channel.isBlank() || arg.isBlank()) {
                        return new CommandResult(
                                "Usage: `/dock_<channel> <externalId>` — e.g."
                                        + " `/dock_slack U7F9LZK1A`.",
                                null);
                    }
                    identityLinks.link(userId, channel, arg);
                    return new CommandResult(
                            "Linked your identity on `" + channel + "` to `" + arg + "`.", null);
                }
                return null;
        }
    }

    /**
     * Internal carrier for slash-command results. {@code newSessionKey} is non-null when the
     * command mints a fresh session (e.g. {@code /new}); the stream/sync endpoints surface it back
     * to the frontend so the URL can be updated.
     */
    private record CommandResult(String message, String newSessionKey) {}

    /** Accumulator for tool-call input-text deltas and tool-result text deltas,
     *  keyed by {@code toolCallId}. Emptied when the corresponding end-event fires. */
    private static final class ToolBuffer {
        final String toolName;
        final StringBuilder input = new StringBuilder();
        final StringBuilder result = new StringBuilder();

        ToolBuffer(String toolName) {
            this.toolName = toolName;
        }

        void appendInput(String delta) {
            input.append(delta);
        }

        void appendResult(String delta) {
            result.append(delta);
        }
    }

    /**
     * 发消息给 Agent，等 Agent 完全说完再一次性返回，而不是一个字一个字地流式推送。
     */
    private Mono<Msg> executeChat(
            String userId, String agentId, String message, String conversationId) {
        Msg userMsg = new UserMessage("user", message);
        long startMs = System.currentTimeMillis();

        InboundMessage inbound;
        if (agentId == null || agentId.isBlank()) {
            // 无 Agent 覆盖，也无会话作用域——纯绑定驱动路由。
            inbound = InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.of(userMsg));
        } else {
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            inbound =
                    InboundMessage.builder(
                                    ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.of(userMsg))
                            .preferredAgentId(gatewayAgentId)
                            .accountId(conversationId)
                            .build();
        }
        // executeChat：同步版，等 Agent 完全处理完，返回最终回复
        Mono<Msg> call = chatUiChannel.dispatch(inbound);

        final String recordedAgentId = agentId != null ? agentId : "(default)";
        //如果 Agent 执行出错，Mono 会变成错误状态，doOnSuccess 不会触发，也就不会记录一条"0ms"或异常的耗时。
        // 触发条件，仅成功时
        return call.doOnSuccess(
                reply ->
                        usageStore.record(
                                userId, recordedAgentId, System.currentTimeMillis() - startMs));
    }

    /**
     * 打包消息：把用户文字包装成 InboundMessage，贴上 userId、agentId、conversationId
     * 丢给通道：chatUiChannel.dispatchStream(inbound) —— 这才是真正调 Agent 的地方
     * 比喻：前台把电话转给客服，客服开始说话，电话那头能听到客服一个字一个字往外蹦。
     */
    private Flux<AgentEvent> executeChatStream(
            String userId, String agentId, String message, String conversationId) {

        // ① 把用户文字包装成框架内部的消息对象
        Msg userMsg = new UserMessage("user", message);
        // ② 记录开始时间，后面算耗时用
        long startMs = System.currentTimeMillis();
        // ③ 根据有没有指定 agentId，构造不同的"入站消息"
        // DM 模式像打客服热线——系统帮你转接；
        // 精确投递像打直拨号——你知道要找谁，直接拨过去，还告诉对方"这是我们的第 3 次通话"。
        InboundMessage inbound;  //InboundMessage 是 Agent 框架内部的标准信封——不管消息从哪来（网页聊天、Webhook、API），都要先塞进这个信封，才能投递给 Agent。
        if (agentId == null || agentId.isBlank()) {
            // 情况A：没指定 Agent → 用 DM 模式（直接消息，让系统自动路由）
            // 就像发了一条"私信"，系统根据用户身份自动路由到合适的 Agent。
            inbound = InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.of(userMsg));
        } else {
            // 情况B：指定了 Agent → 精确投递给这个 Agent
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            inbound =
                    InboundMessage.builder(
                                    ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.of(userMsg))  //Peer.direct(userId)发送者身份，Agent 需要知道是谁在跟它说话
                            .preferredAgentId(gatewayAgentId)  // 指定要哪个 Agent
                            .accountId(conversationId)  // 会话ID，保证同一会话的消息路由到同一个 Agent 实例
                            .build();
        }

        // ④ 把消息丢给通道，拿到 Agent 的事件流，真正调 Agent
        // chatUiChannel 是一个通信通道（类似消息队列），
        // dispatchStream 把消息投递进去，Agent 框架在另一端接收并处理，
        // 产生的所有事件（文字片段、工具调用、工具结果等）以 Flux<AgentEvent> 流的形式返回
        Flux<AgentEvent> events = chatUiChannel.dispatchStream(inbound);

        // ⑤ 流结束后记录耗时
        final String recordedAgentId = agentId != null ? agentId : "(default)";
        return events.doFinally(signalType ->
                usageStore.record(userId, recordedAgentId, System.currentTimeMillis() - startMs));
    }

    private ServerSentEvent<String> sse(String eventType, Object data) {
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"" + eventType + "\"}";
        }
        return ServerSentEvent.<String>builder().event(eventType).data(json).build();
    }
}
