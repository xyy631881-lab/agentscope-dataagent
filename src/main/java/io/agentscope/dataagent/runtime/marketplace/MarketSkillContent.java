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

import java.util.Map;

/**
 * 由 {@link DataAgentMarketplace#fetch(String)} 返回的完整 Skill 负载。{@code markdown} 是
 * SKILL.md 正文；{@code resources} 是以 workspace 相对路径为键的兄弟文件
 * （例如 {@code "templates/intro.md"} → 内容）。
 *
 * @param name        Skill 标识符；与请求的 {@link MarketSkillSummary#name()} 匹配
 * @param description 从摘要镜像的一行描述，以便调用方无需二次查找
 * @param markdown    SKILL.md 正文；必须存在且非空
 * @param resources   相对路径到文件内容的映射，针对每个附属文件；不会为 null，可能为空
 */
public record MarketSkillContent(
        String name, String description, String markdown, Map<String, String> resources) {}
