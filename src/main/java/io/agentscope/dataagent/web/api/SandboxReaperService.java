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

import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRecord;
import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRecord.Status;
import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 沙箱回收器：定期扫描心跳超时的孤儿容器并清理。
 *
 * <p>覆盖以下场景：
 * <ul>
 *   <li>进程被 {@code kill -9} 强杀，容器变成孤儿</li>
 *   <li>容器内 sidecar 故障，心跳停止</li>
 *   <li>多副本中某副本崩溃，其管理的容器无人认领</li>
 * </ul>
 */
@Component
public class SandboxReaperService {

    private static final Logger log = LoggerFactory.getLogger(SandboxReaperService.class);

    /** 默认 TTL：30 分钟无心跳即视为孤儿。 */
    private static final int DEFAULT_TTL_MINUTES = 30;

    private final SandboxLifecycleRepository lifecycleRepo;

    public SandboxReaperService(SandboxLifecycleRepository lifecycleRepo) {
        this.lifecycleRepo = lifecycleRepo;
    }

    /**
     * 每分钟执行一次回收扫描。
     *
     * <p>查找所有 {@code ACTIVE} 状态且 {@code last_heartbeat} 超过 TTL 的记录，
     * 执行 {@code docker stop --time=30 && docker rm} 后标记为 {@code REAPED}。
     */
    @Scheduled(fixedRate = 60_000)
    public void reapStaleSandboxes() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(DEFAULT_TTL_MINUTES);
        List<SandboxLifecycleRecord> stale = lifecycleRepo
                .findByStatusAndLastHeartbeatBefore(Status.ACTIVE, cutoff);

        if (stale.isEmpty()) {
            return;
        }

        log.info("[sandbox-reaper] 发现 {} 个心跳超时的沙箱容器，开始回收...", stale.size());
        for (SandboxLifecycleRecord record : stale) {
            String containerId = record.getContainerId();
            try {
                // 优雅停止（最长等 30 秒）
                Process stopProc = Runtime.getRuntime().exec(
                        new String[]{"docker", "stop", "--time", "30", containerId});
                int stopCode = stopProc.waitFor();

                // 删除容器
                Process rmProc = Runtime.getRuntime().exec(
                        new String[]{"docker", "rm", containerId});
                int rmCode = rmProc.waitFor();

                record.setStatus(Status.REAPED);
                lifecycleRepo.save(record);

                if (stopCode == 0 && rmCode == 0) {
                    log.info("[sandbox-reaper] 已回收容器: containerId={}, userId={}, agentId={}",
                            containerId, record.getUserId(), record.getAgentId());
                } else {
                    log.warn("[sandbox-reaper] 容器回收可能不完整: containerId={}, "
                                    + "stopExit={}, rmExit={}",
                            containerId, stopCode, rmCode);
                }
            } catch (Exception e) {
                log.error("[sandbox-reaper] 回收容器失败: containerId={}, error={}",
                        containerId, e.getMessage(), e);
            }
        }
    }

    /**
     * 应用启动时清除所有孤儿容器（DB 中有记录但已无心跳的）。
     * 由 {@code DataAgentApp} 的 {@code @PostConstruct} 调用。
     */
    public void cleanupOnStartup() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);
        List<SandboxLifecycleRecord> orphans = lifecycleRepo
                .findByStatusAndLastHeartbeatBefore(Status.ACTIVE, cutoff);

        int count = 0;
        for (SandboxLifecycleRecord record : orphans) {
            try {
                Runtime.getRuntime().exec(
                        new String[]{"docker", "rm", "-f", record.getContainerId()})
                        .waitFor();
                record.setStatus(Status.REAPED);
                lifecycleRepo.save(record);
                count++;
            } catch (Exception e) {
                log.warn("[sandbox-reaper] 启动清理失败: containerId={}, error={}",
                        record.getContainerId(), e.getMessage());
            }
        }
        if (count > 0) {
            log.info("[sandbox-reaper] 启动时已清理 {} 个孤儿容器", count);
        }

        // 也清理 DB 中状态为 CREATING 但从未收到心跳的记录
        List<SandboxLifecycleRecord> stuck = lifecycleRepo
                .findByStatusAndLastHeartbeatBefore(Status.CREATING, cutoff);
        for (SandboxLifecycleRecord record : stuck) {
            try {
                Runtime.getRuntime().exec(
                        new String[]{"docker", "rm", "-f", record.getContainerId()})
                        .waitFor();
            } catch (Exception ignored) {
                // 可能容器已被手动删除
            }
            record.setStatus(Status.REAPED);
            lifecycleRepo.save(record);
        }
    }

    /**
     * 应用关闭时优雅关闭所有活跃沙箱。
     * 由 {@code DataAgentApp} 的 {@code @PreDestroy} 调用。
     */
    public void shutdownAll() {
        List<SandboxLifecycleRecord> active = lifecycleRepo
                .findByStatusAndLastHeartbeatBefore(Status.ACTIVE, LocalDateTime.now().plusYears(1));

        for (SandboxLifecycleRecord record : active) {
            try {
                Process stopProc = Runtime.getRuntime().exec(
                        new String[]{"docker", "stop", "--time", "30", record.getContainerId()});
                stopProc.waitFor();
                Runtime.getRuntime().exec(
                        new String[]{"docker", "rm", record.getContainerId()})
                        .waitFor();
                record.setStatus(Status.CLOSED);
                lifecycleRepo.save(record);
            } catch (Exception e) {
                log.warn("[sandbox-reaper] 关闭容器失败: containerId={}, error={}",
                        record.getContainerId(), e.getMessage());
            }
        }
    }
}
