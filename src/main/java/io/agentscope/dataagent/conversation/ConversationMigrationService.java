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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 一次性迁移服务：将旧的 sessions.json 数据导入 JPA 表。
 *
 * <p>从 ConversationService 提取，使核心服务不再承担启动迁移职责。
 * 迁移在 @PostConstruct 中执行，保证在 ConversationService 使用数据前完成。
 */
@Service
public class ConversationMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMigrationService.class);
    private static final ObjectMapper MIGRATION_MAPPER = new ObjectMapper();

    private final SessionEntityRepository sessionRepo;
    private final DataAgentBootstrap bootstrap;

    public ConversationMigrationService(
            SessionEntityRepository sessionRepo, DataAgentBootstrap bootstrap) {
        this.sessionRepo = sessionRepo;
        this.bootstrap = bootstrap;
    }

    @PostConstruct
    void migrate() {
        migrateFromSessionsJson();
    }

    /**
     * 如果 JPA 表为空且 sessions.json 存在，则将旧数据迁移到 JPA。
     * 这是一次性迁移，迁移完成后 sessions.json 不再使用。
     */
    private void migrateFromSessionsJson() {
        try {
            if (sessionRepo.count() > 0) {
                return;
            }
            Path storeFile = resolveSessionsJsonPath();
            if (storeFile == null || !Files.isRegularFile(storeFile)) {
                return;
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
                if (entry != null
                        && entry.getWorkspace() != null
                        && !entry.getWorkspace().isBlank()) {
                    return bootstrap
                            .cwd()
                            .resolve(entry.getWorkspace())
                            .resolve("sessions.json");
                }
            }
            return bootstrap
                    .cwd()
                    .resolve(".agentscope")
                    .resolve("workspace")
                    .resolve("sessions.json");
        } catch (Exception e) {
            return null;
        }
    }

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
}
