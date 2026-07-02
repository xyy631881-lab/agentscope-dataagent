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
package io.agentscope.dataagent.tools.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 由内存中的 {@link LinkedHashMap} 支持的默认 {@link DataSourceRegistry}。在构造时一次性填充；
 * 操作员可以将 bean 替换为 JPA 或 Nacos 支持的实现。集合在构造后不可修改——
 * 需要动态添加条目的调用方应提供更丰富的实现，而不是修改此实现。
 *
 * // 将来可以替换为：
 * public class JpaDataSourceRegistry implements DataSourceRegistry { ... }     // 从数据库读
 * public class NacosDataSourceRegistry implements DataSourceRegistry { ... }  // 从 Nacos 配置中心读
 * public class ServiceDiscoveryDataSourceRegistry { ... }
 * 好处：管理员可以在不改 toolkit 代码的情况下，把内存实现替换成任何其他实现。
 */
//当前默认实现：
public final class InMemoryDataSourceRegistry implements DataSourceRegistry {

    // LinkedHashMap，保证插入顺序一致
    private final Map<String, DataSource> byId;

    public InMemoryDataSourceRegistry(List<DataSource> seed) {
        Objects.requireNonNull(seed, "seed");
        Map<String, DataSource> m = new LinkedHashMap<>();
        for (DataSource ds : seed) {
            if (ds == null) continue;
            m.put(ds.id(), ds);
        }
        this.byId = Map.copyOf(m);  // 构造后不可修改！
    }

    @Override
    public List<DataSource> list() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public Optional<DataSource> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(byId.get(id));
    }
}
