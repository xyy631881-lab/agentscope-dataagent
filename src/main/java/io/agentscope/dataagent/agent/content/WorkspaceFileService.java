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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.activity.ActivityEvent;
import io.agentscope.dataagent.agent.activity.AgentActivityStore;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Extracts all file CRUD business logic from {@link AgentWorkspaceController}.
 *
 * <p>The controller handles HTTP, authentication, and workspace resolution, then delegates the
 * actual file operations to this service. All methods receive a pre-resolved
 * {@link WorkspaceResolutionService.ResolvedWorkspace} so that visibility and access checks remain
 * the controller's responsibility.
 *
 * @see AgentWorkspaceController
 */
@Service
public class WorkspaceFileService {

    private static final int MAX_FILE_SIZE = 512 * 1024;

    private final AgentActivityStore activity;

    public WorkspaceFileService(AgentActivityStore activity) {
        this.activity = activity;
    }

    // -----------------------------------------------------------------
    //  File CRUD
    // -----------------------------------------------------------------

    /**
     * Builds a file tree of the workspace.
     *
     * @param ctx       resolved workspace context
     * @param recursive if {@code true} descends up to 6 levels, otherwise only the top level
     * @return list of file nodes rooted at {@code /}
     */
    public List<AgentWorkspaceController.FileNode> tree(
            WorkspaceResolutionService.ResolvedWorkspace ctx, boolean recursive) {
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        return collectChildrenFs(fs, "/", recursive ? 6 : 1);
    }

    /**
     * Reads a file's text content.
     *
     * @param ctx  resolved workspace context
     * @param path workspace-relative path
     * @return the file content, or a truncation notice if it exceeds {@link #MAX_FILE_SIZE}
     */
    public String readFile(WorkspaceResolutionService.ResolvedWorkspace ctx, String path) {
        String rel = validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        if (!fs.exists(rc, rel)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
        }
        ReadResult rr = fs.read(rc, rel, 0, 0);
        if (!rr.isSuccess()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Read failed: " + rr.error());
        }
        String content =
                rr.fileData() != null && rr.fileData().content() != null
                        ? rr.fileData().content()
                        : "";
        if (content.length() > MAX_FILE_SIZE) {
            return "(file too large to display: " + content.length() + " bytes)";
        }
        return content;
    }

    /**
     * Writes text content to a file, creating it if it does not exist.
     *
     * @param ctx     resolved workspace context
     * @param agentId agent identifier (for activity logging)
     * @param userId  acting user (for activity logging)
     * @param path    workspace-relative path
     * @param content the text to write
     * @return a {@link AgentWorkspaceController.FileNode} describing the written file
     */
    public AgentWorkspaceController.FileNode writeFile(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String agentId,
            String userId,
            String path,
            String content) {
        String rel = validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        if (isDirectoryEntry(fs, rc, rel)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Path is a directory: " + path);
        }
        boolean existed = fs.exists(rc, rel);
        String contentStr = content != null ? content : "";
        ctx.manager().writeUtf8WorkspaceRelative(rc, rel, contentStr);
        if (ctx.ownerId() != null) {
            activity.record(
                    ctx.ownerId(),
                    agentId,
                    activity.actor(userId),
                    existed
                            ? ActivityEvent.Action.EDIT_FILE
                            : ActivityEvent.Action.CREATE_FILE,
                    path,
                    null);
        }
        return fileNode(
                rel, false, (long) contentStr.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * Creates a new file or directory node.
     *
     * <p>Directories are materialised as a {@code .keep} file inside them.
     *
     * @param ctx     resolved workspace context
     * @param agentId agent identifier (for activity logging)
     * @param userId  acting user (for activity logging)
     * @param path    workspace-relative path
     * @param type    {@code "file"} or {@code "dir"} (case-insensitive)
     * @return a {@link AgentWorkspaceController.FileNode} describing the created node
     */
    public AgentWorkspaceController.FileNode createNode(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String agentId,
            String userId,
            String path,
            String type) {
        String rel = validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        boolean isDir = "dir".equalsIgnoreCase(type);
        String materialised = isDir ? rel + "/.keep" : rel;
        if (fs.exists(rc, materialised)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Already exists: " + path);
        }
        ctx.manager().writeUtf8WorkspaceRelative(rc, materialised, "");
        if (ctx.ownerId() != null && !isDir) {
            activity.record(
                    ctx.ownerId(),
                    agentId,
                    activity.actor(userId),
                    ActivityEvent.Action.CREATE_FILE,
                    path,
                    null);
        }
        return fileNode(rel, isDir, isDir ? null : 0L);
    }

    /**
     * Moves/renames a file or directory.
     *
     * @param ctx     resolved workspace context
     * @param agentId agent identifier (for activity logging)
     * @param userId  acting user (for activity logging)
     * @param from    source workspace-relative path
     * @param to      destination workspace-relative path
     * @return a {@link AgentWorkspaceController.FileNode} describing the destination
     */
    public AgentWorkspaceController.FileNode moveNode(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String agentId,
            String userId,
            String from,
            String to) {
        String fromRel = validateRelPath(from);
        String toRel = validateRelPath(to);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        if (!fs.exists(rc, fromRel)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Source not found: " + from);
        }
        if (fs.exists(rc, toRel)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Target already exists: " + to);
        }
        WriteResult mv = fs.move(rc, fromRel, toRel);
        if (!mv.isSuccess()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Move failed: " + mv.error());
        }
        if (ctx.ownerId() != null) {
            activity.record(
                    ctx.ownerId(),
                    agentId,
                    activity.actor(userId),
                    ActivityEvent.Action.RENAME_FILE,
                    to,
                    Map.of("from", from));
        }
        return fileNode(toRel, isDirectoryEntry(fs, rc, toRel), null);
    }

    /**
     * Deletes a file or directory.
     *
     * @param ctx     resolved workspace context
     * @param agentId agent identifier (for activity logging)
     * @param userId  acting user (for activity logging)
     * @param path    workspace-relative path
     */
    public void deleteNode(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String agentId,
            String userId,
            String path) {
        String rel = validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        if (!fs.exists(rc, rel)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: " + path);
        }
        WriteResult wr = fs.delete(rc, rel);
        if (!wr.isSuccess()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Delete failed: " + wr.error());
        }
        if (ctx.ownerId() != null) {
            activity.record(
                    ctx.ownerId(),
                    agentId,
                    activity.actor(userId),
                    ActivityEvent.Action.DELETE_FILE,
                    path,
                    null);
        }
    }

    /**
     * Uploads a multipart file into the workspace.
     *
     * @param ctx     resolved workspace context
     * @param agentId agent identifier (for activity logging)
     * @param userId  acting user (for activity logging)
     * @param path    destination directory (workspace-relative)
     * @param file    the multipart file to upload
     * @return a {@link AgentWorkspaceController.FileNode} describing the uploaded file
     */
    public AgentWorkspaceController.FileNode upload(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String agentId,
            String userId,
            String path,
            org.springframework.web.multipart.MultipartFile file) {
        String dirRel = validateRelPath(path);
        String filename = sanitiseFilename(file.getOriginalFilename());
        String targetRel = (dirRel.isEmpty() ? "" : dirRel + "/") + filename;
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read uploaded file: " + e.getMessage());
        }
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        List<FileUploadResponse> resp =
                fs.uploadFiles(
                        RuntimeContext.empty(),
                        List.of(Map.entry(targetRel, bytes)));
        if (!resp.isEmpty() && resp.get(0).error() != null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Upload failed: " + resp.get(0).error());
        }
        if (ctx.ownerId() != null) {
            activity.record(
                    ctx.ownerId(),
                    agentId,
                    activity.actor(userId),
                    ActivityEvent.Action.UPLOAD_FILE,
                    targetRel,
                    null);
        }
        return fileNode(targetRel, false, (long) bytes.length);
    }

    // -----------------------------------------------------------------
    //  Static helpers
    // -----------------------------------------------------------------

    /**
     * Validates a caller-supplied workspace-relative path. Rejects null/blank, absolute paths,
     * and any segment equal to {@code ".."} or starting with {@code "."}. Returns the trimmed
     * value with backslashes normalised to forward slashes.
     */
    private static String validateRelPath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        String trimmed = path.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path: " + path);
            }
        }
        return trimmed;
    }

    private static String sanitiseFilename(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing filename");
        }
        String trimmed = name.replace("\\", "/");
        int slash = trimmed.lastIndexOf('/');
        String basename = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        return basename;
    }

    /**
     * Recursively walks the composite filesystem starting at {@code absPath} (an absolute path
     * understood by the filesystem — {@code "/"} for root, {@code "/memory"} for a subdir),
     * building {@link AgentWorkspaceController.FileNode}s with workspace-relative paths suitable
     * for the public API.
     *
     * <p>Entries are de-duplicated by relative path: the composite root listing may surface both
     * a routed virtual directory ({@code /memory/}) and a same-named entry from the default
     * store, in which case the routed entry wins.
     */
    private static List<AgentWorkspaceController.FileNode> collectChildrenFs(
            AbstractFilesystem fs, String absPath, int depth) {
        List<AgentWorkspaceController.FileNode> out = new ArrayList<>();
        if (depth <= 0) {
            return out;
        }
        LsResult ls = fs.ls(RuntimeContext.empty(), absPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return out;
        }
        java.util.LinkedHashMap<String, AgentWorkspaceController.FileNode> bySeg =
                new java.util.LinkedHashMap<>();
        String prefix = "/".equals(absPath) ? "" : trimTrailingSlash(absPath) + "/";
        for (FileInfo fi : ls.entries()) {
            String basename = basenameFromFiPath(fi.path());
            if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
                continue;
            }
            String rel =
                    prefix.isEmpty()
                            ? basename
                            : (prefix.startsWith("/")
                                    ? prefix.substring(1) + basename
                                    : prefix + basename);
            if (fi.isDirectory()) {
                String childAbs = "/" + rel;
                List<AgentWorkspaceController.FileNode> children =
                        collectChildrenFs(fs, childAbs, depth - 1);
                bySeg.put(
                        basename,
                        new AgentWorkspaceController.FileNode(
                                basename, rel, "dir", null, children));
            } else {
                if (!bySeg.containsKey(basename)) {
                    bySeg.put(
                            basename,
                            new AgentWorkspaceController.FileNode(
                                    basename, rel, "file", fi.size(), null));
                }
            }
        }
        out.addAll(bySeg.values());
        out.sort(
                Comparator.<AgentWorkspaceController.FileNode, Integer>comparing(
                                n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(AgentWorkspaceController.FileNode::name));
        return out;
    }

    /**
     * Returns whether {@code relPath} exists in {@code fs} as a directory entry. Implemented by
     * listing the parent directory and matching basenames — there is no dedicated
     * {@code isDirectory} on {@link AbstractFilesystem}.
     */
    private static boolean isDirectoryEntry(
            AbstractFilesystem fs, RuntimeContext rc, String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return true;
        }
        int slash = relPath.lastIndexOf('/');
        String parent = slash > 0 ? "/" + relPath.substring(0, slash) : "/";
        String base = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        LsResult ls = fs.ls(rc, parent);
        if (!ls.isSuccess() || ls.entries() == null) {
            return false;
        }
        for (FileInfo fi : ls.entries()) {
            if (basenameFromFiPath(fi.path()).equals(base)) {
                return fi.isDirectory();
            }
        }
        return false;
    }

    private static AgentWorkspaceController.FileNode fileNode(String rel, boolean isDir, Long size) {
        int slash = rel.lastIndexOf('/');
        String name = slash >= 0 ? rel.substring(slash + 1) : rel;
        return new AgentWorkspaceController.FileNode(
                name, rel, isDir ? "dir" : "file", isDir ? null : size, null);
    }

    private static String basenameFromFiPath(String fiPath) {
        if (fiPath == null) return "";
        String s = trimTrailingSlash(fiPath);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
