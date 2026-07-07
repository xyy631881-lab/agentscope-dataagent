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
package io.agentscope.dataagent.workspace.infrastructure;

import io.agentscope.dataagent.workspace.domain.SandboxPool;
import io.agentscope.dataagent.workspace.domain.SharedWorkspaceProjection;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UserSandboxPool 是一个"用户专属沙箱容器池"——它为每个 (userId, agentId)
 * 组合懒创建、缓存复用、空闲回收 Docker 沙箱容器，实现多租户的文件系统隔离。
 *
 * <h2>生命周期已下沉到 AgentScope 2.0 框架</h2>
 *
 * <p>本类不再手写 Docker 容器的创建 / 恢复 / 停止 / 销毁逻辑，而是把"真实生命周期"
 * 委托给框架的 {@link SandboxManager}。每次 {@link #borrow} 都走框架的优先级链路
 * （外部沙箱 → 持久化状态恢复 → 全新创建），容器隔离维度由框架的
 * {@link io.agentscope.harness.agent.sandbox.SandboxIsolationKey}（{@code USER} 作用域 →
 * userId）统一管理，状态落在框架的 {@link SessionSandboxStateStore} 中。这样框架升级
 * Docker 适配层（或换成非 Docker 后端）时，本类无需改动。
 *
 * <h2>与框架原生"每次调用即释放"模型的区别</h2>
 *
 * <p>DataAgent 的浏览器工作区与 Agent 运行需要同一个<em>常驻</em>容器：浏览器 Controller
 * 直接读写容器里的文件，贡献审批通过后需整体重建该 (userId, agentId) 沙箱。因此本池在
 * {@link SandboxManager#acquire acquire} 之后<em>持有</em>该沙箱、不调用
 * {@link SandboxManager#release release}（release 会 stop + 销毁容器），仅在空闲回收 /
 * {@link #invalidate invalidate} / 关闭时才 {@link Sandbox#close() close}。这正是框架
 * {@link SandboxContext} 的 Priority-1 外部沙箱路径所支持的用法——网关仍把持有的沙箱作为
 * {@link SandboxContext#getExternalSandbox()} 注入，框架只复用容器而不销毁它。
 *
 * <h2>应用层仍保留的职责（框架不覆盖，企业多租户所需）</h2>
 * <ul>
 *   <li>常驻持有：浏览器与 Agent 共用同一容器的关键；</li>
 *   <li>空闲回收：后台定时扫描，超过 {@code idleTtl} 未访问即关闭；</li>
 *   <li>invalidate 广播：贡献审批通过后按 agentId 整体重建；</li>
 *   <li>多租户生命周期审计：{@link SandboxLifecycleObserver} 把容器创建/关闭落地到 DB。</li>
 * </ul>
 */
public final class UserSandboxPool implements SandboxPool {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxPool.class);

    private final SandboxClient<DockerSandboxClientOptions> client;
    private final SharedWorkspaceProjection projection;
    private final Duration idleTtl;
    private final SandboxLifecycleObserver lifecycleObserver;

    /**
     * 框架隔离状态的后端。默认用进程内 {@link InMemoryAgentStateStore}，与常驻容器同 pod，
     * 配合"按 userId 粘性负载均衡"——沙箱状态不跨 pod 共享，避免 redis 后端下出现
     * "状态在 A pod、容器在 B pod" 的错配。需要跨 pod 恢复时，注入一个分布式的
     * {@link AgentStateStore}（同时必须配套快照后端，否则容器重建会丢失工作区文件）。
     */
    private final AgentStateStore backingStore;

    /** per-agentId 的框架生命周期管理器（{@link SandboxManager} 与隔离状态存储都按 agentId 绑定）。 */
    private final Map<String, SandboxManager> managersByAgent = new ConcurrentHashMap<>();

    /** 常驻容器缓存表：key 是 (userId, agentId)，value 是容器 Entry。 */
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;

    private static final SandboxSnapshotSpec SNAPSHOT_SPEC = new NoopSnapshotSpec();

    public UserSandboxPool(
            SandboxClient<DockerSandboxClientOptions> client,
            SharedWorkspaceProjection projection,
            Duration idleTtl,
            Duration evictionPollInterval,
            SandboxLifecycleObserver lifecycleObserver) {
        this(
                client,
                projection,
                idleTtl,
                evictionPollInterval,
                lifecycleObserver,
                new InMemoryAgentStateStore());
    }

    /**
     * 允许测试或特殊部署注入自定义隔离状态后端（如 Redis，用于跨 pod 恢复）。
     *
     * @param client              backend client used to create/resume sandboxes
     * @param projection          构建挂载共享种子内容的 {@link WorkspaceSpec}
     * @param idleTtl             沙箱多久未被访问即被回收
     * @param evictionPollInterval 后台回收扫描间隔
     * @param lifecycleObserver   把生命周期事件落地 DB 的观察者
     * @param sandboxStateStore   框架隔离状态后端（默认进程内）
     */
    public UserSandboxPool(
            SandboxClient<DockerSandboxClientOptions> client,
            SharedWorkspaceProjection projection,
            Duration idleTtl,
            Duration evictionPollInterval,
            SandboxLifecycleObserver lifecycleObserver,
            AgentStateStore sandboxStateStore) {
        this.client = Objects.requireNonNull(client, "client");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl");
        this.lifecycleObserver = Objects.requireNonNull(lifecycleObserver, "lifecycleObserver");
        this.backingStore = Objects.requireNonNull(sandboxStateStore, "sandboxStateStore");
        long pollMs =
                Math.max(
                        1_000L,
                        Objects.requireNonNull(evictionPollInterval, "evictionPollInterval")
                                .toMillis());
        this.evictor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "UserSandboxPool-evictor");
                            t.setDaemon(true);
                            return t;
                        });
        this.evictor.scheduleWithFixedDelay(
                this::evictIdleQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
        log.info(
                "UserSandboxPool initialised (lifecycle delegated to framework SandboxManager):"
                        + " idleTtl={}, evictionPoll={}",
                idleTtl,
                evictionPollInterval);
    }

    private SandboxManager managerFor(String agentId) {
        return managersByAgent.computeIfAbsent(
                agentId,
                a -> new SandboxManager(client, new SessionSandboxStateStore(backingStore, a), a));
    }

    private SandboxContext contextFor(String userId, String agentId) {
        return SandboxContext.builder()
                .client(client)
                .clientOptions(new DockerSandboxClientOptions())
                .workspaceSpec(projection.buildSpec(userId, agentId))
                .snapshotSpec(SNAPSHOT_SPEC)
                .isolationScope(IsolationScope.USER)
                .build();
    }

    private RuntimeContext runtimeFor(String userId) {
        return RuntimeContext.builder().userId(userId).build();
    }

    /**
     * borrow(userId, agentId) — 借一个沙箱（核心入口）。
     *
     * <p>缓存命中直接复用并刷新访问时间；缓存未命中则通过框架 {@link SandboxManager#acquire}
     * 创建或恢复容器，启动后<em>持有</em>（不释放），并把它当前（运行中）状态持久化到框架
     * 隔离状态存储，便于进程重启后恢复同一个容器。
     */
    public Sandbox borrow(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Key key = new Key(userId, agentId);
        Entry entry =
                entries.compute(
                        key,
                        (k, existing) -> {
                            if (existing != null) {
                                existing.touch(); // 已有 → 刷新访问时间
                                return existing;
                            }
                            return new Entry(acquireAndStart(k)); // 没有 → 委托框架创建/恢复
                        });
        return entry.sandbox;
    }

    private Sandbox acquireAndStart(Key key) {
        SandboxManager mgr = managerFor(key.agentId());
        SandboxContext ctx = contextFor(key.userId(), key.agentId());
        RuntimeContext rtc = runtimeFor(key.userId());
        try {
            SandboxAcquireResult result = mgr.acquire(ctx, rtc); // 框架决定 恢复 / 创建
            Sandbox sandbox = result.getSandbox();
            sandbox.start();
            // 把当前（运行中）状态持久化，便于跨重启恢复同一容器。
            try {
                mgr.persistState(result, ctx, rtc);
            } catch (Exception e) {
                log.debug("[sandbox-pool] persistState 失败（可忽略）: {}", e.getMessage());
            }
            recordLifecycle(key, sandbox);
            return sandbox;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to start sandbox for " + key + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * invalidate(userId, agentId) — 作废沙箱（强制重建）。
     *
     * <p>用途：MarketContributionService 审批通过一个贡献时，需要让该 agent 下所有用户（或指定
     * 用户）的沙箱重建，以便加载新的共享内容（skills/knowledge 等）。
     */
    public void invalidate(String userId, String agentId) {
        validateSegment("agentId", agentId);
        int removed = 0;
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Key k = e.getKey();
            if (!k.agentId().equals(agentId)) {
                continue;
            }
            if (userId != null && !userId.isBlank() && !k.userId().equals(userId)) {
                continue; // userId 非 null → 只清该用户的沙箱
            }
            it.remove();
            closeQuietly(k, e.getValue().sandbox, "invalidate");
            clearState(k);
            removed++;
        }
        if (removed > 0) {
            log.info(
                    "[sandbox-pool] invalidated {} sandbox(es) for agentId={}, userId={}",
                    removed,
                    agentId,
                    userId == null ? "(all)" : userId);
        }
    }

    /**
     * evictIdle() — 回收空闲沙箱。后台定时线程每隔 evictionPollInterval 扫一次，超过
     * idleTtl 没被访问的容器自动关闭，并清除框架隔离状态。
     */
    void evictIdle() {
        long cutoff = System.currentTimeMillis() - idleTtl.toMillis();
        entries.entrySet()
                .removeIf(
                        e -> {
                            if (e.getValue().lastAccessMs >= cutoff) {
                                return false;
                            }
                            closeQuietly(e.getKey(), e.getValue().sandbox, "idle");
                            clearState(e.getKey());
                            return true;
                        });
    }

    private void evictIdleQuietly() {
        try {
            evictIdle();
        } catch (RuntimeException ex) {
            log.warn("[sandbox-pool] eviction sweep failed: {}", ex.getMessage(), ex);
        }
    }

    /** shutdownAll() — 关闭所有常驻沙箱。应用关闭时由 {@link PreDestroy} 触发。 */
    @PreDestroy
    public void shutdownAll() {
        evictor.shutdownNow();
        for (Map.Entry<Key, Entry> e : entries.entrySet()) {
            closeQuietly(e.getKey(), e.getValue().sandbox, "shutdown");
            clearState(e.getKey());
        }
        entries.clear();
    }

    private void clearState(Key key) {
        try {
            SandboxManager mgr = managerFor(key.agentId());
            mgr.clearState(contextFor(key.userId(), key.agentId()), runtimeFor(key.userId()));
        } catch (Exception e) {
            log.debug("[sandbox-pool] clearState 失败（可忽略）: {}", e.getMessage());
        }
    }

    private void recordLifecycle(Key key, Sandbox sandbox) {
        try {
            if (sandbox.getState() instanceof DockerSandboxState state) {
                lifecycleObserver.onCreated(
                        key.userId(),
                        key.agentId(),
                        state.getContainerId(),
                        state.getContainerName(),
                        state.getImage());
            }
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] 记录生命周期失败: {}", e.getMessage());
        }
    }

    // 关闭失败只 warn 不抛异常——避免一个容器关失败导致整个清理循环中断。
    private void closeQuietly(Key key, Sandbox sandbox, String reason) {
        try {
            sandbox.close(); // = stop() + shutdown()：框架负责优雅停止并移除容器
            log.info("[sandbox-pool] closed sandbox for {} ({})", key, reason);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-pool] failed to close sandbox for {} ({}): {}",
                    key,
                    reason,
                    e.getMessage(),
                    e);
        }
        // 委托观察者更新 DB 生命周期记录
        lifecycleObserver.onClosed(key.userId(), key.agentId());
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
    }

    /**
     * 每个用户 × 每个 Agent = 一个独立容器。Alice 用数据分析师是一个容器，Alice 用报表生成器是
     * 另一个容器。注意：框架的 {@link io.agentscope.harness.agent.sandbox.SandboxIsolationKey}
     * 本身不内嵌 agentId（agentId 只用于隔离状态存储的路径前缀），所以这里保留
     * (userId, agentId) 复合键作为常驻缓存键，以正确区分同一 userId 在不同 agent 下的容器。
     */
    public record Key(String userId, String agentId) {
        public Key {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }

    private static final class Entry {
        final Sandbox sandbox;
        volatile long lastAccessMs; // 最后访问时间，用于空闲回收

        Entry(Sandbox sandbox) {
            this.sandbox = sandbox;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
