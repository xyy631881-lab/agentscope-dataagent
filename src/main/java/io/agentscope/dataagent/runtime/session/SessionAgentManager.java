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
package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话的 CRUD + 维护
 * 系统的会话调度中心——管理所有"用户与 Agent 对话"的会话记录，负责会话的注册、查询、重置、清理和持久化。
 * ChatController
 *   ├── currentSession() → findByGateKey()     ← 查会话是否存在
 *   ├── executeChatStream() → (网关自动注册会话)  ← 会话由网关创建
 *   └── handleSlashCommand() → resetSession()   ← /reset 命令
 *
 * SessionController
 *   ├── inbox() → allSessions()                 ← 列出所有会话
 *   ├── turns() → getSession()                  ← 查看对话详情
 *   ├── reset() → resetSession()                ← 重置会话
 *   ├── markRead() → getSession()               ← 标记已读
 *   └── delete() → removeSession()              ← 删除会话
 *
 * SandboxReaperService
 *   └── (间接) ← 生命周期记录由 UserSandboxRegistry 写入
 */
public class SessionAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentManager.class);

    private final WorkspaceManager workspaceManager;
    private final AgentManagerConfig config;
    private final SessionStore sessionStore;

    private final ConcurrentHashMap<String, SessionEntry> sessionsByKey = new ConcurrentHashMap<>();  //主索引，按主键查会话
    private final ConcurrentHashMap<String, String> labelToSessionKey = new ConcurrentHashMap<>();  //按标签名查会话
    private final ConcurrentHashMap<String, String> gateKeyToSessionKey = new ConcurrentHashMap<>();  //按网关路由键查会话（ChatController 用）
    private final ConcurrentHashMap<String, List<String>> childrenByParent = new ConcurrentHashMap<>();  //查子代理会话

    /**
     * @param workspaceManager workspace 路径解析
     * @param config 维护配置
     * @param sessionStore 持久化 session 注册表；可以为 null
     */
    public SessionAgentManager(
            WorkspaceManager workspaceManager,
            AgentManagerConfig config,
            SessionStore sessionStore) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
        this.config = Objects.requireNonNull(config, "config");
        this.sessionStore = sessionStore;
        if (sessionStore != null) {
            restoreFromStore();  // 从数据库恢复所有会话到内存
        }
    }

    /**
     * 从从持久化存储中读取所有会话记录，重建四个内存索引。应用重启后，之前的会话不会丢失。
     */
    private void restoreFromStore() {
        for (SessionStore.StoredEntry stored : sessionStore.listAll()) {
            SessionEntry entry = stored.toSessionEntry();
            sessionsByKey.put(entry.sessionKey(), entry);
            if (entry.label() != null && !entry.label().isBlank()) {
                labelToSessionKey.put(entry.label().toLowerCase(), entry.sessionKey());
            }
            if (entry.gateKey() != null && !entry.gateKey().isBlank()) {
                gateKeyToSessionKey.put(entry.gateKey(), entry.sessionKey());
            }
            if (entry.spawnedBy() != null && !entry.spawnedBy().isBlank()) {
                childrenByParent
                        .computeIfAbsent(entry.spawnedBy(), k -> new ArrayList<>())
                        .add(entry.sessionKey());
            }
        }
        log.info("从存储恢复了 {} 个 session", sessionsByKey.size());
        runMaintenance();
    }

    // -----------------------------------------------------------------
    //  AgentStateStore 注册表
    // -----------------------------------------------------------------

    //解析会话 key（支持别名）
    public Optional<String> resolveSessionKey(String keyOrLabel) {
        if (keyOrLabel == null || keyOrLabel.isBlank()) {
            return Optional.empty();
        }
        String trimmed = keyOrLabel.trim();
        if (sessionsByKey.containsKey(trimmed)) {
            return Optional.of(trimmed);
        }
        String byLabel = labelToSessionKey.get(trimmed.toLowerCase());
        if (byLabel != null) {
            return Optional.of(byLabel);
        }
        return Optional.empty();
    }

    public boolean exists(String sessionKey) {
        return sessionKey != null && sessionsByKey.containsKey(sessionKey.trim());
    }

    public Optional<SessionEntry> getSession(String sessionKey) {
        if (sessionKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionsByKey.get(sessionKey.trim()));
    }

    /** O(1) 查找:  按网关路由键查会话 */
    public Optional<SessionEntry> findByGateKey(String gateKey, String userId) {
        if (gateKey == null) return Optional.empty();
        String key = gateKeyToSessionKey.get(gateKey);
        if (key == null) return Optional.empty();
        SessionEntry e = sessionsByKey.get(key);
        if (e == null) return Optional.empty();
        if (userId != null && !Objects.equals(userId, e.userId())) return Optional.empty();
        return Optional.of(e);
    }

    public Collection<SessionEntry> allSessions() {
        return sessionsByKey.values();
    }

    // -----------------------------------------------------------------
    //  AgentStateStore 重置
    // -----------------------------------------------------------------

    /**
     * 重置就像"换个新本子"——聊天窗口还在，但 Agent 的记忆被清空了，从白纸重新开始。旧的本子（日志文件）还在磁盘上，不会被删。
     */
    public boolean resetSession(String sessionKey) {
        if (sessionKey == null) return false;
        SessionEntry e = sessionsByKey.get(sessionKey);
        if (e == null) return false;
        // 分配新的 sessionId
        String newSessionId =
                e.kind() == SessionKind.MAIN
                        ? "main-" + UUID.randomUUID()
                        : "subagent-" + UUID.randomUUID();
        // 算出新的文件路径
        String newPath = resolveSessionFilePath(e.userId(), e.agentId(), newSessionId);
        long now = System.currentTimeMillis();
        // 构造新的 SessionEntry（保留 sessionKey、userId、label 等，只换 sessionId 和时间）
        SessionEntry reset =
                new SessionEntry(
                        e.sessionKey(),
                        e.agentId(),
                        newSessionId,
                        e.label(),
                        e.kind(),
                        e.spawnedBy(),
                        e.spawnDepth(),
                        now,
                        now,
                        newPath,
                        e.spawnRunId(),
                        e.gateKey(),
                        e.userId());
        sessionsByKey.put(sessionKey, reset);  // 覆盖旧记录
        if (sessionStore != null) {
            sessionStore.save(reset);  // 持久化
        }
        log.info(
                "Session 已重置: sessionKey={}, newSessionId={}, agentId={}",
                sessionKey,
                newSessionId,
                e.agentId());
        return true;
    }

    /** 超过 idleMs 没活跃的会话，自动重置。防止 Agent 的上下文窗口被过长的历史撑爆。 */
    public int resetIdleSessions(long idleMs) {
        if (idleMs <= 0) return 0;
        long cutoff = System.currentTimeMillis() - idleMs;
        int n = 0;
        for (SessionEntry e : new ArrayList<>(sessionsByKey.values())) {
            if (e.lastActivityMs() < cutoff) {
                if (resetSession(e.sessionKey())) n++;
            }
        }
        if (n > 0) {
            log.info("空闲重置: {} 个 session（空闲 > {} ms）", n, idleMs);
        }
        return n;
    }

    /** 无条件重置所有活跃 session。 */
    public int resetAllSessions() {
        int n = 0;
        for (SessionEntry e : new ArrayList<>(sessionsByKey.values())) {
            if (resetSession(e.sessionKey())) n++;
        }
        if (n > 0) {
            log.info("每日重置: {} 个 session 已重置", n);
        }
        return n;
    }

    // -----------------------------------------------------------------
    //  AgentStateStore 维护
    // -----------------------------------------------------------------

    /**
     * 维护清理
     * 过期清理：超过 N 天没活跃的会话直接删掉
     * 总数限制：最多保留 N 个会话，超出的按活跃时间排序删除
     */
    public int runMaintenance() {
        SessionMaintenanceConfig mc = config.maintenanceConfig();
        if (!mc.enabled()) {
            return 0;
        }
        int removed = 0;
        long now = System.currentTimeMillis();
        // ① 清理过期会话
        if (mc.pruneAfterMs() > 0) {
            long cutoff = now - mc.pruneAfterMs();  // 删除 lastActivityMs < cutoff 的会话
            List<String> staleKeys =
                    sessionsByKey.values().stream()
                            .filter(e -> e.lastActivityMs() < cutoff)
                            .map(SessionEntry::sessionKey)
                            .collect(Collectors.toList());
            for (String key : staleKeys) {
                removeSession(key);
                removed++;
            }
        }
        // ② 限制总数
        if (mc.maxEntries() > 0 && sessionsByKey.size() > mc.maxEntries()) {
            List<SessionEntry> sorted =
                    sessionsByKey.values().stream()
                            .sorted(Comparator.comparingLong(SessionEntry::lastActivityMs))
                            .collect(Collectors.toList());
            int toRemove = sorted.size() - mc.maxEntries();
            for (int i = 0; i < toRemove && i < sorted.size(); i++) {
                removeSession(sorted.get(i).sessionKey());
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Session 维护: 移除了 {} 个 session", removed);
        }
        return removed;
    }

    /**
     *  删除会话
     *  四个索引全部清理，确保没有残留引用。持久化存储也同步删除。
     */
    public void removeSession(String sessionKey) {
        SessionEntry entry = sessionsByKey.remove(sessionKey);
        if (entry == null) {
            return;
        }
        if (entry.label() != null) {
            labelToSessionKey.remove(entry.label().toLowerCase());
        }
        if (entry.spawnedBy() != null) {
            List<String> siblings = childrenByParent.get(entry.spawnedBy());
            if (siblings != null) {
                siblings.remove(sessionKey);
            }
        }
        childrenByParent.remove(sessionKey);
        if (sessionStore != null) {
            sessionStore.remove(sessionKey);
        }
    }

    // -----------------------------------------------------------------
    //  AgentStateStore 查询 / 可观测性
    // -----------------------------------------------------------------
    // 读取对话历史记录
    public HistoryResult history(String sessionKeyOrLabel, int limit) {
        Optional<String> resolved = resolveSessionKey(sessionKeyOrLabel);
        if (resolved.isEmpty()) {
            return new HistoryResult(null, null, null, "未知 session: " + sessionKeyOrLabel);
        }
        Optional<SessionEntry> opt = getSession(resolved.get());
        if (opt.isEmpty()) {
            return new HistoryResult(null, null, null, "未知 session: " + sessionKeyOrLabel);
        }
        SessionEntry entry = opt.get();
        Path path = Path.of(entry.sessionFilePath());
        if (!Files.isRegularFile(path)) {
            return new HistoryResult(entry.sessionKey(), entry.sessionFilePath(), "", null);
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (limit > 0) {
                //tailLines 的作用：如果对话历史很长（几万行），只取最后 N 行，避免把整个大文件都加载到内存。
                content = tailLines(content, limit);
            }
            return new HistoryResult(entry.sessionKey(), entry.sessionFilePath(), content, null);
        } catch (Exception e) {
            return new HistoryResult(
                    entry.sessionKey(), entry.sessionFilePath(), null, e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  内部辅助方法
    // -----------------------------------------------------------------

    private static String tailLines(String content, int maxLines) {
        String[] lines = content.split("\n", -1);
        if (lines.length <= maxLines) {
            return content;
        }
        return String.join(
                "\n", java.util.Arrays.copyOfRange(lines, lines.length - maxLines, lines.length));
    }

    /** 解析给定用户、Agent 和 session 的 session 文件路径。 */
    public String resolveSessionFilePath(String userId, String agentId, String sessionId) {
        RuntimeContext rc =
                userId != null && !userId.isBlank()
                        ? RuntimeContext.builder().userId(userId).build()
                        : RuntimeContext.empty();
        return workspaceManager.resolveSessionFile(rc, agentId, sessionId).toString();
    }
}
