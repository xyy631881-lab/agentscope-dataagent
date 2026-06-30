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
package io.agentscope.dataagent.runtime.config;

import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 从 {@link SkillRepositoryConfigEntry} 构建 {@link AgentSkillRepository}。 */
public final class SkillRepositorySupport {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositorySupport.class);

    private static final String TYPE_FILESYSTEM = "filesystem";
    private static final String TYPE_GIT = "git";

    private SkillRepositorySupport() {}

    /**
     * 通过 {@link #create(Path, SkillRepositoryConfigEntry)} 物化 {@code entries} 中的每个非空条目，
     * 并返回结果列表，保持顺序。实例化失败的条目（未知类型、缺少可选的 Git 依赖等）会被过滤掉，
     * 并以 WARN 级别记录日志。不会为 null；可能为空。
     */
    public static List<AgentSkillRepository> createAll(
            Path cwd, List<SkillRepositoryConfigEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        List<AgentSkillRepository> out = new ArrayList<>(entries.size());
        for (SkillRepositoryConfigEntry entry : entries) {
            AgentSkillRepository repo = create(cwd, entry);
            if (repo != null) {
                out.add(repo);
            }
        }
        return out;
    }

    /**
     * @param cwd   bootstrap 工作目录（用于解析相对路径）
     * @param entry 非空配置条目
     * @return 仓库实例，如果配置无效或可选的 Git 类型不在 classpath 上则返回 {@code null}
     */
    public static AgentSkillRepository create(Path cwd, SkillRepositoryConfigEntry entry) {
        if (entry == null || entry.getType() == null || entry.getType().isBlank()) {
            return null;
        }
        String kind = entry.getType().trim().toLowerCase();
        return switch (kind) {
            case TYPE_FILESYSTEM -> createFilesystem(cwd, entry);
            case TYPE_GIT -> createGit(cwd, entry);
            default -> {
                log.warn(
                        "未知的 skillRepository 类型 '{}'; 应为 '{}' 或 '{}'",
                        entry.getType(),
                        TYPE_FILESYSTEM,
                        TYPE_GIT);
                yield null;
            }
        };
    }

    private static AgentSkillRepository createFilesystem(
            Path cwd, SkillRepositoryConfigEntry entry) {
        String pathStr = entry.getPath();
        if (pathStr == null || pathStr.isBlank()) {
            log.warn("skillRepository 类型 filesystem 需要非空的 'path'");
            return null;
        }
        Path dir = cwd.resolve(pathStr).normalize();
        if (!Files.isDirectory(dir)) {
            log.warn(
                    "skillRepository 路径 '{}' 解析为 '{}'，但该路径不是目录",
                    pathStr,
                    dir);
            return null;
        }
        return new FileSystemSkillRepository(dir);
    }

    private static AgentSkillRepository createGit(Path cwd, SkillRepositoryConfigEntry entry) {
        String remote = entry.getRemoteUrl();
        if (remote == null || remote.isBlank()) {
            log.warn("skillRepository 类型 git 需要非空的 'remoteUrl'");
            return null;
        }
        Path local =
                entry.getLocalPath() != null && !entry.getLocalPath().isBlank()
                        ? cwd.resolve(entry.getLocalPath()).normalize()
                        : null;
        boolean auto = entry.getAutoSync() == null || Boolean.TRUE.equals(entry.getAutoSync());
        try {
            Class<?> gitRepo =
                    Class.forName("io.agentscope.core.skill.repository.GitSkillRepository");
            var ctor =
                    gitRepo.getConstructor(
                            String.class, String.class, Path.class, String.class, boolean.class);
            return (AgentSkillRepository)
                    ctor.newInstance(remote, entry.getBranch(), local, entry.getSource(), auto);
        } catch (ClassNotFoundException e) {
            log.warn(
                    "GitSkillRepository 不在 classpath 上；请添加依赖"
                            + " agentscope-extensions-skill-git-repository");
            return null;
        } catch (ReflectiveOperationException e) {
            log.warn("构造 GitSkillRepository 失败: {}", e.getMessage());
            return null;
        }
    }
}
