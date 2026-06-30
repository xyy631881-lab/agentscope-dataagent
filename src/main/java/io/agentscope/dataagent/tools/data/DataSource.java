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
import java.util.Map;
import java.util.Objects;

/**
 * 管理员策划的编目条目，描述 Agent 可以查询的命名数据源。v1 是一个薄
 * 描述符：一个稳定的 ID、一个人类可读的标签、JDBC 风格的 URL 前缀（或其他连接器
 * 提示），以及一个用于连接器特定配置的不透明 {@code properties} 映射。
 *
 * <p>具体的连接器实现（JDBC、BigQuery、Hologres、OSS+Parquet）明确不在
 * v1 的范围内——toolkit 在此返回描述符，以便 Agent 可以推理使用哪个源，
 * 即将推出的连接器模块将实现实际的 {@code run_sql_preview}。
 */
public record DataSource(
        String id,
        String label,
        String description,
        String kind,
        String urlHint,
        List<String> tags,
        Map<String, String> properties) {

    public DataSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        tags = tags == null ? List.of() : List.copyOf(tags);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
