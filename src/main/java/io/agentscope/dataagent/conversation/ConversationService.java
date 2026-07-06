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
package io.agentscope.dataagent.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.catalog.AgentLifecycleService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentscopeConfig;
import io.agentscope.dataagent.runtime.config.SessionLifecycleConfig;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.runtime.session.SessionMaintenanceConfig;
import io.agentscope.dataagent.web.session.SessionTurnParser;
import io.agentscope.dataagent.web.session.SessionTurnParser.TurnEntry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final ObjectMapper MIGRATION_MAPPER = new ObjectMapper();

    private final SessionEntityRepository sessionRepo;
    private final SessionReadStateRepository readStateRepo;
    private final DataAgentBootstrap bootstrap;
    private final AgentLifecycleService lifecycleService;
    private final SessionMaintenanceConfig maintenanceConfig;

    public ConversationService(
            SessionEntityRepository sessionRepo,
            SessionReadStateRepository readStateRepo,
            DataAgentBootstrap bootstrap,
            AgentLifecycleService lifecycleService) {
        this.sessionRepo = sessionRepo;
        this.readStateRepo = readStateRepo;
        this.bootstrap = bootstrap;
        this.lifecycleService = lifecycleService;
        this.maintenanceConfig = resolveMaintenanceConfig(bootstrap.loadedConfig());
        migrateFromSessionsJson();
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
    public SessionEntry findSessionByConversationId(String agentId, String key, String userId) {
        if (key == null || key.isBlank()) return null;
        String gatewayAgentId = lifecycleService.peekGatewayAgentId(userId, agentId);
        List<SessionEntity> mains = sessionRepo.findByKind(SessionKind.MAIN.getValue());
        for (SessionEntity e : mains) {
            if (!Objects.equals(userId, e.getUserId())) continue;
            if (!sessionMatchesAgent(e.toEntry(), gatewayAgentId)) continue;
            if (key.equals(extractConversationId(e.getGateKey()))) {
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
                || !sessionMatchesAgent(entry, gatewayAgentId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Access denied");
        }
        return entry;
    }

    /** 判断会话是否属于指定的 gatewayAgent。 */
    static boolean sessionMatchesAgent(SessionEntry e, String gatewayAgentId) {
        if (gatewayAgentId == null) return false;
        String gateKey = e.gateKey();
        if (e.kind() == SessionKind.MAIN) {
            return gateKey != null && extractGatewayAgentId(gateKey).equals(gatewayAgentId);
        }
        return gateKey == null || extractGatewayAgentId(gateKey).equals(gatewayAgentId);
    }

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
            if (!sessionMatchesAgent(e.toEntry(), gatewayAgentId)) continue;
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
                            extractConversationId(e.getGateKey()),
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
        String content = readSessionLogContent(agentId, entry);
        return SessionTurnParser.parse(content != null ? content : "");
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
            RuntimeContext rc =
                    userId != null && !userId.isBlank()
                            ? RuntimeContext.builder().userId(userId).build()
                            : RuntimeContext.empty();
            return ha.getWorkspaceManager().resolveSessionFile(rc, agentId, sessionId).toString();
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
        List<SessionEntity> all = sessionRepo.findAll();
        int n = 0;
        for (SessionEntity e : all) {
            if (e.getLastActivityMs() < cutoff) {
                if (resetSessionByKey(e.getSessionKey())) n++;
            }
        }
        if (n > 0) {
            log.info("空闲重置: {} 个 session（空闲 > {} ms）", n, idleMs);
        }
        return n;
    }

    /** 无条件重置所有会话。 */
    @Transactional
    public int resetAllSessions() {
        List<SessionEntity> all = sessionRepo.findAll();
        int n = 0;
        for (SessionEntity e : all) {
            if (resetSessionByKey(e.getSessionKey())) n++;
        }
        if (n > 0) {
            log.info("每日重置: {} 个 session 已重置", n);
        }
        return n;
    }

    /** 维护清理：过期删除 + 总数限制。 */
    @Transactional
    public int runMaintenance() {
        if (!maintenanceConfig.enabled()) {
            return 0;
        }
        int removed = 0;
        long now = System.currentTimeMillis();

        // ① 清理过期会话
        if (maintenanceConfig.pruneAfterMs() > 0) {
            long cutoff = now - maintenanceConfig.pruneAfterMs();
            List<SessionEntity> all = sessionRepo.findAll();
            for (SessionEntity e : all) {
                if (e.getLastActivityMs() < cutoff) {
                    sessionRepo.delete(e);
                    removed++;
                }
            }
        }

        // ② 限制总数
        if (maintenanceConfig.maxEntries() > 0) {
            List<SessionEntity> all = sessionRepo.findAll();
            if (all.size() > maintenanceConfig.maxEntries()) {
                all.sort(Comparator.comparingLong(SessionEntity::getLastActivityMs));
                int toRemove = all.size() - maintenanceConfig.maxEntries();
                for (int i = 0; i < toRemove && i < all.size(); i++) {
                    sessionRepo.delete(all.get(i));
                    removed++;
                }
            }
        }

        if (removed > 0) {
            log.info("Session 维护: 移除了 {} 个 session", removed);
        }
        return removed;
    }

    // ==============================================================
    //  GateKey 解析工具方法
    // ==============================================================

    /** 从 gateKey 中提取 agentId（格式 |x:agentId=value）。 */
    static String extractGatewayAgentId(String gateKey) {
        String needle = "|x:agentId=";
        int i = gateKey.indexOf(needle);
        if (i < 0) return "";
        int start = i + needle.length();
        int end = gateKey.indexOf('|', start);
        return end < 0 ? gateKey.substring(start) : gateKey.substring(start, end);
    }

    /** 从 gateKey 中提取 conversationId（格式 |t:value）。 */
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

    // ==============================================================
    //  启动迁移：sessions.json → JPA
    // ==============================================================

    /**
     * 如果 JPA 表为空且 sessions.json 存在，则将旧数据迁移到 JPA。
     * 这是一次性迁移，迁移完成后 sessions.json 不再使用。
     */
    private void migrateFromSessionsJson() {
        try {
            if (sessionRepo.count() > 0) {
                return; // JPA 已有数据，跳过迁移
            }
            Path storeFile = resolveSessionsJsonPath();
            if (storeFile == null || !Files.isRegularFile(storeFile)) {
                return; // 无 sessions.json，跳过
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) return;
            Map<String, StoredSessionEntry> loaded =
                    MIGRATION_MAPPER.readValue(
                            json,
                            new TypeReference<LinkedHashMap<String, StoredSessionEntry>>() {});
            if (loaded == null || loaded.isEmpty()) return;
            int count = 0;
            for (StoredSessionEntry se : loaded.values()) {
                if (se == null || se.sessionKey == null) continue;
                SessionEntity entity = new SessionEntity();
                entity.setSessionKey(se.sessionKey);
                entity.setAgentId(se.agentId);
                entity.setSessionId(se.sessionId);
                entity.setLabel(se.label);
                entity.setKind(se.kind != null ? se.kind : "main");
                entity.setSpawnedBy(se.spawnedBy);
                entity.setSpawnDepth(se.spawnDepth);
                entity.setCreatedAtMs(se.createdAtMs);
                entity.setLastActivityMs(se.lastActivityMs);
                entity.setSessionFilePath(se.sessionFilePath);
                entity.setSpawnRunId(se.spawnRunId);
                entity.setGateKey(se.gateKey);
                entity.setUserId(se.userId);
                sessionRepo.save(entity);
                count++;
            }
            log.info("从 sessions.json 迁移了 {} 个 session 到 JPA", count);
        } catch (Exception e) {
            log.warn("sessions.json 迁移失败（不影响启动）: {}", e.getMessage());
        }
    }

    private Path resolveSessionsJsonPath() {
        try {
            var fileConfig = bootstrap.loadedConfig();
            var agents = fileConfig != null ? fileConfig.getAgents() : null;
            var main = fileConfig != null ? fileConfig.getMain() : null;
            String mainId = (main != null && !main.isBlank()) ? main.trim() : null;
            if (agents != null && mainId != null && agents.containsKey(mainId)) {
                var entry = agents.get(mainId);
                if (entry != null && entry.getWorkspace() != null && !entry.getWorkspace().isBlank()) {
                    return bootstrap.cwd().resolve(entry.getWorkspace()).resolve("sessions.json");
                }
            }
            return bootstrap.cwd().resolve(".agentscope").resolve("workspace").resolve("sessions.json");
        } catch (Exception e) {
            return null;
        }
    }

    private static SessionMaintenanceConfig resolveMaintenanceConfig(AgentscopeConfig fileConfig) {
        var sessionCfg = fileConfig != null ? fileConfig.getSession() : null;
        if (sessionCfg == null || sessionCfg.getMaintenance() == null) {
            return SessionMaintenanceConfig.disabled();
        }
        var m = sessionCfg.getMaintenance();
        String mode = m.getMode();
        if (mode == null || mode.isBlank() || "off".equalsIgnoreCase(mode)) {
            return SessionMaintenanceConfig.disabled();
        }
        long pruneAfterMs = m.pruneAfterMs();
        int maxEntries = m.getMaxEntries() != null ? m.getMaxEntries() : 0;
        return SessionMaintenanceConfig.enabled(pruneAfterMs, maxEntries);
    }

    // ==============================================================
    //  DTO
    // ==============================================================

    /** sessions.json 旧格式的反序列化 record（迁移用）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredSessionEntry(
            String sessionKey,
            String agentId,
            String sessionId,
            String label,
            String kind,
            String spawnedBy,
            int spawnDepth,
            long createdAtMs,
            long lastActivityMs,
            String sessionFilePath,
            String spawnRunId,
            String gateKey,
            String userId) {}

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
