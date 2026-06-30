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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件系统支持的 {@link DataAgentMarketplace}，从磁盘上每个 Agent 的切片中读取 Skill，
 * 按照约定路径为 {@code ${dataagentHome}/shared/agents/<agentId>/skills/}
 * （此类的调用者选择确切的根路径——请参阅 {@link io.agentscope.dataagent.web.config.DataAgentConfig}）。
 *
 * <p>这是支持管理审批贡献流的进程内实现：已批准的贡献被放入同一个每个 Agent 的切片中，
 * 并对该 Agent 的每个租户可见，因为每个 {@code (userId, agentId)} sandbox 将该切片
 * 作为其较低层投射到容器中。
 *
 * <p>从 marketplace API 的角度来看是只读的——写入通过管理员批准后的
 * {@code MarketContributionService} 带外发生。
 *
 * <p>布局（以 {@code data-agent} 为例）：
 * <pre>
 * ${dataagentHome}/shared/agents/data-agent/skills/
 *   sql-analysis/
 *     SKILL.md
 *     templates/intro.md     ← 按相对路径作为键的附属资源
 *   chart-rendering/
 *     SKILL.md
 * </pre>
 */
public final class LocalApprovalMarketplace implements DataAgentMarketplace {

    private static final Logger log = LoggerFactory.getLogger(LocalApprovalMarketplace.class);
    public static final String TYPE = "local";

    private final String id;
    private final Path skillsRoot;

    public LocalApprovalMarketplace(String id, Path skillsRoot) {
        this.id = Objects.requireNonNull(id, "id");
        this.skillsRoot = Objects.requireNonNull(skillsRoot, "skillsRoot");
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
        return skillsRoot.toAbsolutePath().toString();
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public List<MarketSkillSummary> list() {
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        List<MarketSkillSummary> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(skillsRoot)) {
            entries.filter(Files::isDirectory)
                    .sorted()
                    .forEach(
                            dir -> {
                                Path skillMd = dir.resolve("SKILL.md");
                                if (!Files.isRegularFile(skillMd)) return;
                                String name = dir.getFileName().toString();
                                String description = firstNonBlankLine(skillMd);
                                out.add(new MarketSkillSummary(name, description, null));
                            });
        } catch (IOException e) {
            log.warn(
                    "LocalApprovalMarketplace '{}' 列举 {} 下的 Skill 失败: {}",
                    id,
                    skillsRoot,
                    e.getMessage());
        }
        return out;
    }

    @Override
    public MarketSkillContent fetch(String name) {
        if (name == null || name.isBlank()) return null;
        Path dir = skillsRoot.resolve(name);
        if (!Files.isDirectory(dir) || !dir.normalize().startsWith(skillsRoot.normalize())) {
            return null;
        }
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) return null;

        String markdown;
        try {
            markdown = Files.readString(skillMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> resources = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(skillMd))
                    .sorted()
                    .forEach(
                            p -> {
                                String rel = dir.relativize(p).toString().replace('\\', '/');
                                try {
                                    resources.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                                } catch (IOException ignored) {
                                    // 尽力而为：跳过不可读的附属文件（二进制等）
                                }
                            });
        } catch (IOException e) {
            log.warn(
                    "LocalApprovalMarketplace '{}' 部分获取 '{}': {}",
                    id,
                    name,
                    e.getMessage());
        }
        return new MarketSkillContent(
                name, firstNonBlankLine(skillMd), markdown, Collections.unmodifiableMap(resources));
    }

    @Override
    public void close() {
        // 无需释放持久化资源。
    }

    private static String firstNonBlankLine(Path skillMd) {
        try {
            for (String line : Files.readAllLines(skillMd, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("---")) continue;
                return t.length() > 200 ? t.substring(0, 200) : t;
            }
        } catch (IOException e) {
            // 继续执行
        }
        return "";
    }
}
