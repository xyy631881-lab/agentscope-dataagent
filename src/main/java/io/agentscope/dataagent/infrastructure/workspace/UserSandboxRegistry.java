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
package io.agentscope.dataagent.infrastructure.workspace;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
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
 * UserSandboxRegistry 是一个"用户专属 Docker 容器池"——它为每个 (userId, agentId)
 * 组合懒创建、缓存复用、空闲回收 Docker 沙箱容器，实现多租户的文件系统隔离。
 *
 * 比喻：酒店式公寓前台，住客（用户）来的时候给他开一间房（容器），走的时候不退房（缓存复用），
 * 但空闲超过 TTL 就自动退房（回收）。
 */
public final class UserSandboxRegistry implements SandboxPool {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxRegistry.class);

    private final SandboxClient<DockerSandboxClientOptions> client;
    private final SharedWorkspaceProjection projection;
    private final Duration idleTtl;
    private final SandboxLifecycleObserver lifecycleObserver;
    //entries —— 主缓存表，key 是 (userId, agentId)，value 是容器 Entry，ConcurrentHashMap 保证多线程安全。
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;

    /**
     * @param client backend client used to {@link SandboxClient#create create} new sandboxes
     * @param projection builds the {@link WorkspaceSpec} that mounts per-agent shared seed
     *     content (AGENTS.md / skills/ / subagents/ / knowledge/) into each fresh container.
     *     Encapsulates the host-path layout so the registry can focus on container pooling.
     * @param idleTtl how long a sandbox may sit unused before {@link #evictIdle()} closes it
     * @param evictionPollInterval how often the background scheduler checks for idle sandboxes
     * @param lifecycleObserver observer that persists sandbox lifecycle events to DB;
     *     handles all DB interaction so the registry can focus on container pooling
     */
    public UserSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> client,
            SharedWorkspaceProjection projection,
            Duration idleTtl,
            Duration evictionPollInterval,
            SandboxLifecycleObserver lifecycleObserver) {
        this.client = Objects.requireNonNull(client, "client");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl");
        this.lifecycleObserver = lifecycleObserver;
        long pollMs =
                Math.max(
                        1_000L,
                        Objects.requireNonNull(evictionPollInterval, "evictionPollInterval")
                                .toMillis());
        this.evictor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "UserSandboxRegistry-evictor");
                            t.setDaemon(true);
                            return t;
                        });
        //定时任务驱动
        this.evictor.scheduleWithFixedDelay(
                this::evictIdleQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
        log.info(
                "UserSandboxRegistry initialised: idleTtl={}, evictionPoll={}",
                idleTtl,
                evictionPollInterval);
    }

    /**
     * borrow(userId, agentId) — 借一个沙箱（核心入口）
     * 比喻：没空房就新开一间，有就直接用。
     * compute 是原子操作：同一个 key 同时被两个线程调 borrow，
     * 只会创建一次容器，不会重复。这是关键——避免并发下重复开容器。
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
                                existing.touch();   // 已有 → 刷新访问时间
                                return existing;
                            }
                            return new Entry(createAndStart(k));  // 没有 → 创建新的
                        });
        return entry.sandbox;
    }

    /**
     * invalidate(userId, agentId) — 作废沙箱（强制重建）
     * 用途：MarketContributionService 审批通过一个贡献时，
     * 需要让所有用户的沙箱重建，以便加载新的共享内容（skills/knowledge 等）。
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
                continue;  // userId 非 null → 只清该用户的沙箱
            }
            it.remove();  //清该 Agent 所有用户的
            closeQuietly(k, e.getValue().sandbox, "invalidate");
            removed++;
        }
        if (removed > 0) {
            log.info(
                    "[sandbox-registry] invalidated {} sandbox(es) for agentId={}, userId={}",
                    removed,
                    agentId,
                    userId == null ? "(all)" : userId);
        }
    }

    /**
     * evictIdle() — 回收空闲沙箱
     * 机制：后台定时线程每隔 evictionPollInterval（默认 60 秒）扫一次，
     * 超过 idleTtl（默认 15 分钟）没被访问的容器自动关闭。
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
                            return true;
                        });
    }

    private void evictIdleQuietly() {
        try {
            evictIdle();
        } catch (RuntimeException ex) {
            log.warn("[sandbox-registry] eviction sweep failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * shutdownAll() — 关闭所有沙箱
     * 用途：应用关闭时，确保所有沙箱都被关闭，释放资源。
     */
    @PreDestroy
    public void shutdownAll() {
        evictor.shutdownNow();
        for (Map.Entry<Key, Entry> e : entries.entrySet()) {
            closeQuietly(e.getKey(), e.getValue().sandbox, "shutdown");
        }
        entries.clear();
    }

    /**
     * createAndStart(key) — 创建并启动沙箱
     * 用途：首次借沙箱时，创建并启动沙箱。
     */
    private Sandbox createAndStart(Key key) {
        DockerSandboxClientOptions options = new DockerSandboxClientOptions();
        // 1. 构建工作空间规格（挂载共享内容）—— 委托给 SharedWorkspaceProjection
        WorkspaceSpec ws = projection.buildSpec(key.userId(), key.agentId());
        // 2. 创建 Docker 容器
        Sandbox sandbox = client.create(ws, new NoopSnapshotSpec(), options);
        try {
            // 3. 启动容器
            sandbox.start();
        } catch (Exception startErr) {
            try {
                sandbox.close();  // start 失败会先 close 半成品容器，避免泄漏。这是个好设计——创建失败不留残骸。
            } catch (Exception closeErr) {
                log.warn(
                        "[sandbox-registry] failed to close half-started sandbox for {}: {}",
                        key,
                        closeErr.getMessage());
            }
            throw new IllegalStateException(
                    "Failed to start sandbox for " + key + ": " + startErr.getMessage(), startErr);
        }
        log.info("[sandbox-registry] started sandbox for {}", key);

        // 记录生命周期到 DB
        recordLifecycle(key, sandbox);

        return sandbox;
    }

    /**
     * 通知观察者记录沙箱生命周期。
     *
     * <p><b>Docker adapter layer:</b> This method casts {@code sandbox.getState()} to
     * {@link DockerSandboxState} to extract the container ID, name, and image. This is
     * Docker-specific, but it is acceptable here because this entire class is the Docker-based
     * {@code SandboxPool} implementation — the registry is wired to a
     * {@code DockerSandboxClient} and creates Docker containers exclusively. If a non-Docker
     * backend is ever needed, a separate {@code SandboxPool} implementation should be provided
     * rather than parameterising this cast with a strategy.
     *
     * <p>Registry 本身不再直接操作 DB——所有 DB 记录的读写都委托给 lifecycleObserver。
     */
    private void recordLifecycle(Key key, Sandbox sandbox) {
        try {
            DockerSandboxState state = (DockerSandboxState) sandbox.getState();
            lifecycleObserver.onCreated(
                    key.userId(),
                    key.agentId(),
                    state.getContainerId(),
                    state.getContainerName(),
                    state.getImage());
        } catch (Exception e) {
            log.warn("[sandbox-lifecycle] 记录生命周期失败: {}", e.getMessage());
        }
    }

    //关闭失败只 warn 不抛异常——避免一个容器关失败导致整个清理循环中断。
    private void closeQuietly(Key key, Sandbox sandbox, String reason) {
        try {
            sandbox.close();
            log.info("[sandbox-registry] closed sandbox for {} ({})", key, reason);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-registry] failed to close sandbox for {} ({}): {}",
                    key,
                    reason,
                    e.getMessage(),
                    e);
        }
        // 委托给观察者更新 DB 生命周期记录
        lifecycleObserver.onClosed(key.userId(), key.agentId());
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
    }

    /**
     * 每个用户 × 每个 Agent = 一个独立容器。
     * Alice 用数据分析师是一个容器，Alice 用报表生成器是另一个容器。
     * */
    public record Key(String userId, String agentId) {
        public Key {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }

    private static final class Entry {
        final Sandbox sandbox;
        volatile long lastAccessMs;  // 最后访问时间，用于空闲回收，
        // 为啥要记 lastAccessMs：回收时按这个判断"空多久了"。
        // volatile 是因为多线程读写（borrow/evictor 同时跑）。

        Entry(Sandbox sandbox) {
            this.sandbox = sandbox;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
