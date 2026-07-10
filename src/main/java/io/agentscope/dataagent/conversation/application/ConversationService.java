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
package io.agentscope.dataagent.conversation.application;
import io.agentscope.dataagent.conversation.api.ChatController;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntity;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntityRepository;
import io.agentscope.dataagent.conversation.infrastructure.SessionReadStateEntity;
import io.agentscope.dataagent.conversation.infrastructure.SessionReadStateRepository;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentscopeConfig;
import io.agentscope.dataagent.conversation.domain.SessionEntry;
import io.agentscope.dataagent.conversation.domain.SessionKind;
import io.agentscope.dataagent.conversation.domain.SessionMaintenanceConfig;
import io.agentscope.dataagent.conversation.application.SessionTurnParser.TurnEntry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话域的核心服务层，替代原先的 SessionAgentManager + SessionStore + SessionReadStateStore。
 *
 * <p>三层架构的 Service 层：Controller 只管 HTTP 收发，Repository 只管数据库读写，
 * 业务逻辑（会话查找、权限校验、inbox 组装、日志读取、维护清理）全部在这里。
 *
 * <p>持久化从 sessions.json + ConcurrentHashMap 迁移到 JPA（MySQL/H2），
 * 消除了 JSON 文件读写和内存索引，查询直接走数据库索引。
 *
 * <p>启动迁移（sessions.json → JPA）已提取到 {@link ConversationMigrationService}。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final SessionEntityRepository sessionRepo;
    private final SessionReadStateRepository readStateRepo;
    private final DataAgentBootstrap bootstrap;
    private final AgentLifecycleService lifecycleService;
    private final SessionMaintenanceConfig maintenanceConfig;
    private final io.agentscope.core.state.AgentStateStore agentStateStore;

    public ConversationService(
            SessionEntityRepository sessionRepo,
            SessionReadStateRepository readStateRepo,
            DataAgentBootstrap bootstrap,
            AgentLifecycleService lifecycleService,
            ConversationMigrationService migrationService,
            io.agentscope.core.state.AgentStateStore agentStateStore) {
        this.sessionRepo = sessionRepo;
        this.readStateRepo = readStateRepo;
        this.bootstrap = bootstrap;
        this.lifecycleService = lifecycleService;
        this.maintenanceConfig = ConversationSupport.resolveMaintenanceConfig(bootstrap.loadedConfig());
        this.agentStateStore = agentStateStore;
        // migrationService is injected to guarantee it initializes (and runs migration) first
    }

    // ==============================================================
    //  会话查找
    // ==============================================================

    /** 按 sessionKey 查找会话。 */
    public Optional<SessionEntry> findByKey(String sessionKey) {
        if (sessionKey == null) return Optional.empty();
        return sessionRepo.findBySessionKey(sessionKey.trim()).map(SessionEntity::toEntry);
    }

    /** 按网关路由键查找会话（ChatController 用）。 */
    public Optional<SessionEntry> findByGateKey(String gateKey, String userId) {
        if (gateKey == null) return Optional.empty();
        return sessionRepo
                .findByGateKeyAndUserId(gateKey, userId)
                .map(SessionEntity::toEntry);
    }

    /** 按 conversationId 查找会话（前端只知道 conversationId）。 */
    public SessionEntry createSessionRecord(
            String agentId, String sessionKey, String userId, String gateKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        Optional<SessionEntity> existing = sessionRepo.findBySessionKey(sessionKey.trim());
        if (existing.isPresent()) {
            SessionEntity e = existing.get();
            e.setLastActivityMs(System.currentTimeMillis());
            sessionRepo.save(e);
            return e.toEntry();
        }

        SessionEntity entity = new SessionEntity();
        entity.setSessionKey(sessionKey.trim());
        entity.setAgentId(agentId);
        entity.setSessionId("main-" + UUID.randomUUID());
        entity.setLabel(null);
        entity.setKind("main");
        entity.setSpawnedBy(null);
        entity.setSpawnDepth(0);
        entity.setCreatedAtMs(System.currentTimeMillis());
        entity.setLastActivityMs(System.currentTimeMillis());
        entity.setSessionFilePath(resolveSessionFilePath(userId, agentId, entity.getSessionId()));
        entity.setSpawnRunId(null);
        entity.setGateKey(gateKey);
        entity.setUserId(userId);
        sessionRepo.save(entity);
        log.info(
                "Created new session record: sessionKey={}, agentId={}, userId={}",
                sessionKey, agentId, userId);
        return entity.toEntry();
    }

    public SessionEntry findSessionByConversationId(String agentId, String key, String userId) {
        if (key == null || key.isBlank()) return null;
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);
        List<SessionEntity> mains = sessionRepo.findByUserIdAndKind(userId, SessionKind.MAIN.getValue());
        for (SessionEntity e : mains) {
            if (!ConversationSupport.sessionMatchesAgent(e.toEntry(), gatewayAgentId)) continue;
            if (key.equals(ConversationSupport.extractConversationId(e.getGateKey()))) {
                return e.toEntry();
            }
        }
        return null;
    }

    /** 检查会话是否存在（用于 currentSession 端点）。 */
    public boolean sessionExists(String agentId, String key, String userId) {
        SessionEntry entry = requireOwnedSession(agentId, key, userId);
        return entry != null;
    }

    // ==============================================================
    //  会话归属校验
    // ==============================================================

    /**
     * 找到会话并校验归属（用户 + Agent 都要匹配）。
     * 支持 sessionKey 和 conversationId 两种 key 格式。
     */
    public SessionEntry requireOwnedSession(String agentId, String key, String userId) {
        SessionEntry entry =
                findByKey(key)
                        .orElseGet(() -> findSessionByConversationId(agentId, key, userId));
        if (entry == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "AgentStateStore not found: " + key);
        }
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);
        if (!Objects.equals(entry.userId(), userId)
                || !ConversationSupport.sessionMatchesAgent(entry, gatewayAgentId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /** 判断会话是否属于指定的 gatewayAgent。 */

    // ==============================================================
    //  Inbox（收件箱）
    // ==============================================================

    /**
     * 组装收件箱列表：按用户+Agent 筛选会话，按最后活跃时间倒序，
     * 附带未读标记和最后一条消息预览。
     */
    @Transactional(readOnly = true)
    public List<InboxEntry> inbox(
            String userId, String agentId, int limit, boolean unreadOnly) {
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);

        List<SessionEntity> all = sessionRepo.findByUserIdOrderByLastActivityMsDesc(userId);
        List<SessionEntity> matched = new ArrayList<>();
        for (SessionEntity e : all) {
            if (!ConversationSupport.sessionMatchesAgent(e.toEntry(), gatewayAgentId)) continue;
            matched.add(e);
        }
        matched.sort(Comparator.comparingLong(SessionEntity::getLastActivityMs).reversed());
        if (matched.size() > limit) {
            matched = matched.subList(0, limit);
        }

        List<InboxEntry> out = new ArrayList<>(matched.size());
        for (SessionEntity e : matched) {
            boolean unread = isUnread(userId, e.getSessionKey(), e.getLastActivityMs());
            if (unreadOnly && !unread) continue;
            String preview = lastMessagePreview(agentId, e.toEntry());
            out.add(
                    new InboxEntry(
                            e.getSessionKey(),
                            e.getSessionId(),
                            e.getAgentId(),
                            ConversationSupport.extractConversationId(e.getGateKey()),
                            e.getLabel(),
                            e.getLastActivityMs(),
                            preview,
                            unread));
        }
        return out;
    }

    // ==============================================================
    //  对话轮次
    // ==============================================================

    /** 读取会话日志并解析为结构化轮次。 */
    @Transactional(readOnly = true)
    public List<TurnEntry> getTurns(String agentId, String key, String userId) {
        SessionEntry entry = requireOwnedSession(agentId, key, userId);
        // 优先从 workspace 读取；若当前请求无沙箱上下文（如 HTTP 会话查询），
        // readSessionLogContent 会抛出 SandboxConfigurationException，此时降级到 AgentStateStore。
        try {
            String content = readSessionLogContent(agentId, entry);
            if (content != null && !content.isEmpty()) {
                return SessionTurnParser.parse(content);
            }
        } catch (Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("从 workspace 读取会话日志失败 (agentId={}, key={})，尝试 AgentStateStore: {}",
                        agentId, key, ex.getMessage());
            }
        }
        // 备选：从 AgentStateStore 读取（workspace 文件可能存储在 Docker 沙箱内，本地文件系统不可见）
        return getTurnsFromAgentStateStore(userId, entry);
    }

    private List<TurnEntry> getTurnsFromAgentStateStore(String userId, SessionEntry entry) {
        if (entry.gateKey() == null || entry.gateKey().isBlank()) {
            return List.of();
        }
        try {
            java.util.Optional<io.agentscope.core.state.AgentState> opt =
                    agentStateStore.get(userId, entry.gateKey(), "agent_state", io.agentscope.core.state.AgentState.class);
            if (opt.isEmpty()) {
                return List.of();
            }
            List<io.agentscope.core.message.Msg> context = opt.get().getContext();
            List<TurnEntry> turns = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < context.size(); i++) {
                io.agentscope.core.message.Msg msg = context.get(i);
                String roleStr = msg.getRole() != null ? msg.getRole().name().toUpperCase() : "UNKNOWN";
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    turns.add(new TurnEntry(
                            "m" + i,           // id
                            null,              // parentId
                            roleStr,           // role
                            text,              // content
                            now,               // timestampMs
                            null,              // toolName
                            null,              // toolInput
                            null               // toolResult
                    ));
                }
            }
            return turns;
        } catch (Exception e) {
            log.warn("从 AgentStateStore 读取历史失败 (gateKey={}): {}", entry.gateKey(), e.getMessage());
            return List.of();
        }
    }

    // ==============================================================
    //  会话重置
    // ==============================================================

    /** 重置会话（清空对话历史，分配新的 sessionId，保留 sessionKey）。 */
    @Transactional
    public ResetResult reset(String agentId, String key, String userId) {
        SessionEntry entry = requireOwnedSession(agentId, key, userId);
        boolean ok = resetSessionByKey(entry.sessionKey());
        return new ResetResult(key, ok);
    }

    /** 按 sessionKey 重置会话（ChatController /reset 命令用）。 */
    @Transactional
    public boolean resetSessionByKey(String sessionKey) {
        if (sessionKey == null) return false;
        Optional<SessionEntity> opt = sessionRepo.findBySessionKey(sessionKey);
        if (opt.isEmpty()) return false;
        SessionEntity e = opt.get();
        SessionKind kind = "main".equals(e.getKind()) ? SessionKind.MAIN : SessionKind.SUBAGENT;
        String newSessionId =
                kind == SessionKind.MAIN
                        ? "main-" + UUID.randomUUID()
                        : "subagent-" + UUID.randomUUID();
        String newPath = resolveSessionFilePath(e.getUserId(), e.getAgentId(), newSessionId);
        long now = System.currentTimeMillis();
        SessionEntity reset = e.copyWithNewSessionId(newSessionId, newPath, now);
        sessionRepo.save(reset);
        log.info(
                "Session 已重置: sessionKey={}, newSessionId={}, agentId={}",
                sessionKey,
                newSessionId,
                e.getAgentId());
        return true;
    }

    // ==============================================================
    //  已读状态
    // ==============================================================

    /** 标记会话为已读，返回已读时间戳。 */
    @Transactional
    public ReadStateResult markRead(String userId, String agentId, String key) {
        SessionEntry entry = requireOwnedSession(agentId, key, userId);
        long readAtMs = markReadInternal(userId, entry.sessionKey());
        return new ReadStateResult(key, readAtMs, false);
    }

    private long markReadInternal(String userId, String sessionKey) {
        long now = System.currentTimeMillis();
        SessionReadStateEntity state =
                readStateRepo
                        .findByUserIdAndSessionKey(userId, sessionKey)
                        .orElseGet(() -> new SessionReadStateEntity(userId, sessionKey, now));
        state.setLastReadAt(now);
        readStateRepo.save(state);
        return now;
    }

    /** 判断会话是否未读（lastActivityMs > lastReadAt）。 */
    public boolean isUnread(String userId, String sessionKey, long lastActivityMs) {
        return lastActivityMs > lastReadAt(userId, sessionKey);
    }

    private long lastReadAt(String userId, String sessionKey) {
        return readStateRepo
                .findByUserIdAndSessionKey(userId, sessionKey)
                .map(SessionReadStateEntity::getLastReadAt)
                .orElse(0L);
    }

    // ==============================================================
    //  会话删除
    // ==============================================================

    @Transactional
    public void deleteSession(String agentId, String key, String userId) {
        SessionEntry entry = requireOwnedSession(agentId, key, userId);
        sessionRepo.deleteBySessionKey(entry.sessionKey());
    }

    // ==============================================================
    //  日志读取
    // ==============================================================

    /**
     * 读取会话日志内容，优先走 WorkspaceManager（新版路径），
     * 回退到直接读 sessionFilePath（旧版路径）。
     */
    public String readSessionLogContent(String urlAgentId, SessionEntry entry) {
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
        // 旧版兜底：直接读 sessionFilePath
        return readSessionFileDirectly(entry);
    }

    private String readSessionFileDirectly(SessionEntry entry) {
        if (entry.sessionFilePath() == null || entry.sessionFilePath().isBlank()) {
            return "";
        }
        try {
            Path path = Path.of(entry.sessionFilePath());
            if (!Files.isRegularFile(path)) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取 session 日志失败: {}", e.getMessage());
            return "";
        }
    }

    /** 从会话日志中提取最后一条消息作为预览（最多 200 字）。 */
    public String lastMessagePreview(String agentId, SessionEntry entry) {
        try {
            String content = readSessionLogContent(agentId, entry);
            if (content == null || content.isEmpty()) {
                return null;
            }
            List<TurnEntry> turns = SessionTurnParser.parse(content);
            for (int i = turns.size() - 1; i >= 0; i--) {
                TurnEntry t = turns.get(i);
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

    // ==============================================================
    //  会话文件路径解析
    // ==============================================================

    public String resolveSessionFilePath(String userId, String agentId, String sessionId) {
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);
        HarnessAgent ha =
                gatewayAgentId != null ? bootstrap.gateway().findAgent(gatewayAgentId) : null;
        if (ha != null && ha.getWorkspaceManager() != null) {
            // 使用 RuntimeContext.empty() 与 readSessionLogContent 保持一致，
            // 避免 userId 被加入路径前缀导致数据库中 session_file_path 与实际文件路径不一致
            return ha.getWorkspaceManager().resolveSessionFile(RuntimeContext.empty(), agentId, sessionId).toString();
        }
        return null;
    }

    // ==============================================================
    //  维护清理
    // ==============================================================

    /** 重置超过 idleMs 没活跃的会话。 */
    @Transactional
    public int resetIdleSessions(long idleMs) {
        if (idleMs <= 0) return 0;
        long cutoff = System.currentTimeMillis() - idleMs;
        List<SessionEntity> idle = sessionRepo.findByLastActivityMsBefore(cutoff);
        if (idle.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        List<SessionEntity> toSave = new ArrayList<>(idle.size());
        for (SessionEntity e : idle) {
            SessionKind kind = "main".equals(e.getKind()) ? SessionKind.MAIN : SessionKind.SUBAGENT;
            String newSessionId =
                    kind == SessionKind.MAIN
                            ? "main-" + UUID.randomUUID()
                            : "subagent-" + UUID.randomUUID();
            String newPath = resolveSessionFilePath(e.getUserId(), e.getAgentId(), newSessionId);
            toSave.add(e.copyWithNewSessionId(newSessionId, newPath, now));
        }
        sessionRepo.saveAll(toSave);
        if (!toSave.isEmpty()) {
            log.info("空闲重置: {} 个 session（空闲 > {} ms）", toSave.size(), idleMs);
        }
        return toSave.size();
    }

    /** 无条件重置所有会话。 */
    @Transactional
    public int resetAllSessions() {
        List<SessionEntity> all = sessionRepo.findAll();
        if (all.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        List<SessionEntity> toSave = new ArrayList<>(all.size());
        for (SessionEntity e : all) {
            SessionKind kind = "main".equals(e.getKind()) ? SessionKind.MAIN : SessionKind.SUBAGENT;
            String newSessionId =
                    kind == SessionKind.MAIN
                            ? "main-" + UUID.randomUUID()
                            : "subagent-" + UUID.randomUUID();
            String newPath = resolveSessionFilePath(e.getUserId(), e.getAgentId(), newSessionId);
            toSave.add(e.copyWithNewSessionId(newSessionId, newPath, now));
        }
        sessionRepo.saveAll(toSave);
        if (!toSave.isEmpty()) {
            log.info("每日重置: {} 个 session 已重置", toSave.size());
        }
        return toSave.size();
    }

    /** 维护清理：过期删除 + 总数限制。 */
    @Transactional
    public int runMaintenance() {
        if (!maintenanceConfig.enabled()) {
            return 0;
        }
        int removed = 0;
        long now = System.currentTimeMillis();

        // ① 清理过期会话（走索引 ix_conv_session_activity_global）
        if (maintenanceConfig.pruneAfterMs() > 0) {
            long cutoff = now - maintenanceConfig.pruneAfterMs();
            List<SessionEntity> expired = sessionRepo.findByLastActivityMsBefore(cutoff);
            if (!expired.isEmpty()) {
                sessionRepo.deleteAllInBatch(expired);
                removed += expired.size();
            }
        }

        // ② 限制总数（只加载需要删除的最旧 N 条，不加载全表）
        if (maintenanceConfig.maxEntries() > 0) {
            long total = sessionRepo.count();
            int toRemove = (int) (total - maintenanceConfig.maxEntries());
            if (toRemove > 0) {
                Pageable page = PageRequest.of(0, toRemove);
                List<SessionEntity> oldest = sessionRepo.findAllByOrderByLastActivityMsAsc(page);
                if (!oldest.isEmpty()) {
                    sessionRepo.deleteAllInBatch(oldest);
                    removed += oldest.size();
                }
            }
        }

        if (removed > 0) {
            log.info("Session 维护: 移除了 {} 个 session", removed);
        }
        return removed;
    }

    // ==============================================================
    //  运行时统计（供 RuntimeController 调用）
    // ==============================================================

    /** 会话总数。 */
    public long sessionCount() {
        return sessionRepo.count();
    }

    /** 有会话记录的去重用户数。 */
    public long userCount() {
        return sessionRepo.countDistinctUserId();
    }

    /** 最近活跃的 N 条会话（按 lastActivityMs 倒序）。 */
    public List<SessionEntity> recentSessions(int limit) {
        return sessionRepo.findAllByOrderByLastActivityMsDesc(
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    // ==============================================================
    //  GateKey 解析工具方法
    // ==============================================================

    /** 从 gateKey 中提取 agentId（格式 |x:agentId=value）。 */

    /** 从 gateKey 中提取 conversationId（格式 |t:value）。 */

    // ==============================================================
    //  配置解析
    // ==============================================================


    // ==============================================================
    //  DTO
    // ==============================================================

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