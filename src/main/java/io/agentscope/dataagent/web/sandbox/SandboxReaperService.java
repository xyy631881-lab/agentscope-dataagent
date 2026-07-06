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
package io.agentscope.dataagent.web.sandbox;

import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRecord;
import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRecord.Status;
import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    /** 单条 docker 命令最长等待时间（秒），超时强制销毁进程避免卡死调度线程。 */
    private static final long DOCKER_CMD_TIMEOUT_SEC = 60;

    private final SandboxLifecycleRepository lifecycleRepo;

    public SandboxReaperService(SandboxLifecycleRepository lifecycleRepo) {
        this.lifecycleRepo = lifecycleRepo;
    }

    /**
     每分钟执行一次回收扫描。
         每 60 秒自动执行一次（@Scheduled(fixedRate = 60_000)）
         算出一个截止时间 = 当前时间 - 30分钟（DEFAULT_TTL_MINUTES）
         从数据库查出所有 状态为 ACTIVE 且 最后心跳时间早于截止时间 的容器记录
         如果没有超时的，直接返回，啥也不干
         如果有超时的，逐个处理：
         先执行 docker stop --time 30 <容器ID>：给容器 30 秒时间优雅停止
         再执行 docker rm <容器ID>：删除容器
         把数据库记录状态改为 REAPED（已回收）
         记录日志（成功/部分失败/异常）
     覆盖的场景：
         进程被 kill -9 强杀，来不及关闭容器
         容器内的 sidecar 故障，心跳停止
         多副本部署时某副本崩溃，它管理的容器没人管了
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
                // 优雅停止（docker stop 内部最长 30 秒，这里再给 60 秒兜底）
                int stopCode = runDockerCmd(List.of("docker", "stop", "--time", "30", containerId));
                // 删除容器
                int rmCode = runDockerCmd(List.of("docker", "rm", containerId));

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
         从数据库查出所有状态为 ACTIVE 或 CREATING 的记录
         过滤出那些 最后心跳为空（从来没收到心跳）或 最后心跳早于1分钟前 的记录
         对每个孤儿容器执行 docker rm -f <容器ID>：强制删除（-f 表示即使容器还在运行也直接删）
         把数据库记录状态改为 REAPED
         统计清理数量，打印日志
     */
    public void cleanupOnStartup() {
        // ACTIVE 且 1 分钟前还没心跳的，肯定是上次进程崩溃留下的孤儿
        // （正常活跃容器心跳间隔远小于 1 分钟）
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);
        List<SandboxLifecycleRecord> orphans = lifecycleRepo
                .findByStatusIn(List.of(Status.ACTIVE, Status.CREATING)).stream()
                .filter(r -> r.getLastHeartbeat() == null
                        || r.getLastHeartbeat().isBefore(cutoff))
                .toList();

        int count = 0;
        for (SandboxLifecycleRecord record : orphans) {
            try {
                runDockerCmd(List.of("docker", "rm", "-f", record.getContainerId()));
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
    }

    /**
     * 应用关闭时优雅关闭所有活跃沙箱。
         从数据库查出所有状态为 ACTIVE 的容器记录
         逐个执行：
         docker stop --time 30 <容器ID>：优雅停止（给 30 秒时间清理）
         docker rm <容器ID>：删除容器
         把数据库记录状态改为 CLOSED（正常关闭）
         失败了只打 warn 日志，不抛异常，尽量多关几个
     */
    public void shutdownAll() {
        List<SandboxLifecycleRecord> active = lifecycleRepo.findByStatus(Status.ACTIVE);

        for (SandboxLifecycleRecord record : active) {
            try {
                runDockerCmd(List.of("docker", "stop", "--time", "30", record.getContainerId()));
                runDockerCmd(List.of("docker", "rm", record.getContainerId()));
                record.setStatus(Status.CLOSED);
                lifecycleRepo.save(record);
            } catch (Exception e) {
                log.warn("[sandbox-reaper] 关闭容器失败: containerId={}, error={}",
                        record.getContainerId(), e.getMessage());
            }
        }
    }

    // ---- 内部工具方法 ----

    /**
     * 执行一条 docker 命令，解决两个老问题：
     * 用 ProcessBuilder 启动一个子进程执行传入的命令
     *
     * 关键优化 1：把 stdout 重定向到 DISCARD（丢弃），stderr 合并到 stdout 一起丢弃
     * 这解决了经典死锁问题——如果子进程输出太多内容填满了管道缓冲区，没人读的话 waitFor() 就会永远阻塞
     * 关键优化 2：waitFor 加了 60 秒超时（DOCKER_CMD_TIMEOUT_SEC），
     * 超时后调用 destroyForcibly() 强制杀掉子进程，防止定时调度线程被永久卡住
     * 如果被中断（InterruptedException），先强制杀进程，再恢复中断标志位，然后抛出异常
     *
     * 通俗理解：这是清洁工的"工具箱"，用来执行 docker 命令。之前有两个老 bug：
     * 死锁 bug：原来用 Runtime.exec 执行命令但不读输出，如果 docker 命令输出太多，管道满了就卡死了。现在直接把输出丢掉，管道永远不会满。
     * 卡死 bug：原来 waitFor() 没有超时，万一 docker 命令卡住了，整个定时任务线程就废了。现在加了 60 秒超时，超时就强杀子进程。
     */
    private int runDockerCmd(List<String> cmd) throws InterruptedException, java.io.IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)  // 丢弃 stdout，防管道满死锁
                .redirectErrorStream(true);                        // stderr 合并到 stdout 一起丢
        Process proc = pb.start();
        try {
            if (!proc.waitFor(DOCKER_CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                log.warn("[sandbox-reaper] docker 命令超时（{}秒），强制终止: {}",
                        DOCKER_CMD_TIMEOUT_SEC, String.join(" ", cmd));
                proc.destroyForcibly();
                return -1;
            }
            return proc.exitValue();
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();  // 恢复中断标志
            throw e;
        }
    }
}
