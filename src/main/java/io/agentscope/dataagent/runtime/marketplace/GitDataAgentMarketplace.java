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

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git 支持的每个用户 marketplace。将克隆/拉取/文件遍历委托给
 * {@code agentscope-extensions-skill-git-repository} 模块中的
 * {@link GitSkillRepository}。
 *
 * <p>约定与底层仓库一致：Skill 位于 {@code <repo>/skills/<name>/} 下
 * （如果不存在 {@code skills/} 目录则直接在仓库根目录下），每个目录包含
 * {@code SKILL.md} 和可选的附属文件。
 *
 * <p>生命周期：每个用户实例由 {@link UserMarketplaceRegistry} 创建/关闭。
 * 底层克隆保存在提供给工厂的每个用户缓存目录下，这样配置同一上游仓库的
 * 两个用户不会在共享工作副本上发生冲突。
 */
public class GitDataAgentMarketplace implements DataAgentMarketplace {

    private static final Logger logger = LoggerFactory.getLogger(GitDataAgentMarketplace.class);
    public static final String TYPE = "git";

    private final String id;
    private final String remoteUrl;
    private final String branch;
    private final Path localPath;
    private final GitSkillRepository repo;

    /**
     * @param id 用户选择的稳定 marketplace ID
     * @param remoteUrl 上游 git 仓库的 HTTPS 或 SSH URL
     * @param branch 可选分支（null 表示远程默认分支）
     * @param localPath 可选的本地克隆目标；为 null 时底层仓库创建临时目录并注册 JVM
     *     关闭钩子以清理它
     */
    public GitDataAgentMarketplace(String id, String remoteUrl, String branch, Path localPath) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("remoteUrl 不能为空");
        }
        this.id = id;
        this.remoteUrl = remoteUrl.trim();
        this.branch = (branch == null || branch.isBlank()) ? null : branch.trim();
        this.localPath = localPath;
        this.repo =
                new GitSkillRepository(this.remoteUrl, this.branch, this.localPath, "git:" + id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayLocation() {
        return branch != null ? remoteUrl + " @" + branch : remoteUrl;
    }

    @Override
    public List<MarketSkillSummary> list() {
        List<AgentSkill> skills = repo.getAllSkills();
        List<MarketSkillSummary> summaries = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            summaries.add(new MarketSkillSummary(skill.getName(), skill.getDescription(), null));
        }
        return summaries;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        AgentSkill skill = repo.getSkill(name);
        if (skill == null) {
            return null;
        }
        return new MarketSkillContent(
                skill.getName(),
                skill.getDescription(),
                skill.getSkillContent(),
                skill.getResources());
    }

    @Override
    public void close() {
        try {
            repo.close();
        } catch (RuntimeException e) {
            logger.warn("关闭 git marketplace {} ({}) 失败", id, remoteUrl, e);
        }
    }
}
