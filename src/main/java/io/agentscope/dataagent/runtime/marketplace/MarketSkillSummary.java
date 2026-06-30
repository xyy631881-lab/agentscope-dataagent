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

/**
 * 由 {@link DataAgentMarketplace#list()} 返回的轻量级 Skill 描述符。用于填充
 * marketplace 浏览器，而无需承担下载每个 SKILL.md 的成本。
 *
 * @param name        用户安装时使用的稳定标识符
 * @param description 在 UI 中显示的一行描述；可能为空但不会为 null
 * @param version     上游版本字符串，如果源没有版本概念（例如没有标签的 git 仓库）
 *                    则为 {@code null}
 */
public record MarketSkillSummary(String name, String description, String version) {}
