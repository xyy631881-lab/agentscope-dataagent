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
package io.agentscope.dataagent.conversation.api;
import io.agentscope.dataagent.agent.domain.AgentAclService;

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
import io.agentscope.dataagent.conversation.application.ConversationService;
import io.agentscope.dataagent.conversation.domain.SessionEntry;
import io.agentscope.dataagent.agent.domain.ActivityEvent;
import io.agentscope.dataagent.agent.application.AgentActivityStore;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.security.infrastructure.IdentityLinkStore;
import io.agentscope.dataagent.agent.application.AgentAccessGuard;
import io.agentscope.dataagent.agent.domain.AgentAclService.Tier;
import io.agentscope.dataagent.conversation.application.UsageStore;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

import java.io.IOException;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * 聊天端点，作用于特定的 Agent。
 * 是用户和 Agent 之间的唯一入口，相当于客服中心前台——用户打电话进来（HTTP 请求），
 * 前台把电话转给对应客服（Agent）。
 *
 * <p>从 WebFlux 迁移至 Spring MVC：
 * {@code Flux<ServerSentEvent<String>>} → {@link SseEmitter}，
 * {@code Mono<ChatResponse>} → 直接返回 {@code ChatResponse}。
 * Agent 事件流（{@code Flux<AgentEvent>}）来自框架接口，通过 {@code .subscribe()}
 * 订阅后推送到 SseEmitter。
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final long SSE_TIMEOUT = 300_000L; // 5 分钟

    private final ChatUiChannel chatUiChannel;
    private final ConversationService conversationService;
    private final AgentCatalogService catalogService;
    private final AgentLifecycleService lifecycleService;
    private final IdentityLinkStore identityLinks;
    private final UsageStore usageStore;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

    public ChatController(
            ChatUiChannel chatUiChannel,
            ConversationService conversationService,
            AgentCatalogService catalogService,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            AgentAccessGuard guard,
            AgentActivityStore activity,
            AgentLifecycleService lifecycleService) {
        this.chatUiChannel = chatUiChannel;
        this.conversationService = conversationService;
        this.catalogService = catalogService;
        this.lifecycleService = lifecycleService;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.guard = guard;
        this.activity = activity;
    }

    public record ChatRequest(String message, String sessionKey) {}

    public record ChatResponse(String reply, String sessionKey) {}

    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /**
     * SSE 流式端点。
     *
     * <p>返回 {@link SseEmitter}，Spring MVC 会保持响应打开直到 emitter 完成。
     * Agent 事件流（{@code Flux<AgentEvent>}）通过 {@code .subscribe()} 订阅，
     * 每个事件转换为 SSE 帧后推送到 emitter。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        String conversationId = ChatSupport.normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);

        // 斜杠命令短路
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            try {
                Map<String, Object> tokenFrame = Map.of("type", "token", "data", cmd.message);
                emitter.send(SseEmitter.event().name("token").data(ChatSupport.toJson("token", tokenFrame)));
                Map<String, Object> doneFrame = new LinkedHashMap<>();
                doneFrame.put("type", "done");
                doneFrame.put(
                        "sessionKey",
                        cmd.newSessionKey != null ? cmd.newSessionKey : resolvedConversationId);
                emitter.send(SseEmitter.event().name("done").data(ChatSupport.toJson("done", doneFrame)));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            emitter.complete();
            return emitter;
        }

        // 订阅 Agent 事件流，推送到 SseEmitter
        final Map<String, ToolBuffer> buffers = new ConcurrentHashMap<>();

        executeChatStream(userId, agentId, req.message(), resolvedConversationId)
                .mapNotNull(event -> convertToSseFrame(event, buffers, resolvedConversationId))
                .filter(Objects::nonNull)
                .onErrorResume(ex -> {
                    log.warn(
                            "Chat stream error: userId={}, agentId={}, error={}",
                            userId, agentId, ex.getMessage());
                    return Flux.just(sseFrame("error",
                            Map.of("type", "error", "error", ex.getMessage())));
                })
                .subscribe(
                        frame -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(frame.event())
                                        .data(frame.data()));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.warn("SSE subscription error: {}", error.getMessage());
                            emitter.completeWithError(error);
                        },
                        () -> emitter.complete());

        return emitter;
    }

    @GetMapping("/session")
    public CurrentSessionResponse currentSession(
            @PathVariable String agentId,
            @RequestParam(required = false) String sessionKey,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        String conversationId = ChatSupport.normalizedConversationId(sessionKey);
        if (conversationId == null) {
            return new CurrentSessionResponse(null, false);
        }
        String gateKey = resolveGateKey(userId, agentId, conversationId);
        boolean exists = gateKey != null && findSessionKeyByGate(userId, gateKey) != null;
        return new CurrentSessionResponse(conversationId, exists);
    }

    @PostMapping("/send")
    public ChatResponse send(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        String conversationId = ChatSupport.normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            return new ChatResponse(
                    cmd.message,
                    cmd.newSessionKey != null ? cmd.newSessionKey : resolvedConversationId);
        }
        Msg reply = executeChat(userId, agentId, req.message(), resolvedConversationId);
        String text = reply.getTextContent() != null ? reply.getTextContent() : "";
        return new ChatResponse(text, resolvedConversationId);
    }

    // -----------------------------------------------------------------
    //  内部辅助方法
    // -----------------------------------------------------------------

    private void recordRunSession(
            AgentDefinition def, String agentId, String userId, String conversationId) {
        if (def == null || def.ownerId() == null) {
            return;
        }
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



    private String resolveGateKey(String userId, String agentId, String conversationId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            String gatewayAgentId = lifecycleService.resolveGatewayAgentId(userId, agentId);
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

    private String findSessionKeyByGate(String userId, String gateKey) {
        if (gateKey == null) return null;
        return conversationService.findByGateKey(gateKey, userId)
                .map(SessionEntry::sessionKey)
                .orElse(null);
    }

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
                    startedSessions.remove(gateKey);
                    return new CommandResult(
                            "No active session yet — your next message will start a fresh"
                                    + " conversation.",
                            null);
                }
                boolean ok = conversationService.resetSessionByKey(sessionKey);
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

    private record CommandResult(String message, String newSessionKey) {}

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
     * 同步执行聊天——阻塞直到 Agent 返回最终回复。
     * {@code chatUiChannel.dispatch()} 返回 {@code Mono<Msg>}（框架接口），用 {@code .block()} 同步获取。
     */
    private Msg executeChat(
            String userId, String agentId, String message, String conversationId) {
        Msg userMsg = new UserMessage("user", message);
        long startMs = System.currentTimeMillis();

        InboundMessage inbound;
        if (agentId == null || agentId.isBlank()) {
            inbound = InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.of(userMsg));
        } else {
            String gatewayAgentId = lifecycleService.resolveGatewayAgentId(userId, agentId);
            inbound =
                    InboundMessage.builder(
                                    ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.of(userMsg))
                            .preferredAgentId(gatewayAgentId)
                            .accountId(conversationId)
                            .build();
        }

        final String recordedAgentId = agentId != null ? agentId : "(default)";
        Msg reply = chatUiChannel.dispatch(inbound).block();
        usageStore.record(userId, recordedAgentId, System.currentTimeMillis() - startMs);
        return reply;
    }

    /**
     * 构建流式聊天的 Agent 事件流。
     * {@code chatUiChannel.dispatchStream()} 返回 {@code Flux<AgentEvent>}（框架接口），
     * 由调用方订阅。
     */
    private Flux<AgentEvent> executeChatStream(
            String userId, String agentId, String message, String conversationId) {
        Msg userMsg = new UserMessage("user", message);
        long startMs = System.currentTimeMillis();

        InboundMessage inbound;
        if (agentId == null || agentId.isBlank()) {
            inbound = InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.of(userMsg));
        } else {
            String gatewayAgentId = lifecycleService.resolveGatewayAgentId(userId, agentId);
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

    /**
     * 将 AgentEvent 转换为 SSE 帧。
     */
    private SseFrame convertToSseFrame(
            AgentEvent event, Map<String, ToolBuffer> buffers, String conversationId) {
        if (event instanceof TextBlockDeltaEvent delta) {
            return sseFrame("token", Map.of("type", "token", "data", delta.getDelta()));
        }
        if (event instanceof ToolCallStartEvent tc) {
            buffers.put(tc.getToolCallId(), new ToolBuffer(tc.getToolCallName()));
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
            return sseFrame("tool_call", Map.of(
                    "type", "tool_call",
                    "toolName", toolName,
                    "toolInput", toolInput));
        }
        if (event instanceof ToolResultStartEvent start) {
            buffers.computeIfAbsent(start.getToolCallId(), k -> new ToolBuffer(null));
            return null;
        }
        if (event instanceof ToolResultTextDeltaEvent delta) {
            ToolBuffer buf = buffers.get(delta.getToolCallId());
            if (buf != null) buf.appendResult(delta.getDelta());
            return null;
        }
        if (event instanceof ToolResultEndEvent end) {
            ToolBuffer buf = buffers.remove(end.getToolCallId());
            String toolResult = (buf != null) ? buf.result.toString() : end.getState().name();
            return sseFrame("tool_result", Map.of(
                    "type", "tool_result",
                    "toolName", end.getToolCallName(),
                    "toolResult", toolResult));
        }
        if (event instanceof AgentEndEvent) {
            return sseFrame("done", Map.of(
                    "type", "done",
                    "sessionKey", conversationId));
        }
        return null;
    }

    /** SSE 帧DTO：事件名 + JSON 数据。 */
    private record SseFrame(String event, String data) {}

    private SseFrame sseFrame(String eventType, Object data) {
        return new SseFrame(eventType, ChatSupport.toJson(eventType, data));
    }


}