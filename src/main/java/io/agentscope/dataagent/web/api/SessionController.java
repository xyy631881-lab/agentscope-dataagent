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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.HistoryResult;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.agent.catalog.AgentCatalogService;
import io.agentscope.dataagent.agent.catalog.AgentLifecycleService;
import io.agentscope.dataagent.web.session.SessionReadStateStore;
import io.agentscope.dataagent.web.session.SessionTurnParser;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 这个类是系统的会话管家——管理用户和 Agent 之间的所有对话记录，就像一个聊天应用的"消息管理器"。
 * 前端
 *  │
 *  ├── GET  /inbox          → 收件箱列表（会话 + 未读 + 预览）
 *  ├── GET  /{key}          → 对话详情（完整轮次）
 *  ├── POST /{key}/reset    → 重置会话（清空历史）
 *  ├── PATCH /{key}/read    → 标记已读
 *  └── DELETE /{key}        → 删除会话
 *  │
 *  ▼
 * SessionController
 *  │
 *  ├── requireOwnedSession()  ← 所有接口的安检门
 *  │     ├── 找会话（支持 sessionKey / conversationId 两种 key）
 *  │     └── 校验归属（用户 + Agent 都要匹配）
 *  │
 *  ├── SessionAgentManager    ← 会话的增删改查
 *  ├── SessionReadStateStore  ← 已读/未读状态
 *  ├── SessionTurnParser      ← 日志 → 结构化轮次
 *  └── WorkspaceManager       ← 读取日志文件
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private final DataAgentBootstrap bootstrap;
    private final SessionAgentManager sessionAgentManager;
    private final SessionReadStateStore readStateStore;
    private final AgentCatalogService catalogService;
    private final AgentLifecycleService lifecycleService;

    public SessionController(
            DataAgentBootstrap builderBootstrap,
            SessionReadStateStore readStateStore,
            AgentCatalogService catalogService,
            AgentLifecycleService lifecycleService) {
        this.bootstrap = builderBootstrap;
        this.sessionAgentManager = builderBootstrap.sessionAgentManager();
        this.readStateStore = readStateStore;
        this.catalogService = catalogService;
        this.lifecycleService = lifecycleService;
    }

    /**
     * 收件箱：会话列表 + 未读标记 + 最后一条消息预览
     * 就像微信的聊天列表页——每个会话一行，显示会话名、最后一条消息预览、时间、有没有红点（未读标记）。
     * @param agentId Agent ID
     * @param limit 最大返回数量
     * @param unreadOnly 是否仅返回未读会话
     * @param auth 认证信息，用于获取当前用户 ID 和会话 ID 映射关系
     * @return 收件箱中的会话列表
     * ① 算出这个用户的 gatewayAgentId
     * ② 从所有会话中筛选出属于这个用户 + 这个 Agent 的会话
     * ③ 按最后活跃时间倒序排列（最近的排最前）
     * ④ 取前 limit 条
     * ⑤ 对每条会话：
     *    - 查是否未读
     *    - 如果 unreadOnly=true 且已读 → 跳过
     *    - 提取最后一条消息作为预览（最多200字）
     * ⑥ 返回收件箱列表
     */
    @GetMapping("/inbox")
    public Mono<List<InboxEntry>> inbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);
                    List<SessionEntry> matched =
                            sessionAgentManager.allSessions().stream()
                                    .filter(e -> Objects.equals(e.userId(), userId))
                                    .filter(e -> sessionMatchesAgent(e, gatewayAgentId))
                                    .sorted(
                                            Comparator.comparingLong(SessionEntry::lastActivityMs)
                                                    .reversed())
                                    .limit(limit)
                                    .toList();

                    List<InboxEntry> out = new ArrayList<>(matched.size());
                    for (SessionEntry e : matched) {
                        boolean unread =
                                readStateStore.isUnread(userId, e.sessionKey(), e.lastActivityMs());
                        if (unreadOnly && !unread) continue;
                        String preview = lastMessagePreview(agentId, e);
                        out.add(
                                new InboxEntry(
                                        e.sessionKey(),
                                        e.sessionId(),
                                        e.agentId(),
                                        extractConversationId(e.gateKey()),
                                        e.label(),
                                        e.lastActivityMs(),
                                        preview,
                                        unread));
                    }
                    return out;
                });
    }

    /**
     * 查看某个会话的完整对话记录
     * 点进一个聊天会话，看到完整的聊天记录，每条消息谁说的、说了什么、什么时候说的，一目了然。
     * @param agentId Agent ID
     * @param key 会话 ID
     * @param auth 认证信息，用于获取当前用户 ID 和会话 ID 映射关系
     * @return 会话的完整对话记录
     * ① 找到这个会话（requireOwnedSession）
     * ② 读取会话日志文件的内容
     * ③ 用 SessionTurnParser 解析成结构化的对话轮次
     * ④ 返回轮次列表
     */
    @GetMapping("/{key}")
    public Mono<List<SessionTurnParser.TurnEntry>> turns(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    String content = readSessionLogContent(agentId, entry);
                    return SessionTurnParser.parse(content != null ? content : "");
                });
    }

    /**
     * 重置会话（清空对话历史，但保留会话）
     * 重置就像"清空聊天记录"，你们还在同一个聊天窗口里，但之前的对话都没了，Agent 也不记得之前说过什么了。
     * 点击重置按钮，会话会清空，但是会话会保留在收件箱中。
     * @param agentId Agent ID
     * @param key 会话 ID
     * @param auth 认证信息，用于获取当前用户 ID 和会话 ID 映射关系
     * @return 重置结果
     * ① 找到这个会话（requireOwnedSession）
     * ② 调用 sessionAgentManager.resetSession() 清空对话历史
     * ③ 返回重置结果
     */
    @PostMapping("/{key}/reset")
    public Mono<ResetResult> reset(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    boolean ok = sessionAgentManager.resetSession(entry.sessionKey());
                    return new ResetResult(key, ok);
                });
    }

    /**
     * 标记会话为已读
     * 就像微信点进聊天窗口——红点消失，标记为已读。但如果 Agent 又回复了新消息，就又变成未读了。
     * @param agentId Agent ID
     * @param key 会话 ID
     * @param auth 认证信息，用于获取当前用户 ID 和会话 ID 映射关系
     * @return 标记已读结果
     * ① 找到这个会话（requireOwnedSession）
     * ② 调用 readStateStore.markRead() 标记已读
     * ③ 返回标记已读结果
     */
    @PatchMapping("/{key}/read")
    public Mono<ReadStateResult> markRead(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    long readAtMs = readStateStore.markRead(userId, entry.sessionKey());
                    return new ReadStateResult(key, readAtMs, false);
                });
    }

    /**
     * 彻底删除会话
     * ：左滑删除聊天——整个会话连同对话记录都没了。
     * @param agentId Agent ID
     * @param key 会话 ID
     * @param auth 认证信息，用于获取当前用户 ID 和会话 ID 映射关系
     * @return 删除结果
     * ① 找到这个会话（requireOwnedSession）
     * ② 调用 sessionAgentManager.removeSession() 删除会话
     * ③ 返回 204 No Content
     */
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    SessionEntry entry = requireOwnedSession(agentId, key, userId);
                    sessionAgentManager.removeSession(entry.sessionKey());
                });
    }

    // -----------------------------------------------------------------
    //  核心内部方法
    // -----------------------------------------------------------------

    /**
     * 安全校验
     * ① 找到会话（支持两种 key 格式）
     *    - 内部存储 key（旧版）: "sess-xyz-789"
     *    - conversationId（新版）: "conv-456"
     *
     * ② 校验归属
     *    - 这个会话是不是这个用户的？
     *    - 这个会话是不是属于 URL 中指定的 Agent？
     *    - 不满足 → 403 Forbidden
     *   为什么支持两种 key？ 因为前端只知道 conversationId（创建会话时自己生成的），
     *   但系统内部用 sessionKey。requireOwnedSession 两种都能查，对前端透明。
     */
    private SessionEntry requireOwnedSession(String agentId, String key, String userId) {
        SessionEntry entry =
                sessionAgentManager
                        .getSession(key)
                        .orElseGet(() -> findSessionByConversationId(agentId, key, userId));
        if (entry == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "AgentStateStore not found: " + key);
        }
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);
        if (!Objects.equals(entry.userId(), userId)
                || !sessionMatchesAgent(entry, gatewayAgentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /**
     * Scans registered MAIN sessions for one whose {@code gateKey} carries {@code |t:<key>} and
     * matches the user+agent pair. Returns {@code null} if no match.
     */
    private SessionEntry findSessionByConversationId(String agentId, String key, String userId) {
        if (key == null || key.isBlank()) return null;
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(userId, e.userId())) continue;
            if (!sessionMatchesAgent(e, gatewayAgentId)) continue;
            if (key.equals(extractConversationId(e.gateKey()))) {
                return e;
            }
        }
        return null;
    }

    /**
     * 会话与 Agent 的匹配
     * 确保你查的会话确实属于你指定的 Agent，不会把 Agent A 的会话误当成 Agent B 的。
     * 从 gateKey 中提取 agentId 片段，和 URL 中的 gatewayAgentId 比较。
     */
    private static boolean sessionMatchesAgent(SessionEntry e, String gatewayAgentId) {
        if (gatewayAgentId == null) return false;
        String gateKey = e.gateKey();
        if (e.kind() == SessionKind.MAIN) {
            return gateKey != null && extractGatewayAgentId(gateKey).equals(gatewayAgentId);
        }
        // For sub/group sessions, gateKey may be unset; userId match upstream is sufficient.
        return gateKey == null || extractGatewayAgentId(gateKey).equals(gatewayAgentId);
    }

    /**
     * Extracts the {@code agentId} value from a canonical gateKey segment of the form
     * {@code |x:agentId=<value>}. Returns an empty string if no such segment is present.
     */
    private static String extractGatewayAgentId(String gateKey) {
        String needle = "|x:agentId=";
        int i = gateKey.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        return end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
    }

    /**
     * Extracts the conversationId (the threadId portion of {@link
     * io.agentscope.harness.agent.gateway.MsgContext}) from a canonical gateKey segment of the
     * form {@code |t:<value>}. Returns {@code null} when the gateKey is missing or has no thread
     * segment — pre-multi-session sessions live with a {@code null} conversationId and can still be
     * addressed by their storage key.
     */
    static String extractConversationId(String gateKey) {
        if (gateKey == null) return null;
        String needle = "|t:";
        int i = gateKey.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        String val = end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
        return val.isEmpty() ? null : val;
    }

    /**
     * 最后一条消息预览
     *从会话日志中解析出所有轮次，从后往前找第一条有内容的消息，截取前 200 字作为预览。
     * 通俗理解：微信聊天列表上每行显示的那句"最后一条消息"。
     */
    private String lastMessagePreview(String agentId, SessionEntry entry) {
        try {
            String content = readSessionLogContent(agentId, entry);
            if (content == null || content.isEmpty()) {
                return null;
            }
            List<SessionTurnParser.TurnEntry> turns = SessionTurnParser.parse(content);
            for (int i = turns.size() - 1; i >= 0; i--) {
                SessionTurnParser.TurnEntry t = turns.get(i);
                if (t.content() != null && !t.content().isBlank()) {
                    String trimmed = t.content().trim();
                    return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /**
     * 读取会话日志
     * ① 优先读 .log.jsonl 文件（新版格式）
     *    路径: agents//sessions/.log.jsonl
     *
     * ② 其次读 .jsonl 文件（旧版格式）
     *    路径: agents//sessions/.jsonl
     *
     * ③ 最后兜底用 sessionAgentManager.history()（最旧的方式）
     */
    private String readSessionLogContent(String urlAgentId, SessionEntry entry) {
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(entry.userId(), urlAgentId);
        HarnessAgent ha =
                gatewayAgentId != null ? bootstrap.gateway().findAgent(gatewayAgentId) : null;
        if (ha != null) {
            WorkspaceManager wm = ha.getWorkspaceManager();
            String innerAgentId = ha.getName();
            if (wm != null && innerAgentId != null && !innerAgentId.isBlank()) {
                String relLog =
                        "agents/" + innerAgentId + "/sessions/" + entry.sessionId() + ".log.jsonl";
                String fromLog = wm.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), relLog);
                if (fromLog != null && !fromLog.isEmpty()) {
                    return fromLog;
                }
                String relCtx =
                        "agents/" + innerAgentId + "/sessions/" + entry.sessionId() + ".jsonl";
                String fromCtx = wm.readManagedWorkspaceFileUtf8(RuntimeContext.empty(), relCtx);
                if (fromCtx != null && !fromCtx.isEmpty()) {
                    return fromCtx;
                }
            }
        }
        HistoryResult raw = sessionAgentManager.history(entry.sessionKey(), 0);
        if (raw == null || raw.error() != null) {
            return "";
        }
        return raw.content() != null ? raw.content() : "";
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboxEntry(
            String sessionKey,
            String sessionId,
            String agentId,
            String conversationId,
            String label,
            long lastActivityMs,
            String lastMessage,
            boolean unread) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResetResult(String sessionKey, boolean reset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadStateResult(String sessionKey, long readAtMs, boolean unread) {}
}
