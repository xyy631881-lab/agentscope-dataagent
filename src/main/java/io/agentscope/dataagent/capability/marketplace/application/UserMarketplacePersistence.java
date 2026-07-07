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
package io.agentscope.dataagent.capability.marketplace.application;
import io.agentscope.dataagent.capability.marketplace.domain.MarketplaceConfigEntry;
import io.agentscope.dataagent.capability.marketplace.infrastructure.UserMarketplaceEntity;
import io.agentscope.dataagent.capability.marketplace.infrastructure.UserMarketplaceRepository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * JPA 支持的 marketplace 持久化。将每个用户的 marketplace 集合作为一行存储在
 * {@code dataagent_user_marketplace} 中，以 {@code (user_id, marketplace_id)} 为键。
 * <em>不会</em> 修改 {@code agentscope.json}——DataAgent 将此视为平台状态，而非单用户配置。
 *
 * <p>所有变更操作都在 Spring 管理的事务中执行，并在成功后驱动
 * {@link UserMarketplaceRegistry#reload(String, String, MarketplaceConfigEntry)} 或
 * {@link UserMarketplaceRegistry#unregister(String, String)}，
 * 以便内存中的实时注册表与数据库保持一致，无需重启。
 */
@Component
public class UserMarketplacePersistence {

    private static final Logger log = LoggerFactory.getLogger(UserMarketplacePersistence.class);

    private final UserMarketplaceRepository repository;
    private final UserMarketplaceRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserMarketplacePersistence(
            UserMarketplaceRepository repository, @Lazy UserMarketplaceRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    /**
     * 加载 {@code userId} 拥有的所有 marketplace，返回 {@code (id → entry)} 映射，按 id 排序。
     * 该映射适合直接注入到 {@link UserMarketplaceRegistry}。
     */
    public Map<String, MarketplaceConfigEntry> loadAllForUser(String userId) {
        List<UserMarketplaceEntity> rows = repository.findByUserIdOrderByMarketplaceIdAsc(userId);
        Map<String, MarketplaceConfigEntry> out = new LinkedHashMap<>();
        for (UserMarketplaceEntity row : rows) {
            try {
                out.put(row.getMarketplaceId(), toEntry(row));
            } catch (RuntimeException ex) {
                log.warn(
                        "跳过格式错误的 marketplace 行 id={}，用户='{}': {}",
                        row.getId(),
                        userId,
                        ex.getMessage());
            }
        }
        return out;
    }

    /** 如果存在，为 {@code userId} 加载一个 marketplace。 */
    public Optional<MarketplaceConfigEntry> load(String userId, String marketplaceId) {
        return repository.findByUserIdAndMarketplaceId(userId, marketplaceId).map(this::toEntry);
    }

    /** {@code userId} 是否拥有给定 ID 的 marketplace。 */
    public boolean exists(String userId, String marketplaceId) {
        return repository.existsByUserIdAndMarketplaceId(userId, marketplaceId);
    }

    /**
     * 为 {@code userId} 插入新的 marketplace 行。调用方负责确保该 ID 不存在——
     * 先使用 {@link #exists(String, String)} 向用户返回 409。
     */
    @Transactional
    public void insert(String userId, String marketplaceId, MarketplaceConfigEntry entry) {
        UserMarketplaceEntity row =
                new UserMarketplaceEntity(
                        userId, marketplaceId, entry.getType(), writePropertiesJson(entry));
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        registry.reload(userId, marketplaceId, entry);
    }

    /** 替换 {@code (userId, marketplaceId)} 的现有行。 */
    @Transactional
    public void update(String userId, String marketplaceId, MarketplaceConfigEntry entry) {
        UserMarketplaceEntity row =
                repository
                        .findByUserIdAndMarketplaceId(userId, marketplaceId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "未找到 marketplace: " + marketplaceId));
        row.setType(entry.getType());
        row.setPropertiesJson(writePropertiesJson(entry));
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        registry.reload(userId, marketplaceId, entry);
    }

    /** 删除行并注销实时实例。如果行已不存在则为空操作。 */
    @Transactional
    public boolean delete(String userId, String marketplaceId) {
        if (!repository.existsByUserIdAndMarketplaceId(userId, marketplaceId)) {
            return false;
        }
        repository.deleteByUserIdAndMarketplaceId(userId, marketplaceId);
        registry.unregister(userId, marketplaceId);
        return true;
    }

    // -----------------------------------------------------------------
    //  辅助方法
    // -----------------------------------------------------------------

    private MarketplaceConfigEntry toEntry(UserMarketplaceEntity row) {
        MarketplaceConfigEntry entry = new MarketplaceConfigEntry();
        entry.setType(row.getType());
        Map<String, Object> props = readPropertiesJson(row.getPropertiesJson());
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                entry.setProperty(e.getKey(), e.getValue());
            }
        }
        return entry;
    }

    private String writePropertiesJson(MarketplaceConfigEntry entry) {
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        try {
            return mapper.writeValueAsString(props);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "无法序列化 marketplace 属性: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readPropertiesJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "无法反序列化 marketplace 属性: " + e.getMessage(), e);
        }
    }
}