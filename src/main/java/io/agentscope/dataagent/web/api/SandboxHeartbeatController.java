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

import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRecord.Status;
import io.agentscope.dataagent.web.persistence.jpa.SandboxLifecycleRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部 API：沙箱容器心跳上报。
 *
 * <p>容器内 sidecar 进程定期调用此端点更新心跳时间戳。
 * 调度器根据 {@code last_heartbeat} 判断容器是否仍存活。
 *
 * <p>路径前缀 {@code /api/internal} 确保外部请求不会意外触发。
 */
@RestController
@RequestMapping("/api/internal/sandbox")
public class SandboxHeartbeatController {

    private static final Logger log = LoggerFactory.getLogger(SandboxHeartbeatController.class);

    private final SandboxLifecycleRepository lifecycleRepo;

    public SandboxHeartbeatController(SandboxLifecycleRepository lifecycleRepo) {
        this.lifecycleRepo = lifecycleRepo;
    }

    /**
     * 容器心跳上报。由容器内 sidecar 每隔 30 秒调用一次。
     *
     * @param containerId Docker 容器 ID
     * @return 200 OK 或 404（容器记录不存在）
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestParam("containerId") String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return lifecycleRepo.findByContainerId(containerId)
                .<ResponseEntity<Void>>map(record -> {
                    record.setLastHeartbeat(LocalDateTime.now());
                    if (record.getStatus() == Status.CREATING) {
                        record.setStatus(Status.ACTIVE);
                    }
                    lifecycleRepo.save(record);
                    return ResponseEntity.ok().build();
                })
                .orElseGet(() -> {
                    log.warn("[sandbox-heartbeat] 收到未知容器的心跳: containerId={}", containerId);
                    return ResponseEntity.notFound().build();
                });
    }
}
