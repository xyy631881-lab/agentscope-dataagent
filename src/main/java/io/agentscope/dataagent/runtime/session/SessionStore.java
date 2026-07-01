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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由 JSON 文件（{@code sessions.json}）支持的持久化 session 注册表。镜像 OpenClaw 的
 * {@code sessions.json} 存储，跨重启跟踪 session 元数据。
 *
 * <p>线程安全：使用读写锁，使得并发读取非阻塞，写入串行化。文件写入是原子的（先写入临时文件，然后重命名）。
 *
 * <p>存储文件包含一个以 {@code sessionKey} 为键的 JSON 对象，每个值是一个
 * {@link StoredEntry}，捕获需要在重启后保留的 {@link SessionEntry} 字段子集。
 */
public final class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, StoredEntry> entries = new LinkedHashMap<>();

    /**
     * 用于磁盘持久化的 {@link SessionEntry} JSON 可序列化子集。使用
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 以实现添加新字段时的前向兼容。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredEntry(
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
            String userId) {

        public static StoredEntry from(SessionEntry e) {
            return new StoredEntry(
                    e.sessionKey(),
                    e.agentId(),
                    e.sessionId(),
                    e.label(),
                    e.kind().getValue(),
                    e.spawnedBy(),
                    e.spawnDepth(),
                    e.createdAtMs(),
                    e.lastActivityMs(),
                    e.sessionFilePath(),
                    e.spawnRunId(),
                    e.gateKey(),
                    e.userId());
        }

        public SessionEntry toSessionEntry() {
            SessionKind sk = "main".equals(kind) ? SessionKind.MAIN : SessionKind.SUBAGENT;
            return new SessionEntry(
                    sessionKey,
                    agentId,
                    sessionId,
                    label,
                    sk,
                    spawnedBy,
                    spawnDepth,
                    createdAtMs,
                    lastActivityMs,
                    sessionFilePath,
                    spawnRunId,
                    gateKey,
                    userId);
        }
    }

    public SessionStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    /**
     * 将存储文件中的所有条目加载到内存中。启动时调用一次。如果文件不存在或为空，
     * 存储从空状态开始。
     */
    public void load() {
        lock.writeLock().lock();
        try {
            entries.clear();
            if (!Files.isRegularFile(storeFile)) {
                return;
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;
            }
            Map<String, StoredEntry> loaded =
                    MAPPER.readValue(
                            json, new TypeReference<LinkedHashMap<String, StoredEntry>>() {});
            if (loaded != null) {
                entries.putAll(loaded);
            }
            log.info("从 {} 加载了 {} 个 session 条目", entries.size(), storeFile);
        } catch (IOException e) {
            log.warn("从 {} 加载 session 存储失败: {}", storeFile, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 持久化单个 session 条目（更新或插入）。 */
    public void save(SessionEntry entry) {
        lock.writeLock().lock();
        try {
            entries.put(entry.sessionKey(), StoredEntry.from(entry));
            flushToDisk();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 按 key 移除 session 条目。 */
    public void remove(String sessionKey) {
        lock.writeLock().lock();
        try {
            if (entries.remove(sessionKey) != null) {
                flushToDisk();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 返回所有已存储条目的快照。 */
    public Collection<StoredEntry> listAll() {
        lock.readLock().lock();
        try {
            return List.copyOf(entries.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    private void flushToDisk() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            Files.write(
                    tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    tmp,
                    storeFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("刷新 session 存储到 {} 失败: {}", storeFile, e.getMessage());
        }
    }
}
