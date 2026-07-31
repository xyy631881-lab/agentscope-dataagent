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
package io.agentscope.dataagent.capability.marketplace.domain;

import java.util.List;

/**
 * Builder 管理的 Skill marketplace。通过 UI 浏览和配置，独立于 Agent 自身加载的运行时
 * {@code skillRepositories}。
 *
 * <p>实现是有状态的（打开 git 克隆、打开 nacos 客户端），当注册表替换或移除它们时
 * 必须关闭。
 */
public interface DataAgentMarketplace extends AutoCloseable {

    /** 稳定 ID，由用户在创建 marketplace 时选择。 */
    String id();

    /** UI 用于徽章和配置表单的标识符（{@code "git"} / {@code "nacos"}）。 */
    String type();

    /** 在 UI 中显示的可读位置（URL、服务器地址等）。不包含凭据。 */
    String displayLocation();

    /** 此 marketplace 是否接受写入——builder 将 marketplace 视为只读，但 UI 可以显示此信息。 */
    default boolean writable() {
        return false;
    }

    /**
     * 列出此 marketplace 暴露的所有 Skill。实现可以缓存或每次都访问上游；
     * 调用方应将其视为可能较慢的操作，让 UI 惰性加载。
     */
    List<MarketSkillSummary> list();

    /**
     * 获取指定名称的 Skill 的完整内容（SKILL.md 及任何附属资源），
     * 如果不存在则返回 {@code null}。
     */
    MarketSkillContent fetch(String name);

    /**
     * 获取指定名称 Skill 的某个历史版本内容。仅支持版本归档型 marketplace
     * （如 {@code LocalApprovalMarketplace} 从 {@code .versions/v<n>/} 读取）；
     * 其他实现默认返回 {@code null} 表示不支持版本化 fetch，调用方应回退到
     * {@link #fetch(String)} 安装最新版本。
     *
     * @param name    skill 名称
     * @param version 大于等于 1 的版本号（对应审批时分配的 {@code ContributionEntity.version}）
     * @return 版本快照内容；若 marketplace 不支持版本化或该版本不存在，则返回 {@code null}
     */
    default MarketSkillContent fetchVersion(String name, int version) {
        return null;
    }

    /** Lists installable archived versions for a skill, newest first. */
    default List<Integer> listVersions(String name) {
        return List.of();
    }

    /** 释放上游资源（关闭 git、停止 nacos 客户端）。可安全重复调用。 */
    @Override
    void close();
}
