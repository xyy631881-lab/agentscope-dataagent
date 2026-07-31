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
package io.agentscope.dataagent.agent.application;
import io.agentscope.dataagent.agent.api.AgentSkillsController;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Low-level filesystem I/O and skill metadata helpers extracted from
 * {@link AgentSkillsController}.
 *
 * <p>All methods are static and operate on {@link AbstractFilesystem} or
 * {@link WorkspaceManager}; no Spring dependencies are required. The class is
 * annotated {@code @Service} so it participates in component scanning, but
 * callers invoke the static methods directly (e.g. {@code SkillFileService.readUtf8(...)}).
 *
 * @see AgentSkillsController
 */
@Service
public class SkillFileService {

    private static final Logger log = LoggerFactory.getLogger(SkillFileService.class);

    private static final Pattern FRONT_MATTER =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    public static final Pattern DESCRIPTION_LINE =
            Pattern.compile("^\\s*description\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern NAME_LINE =
            Pattern.compile("^\\s*name\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    static final String INSTALL_META_FILE = "_install.meta.json";
    static final String ORIGIN_CUSTOM = "custom";
    static final String ORIGIN_MARKETPLACE = "marketplace";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @FunctionalInterface
    interface FileVisitor {
        void visit(String relativePath, String absolutePath);
    }

    // -----------------------------------------------------------------
    //  Directory walking & file size
    // -----------------------------------------------------------------

    /**
     * Recursively walks {@code rootAbs} on the abstract filesystem, invoking {@code visitor} for
     * each regular file with the path relative to {@code rootAbs + "/"}. Tolerant of ls failures
     * (silently treated as empty).
     */
    static void walk(
            AbstractFilesystem fs, String rootAbs, String relativeBase, FileVisitor visitor) {
        walk(fs, RuntimeContext.empty(), rootAbs, relativeBase, visitor);
    }

    static void walk(
            AbstractFilesystem fs,
            RuntimeContext context,
            String rootAbs,
            String relativeBase,
            FileVisitor visitor) {
        LsResult ls = fs.ls(context, rootAbs);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return;
        for (FileInfo info : ls.entries()) {
            String abs = info.path();
            String name = leafName(abs);
            if (name.isBlank()) continue;
            if (info.isDirectory()) {
                walk(fs, context, abs, relativeBase, visitor);
            } else {
                String rel =
                        abs.startsWith(relativeBase) ? abs.substring(relativeBase.length()) : name;
                visitor.visit(rel, abs);
            }
        }
    }

    static long fileSize(AbstractFilesystem fs, String absolutePath) {
        return fileSize(fs, RuntimeContext.empty(), absolutePath);
    }

    static long fileSize(AbstractFilesystem fs, RuntimeContext context, String absolutePath) {
        // The cheap path — info.size() from ls — is already consumed by the caller's walk; we
        // re-stat via a parent ls to keep this function self-contained. One shell exec per call
        // in the sandbox-backed filesystem, used only on individual workspace skill directories.
        int slash = absolutePath.lastIndexOf('/');
        if (slash <= 0) return 0L;
        String parent = absolutePath.substring(0, slash);
        String name = absolutePath.substring(slash + 1);
        LsResult ls = fs.ls(context, parent);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return 0L;
        for (FileInfo info : ls.entries()) {
            if (leafName(info.path()).equals(name)) return info.size();
        }
        return 0L;
    }

    // -----------------------------------------------------------------
    //  File reading
    // -----------------------------------------------------------------

    public static String readUtf8(AbstractFilesystem fs, String absolutePath) {
        return readUtf8(fs, RuntimeContext.empty(), absolutePath);
    }

    public static String readUtf8(
            AbstractFilesystem fs, RuntimeContext context, String absolutePath) {
        ReadResult r = fs.read(context, absolutePath, 0, Integer.MAX_VALUE);
        if (r == null || !r.isSuccess() || r.fileData() == null) return null;
        return r.fileData().content();
    }

    public static String leafName(String absolutePath) {
        if (absolutePath == null) return "";
        int slash = absolutePath.lastIndexOf('/');
        return slash >= 0 ? absolutePath.substring(slash + 1) : absolutePath;
    }

    // -----------------------------------------------------------------
    //  Skill metadata (size, resources, workspace skill info, install meta)
    // -----------------------------------------------------------------

    static AgentSkillsController.SkillSize computeSize(AbstractFilesystem fs, String dirName) {
        return computeSize(fs, RuntimeContext.empty(), dirName);
    }

    static AgentSkillsController.SkillSize computeSize(
            AbstractFilesystem fs, RuntimeContext context, String dirName) {
        long[] total = new long[] {0L};
        int[] count = new int[] {0};
        walk(
                fs,
                context,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    if (relativePath.equals(INSTALL_META_FILE)) return;
                    total[0] += fileSize(fs, context, absolutePath);
                    if (!relativePath.equals("SKILL.md")) count[0]++;
                });
        return new AgentSkillsController.SkillSize(total[0], count[0]);
    }

    public static Map<String, String> collectResources(AbstractFilesystem fs, String dirName) {
        return collectResources(fs, RuntimeContext.empty(), dirName);
    }

    public static Map<String, String> collectResources(
            AbstractFilesystem fs, RuntimeContext context, String dirName) {
        Map<String, String> out = new LinkedHashMap<>();
        walk(
                fs,
                context,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    if (relativePath.equals("SKILL.md") || relativePath.equals(INSTALL_META_FILE)) {
                        return;
                    }
                    String content = readUtf8(fs, context, absolutePath);
                    out.put(relativePath, content != null ? content : "");
                });
        return out;
    }

    public static AgentSkillsController.WorkspaceSkillInfo readWorkspaceSkill(
            AbstractFilesystem fs, String dirName) {
        return readWorkspaceSkill(fs, RuntimeContext.empty(), dirName);
    }

    public static AgentSkillsController.WorkspaceSkillInfo readWorkspaceSkill(
            AbstractFilesystem fs, RuntimeContext context, String dirName) {
        String content = readUtf8(fs, context, "/skills/" + dirName + "/SKILL.md");
        if (content == null) return null;
        String description = parseFrontMatterField(content, DESCRIPTION_LINE);
        String name = parseFrontMatterField(content, NAME_LINE);
        if (name == null || name.isBlank()) {
            name = dirName;
        }
        AgentSkillsController.SkillSize size = computeSize(fs, context, dirName);
        AgentSkillsController.SkillMarketplaceMeta meta = readInstallMeta(fs, context, dirName);
        String origin = meta != null ? ORIGIN_MARKETPLACE : ORIGIN_CUSTOM;
        return new AgentSkillsController.WorkspaceSkillInfo(
                dirName,
                name,
                description,
                size.totalBytes(),
                size.resourceCount(),
                fs.exists(context, "/skills/" + dirName + "/references"),
                fs.exists(context, "/skills/" + dirName + "/scripts"),
                origin,
                meta);
    }

    static AgentSkillsController.SkillMarketplaceMeta readInstallMeta(
            AbstractFilesystem fs, String dirName) {
        return readInstallMeta(fs, RuntimeContext.empty(), dirName);
    }

    static AgentSkillsController.SkillMarketplaceMeta readInstallMeta(
            AbstractFilesystem fs, RuntimeContext context, String dirName) {
        String json = readUtf8(fs, context, "/skills/" + dirName + "/" + INSTALL_META_FILE);
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, AgentSkillsController.SkillMarketplaceMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    //  File writing (install meta + resources)
    // -----------------------------------------------------------------

    static void writeInstallMeta(
            WorkspaceManager wsm,
            String targetName,
            AgentSkillsController.SkillMarketplaceMeta meta) {
        writeInstallMeta(wsm, RuntimeContext.empty(), targetName, meta);
    }

    static void writeInstallMeta(
            WorkspaceManager wsm,
            RuntimeContext context,
            String targetName,
            AgentSkillsController.SkillMarketplaceMeta meta) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
            wsm.writeUtf8WorkspaceRelative(
                    context, "skills/" + targetName + "/" + INSTALL_META_FILE, json);
        } catch (Exception e) {
            log.warn(
                    "Failed to write {} for {}: {}", INSTALL_META_FILE, targetName, e.getMessage());
        }
    }

    public static void writeResources(
            WorkspaceManager wsm, String targetName, Map<String, String> resources) {
        writeResources(wsm, RuntimeContext.empty(), targetName, resources);
    }

    public static void writeResources(
            WorkspaceManager wsm,
            RuntimeContext context,
            String targetName,
            Map<String, String> resources) {
        if (resources == null) return;
        for (Map.Entry<String, String> e : resources.entrySet()) {
            String rel = e.getKey();
            if (rel == null || rel.isBlank()) continue;
            String safe = sanitiseRelativePath(rel);
            String body = e.getValue() != null ? e.getValue() : "";
            wsm.writeUtf8WorkspaceRelative(
                    context, "skills/" + targetName + "/" + safe, body);
        }
    }

    /**
     * Installs a complete skill bundle into the durable host workspace.
     *
     * <p>The bundle is fully materialised in a sibling staging directory before the target is
     * replaced. Control-plane installs therefore do not depend on a live Docker container and a
     * failed resource write cannot leave a half-created skill visible to the next agent rebuild.
     */
    public static AgentSkillsController.WorkspaceSkillInfo installDurable(
            Path workspaceRoot,
            String targetName,
            String markdown,
            Map<String, String> resources,
            AgentSkillsController.SkillMarketplaceMeta meta,
            boolean overwrite) {
        validateSkillName(targetName);
        if (markdown == null || markdown.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Skill bundle contains an empty SKILL.md");
        }

        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path skillsRoot = root.resolve("skills").normalize();
        Path target = skillsRoot.resolve(targetName).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Skill target escapes workspace: " + targetName);
        }

        Path staging = null;
        try {
            Files.createDirectories(skillsRoot);
            if (Files.exists(target) && !overwrite) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Workspace skill already exists: " + targetName);
            }

            staging =
                    Files.createDirectory(
                            skillsRoot.resolve(
                                    ".install-" + targetName + "-" + UUID.randomUUID()));
            Files.writeString(
                    staging.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
            writeDurableResources(staging, resources);
            if (meta != null) {
                Files.writeString(
                        staging.resolve(INSTALL_META_FILE),
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta),
                        StandardCharsets.UTF_8);
            }

            if (Files.exists(target)) {
                deleteTree(target);
            }
            moveDirectory(staging, target);
            staging = null;
            return readDurable(root, targetName);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to install skill '" + targetName + "': " + e.getMessage(),
                    e);
        } finally {
            if (staging != null) {
                try {
                    deleteTree(staging);
                } catch (IOException cleanupFailure) {
                    log.warn(
                            "Failed to clean skill staging directory {}: {}",
                            staging,
                            cleanupFailure.getMessage());
                }
            }
        }
    }

    /** Reads skill metadata directly from the durable workspace. */
    public static AgentSkillsController.WorkspaceSkillInfo readDurable(
            Path workspaceRoot, String targetName) {
        validateSkillName(targetName);
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path skillDir = root.resolve("skills").resolve(targetName).normalize();
        if (!skillDir.startsWith(root) || !Files.isDirectory(skillDir)) return null;
        try {
            Path skillFile = skillDir.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) return null;
            String markdown = Files.readString(skillFile, StandardCharsets.UTF_8);
            String description = parseFrontMatterField(markdown, DESCRIPTION_LINE);
            String name = parseFrontMatterField(markdown, NAME_LINE);
            if (name == null || name.isBlank()) name = targetName;

            long totalBytes = 0L;
            int resourceCount = 0;
            try (var files = Files.walk(skillDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String relative = skillDir.relativize(file).toString().replace('\\', '/');
                    if (INSTALL_META_FILE.equals(relative)) continue;
                    totalBytes += Files.size(file);
                    if (!"SKILL.md".equals(relative)) resourceCount++;
                }
            }

            AgentSkillsController.SkillMarketplaceMeta meta = null;
            Path metaFile = skillDir.resolve(INSTALL_META_FILE);
            if (Files.isRegularFile(metaFile)) {
                meta = MAPPER.readValue(metaFile.toFile(), AgentSkillsController.SkillMarketplaceMeta.class);
            }
            return new AgentSkillsController.WorkspaceSkillInfo(
                    targetName,
                    name,
                    description,
                    totalBytes,
                    resourceCount,
                    Files.isDirectory(skillDir.resolve("references")),
                    Files.isDirectory(skillDir.resolve("scripts")),
                    meta != null ? ORIGIN_MARKETPLACE : ORIGIN_CUSTOM,
                    meta);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Lists all skill bundles from the durable workspace without acquiring a sandbox lease. */
    public static List<AgentSkillsController.WorkspaceSkillInfo> listDurable(Path workspaceRoot) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path skillsRoot = root.resolve("skills").normalize();
        if (!Files.isDirectory(skillsRoot)) return List.of();
        List<AgentSkillsController.WorkspaceSkillInfo> result = new ArrayList<>();
        try (var children = Files.list(skillsRoot)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".install-")) continue;
                AgentSkillsController.WorkspaceSkillInfo skill = readDurable(root, name);
                if (skill != null) result.add(skill);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.comparing(AgentSkillsController.WorkspaceSkillInfo::name));
        return result;
    }

    /** Reads SKILL.md and bundle resources directly from the durable workspace. */
    public static AgentSkillsController.WorkspaceSkillDetail readDurableDetail(
            Path workspaceRoot, String targetName) {
        validateSkillName(targetName);
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path skillDir = root.resolve("skills").resolve(targetName).normalize();
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!skillDir.startsWith(root) || !Files.isRegularFile(skillFile)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "SKILL.md missing for: " + targetName);
        }
        try {
            String markdown = Files.readString(skillFile, StandardCharsets.UTF_8);
            Map<String, String> resources = new LinkedHashMap<>();
            try (var files = Files.walk(skillDir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String relative = skillDir.relativize(file).toString().replace('\\', '/');
                    if ("SKILL.md".equals(relative) || INSTALL_META_FILE.equals(relative)) continue;
                    resources.put(relative, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            return new AgentSkillsController.WorkspaceSkillDetail(
                    targetName,
                    parseFrontMatterField(markdown, DESCRIPTION_LINE),
                    markdown,
                    resources);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces a user-edited durable skill bundle while preserving its marketplace metadata. */
    public static AgentSkillsController.WorkspaceSkillInfo upsertDurable(
            Path workspaceRoot,
            String targetName,
            String markdown,
            Map<String, String> resources) {
        AgentSkillsController.WorkspaceSkillInfo existing = readDurable(workspaceRoot, targetName);
        AgentSkillsController.SkillMarketplaceMeta meta =
                existing != null ? existing.marketplace() : null;
        return installDurable(
                workspaceRoot, targetName, markdown, resources, meta, true);
    }

    /** Deletes a durable skill bundle. */
    public static void deleteDurable(Path workspaceRoot, String targetName) {
        validateSkillName(targetName);
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path target = root.resolve("skills").resolve(targetName).normalize();
        if (!target.startsWith(root) || !Files.isDirectory(target)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Skill not found: " + targetName);
        }
        try {
            deleteTree(target);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to delete skill '" + targetName + "': " + e.getMessage(),
                    e);
        }
    }

    private static void writeDurableResources(Path skillDir, Map<String, String> resources)
            throws IOException {
        if (resources == null) return;
        for (Map.Entry<String, String> resource : resources.entrySet()) {
            if (resource.getKey() == null || resource.getKey().isBlank()) continue;
            String safe = sanitiseRelativePath(resource.getKey());
            Path target = skillDir.resolve(safe).normalize();
            if (!target.startsWith(skillDir)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Resource escapes skill directory: " + safe);
            }
            Files.createDirectories(target.getParent());
            Files.writeString(
                    target,
                    resource.getValue() != null ? resource.getValue() : "",
                    StandardCharsets.UTF_8);
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            try {
                paths.sorted(Comparator.reverseOrder()).forEach(SkillFileService::deleteUnchecked);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    private static void deleteUnchecked(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -----------------------------------------------------------------
    //  Front-matter parsing & validation
    // -----------------------------------------------------------------

    public static String parseFrontMatterField(String markdown, Pattern fieldPattern) {
        if (markdown == null) return null;
        Matcher m = FRONT_MATTER.matcher(markdown);
        if (!m.find()) return null;
        Matcher f = fieldPattern.matcher(m.group(1));
        if (!f.find()) return null;
        String value = f.group(1).trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    public static void validateSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid skill name: " + name);
        }
    }

    static String sanitiseRelativePath(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resource path is required");
        }
        String normalized = relative.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid resource path: " + relative);
        }
        return normalized;
    }
}
