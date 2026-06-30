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
package io.agentscope.dataagent.runtime.marketplace;

import io.agentscope.dataagent.runtime.config.MarketplaceConfigEntry;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每个用户 {@link DataAgentMarketplace} 实例的实时注册表，以 {@code userId} → marketplace id 为键。
 *
 * <p>builder 中的 marketplace 是用户私有的：没有管理员管理的平台层级。两个不同的用户
 * 可以重用相同的 marketplace ID 而不会冲突，且永远不会看到对方的条目。
 * 该注册表类似 claw 的 {@code ClawMarketplaceRegistry}，但增加了外层用户 ID 维度，
 * 并且没有启动预加载——实例按需构建，因为用户群体可能很大。
 *
 * <p>生命周期：{@code UserMarketplacePersistence} 在写入行后通过
 * {@link #reload(String, String, MarketplaceConfigEntry)} 和
 * {@link #unregister(String, String)} 驱动变更。注册表拥有被替换或移除的实例的
 * {@link DataAgentMarketplace#close()} 所有权，以便之前的 git clone/nacos 客户端
 * 被及时释放。
 */
@Component
public class UserMarketplaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserMarketplaceRegistry.class);

    private final WorkspaceManagerFactory workspaceFactory;
    private final UserMarketplacePersistence persistence;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, DataAgentMarketplace>>
            byUser = new ConcurrentHashMap<>();
    private final Map<String, DataAgentMarketplaceFactory> factories;

    public UserMarketplaceRegistry(
            WorkspaceManagerFactory workspaceFactory,
            UserMarketplacePersistence persistence,
            List<DataAgentMarketplaceFactoryRegistration> registrations) {
        this.workspaceFactory = workspaceFactory;
        this.persistence = persistence;
        Map<String, DataAgentMarketplaceFactory> map = new java.util.HashMap<>();
        for (DataAgentMarketplaceFactoryRegistration r : registrations) {
            map.put(r.type().toLowerCase(Locale.ROOT), r.factory());
        }
        this.factories = Map.copyOf(map);
    }

    /**
     * Spring 可注入的 {@link DataAgentMarketplaceFactory} 注册，用于给定的类型标识符。
     * 在任何配置类中提交返回此记录的 {@code @Bean}，即可使相应的 marketplace 类型
     * 对注册表可用。
     */
    public record DataAgentMarketplaceFactoryRegistration(
            String type, DataAgentMarketplaceFactory factory) {}

    /**
     * {@code userId} 的每个实时 marketplace 的快照，按 ID 排序。首次为用户调用时
     * 惰性地从数据库加载每个用户的映射。
     */
    public List<DataAgentMarketplace> list(String userId) {
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        List<DataAgentMarketplace> snapshot = new ArrayList<>(map.values());
        snapshot.sort(Comparator.comparing(DataAgentMarketplace::id));
        return snapshot;
    }

    /** 返回 {@code userId} 在给定 ID 下的 marketplace（如果已注册）。 */
    public Optional<DataAgentMarketplace> find(String userId, String id) {
        if (userId == null || id == null) return Optional.empty();
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        return Optional.ofNullable(map.get(id));
    }

    /** {@code userId} 是否拥有给定 ID 的 marketplace。 */
    public boolean contains(String userId, String id) {
        if (userId == null || id == null) return false;
        return ensureLoaded(userId).containsKey(id);
    }

    /**
     * 替换（或首次安装）{@code userId} 在 {@code id} 下的 marketplace，
     * 使用 {@code entry} 构建。之前的注册实例（如果有）在新实例就位后关闭。
     */
    public DataAgentMarketplace reload(String userId, String id, MarketplaceConfigEntry entry) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        DataAgentMarketplace next = build(userId, id, entry);
        ConcurrentHashMap<String, DataAgentMarketplace> map = ensureLoaded(userId);
        DataAgentMarketplace previous = map.put(id, next);
        closeQuietly(previous);
        return next;
    }

    /** 移除并关闭 {@code userId} 在 {@code id} 下的 marketplace。如果未注册则为空操作。 */
    public boolean unregister(String userId, String id) {
        if (userId == null || id == null) return false;
        ConcurrentHashMap<String, DataAgentMarketplace> map = byUser.get(userId);
        if (map == null) return false;
        DataAgentMarketplace removed = map.remove(id);
        if (removed == null) return false;
        closeQuietly(removed);
        return true;
    }

    /**
     * 从配置条目构建 marketplace 实例而不注册它。由 {@code MarketplacesController#testTransient}
     * 使用，以便连接探测使用与真实注册相同的代码路径，但如果探测失败则不会占用（id）槽位。
     *
     * @throws IllegalArgumentException 如果 {@code entry.type} 未知或必填字段缺失
     */
    public DataAgentMarketplace build(String userId, String id, MarketplaceConfigEntry entry) {
        if (entry.getType() == null || entry.getType().isBlank()) {
            throw new IllegalArgumentException("marketplace '" + id + "' 没有类型");
        }
        String type = entry.getType().toLowerCase(Locale.ROOT);
        Map<String, Object> props =
                entry.getProperties() != null ? entry.getProperties() : Map.of();
        DataAgentMarketplaceFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "不支持的 marketplace 类型 '" + entry.getType() + "' 用于 '" + id
                            + "' — 为此类型注册一个 "
                            + DataAgentMarketplaceFactory.class.getSimpleName()
                            + " bean");
        }
        return factory.create(userId, id, props, workspaceFactory);
    }

    /**
     * 用于插入 marketplace 存储的 SPI。v1 在 {@code "local"} 类型下提供
     * {@link io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace}；
     * git 和 nacos 存储故意不打包（如果需要，从 agentscope-builder 中提取
     * {@code GitDataAgentMarketplace} / {@code NacosDataAgentMarketplace} 类）。
     */
    @FunctionalInterface
    public interface DataAgentMarketplaceFactory {
        DataAgentMarketplace create(
                String userId, String id, Map<String, Object> props, WorkspaceManagerFactory wsf);
    }

    /** 关闭每个 marketplace；在关闭期间使用，以免泄漏 git 克隆/客户端。 */
    @PreDestroy
    public void closeAll() {
        Collection<ConcurrentHashMap<String, DataAgentMarketplace>> snapshot =
                new ArrayList<>(byUser.values());
        byUser.clear();
        for (ConcurrentHashMap<String, DataAgentMarketplace> map : snapshot) {
            for (DataAgentMarketplace mp : map.values()) {
                closeQuietly(mp);
            }
        }
    }

    private ConcurrentHashMap<String, DataAgentMarketplace> ensureLoaded(String userId) {
        return byUser.computeIfAbsent(userId, this::hydrateFromStore);
    }

    private ConcurrentHashMap<String, DataAgentMarketplace> hydrateFromStore(String userId) {
        ConcurrentHashMap<String, DataAgentMarketplace> map = new ConcurrentHashMap<>();
        for (Map.Entry<String, MarketplaceConfigEntry> e :
                persistence.loadAllForUser(userId).entrySet()) {
            try {
                map.put(e.getKey(), build(userId, e.getKey(), e.getValue()));
            } catch (RuntimeException ex) {
                log.warn(
                        "为用户 '{}' 初始化 marketplace '{}' 失败: {}",
                        e.getKey(),
                        userId,
                        ex.getMessage(),
                        ex);
            }
        }
        return map;
    }

    private void closeQuietly(DataAgentMarketplace mp) {
        if (mp == null) return;
        try {
            mp.close();
        } catch (RuntimeException e) {
            log.warn("关闭 marketplace '{}' 失败: {}", mp.id(), e.getMessage(), e);
        }
    }
}
