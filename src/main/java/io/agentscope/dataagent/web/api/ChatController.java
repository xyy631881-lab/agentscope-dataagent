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
 *
 * <ul>
 *   <li>{@code POST /api/agents/{agentId}/chat/stream} — {@code token | tool_call |
 *       tool_result | done | error} 事件的 SSE 流。
 *   <li>{@code POST /api/agents/{agentId}/chat/send} — 同步回复（非流式）。
 * </ul>
 *
 * <p>每个用户在每个 {@code (userId, agentId)} 对中有隔离的会话（agent ID 是
 * {@link io.agentscope.harness.agent.gateway.MsgContext#canonicalKey()} 的一部分）。
 * 斜杠命令 {@code /new}、{@code /reset}、{@code /identity} 和
 * {@code /dock_<channel> <id>} 在调用 Agent 之前被拦截。
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
     *
     * <p>{@code sessionKey} 是调用方提供的会话标识符，用于寻址同一 (userId, agentId)
     * 对的多个类似 ChatGPT 的会话之一。{@code null}/空请求会通过委托给网关的确定性
     * 键回退到传统的单会话行为。
     */
    public record ChatRequest(String message, String sessionKey) {}

    /** 同步端点的响应。 */
    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * 对 {@link #currentSession} 的响应。当会话条目已创建时（即用户已发送至少一条消息）
     * {@code exists} 为 {@code true}；前端使用此信息来决定在挂载时是否获取对话轮次。
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /**
     * SSE 流式端点。基于 {@link ChatUiChannel#dispatchStream} 实现，
     * 直接消费 {@code Flux<AgentEvent>}，所有事件（token、tool_call、tool_result、done、error）
     * 在同一个流里有序到达，无需手动合并。
     *
     * <p>前端通过 {@code chat.ts} 消费这些事件，将 {@code data:} 负载解析为 JSON，
     * 格式为 {@code { type, data?, toolName?, toolInput?, toolResult?, error?, sessionKey? }}。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        String conversationId = normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);

        // 斜杠命令会短路 Agent，直接生成一条合成的单 token 回复。
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

        // Single map tracks accumulated tool input/results per toolCallId.
        // Replaces the former 3-map approach (toolNames + toolInputs + toolResults).
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
     * Reports whether a session is already registered for the (userId, agentId, conversationId)
     * tuple. The returned {@code sessionKey} field is the caller's own {@code conversationId} (or
     * {@code null} when none was supplied) — never the internal storage key. {@code exists} is
     * {@code true} when the harness has registered a session for this tuple; the FE uses this to
     * decide whether to fetch turns on mount.
     */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(
            @PathVariable String agentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            String sessionKey,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        String conversationId = normalizedConversationId(sessionKey);
        return Mono.fromCallable(
                () -> {
                    if (conversationId == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    String gateKey = resolveGateKey(userId, agentId, conversationId);
                    boolean exists =
                            gateKey != null && findSessionKeyByGate(userId, gateKey) != null;
                    return new CurrentSessionResponse(conversationId, exists);
                });
    }

    /** Synchronous (non-streaming) chat. Blocks until the agent produces a reply. */
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
     * Emits a single {@code RUN_SESSION} event the first time a (userId, agentId, conversationId)
     * tuple starts a chat in this process. Subsequent turns within the same session are silent.
     * Resetting the session via {@code /reset} clears the cached marker so a fresh session is
     * logged again.
     */
    private void recordRunSession(
            AgentDefinition def, String agentId, String userId, String conversationId) {
        if (def == null || def.ownerId() == null) {
            // Globals have no per-agent activity log.
            return;
        }
        // Use the gateKey here as a per-(user, agent, conversation) dedupe id — the real
        // sessionKey may not exist yet on the very first turn.
        String dedupeKey = resolveGateKey(userId, agentId, conversationId);
        if (dedupeKey == null) return;
        if (!startedSessions.add(dedupeKey)) return;
        activity.record(
                def.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.RUN_SESSION,
                dedupeKey,
                null);
    }

    /** Normalizes a request-supplied {@code sessionKey} into a conversationId, or {@code null}. */
    private static String normalizedConversationId(String key) {
        return (key != null && !key.isBlank()) ? key.trim() : null;
    }

    /**
     * Computes the gateway routing key for a given (userId, agentId, conversationId) tuple. This
     * is the {@link io.agentscope.harness.agent.gateway.MsgContext#canonicalKey()} the gateway uses to look up (or create) the
     * underlying session — it is <em>not</em> the {@code SessionEntry.sessionKey()} the storage
     * layer uses. Use {@link #findSessionKeyByGate} to translate to the real sessionKey.
     *
     * <p>Uses {@link ChatUiChannel#previewRoute} so the key matches exactly what
     * {@link #executeChat} will produce when it dispatches through the same channel.
     * {@code conversationId} (when non-null) flows through to {@code MsgContext.threadId}, so each
     * ChatGPT-style session yields a distinct gateKey and therefore a distinct underlying session.
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
     * O(1) lookup: translates a gateway routing key into the session key via the
     * gateKeyToSessionKey index in SessionAgentManager.
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
     * 核心调度逻辑（同步）。通过 {@link ChatUiChannel#dispatch} 路由，用于 {@link #send} 端点。
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
        Mono<Msg> call = chatUiChannel.dispatch(inbound);

        final String recordedAgentId = agentId != null ? agentId : "(default)";
        return call.doOnSuccess(
                reply ->
                        usageStore.record(
                                userId, recordedAgentId, System.currentTimeMillis() - startMs));
    }

    /**
     * 核心调度逻辑（流式）。通过 {@link ChatUiChannel#dispatchStream} 路由，
     * 返回 {@code Flux<AgentEvent>}，由 {@link #stream} 方法转为 SSE 流。
     */
    private Flux<AgentEvent> executeChatStream(
            String userId, String agentId, String message, String conversationId) {
        Msg userMsg = new UserMessage("user", message);
        long startMs = System.currentTimeMillis();

        InboundMessage inbound;
        if (agentId == null || agentId.isBlank()) {
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
        Flux<AgentEvent> events = chatUiChannel.dispatchStream(inbound);

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
