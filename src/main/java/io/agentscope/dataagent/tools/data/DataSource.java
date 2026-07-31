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
 * 只存元信息（名字、类型、标签），不存密码、不存敏感信息。真正的连接由底层的 JDBC DataSource 管理。
 * 通俗理解：每条 DataSource 就像一张名片——上面写着"我是谁、我能提供什么、我的联系方式"，但不写内部机密。
 */
public record DataSource(
        String id,  // 唯一标识
        String label,  // 显示名
        String description,  // 详细描述
        String kind,  // 类型："mysql"
        String urlHint,  // URL 前缀提示
        List<String> tags,  // 标签
        Map<String, String> properties)  // 连接器特定配置
{
    public DataSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        tags = tags == null ? List.of() : List.copyOf(tags);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
