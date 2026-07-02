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
 * 存储引擎（数据的读写）
 * SessionStore 是会话注册表的持久化层——把所有会话元数据存到一个sessions.json文件里，
 * 通过读写锁保证线程安全，通过"先写临时文件再原子重命名"保证写入安全，启动时加载到内存、运行时同步刷盘，
 * 确保应用重启后会话数据不丢失。
 *
 * SessionAgentManager 用四个 ConcurrentHashMap 在内存中管理会话，但内存是易失的——进程一重启，
 * 所有数据就没了。SessionStore 就是解决这个问题的：
 * 应用运行中：内存（ConcurrentHashMap）← 快，但重启丢失
 *                 ↕ 同步
 * 应用重启后：磁盘（sessions.json）← 慢，但持久保存
 *
 * 通俗理解：内存是白板——写上去快，但擦了就没了；SessionStore 是笔记本——写上去慢一点，但永久保存。
 * 每天上班时把笔记本上的内容抄到白板上（load），工作中白板有更新就同步到笔记本（flushToDisk）。
 *
 * 整个文件就是一个大 JSON 对象，key 是 sessionKey，value 是 StoredEntry。
 *
 * 应用启动
 *   │
 *   ▼
 * SessionAgentManager 构造函数
 *   │
 *   ├── sessionStore.load()           ← 从 磁盘的 sessions.json 加载到内存
 *   └── restoreFromStore()            ← 重建四个内存索引
 *   │
 *   ▼
 * 运行时操作
 *   │
 *   ├── save / reset / remove
 *   │     │
 *   │     ├── 更新内存索引（sessionsByKey 等）
 *   │     └── sessionStore.save()     ← 内存变更同步到磁盘
 *   │
 *   ▼
 * 应用重启 → 重复上述流程
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

        // 内存 → 磁盘
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

        // 磁盘 → 内存
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
     * 文件不存在或损坏，不会让应用启动失败，而是以空状态启动。这在首次部署或文件意外删除时很重要。
     */
    public void load() {
        lock.writeLock().lock();  // 写锁（因为要清空+重建 entries）
        try {
            entries.clear();
            if (!Files.isRegularFile(storeFile)) {
                return;  // 文件不存在 → 空状态启动
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return;  // 文件为空 → 空状态启动
            }
            Map<String, StoredEntry> loaded =
                    MAPPER.readValue(
                            json, new TypeReference<LinkedHashMap<String, StoredEntry>>() {});
            if (loaded != null) {
                entries.putAll(loaded);  // 全部加载到内存
            }
            log.info("从 {} 加载了 {} 个 session 条目", entries.size(), storeFile);
        } catch (IOException e) {
            log.warn("从 {} 加载 session 存储失败: {}", storeFile, e.getMessage());  // 加载失败 → 不崩溃，空状态启动
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 持久化单个 session 条目（更新或插入）。 */
    public void save(SessionEntry entry) {
        lock.writeLock().lock();
        try {
            entries.put(entry.sessionKey(), StoredEntry.from(entry));
            flushToDisk();  // 每次保存都立即刷盘
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 按 key 移除 session 条目。 */
    public void remove(String sessionKey) {
        lock.writeLock().lock();
        try {
            if (entries.remove(sessionKey) != null) {
                flushToDisk();  // 只有真正删了才刷盘
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 返回所有已存储条目的快照。 */
    public Collection<StoredEntry> listAll() {
        lock.readLock().lock();  // 读锁（不阻塞其他读者）
        try {
            //List.copyOf 创建一个不可变副本——调用者拿到的是快照，之后内存中的数据变了也不影响这个副本。
            return List.copyOf(entries.values());  // 返回快照副本
        } finally {
            lock.readLock().unlock();
        }
    }

    //  原子写入磁盘，修改重要文件时，先在草稿纸上写好，确认无误后再替换正式文件。如果写到一半被打断，正式文件还是好的。
    private void flushToDisk() {
        try {
            // ① 确保目录存在
            Files.createDirectories(storeFile.getParent());
            // ② 先写到临时文件
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(entries);
            Files.write(
                    tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // ③ 原子重命名：临时文件 → 正式文件
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
