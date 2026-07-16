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
import io.agentscope.dataagent.agent.api.AgentWorkspaceController;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.domain.ActivityEvent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * Builds the user-isolation {@link RuntimeContext} for workspace filesystem operations.
     * Carrying the userId routes the sandbox slot lookup to the same namespace the agent execution
     * uses — {@code RuntimeContext.empty()} would drop the isolation key and resolve to a fresh,
     * empty sandbox slot (the "empty workspace tree" symptom).
     */
    private static RuntimeContext userContext(WorkspaceResolutionService.ResolvedWorkspace ctx) {
        return RuntimeContext.builder().userId(ctx.ownerId()).build();
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
        List<AgentWorkspaceController.FileNode> sandboxTree = WorkspaceFileSupport.collectChildrenFs(
                fs, userContext(ctx), "/", recursive ? 6 : 1);
        Path mirror = localMirrorRoot(ctx);
        if (mirror == null) {
            return sandboxTree;
        }
        return WorkspaceFileSupport.mergeTrees(
                sandboxTree, WorkspaceFileSupport.collectChildrenHost(mirror, recursive ? 6 : 1));
    }

    /**
     * Reads a file's text content.
     *
     * @param ctx  resolved workspace context
     * @param path workspace-relative path
     * @return the file content, or a truncation notice if it exceeds {@link #MAX_FILE_SIZE}
     */
    public String readFile(WorkspaceResolutionService.ResolvedWorkspace ctx, String path) {
        String rel = WorkspaceFileSupport.validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = userContext(ctx);
        try {
            if (fs.exists(rc, rel)) {
                ReadResult rr = fs.read(rc, rel, 0, 0);
                if (rr.isSuccess()) {
                    String content =
                            rr.fileData() != null && rr.fileData().content() != null
                                    ? rr.fileData().content()
                                    : "";
                    return truncate(content);
                }
            }
        } catch (Exception ignored) {
            // The read-only local mirror remains available after Docker has reclaimed a sandbox.
        }
        Path mirrorFile = localMirrorFile(ctx, rel);
        if (mirrorFile != null && Files.isRegularFile(mirrorFile)) {
            try {
                return truncate(Files.readString(mirrorFile, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Read local mirror failed: " + e.getMessage());
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
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
        String rel = WorkspaceFileSupport.validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = userContext(ctx);
        if (WorkspaceFileSupport.isDirectoryEntry(fs, rc, rel)) {
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
        return WorkspaceFileSupport.fileNode(
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
        String rel = WorkspaceFileSupport.validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = userContext(ctx);
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
        return WorkspaceFileSupport.fileNode(rel, isDir, isDir ? null : 0L);
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
        String fromRel = WorkspaceFileSupport.validateRelPath(from);
        String toRel = WorkspaceFileSupport.validateRelPath(to);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = userContext(ctx);
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
        return WorkspaceFileSupport.fileNode(toRel, WorkspaceFileSupport.isDirectoryEntry(fs, rc, toRel), null);
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
        String rel = WorkspaceFileSupport.validateRelPath(path);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = userContext(ctx);
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
        String dirRel = WorkspaceFileSupport.validateRelPath(path);
        String filename = WorkspaceFileSupport.sanitiseFilename(file.getOriginalFilename());
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
                        RuntimeContext.builder().userId(ctx.ownerId()).build(),
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
        return WorkspaceFileSupport.fileNode(targetRel, false, (long) bytes.length);
    }

    private static String truncate(String content) {
        if (content.length() > MAX_FILE_SIZE) {
            return "(file too large to display: " + content.length() + " bytes)";
        }
        return content;
    }

    private static Path localMirrorRoot(WorkspaceResolutionService.ResolvedWorkspace ctx) {
        if (ctx.localMirrorPath() == null || ctx.localMirrorPath().isBlank()) {
            return null;
        }
        Path mirror = Path.of(ctx.localMirrorPath()).toAbsolutePath().normalize();
        return Files.isDirectory(mirror) ? mirror : null;
    }

    private static Path localMirrorFile(
            WorkspaceResolutionService.ResolvedWorkspace ctx, String relativePath) {
        Path root = localMirrorRoot(ctx);
        if (root == null) {
            return null;
        }
        Path candidate = root.resolve(relativePath).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    // -----------------------------------------------------------------
    //  Static helpers
    // -----------------------------------------------------------------

    /**
     * Validates a caller-supplied workspace-relative path. Rejects null/blank, absolute paths,
     * and any segment equal to {@code ".."} or starting with {@code "."}. Returns the trimmed
     * value with backslashes normalised to forward slashes.
     */



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



    /**
     * Returns whether {@code relPath} exists in {@code fs} as a directory entry. Implemented by
     * listing the parent directory and matching basenames — there is no dedicated
     * {@code isDirectory} on {@link AbstractFilesystem}.
     */


}
