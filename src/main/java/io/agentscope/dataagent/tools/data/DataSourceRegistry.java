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
 * DataSourceRegistry 是一个数据源目录——告诉 Agent "有哪些数据库可以查"，就像公司里的"数据资产清单"。
 * 通俗理解：DataSourceRegistry 就像一本"电话簿"——Agent 要查数据时，
 * 先翻电话簿找到对应的数据库联系方式（URL），然后拨号查询。
 */
public interface DataSourceRegistry {

    /** 列出所有已配置的数据源。 */
    List<DataSource> list();

    /** 按 ID 查找数据源。 */
    Optional<DataSource> findById(String id);
}
