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
package io.agentscope.dataagent.agent.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.LinkedHashMap;
import java.util.Map;
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
    static final Pattern DESCRIPTION_LINE =
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
        LsResult ls = fs.ls(null, rootAbs);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return;
        for (FileInfo info : ls.entries()) {
            String abs = info.path();
            String name = leafName(abs);
            if (name.isBlank()) continue;
            if (info.isDirectory()) {
                walk(fs, abs, relativeBase, visitor);
            } else {
                String rel =
                        abs.startsWith(relativeBase) ? abs.substring(relativeBase.length()) : name;
                visitor.visit(rel, abs);
            }
        }
    }

    static long fileSize(AbstractFilesystem fs, String absolutePath) {
        // The cheap path — info.size() from ls — is already consumed by the caller's walk; we
        // re-stat via a parent ls to keep this function self-contained. One shell exec per call
        // in the sandbox-backed filesystem, used only on individual workspace skill directories.
        int slash = absolutePath.lastIndexOf('/');
        if (slash <= 0) return 0L;
        String parent = absolutePath.substring(0, slash);
        String name = absolutePath.substring(slash + 1);
        LsResult ls = fs.ls(null, parent);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return 0L;
        for (FileInfo info : ls.entries()) {
            if (leafName(info.path()).equals(name)) return info.size();
        }
        return 0L;
    }

    // -----------------------------------------------------------------
    //  File reading
    // -----------------------------------------------------------------

    static String readUtf8(AbstractFilesystem fs, String absolutePath) {
        ReadResult r = fs.read(null, absolutePath, 0, Integer.MAX_VALUE);
        if (r == null || !r.isSuccess() || r.fileData() == null) return null;
        return r.fileData().content();
    }

    static String leafName(String absolutePath) {
        if (absolutePath == null) return "";
        int slash = absolutePath.lastIndexOf('/');
        return slash >= 0 ? absolutePath.substring(slash + 1) : absolutePath;
    }

    // -----------------------------------------------------------------
    //  Skill metadata (size, resources, workspace skill info, install meta)
    // -----------------------------------------------------------------

    static AgentSkillsController.SkillSize computeSize(AbstractFilesystem fs, String dirName) {
        long[] total = new long[] {0L};
        int[] count = new int[] {0};
        walk(
                fs,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    if (relativePath.equals(INSTALL_META_FILE)) return;
                    total[0] += fileSize(fs, absolutePath);
                    if (!relativePath.equals("SKILL.md")) count[0]++;
                });
        return new AgentSkillsController.SkillSize(total[0], count[0]);
    }

    static Map<String, String> collectResources(AbstractFilesystem fs, String dirName) {
        Map<String, String> out = new LinkedHashMap<>();
        walk(
                fs,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    if (relativePath.equals("SKILL.md") || relativePath.equals(INSTALL_META_FILE)) {
                        return;
                    }
                    String content = readUtf8(fs, absolutePath);
                    out.put(relativePath, content != null ? content : "");
                });
        return out;
    }

    static AgentSkillsController.WorkspaceSkillInfo readWorkspaceSkill(
            AbstractFilesystem fs, String dirName) {
        String content = readUtf8(fs, "/skills/" + dirName + "/SKILL.md");
        if (content == null) return null;
        String description = parseFrontMatterField(content, DESCRIPTION_LINE);
        String name = parseFrontMatterField(content, NAME_LINE);
        if (name == null || name.isBlank()) {
            name = dirName;
        }
        AgentSkillsController.SkillSize size = computeSize(fs, dirName);
        AgentSkillsController.SkillMarketplaceMeta meta = readInstallMeta(fs, dirName);
        String origin = meta != null ? ORIGIN_MARKETPLACE : ORIGIN_CUSTOM;
        return new AgentSkillsController.WorkspaceSkillInfo(
                dirName,
                name,
                description,
                size.totalBytes(),
                size.resourceCount(),
                fs.exists(null, "/skills/" + dirName + "/references"),
                fs.exists(null, "/skills/" + dirName + "/scripts"),
                origin,
                meta);
    }

    static AgentSkillsController.SkillMarketplaceMeta readInstallMeta(
            AbstractFilesystem fs, String dirName) {
        String json = readUtf8(fs, "/skills/" + dirName + "/" + INSTALL_META_FILE);
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
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), "skills/" + targetName + "/" + INSTALL_META_FILE, json);
        } catch (Exception e) {
            log.warn(
                    "Failed to write {} for {}: {}", INSTALL_META_FILE, targetName, e.getMessage());
        }
    }

    static void writeResources(
            WorkspaceManager wsm, String targetName, Map<String, String> resources) {
        if (resources == null) return;
        for (Map.Entry<String, String> e : resources.entrySet()) {
            String rel = e.getKey();
            if (rel == null || rel.isBlank()) continue;
            String safe = sanitiseRelativePath(rel);
            String body = e.getValue() != null ? e.getValue() : "";
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), "skills/" + targetName + "/" + safe, body);
        }
    }

    // -----------------------------------------------------------------
    //  Front-matter parsing & validation
    // -----------------------------------------------------------------

    static String parseFrontMatterField(String markdown, Pattern fieldPattern) {
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

    static void validateSkillName(String name) {
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
