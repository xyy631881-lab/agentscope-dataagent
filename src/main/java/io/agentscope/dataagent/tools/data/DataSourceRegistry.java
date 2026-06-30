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

import java.util.List;
import java.util.Optional;

/**
 * DataAgent 部署公开的管理员策划的 {@link DataSource} 描述符集的 SPI。
 * 实现位于 Spring {@code @Bean} 之后，以便操作员可以交换内存中的存根为
 * 基于 JPA、Nacos 或服务发现的实现，而无需更改 toolkit 代码。
 *
 * <p>从 Agent 的角度来看，注册表是只读的；管理员写入路径（CRUD UI、REST）在
 * v1 中不在范围内——操作员通过 {@code agentscope.json} 或专用的
 * Spring {@code @Bean} 来填充注册表。
 */
public interface DataSourceRegistry {

    /** 列出所有已配置的数据源。 */
    List<DataSource> list();

    /** 按 ID 查找数据源。 */
    Optional<DataSource> findById(String id);
}
