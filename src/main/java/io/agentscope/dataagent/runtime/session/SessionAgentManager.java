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
 * MAIN session 状态管理器，供 web 层使用。
 *
 * <p>Phase 3+ (2026-06-30): 子代理生成/执行/通知已全部迁移至 AgentScope 2.0 内置的
 * SubagentsMiddleware。本类仅保留 MAIN session 的注册表操作（查询、重置、维护）。
 *
 * <p>由 web 层（{@code ChatController}、{@code SessionController}、
 * {@code SessionLifecycleScheduler}）使用。
 */
public class SessionAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentManager.class);

    private final WorkspaceManager workspaceManager;
    private final AgentManagerConfig config;
    private final SessionStore sessionStore;

    private final ConcurrentHashMap<String, SessionEntry> sessionsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> labelToSessionKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> gateKeyToSessionKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> childrenByParent =
            new ConcurrentHashMap<>();

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
            restoreFromStore();
        }
    }

    /**
     * 从持久化 {@link SessionStore} 恢复内存中 session 注册表。
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
    //  访问器
    // -----------------------------------------------------------------

    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    public AgentManagerConfig getConfig() {
        return config;
    }

    public SessionStore getSessionStore() {
        return sessionStore;
    }

    // -----------------------------------------------------------------
    //  AgentStateStore 注册表
    // -----------------------------------------------------------------

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

    /** O(1) 查找: 根据 gateway routing key 查找 session。 */
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
     * 重置一个 session：分配新的 {@code sessionId}，同时保留 {@code sessionKey}、所有权和标签。
     * 用于实现 {@code /new}、{@code /reset} 命令以及空闲/每日自动重置。
     */
    public boolean resetSession(String sessionKey) {
        if (sessionKey == null) return false;
        SessionEntry e = sessionsByKey.get(sessionKey);
        if (e == null) return false;
        String newSessionId =
                e.kind() == SessionKind.MAIN
                        ? "main-" + UUID.randomUUID()
                        : "subagent-" + UUID.randomUUID();
        String newPath = resolveSessionFilePath(e.userId(), e.agentId(), newSessionId);
        long now = System.currentTimeMillis();
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
        sessionsByKey.put(sessionKey, reset);
        if (sessionStore != null) {
            sessionStore.save(reset);
        }
        log.info(
                "Session 已重置: sessionKey={}, newSessionId={}, agentId={}",
                sessionKey,
                newSessionId,
                e.agentId());
        return true;
    }

    /** 重置空闲超过 {@code idleMs} 毫秒的 session。 */
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
     * 运行 session 维护：清理过期 session 并限制总条目数。
     * @return 移除的 session 数
     */
    public int runMaintenance() {
        SessionMaintenanceConfig mc = config.maintenanceConfig();
        if (!mc.enabled()) {
            return 0;
        }
        int removed = 0;
        long now = System.currentTimeMillis();

        if (mc.pruneAfterMs() > 0) {
            long cutoff = now - mc.pruneAfterMs();
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
     * 从所有注册表中移除一个 session（内存 + 存储）。
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
