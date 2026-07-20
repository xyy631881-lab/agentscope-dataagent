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
import io.agentscope.dataagent.agent.application.AgentAclService;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.dataagent.conversation.application.ConversationService;
import io.agentscope.dataagent.conversation.domain.SessionEntry;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.conversation.application.RunSessionActivityRecorder;
import io.agentscope.dataagent.workspace.application.LocalWorkspaceMirrorService;
import io.agentscope.dataagent.workspace.application.WorkspaceArtifactService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.security.infrastructure.IdentityLinkStore;
import io.agentscope.dataagent.agent.application.AgentAccessGuard;
import io.agentscope.dataagent.agent.application.AgentAclService.Tier;
import io.agentscope.dataagent.conversation.application.UsageStore;
import io.agentscope.dataagent.config.ModelConfig;
import io.agentscope.dataagent.config.properties.ApiModelProperties;
import io.agentscope.dataagent.model.application.TenantModelService;
import io.agentscope.dataagent.observability.application.TraceRunService;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;

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
import reactor.core.Disposable;
import reactor.core.publisher.SignalType;
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
    private final TenantModelService tenantModels;
    private final ApiModelProperties apiModelProperties;
    private final TraceRunService traceRuns;
    private final AgentAccessGuard guard;
    private final RunSessionActivityRecorder runSessionActivity;
    private final LocalWorkspaceMirrorService workspaceMirrorService;
    private final WorkspaceArtifactService workspaceArtifactService;
    private final DataAgentBootstrap bootstrap;

    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingConfirm> pendingConfirms = new ConcurrentHashMap<>();
    private final Map<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    public ChatController(
            ChatUiChannel chatUiChannel,
            ConversationService conversationService,
            AgentCatalogService catalogService,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            TenantModelService tenantModels,
            ApiModelProperties apiModelProperties,
            TraceRunService traceRuns,
            AgentAccessGuard guard,
            RunSessionActivityRecorder runSessionActivity,
            AgentLifecycleService lifecycleService,
            LocalWorkspaceMirrorService workspaceMirrorService,
            WorkspaceArtifactService workspaceArtifactService,
            DataAgentBootstrap bootstrap) {
        this.chatUiChannel = chatUiChannel;
        this.conversationService = conversationService;
        this.catalogService = catalogService;
        this.lifecycleService = lifecycleService;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.tenantModels = tenantModels;
        this.apiModelProperties = apiModelProperties;
        this.traceRuns = traceRuns;
        this.guard = guard;
        this.runSessionActivity = runSessionActivity;
        this.workspaceMirrorService = workspaceMirrorService;
        this.workspaceArtifactService = workspaceArtifactService;
        this.bootstrap = bootstrap;
    }

    public record ChatRequest(
            String message,
            String sessionKey,
            List<ConfirmDecision> confirmResults,
            String requestId) {}

    /** A single approve/reject decision returned by the UI for a paused tool call. */
    public record ConfirmDecision(String toolCallId, boolean approved) {}

    /** Pending human-in-the-loop confirmation, keyed by conversation id. */
    private record PendingConfirm(String replyId, List<ToolUseBlock> toolCalls) {}

    private record ActiveStream(String userId, String agentId, Disposable subscription, SseEmitter emitter) {}

    public record ChatResponse(String reply, String sessionKey) {}

    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    @PostMapping("/session")
    public CurrentSessionResponse createSession(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        String conversationId = UUID.randomUUID().toString();
        conversationService.createSessionRecord(
                agentId, conversationId, userId, resolveGateKey(userId, agentId, conversationId), null);
        return new CurrentSessionResponse(conversationId, true);
    }

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
        long requestStartedAt = System.currentTimeMillis();
        String requestId = requestId(req);

        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        String conversationId = ChatSupport.normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        String gateKey = resolveGateKey(userId, agentId, resolvedConversationId);
        boolean running = gateKey != null && bootstrap.gateway().isSessionRunning(gateKey);
        log.info(
                "[stream-debug] accepted requestId={}, userId={}, agentId={}, conversationId={}, gateKey={}, running={}, messageChars={}",
                requestId,
                userId,
                agentId,
                resolvedConversationId,
                gateKey,
                running,
                req.message() != null ? req.message().length() : 0);
        if (running) {
            return immediateStreamError(
                    emitter,
                    "当前会话仍有执行中的请求。请先点击 Stop，或等待当前请求结束后再发送。");
        }
        boolean recordRunActivity =
                recordRunSession(
                        def,
                        agentId,
                        userId,
                        resolvedConversationId,
                        conversationLabel(req.message()));
        log.info(
                "[stream-debug] session-recorded requestId={}, conversationId={}, gateKey={}",
                requestId,
                resolvedConversationId,
                gateKey);

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
            recordRunActivity(recordRunActivity, def, agentId, userId, gateKey);
            return emitter;
        }

        // 订阅 Agent 事件流，推送到 SseEmitter
        final Map<String, ToolBuffer> buffers = new ConcurrentHashMap<>();
        final List<WorkspaceArtifactService.ChartArtifactRequest> chartArtifacts =
                java.util.Collections.synchronizedList(new ArrayList<>());
        String logicalModelId = logicalModelId(def);
        String effectiveModelId = tenantModels.effectiveModelLabel(userId, logicalModelId);
        TraceRunService.TraceScope traceScope = startTrace(
                userId, agentId, resolvedConversationId, effectiveModelId);
        log.info(
                "[stream-debug] trace-started requestId={}, conversationId={}, model={}",
                requestId,
                resolvedConversationId,
                effectiveModelId);
        AtomicBoolean firstSseFrame = new AtomicBoolean(true);

        Disposable subscription = executeChatStream(
                        userId,
                        agentId,
                        req,
                        resolvedConversationId,
                        logicalModelId,
                        effectiveModelId,
                        traceScope)
                .doOnSubscribe(ignored -> log.info(
                        "[stream-debug] subscribed requestId={}, conversationId={}; waiting for gateway events",
                        requestId,
                        resolvedConversationId))
                .mapNotNull(event -> {
                    try {
                        return convertToSseFrame(event, buffers, chartArtifacts, resolvedConversationId);
                    } catch (Exception ex) {
                        // 单个事件转换失败不杀死整个流（如沙箱过期等清理阶段异常）
                        log.debug(
                                "Skipping SSE frame conversion: event={}, error={}",
                                event.getClass().getSimpleName(), ex.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .doOnNext(frame -> {
                    if (firstSseFrame.compareAndSet(true, false)) {
                        log.info(
                                "[stream-debug] first-sse-frame requestId={}, conversationId={}, event={}",
                                requestId,
                                resolvedConversationId,
                                frame.event());
                    }
                })
                .onErrorResume(ex -> {
                    // 框架清理阶段（沙箱已回收后访问文件系统）的异常属于"事后噪音"，
                    // Agent 已正常完成、done 事件也已发出，不应作为错误推给前端。
                    String msg = ex.getMessage() != null ? ex.getMessage() : "";
                    if (isCleanupNoise(msg)) {
                        log.debug(
                                "Suppressed post-completion cleanup noise: userId={}, agentId={}, detail={}",
                                userId, agentId, msg);
                        return Flux.empty(); // 静默完成，不发 error 帧
                    }
                    // 对 WorkspaceStartException 打印完整 cause 链，方便定位
                    if (ex instanceof io.agentscope.harness.agent.sandbox.SandboxException.WorkspaceStartException wse) {
                        log.error(
                                "Chat stream error (WorkspaceStart): userId={}, agentId={}, outer={}",
                                userId, agentId, msg, wse.getCause());
                    } else {
                        log.warn(
                                "Chat stream error: userId={}, agentId={}, error={}",
                                userId, agentId, msg);
                    }
                    return Flux.just(sseFrame("error",
                            Map.of("type", "error", "error", msg)));
                })
                .doFinally(
                        signal ->
                                recordRunActivity(
                                        recordRunActivity, def, agentId, userId, gateKey))
                .subscribe(
                        frame -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(frame.event())
                                        .data(frame.data()));
                            } catch (IOException e) {
                                log.info(
                                        "[stream-debug] client-disconnected requestId={}, conversationId={}, error={}",
                                        requestId,
                                        resolvedConversationId,
                                        e.getMessage());
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            log.warn(
                                    "[stream-debug] sse-subscription-error requestId={}, conversationId={}, error={}",
                                    requestId,
                                    resolvedConversationId,
                                    error.getMessage());
                            emitter.completeWithError(error);
                        },
                        () -> {
                            workspaceArtifactService.persistCharts(userId, agentId, chartArtifacts);
                            workspaceMirrorService.synchronize(userId, agentId);
                            log.info(
                                    "[stream-debug] completed requestId={}, conversationId={}, durationMs={}",
                                    requestId,
                                    resolvedConversationId,
                                    System.currentTimeMillis() - requestStartedAt);
                            emitter.complete();
                        });

        activeStreams.put(requestId, new ActiveStream(userId, agentId, subscription, emitter));
        emitter.onCompletion(() -> {
            activeStreams.remove(requestId);
            subscription.dispose();
            log.info(
                    "[stream-debug] emitter-complete requestId={}, conversationId={}, durationMs={}",
                    requestId,
                    resolvedConversationId,
                    System.currentTimeMillis() - requestStartedAt);
        });
        emitter.onTimeout(() -> {
            activeStreams.remove(requestId);
            subscription.dispose();
            log.warn(
                    "[stream-debug] emitter-timeout requestId={}, conversationId={}, durationMs={}",
                    requestId,
                    resolvedConversationId,
                    System.currentTimeMillis() - requestStartedAt);
            emitter.complete();
        });
        emitter.onError(error -> {
            activeStreams.remove(requestId);
            subscription.dispose();
            log.info(
                    "[stream-debug] emitter-error requestId={}, conversationId={}, error={}",
                    requestId,
                    resolvedConversationId,
                    error.getMessage());
        });

        return emitter;
    }

    @PostMapping("/cancel")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancel(
            @PathVariable String agentId, @RequestBody CancelRequest request, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "requestId is required");
        }
        ActiveStream active = activeStreams.remove(request.requestId());
        if (active == null) {
            log.info("[stream-debug] cancel ignored: requestId={} is not active", request.requestId());
            return;
        }
        if (!userId.equals(active.userId()) || !agentId.equals(active.agentId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Cannot cancel another user's request");
        }
        log.info(
                "[stream-debug] cancel requestId={}, userId={}, agentId={}",
                request.requestId(), userId, agentId);
        active.subscription().dispose();
        active.emitter().complete();
    }

    public record CancelRequest(String requestId) {}

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
        boolean recordRunActivity =
                recordRunSession(
                        def,
                        agentId,
                        userId,
                        resolvedConversationId,
                        conversationLabel(req.message()));
        String gateKey = resolveGateKey(userId, agentId, resolvedConversationId);
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            recordRunActivity(recordRunActivity, def, agentId, userId, gateKey);
            return new ChatResponse(
                    cmd.message,
                    cmd.newSessionKey != null ? cmd.newSessionKey : resolvedConversationId);
        }
        String logicalModelId = logicalModelId(def);
        String effectiveModelId = tenantModels.effectiveModelLabel(userId, logicalModelId);
        TraceRunService.TraceScope traceScope = startTrace(
                userId, agentId, resolvedConversationId, effectiveModelId);
        try {
            Msg reply = executeChat(
                    userId,
                    agentId,
                    req.message(),
                    resolvedConversationId,
                    logicalModelId,
                    effectiveModelId,
                    traceScope);
            traceRuns.complete(traceScope, "SUCCESS", null);
            workspaceMirrorService.synchronize(userId, agentId);
            String text = reply.getTextContent() != null ? reply.getTextContent() : "";
            return new ChatResponse(text, resolvedConversationId);
        } catch (RuntimeException exception) {
            traceRuns.complete(traceScope, "ERROR", exception);
            throw exception;
        } finally {
            recordRunActivity(recordRunActivity, def, agentId, userId, gateKey);
        }
    }

    // -----------------------------------------------------------------
    //  内部辅助方法
    // -----------------------------------------------------------------

    private boolean recordRunSession(
            AgentDefinition def,
            String agentId,
            String userId,
            String conversationId,
            String initialLabel) {
        String gateKey = resolveGateKey(userId, agentId, conversationId);
        if (gateKey == null) return false;

        // 确保会话记录在数据库中存在（全局 Agent 的 def.ownerId 为 null，但会话记录仍应创建）
        conversationService.createSessionRecord(agentId, conversationId, userId, gateKey, initialLabel);

        if (def == null || def.ownerId() == null) {
            return false;
        }
        return startedSessions.add(gateKey);
    }

    private void recordRunActivity(
            boolean shouldRecord,
            AgentDefinition def,
            String agentId,
            String userId,
            String gateKey) {
        if (!shouldRecord || def == null || def.ownerId() == null || gateKey == null) return;
        if (!runSessionActivity.tryRecord(def.ownerId(), agentId, userId, gateKey)) {
            startedSessions.remove(gateKey);
        }
    }

    private static String conversationLabel(String message) {
        if (message == null) return null;
        String normalized = message.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank() || normalized.startsWith("/")) return null;
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60) + "...";
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
            String userId,
            String agentId,
            String message,
            String conversationId,
            String logicalModelId,
            String effectiveModelId,
            TraceRunService.TraceScope traceScope) {
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
        Msg reply = ContextPropagationOperator.runWithContext(
                        chatUiChannel.dispatch(inbound), traceScope.otelContext())
                .block();
        long durationMs = System.currentTimeMillis() - startMs;
        usageStore.record(
                new UsageStore.UsageEvent(
                        TenantModelService.tenantForUser(userId),
                        userId,
                        recordedAgentId,
                        conversationId,
                        effectiveModelId,
                        0,
                        0,
                        0,
                        durationMs,
                        tenantModels.calculateCostMicrousd(userId, logicalModelId, 0, 0, 0),
                        "SUCCESS",
                        System.currentTimeMillis()));
        return reply;
    }

    /**
     * 构建流式聊天的 Agent 事件流。
     * {@code chatUiChannel.dispatchStream()} 返回 {@code Flux<AgentEvent>}（框架接口），
     * 由调用方订阅。
     */
    private Flux<AgentEvent> executeChatStream(
            String userId,
            String agentId,
            ChatRequest req,
            String conversationId,
            String logicalModelId,
            String effectiveModelId,
            TraceRunService.TraceScope traceScope) {
        Msg userMsg;
        if (req.confirmResults() != null && !req.confirmResults().isEmpty()) {
            // Resume a paused (human-in-the-loop) agent: replay the user's decision as a
            // confirmation result on the original tool call so the engine can proceed.
            List<ConfirmResult> results = buildConfirmResults(req.confirmResults(), conversationId);
            userMsg = UserMessage.builder()
                    .textContent(req.message() != null ? req.message() : "")
                    .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                    .build();
        } else {
            userMsg = new UserMessage("user", req.message());
        }
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
        UsageTotals usageTotals = new UsageTotals();
        SubagentSpawnResultAccumulator spawnResults = new SubagentSpawnResultAccumulator();
        AtomicReference<Throwable> terminalError = new AtomicReference<>();
        AtomicBoolean firstAgentEvent = new AtomicBoolean(true);
        log.info(
                "[stream-debug] gateway-dispatch-created userId={}, agentId={}, gatewayAgentId={}, conversationId={}",
                userId,
                agentId,
                agentId == null || agentId.isBlank()
                        ? "(default)"
                        : lifecycleService.resolveGatewayAgentId(userId, agentId),
                conversationId);
        return ContextPropagationOperator.runWithContext(events, traceScope.otelContext())
                // An idle event stream means the model/gateway stopped making progress. Terminate
                // it instead of leaving the browser permanently on "正在思考".
                .timeout(Duration.ofSeconds(180))
                .doOnSubscribe(ignored -> log.info(
                        "[stream-debug] agent-stream-subscribed userId={}, agentId={}, conversationId={}",
                        userId,
                        agentId,
                        conversationId))
                .doOnNext(event -> {
                    if (firstAgentEvent.compareAndSet(true, false)) {
                        log.info(
                                "[stream-debug] first-agent-event userId={}, agentId={}, conversationId={}, eventType={}",
                                userId,
                                agentId,
                                conversationId,
                                event.getClass().getSimpleName());
                    }
                    if (event instanceof ModelCallEndEvent modelCallEnd) {
                        usageTotals.add(modelCallEnd.getUsage());
                    }
                    if (event instanceof AgentStartEvent agentStart) {
                        recordSubagentStart(
                                event,
                                agentStart,
                                conversationId,
                                recordedAgentId,
                                userId);
                    }
                    spawnResults
                            .accept(event)
                            .ifPresent(
                                    spawn ->
                                            recordSubagentSpawnResult(
                                                    spawn,
                                                    conversationId,
                                                    userId));
                })
                .doOnError(error -> {
                    terminalError.set(error);
                    log.warn(
                            "[stream-debug] agent-stream-error userId={}, agentId={}, conversationId={}, error={}",
                            userId,
                            agentId,
                            conversationId,
                            error.getMessage());
                })
                .doOnCancel(() -> log.info(
                        "[stream-debug] agent-stream-cancelled userId={}, agentId={}, conversationId={}",
                        userId,
                        agentId,
                        conversationId))
                .doFinally(signalType -> {
                    long durationMs = System.currentTimeMillis() - startMs;
                    String outcome = outcomeFor(signalType);
                    usageStore.record(
                            new UsageStore.UsageEvent(
                                    TenantModelService.tenantForUser(userId),
                                    userId,
                                    recordedAgentId,
                                    conversationId,
                                    effectiveModelId,
                                    usageTotals.inputTokens(),
                                    usageTotals.outputTokens(),
                                    usageTotals.cachedPromptTokens(),
                                    durationMs,
                                    tenantModels.calculateCostMicrousd(
                                            userId,
                                            logicalModelId,
                                            usageTotals.inputTokens(),
                                            usageTotals.outputTokens(),
                                            usageTotals.cachedPromptTokens()),
                                    outcome,
                                    System.currentTimeMillis()));
                    traceRuns.complete(traceScope, outcome, terminalError.get());
                    log.info(
                            "[stream-debug] agent-stream-finalized userId={}, agentId={}, conversationId={}, signal={}, outcome={}, durationMs={}, inputTokens={}, outputTokens={}, cachedPromptTokens={}",
                            userId,
                            agentId,
                            conversationId,
                            signalType,
                            outcome,
                            durationMs,
                            usageTotals.inputTokens(),
                            usageTotals.outputTokens(),
                            usageTotals.cachedPromptTokens());
                });
    }

    private void recordSubagentStart(
            AgentEvent event,
            AgentStartEvent start,
            String parentConversationId,
            String parentAgentId,
            String userId) {
        String source = event.getSource();
        if (source == null || source.isBlank() || !source.contains("/")) return;
        try {
            conversationService.recordSubagentSession(
                    parentConversationId,
                    start.getName() != null ? start.getName() : parentAgentId + "-subagent",
                    start.getSessionId(),
                    userId,
                    source,
                    event.getId());
        } catch (RuntimeException exception) {
            // The session tree is an operational read model and must not fail the agent stream.
            log.warn(
                    "Unable to record subagent session: parent={}, child={}, source={}, error={}",
                    parentConversationId,
                    start.getSessionId(),
                    source,
                    exception.getMessage());
        }
    }

    private void recordSubagentSpawnResult(
            SubagentSpawnResultAccumulator.SpawnResult spawn,
            String parentConversationId,
            String userId) {
        try {
            conversationService.recordSubagentSession(
                    parentConversationId,
                    spawn.agentId(),
                    spawn.sessionId(),
                    userId,
                    parentConversationId + "/" + spawn.agentId(),
                    spawn.toolCallId());
        } catch (RuntimeException exception) {
            log.warn(
                    "Unable to record agent_spawn result: parent={}, child={}, error={}",
                    parentConversationId,
                    spawn.sessionId(),
                    exception.getMessage());
        }
    }

    private TraceRunService.TraceScope startTrace(
            String userId, String agentId, String conversationId, String effectiveModelId) {
        var rootSpan = GlobalOpenTelemetry.getTracer("io.agentscope.dataagent.chat")
                .spanBuilder("dataagent.chat.run")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("dataagent.agent.id", agentId)
                .setAttribute("dataagent.session.key", conversationId)
                .setAttribute("gen_ai.request.model", effectiveModelId)
                .startSpan();
        return traceRuns.start(rootSpan, userId, agentId, conversationId, effectiveModelId);
    }

    private static String requestId(ChatRequest request) {
        if (request != null && request.requestId() != null && !request.requestId().isBlank()) {
            return request.requestId();
        }
        return UUID.randomUUID().toString();
    }

    private SseEmitter immediateStreamError(SseEmitter emitter, String message) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("error")
                            .data(
                                    sseFrame(
                                                    "error",
                                                    Map.of("type", "error", "error", message))
                                            .data()));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            return emitter;
        }
        emitter.complete();
        return emitter;
    }

    private String logicalModelId(AgentDefinition definition) {
        if (definition != null && definition.model() != null && !definition.model().isBlank()) {
            return definition.model();
        }
        return ModelConfig.resolveActiveId(apiModelProperties);
    }

    private static String outcomeFor(SignalType signalType) {
        if (signalType == SignalType.ON_COMPLETE) return "SUCCESS";
        if (signalType == SignalType.CANCEL) return "CANCELLED";
        return "ERROR";
    }

    private static final class UsageTotals {
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cachedPromptTokens = new AtomicLong();

        private void add(ChatUsage usage) {
            if (usage == null) return;
            inputTokens.addAndGet(Math.max(0, usage.getInputTokens()));
            outputTokens.addAndGet(Math.max(0, usage.getOutputTokens()));
            cachedPromptTokens.addAndGet(Math.max(0, usage.getCachedTokens()));
        }

        private long inputTokens() { return inputTokens.get(); }
        private long outputTokens() { return outputTokens.get(); }
        private long cachedPromptTokens() { return cachedPromptTokens.get(); }
    }

    /**
     * Builds {@link ConfirmResult}s for a resumed (paused) agent. Matches each UI decision back to
     * the original {@link ToolUseBlock} captured when the agent paused, then clears the pending entry.
     */
    private List<ConfirmResult> buildConfirmResults(
            List<ConfirmDecision> decisions, String conversationId) {
        PendingConfirm pending = pendingConfirms.remove(conversationId);
        if (pending == null) {
            log.warn("No pending confirmation for conversation {}; cannot resume HITL", conversationId);
            return List.of();
        }
        Map<String, ToolUseBlock> byId = new HashMap<>();
        for (ToolUseBlock tc : pending.toolCalls()) {
            byId.put(tc.getId(), tc);
        }
        List<ConfirmResult> results = new ArrayList<>();
        for (ConfirmDecision d : decisions) {
            ToolUseBlock tc = byId.get(d.toolCallId());
            if (tc == null) {
                log.warn("ConfirmDecision references unknown toolCallId={}", d.toolCallId());
                continue;
            }
            results.add(new ConfirmResult(d.approved(), tc));
        }
        return results;
    }

    /**
     * 将 AgentEvent 转换为 SSE 帧。
     */
    private SseFrame convertToSseFrame(
            AgentEvent event,
            Map<String, ToolBuffer> buffers,
            List<WorkspaceArtifactService.ChartArtifactRequest> chartArtifacts,
            String conversationId) {
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
            ToolBuffer buf = buffers.get(end.getToolCallId());
            String toolName = (buf != null) ? buf.toolName : end.getToolCallName();
            String toolInput = (buf != null) ? buf.input.toString() : "";
            return sseFrame("tool_call", Map.of(
                    "type", "tool_call",
                    "toolCallId", end.getToolCallId(),
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
            if (buf != null
                    && "render_chart".equals(buf.toolName)
                    && !toolResult.trim().toLowerCase().startsWith("error")) {
                chartArtifacts.add(
                        new WorkspaceArtifactService.ChartArtifactRequest(
                                end.getToolCallId(), buf.input.toString()));
            }
            return sseFrame("tool_result", Map.of(
                    "type", "tool_result",
                    "toolCallId", end.getToolCallId(),
                    "toolName", end.getToolCallName(),
                    "toolResult", toolResult));
        }
        if (event instanceof RequireUserConfirmEvent confirm) {
            // Stash the paused call so the UI's reply can be matched back to the right tool call.
            log.info(
                    "HITL confirm required: conversationId={}, replyId={}, toolCalls={}",
                    conversationId, confirm.getReplyId(),
                    confirm.getToolCalls().stream().map(ToolUseBlock::getName).toList());
            pendingConfirms.put(
                    conversationId, new PendingConfirm(confirm.getReplyId(), confirm.getToolCalls()));
            List<Map<String, Object>> calls =
                    confirm.getToolCalls().stream()
                            .map(tc ->
                                    Map.of("id", tc.getId(), "name", tc.getName(), "input", tc.getInput()))
                            .toList();
            return sseFrame("confirm", Map.of(
                    "type", "confirm",
                    "replyId", confirm.getReplyId(),
                    "toolCalls", calls));
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

    /**
     * 判断异常是否属于框架"事后清理噪音"——Agent 已完成执行、done 事件已发出后，
     * 清理代码访问已过期/已回收的沙箱容器或文件系统时抛出的异常。
     * 此类异常不应作为错误推给前端，静默吞掉即可。
     */
    private static boolean isCleanupNoise(String errorMessage) {
        if (errorMessage == null) return false;
        String lower = errorMessage.toLowerCase();
        return lower.contains("no active sandbox")
                || lower.contains("sandbox filesystem used outside")
                || lower.contains("no such container")
                || lower.contains("failed to stop container");
    }


}
