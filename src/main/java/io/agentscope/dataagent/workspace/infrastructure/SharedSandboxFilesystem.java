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
package io.agentscope.dataagent.workspace.infrastructure;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Browser-facing filesystem backed by the same AgentScope sandbox lifecycle as agent execution.
 *
 * <p>The filesystem never keeps a raw container reference. Every operation receives a framework
 * lease, starts the sandbox, persists the state, and releases the lease. This lets web workspace
 * APIs and agent tools share one isolation key without a second application-owned container pool.
 */
public final class SharedSandboxFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(SharedSandboxFilesystem.class);
    private static final int UPLOAD_CHUNK_BYTES = 8 * 1024;

    private final String fsId;
    private final SandboxManager sandboxManager;
    private final SandboxContext sandboxContext;
    private final RuntimeContext sandboxRuntimeContext;
    private final Path localMirrorDirectory;

    public SharedSandboxFilesystem(
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            AgentStateStore stateStore,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard,
            String userId,
            String agentId,
            Path localMirrorDirectory) {
        Objects.requireNonNull(sandboxClient, "sandboxClient");
        Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(snapshotSpec, "snapshotSpec");
        Objects.requireNonNull(executionGuard, "executionGuard");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(agentId, "agentId");
        this.sandboxManager =
                new SandboxManager(
                        sandboxClient,
                        new SessionSandboxStateStore(stateStore, agentId),
                        agentId,
                        executionGuard);
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/workspace");
        this.sandboxContext =
                SandboxContext.builder()
                        .client(sandboxClient)
                        // DockerSandbox persists the workspace using this path after each
                        // browser-side operation. Its default options leave it null on Windows,
                        // which produces a ProcessBuilder NPE during `docker exec ... tar`.
                        .clientOptions(new DockerSandboxClientOptions().workspaceRoot("/workspace"))
                        // Match the agent's DockerFilesystemSpec exactly. A different projection
                        // hash causes the framework to allocate another sandbox state slot, making
                        // plan files visible in Docker but absent from the browser workspace.
                        .workspaceSpec(workspaceSpec)
                        .snapshotSpec(snapshotSpec)
                        .isolationScope(IsolationScope.USER)
                        .build();
        this.sandboxRuntimeContext = RuntimeContext.builder().userId(userId).build();
        this.fsId = "framework-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
        this.localMirrorDirectory = localMirrorDirectory;
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        try {
            return withSandbox(
                    sandbox -> {
                        ExecResult result = sandbox.exec(runtimeContext, command, timeoutSeconds);
                        return new ExecuteResponse(
                                result.combinedOutput(), result.exitCode(), result.truncated());
                    });
        } catch (SandboxException.ExecTimeoutException e) {
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            return new ExecuteResponse(combinedOutput(e), e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[framework-sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        try {
            return withSandbox(sandbox -> uploadFiles(sandbox, runtimeContext, files));
        } catch (Exception e) {
            log.warn("[framework-sandbox-fs] uploadFiles failed", e);
            return files.stream()
                    .map(file -> FileUploadResponse.fail(file.getKey(), e.getMessage()))
                    .toList();
        }
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        try {
            return withSandbox(sandbox -> downloadFiles(sandbox, runtimeContext, paths));
        } catch (Exception e) {
            log.warn("[framework-sandbox-fs] downloadFiles failed", e);
            return paths.stream().map(path -> FileDownloadResponse.fail(path, e.getMessage())).toList();
        }
    }

    private List<FileUploadResponse> uploadFiles(
            Sandbox sandbox,
            RuntimeContext runtimeContext,
            List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> results = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> file : files) {
            results.add(uploadFile(sandbox, runtimeContext, file.getKey(), file.getValue()));
        }
        return results;
    }

    /**
     * Streams a binary browser upload in small base64 chunks. A whole PDF/DOCX encoded into one
     * `docker exec` command exceeds Windows' command-line limit long before Spring's upload limit
     * is reached, which made Markdown work while office documents silently failed.
     */
    static FileUploadResponse uploadFile(
            Sandbox sandbox, RuntimeContext runtimeContext, String path, byte[] content) {
        String quotedPath = FilesystemUtils.shellQuote(path);
        String temporaryPath = path + ".dataagent-upload-" + UUID.randomUUID();
        String quotedTemporaryPath = FilesystemUtils.shellQuote(temporaryPath);
        String stage = "setup";
        try {
            ExecResult setup =
                    sandbox.exec(
                            runtimeContext,
                            "mkdir -p $(dirname "
                                    + quotedPath
                                    + ") && : > "
                                    + quotedTemporaryPath,
                            null);
            if (!setup.ok()) {
                return FileUploadResponse.fail(path, setup.combinedOutput());
            }
            for (int offset = 0; offset < content.length; offset += UPLOAD_CHUNK_BYTES) {
                stage = "append@" + offset;
                int length = Math.min(UPLOAD_CHUNK_BYTES, content.length - offset);
                byte[] chunk = java.util.Arrays.copyOfRange(content, offset, offset + length);
                String encoded = Base64.getEncoder().encodeToString(chunk);
                ExecResult append =
                        sandbox.exec(
                                runtimeContext,
                                "printf '%s' "
                                        + FilesystemUtils.shellQuote(encoded)
                                        + " | base64 -d >> "
                                        + quotedTemporaryPath,
                                null);
                if (!append.ok()) {
                    deleteTemporaryUpload(sandbox, runtimeContext, quotedTemporaryPath);
                    return FileUploadResponse.fail(path, append.combinedOutput());
                }
            }
            stage = "commit";
            ExecResult commit =
                    sandbox.exec(
                            runtimeContext,
                            "mv " + quotedTemporaryPath + " " + quotedPath,
                            null);
            if (!commit.ok()) {
                deleteTemporaryUpload(sandbox, runtimeContext, quotedTemporaryPath);
                return FileUploadResponse.fail(path, commit.combinedOutput());
            }
            return FileUploadResponse.success(path);
        } catch (SandboxException.ExecException e) {
            deleteTemporaryUpload(sandbox, runtimeContext, quotedTemporaryPath);
            return FileUploadResponse.fail(path, combinedOutput(e));
        } catch (Exception e) {
            deleteTemporaryUpload(sandbox, runtimeContext, quotedTemporaryPath);
            log.warn("[framework-sandbox-fs] upload failed at {} for {}", stage, path, e);
            return FileUploadResponse.fail(path, e.getMessage());
        }
    }

    private static void deleteTemporaryUpload(
            Sandbox sandbox, RuntimeContext runtimeContext, String quotedTemporaryPath) {
        try {
            sandbox.exec(runtimeContext, "rm -f " + quotedTemporaryPath, null);
        } catch (Exception ignored) {
            // Preserve the original upload failure.
        }
    }

    private List<FileDownloadResponse> downloadFiles(
            Sandbox sandbox, RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());
        for (String path : paths) {
            try {
                ExecResult result =
                        sandbox.exec(runtimeContext, "base64 " + FilesystemUtils.shellQuote(path), null);
                results.add(
                        result.ok()
                                ? FileDownloadResponse.success(
                                        path,
                                        Base64.getDecoder()
                                                .decode(result.stdout().trim().getBytes(StandardCharsets.UTF_8)))
                                : FileDownloadResponse.fail(path, result.combinedOutput()));
            } catch (SandboxException.ExecException e) {
                results.add(FileDownloadResponse.fail(path, combinedOutput(e)));
            } catch (Exception e) {
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }

    private <T> T withSandbox(SandboxAction<T> action) throws Exception {
        SandboxAcquireResult acquired = sandboxManager.acquire(sandboxContext, sandboxRuntimeContext);
        boolean started = false;
        try {
            acquired.getSandbox().start();
            started = true;
            T value = action.apply(acquired.getSandbox());
            mirrorToHost(acquired.getSandbox());
            return value;
        } finally {
            try {
                if (started) {
                    sandboxManager.persistState(acquired, sandboxContext, sandboxRuntimeContext);
                }
            } finally {
                try {
                    sandboxManager.release(acquired);
                } finally {
                    acquired.getLease().close();
                }
            }
        }
    }

    private static String combinedOutput(SandboxException.ExecException e) {
        return (e.getStdout() != null ? e.getStdout() : "")
                + (e.getStderr() != null && !e.getStderr().isBlank()
                        ? "\n" + e.getStderr()
                        : "");
    }

    /**
     * Exports the current user-isolated workspace after a successful browser operation. This is a
     * one-way local mirror for inspection; writes still go through the workspace API and sandbox
     * permission model.
     */
    private void mirrorToHost(Sandbox sandbox) {
        if (localMirrorDirectory == null || !(sandbox.getState() instanceof DockerSandboxState state)) {
            return;
        }
        String containerId = state.getContainerId();
        if (containerId == null || containerId.isBlank()) return;
        Path target = localMirrorDirectory.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || target.getFileName() == null || target.equals(target.getRoot())) {
            log.warn("[workspace-mirror] refused unsafe mirror target {}", target);
            return;
        }
        Path staging = parent.resolve("." + target.getFileName() + ".stage-" + UUID.randomUUID());
        try {
            Files.createDirectories(parent);
            Files.createDirectories(staging);
            // `docker cp <container>:/workspace/. <staging>` mis-routes Windows paths whose
            // first component contains a drive letter (the daemon sees `E:` and treats it as
            // a container id, producing `mkdir <staging>\E:`). Stream the tar via the framework's
            // own `persistWorkspace()` (which runs `docker exec tar c -C /workspace .` and
            // returns the bytes in memory) and extract locally with commons-compress — no Windows
            // path crosses the daemon boundary.
            try (InputStream tarStream = sandbox.persistWorkspace()) {
                extractTarToStaging(tarStream, staging);
            }
            Files.writeString(
                    staging.resolve(".dataagent-mirror"),
                    "Read-only mirror of sandbox /workspace. Last sync: " + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            replaceMirrorDirectory(staging, target);
        } catch (IOException e) {
            log.warn("[workspace-mirror] failed to sync {}: {}", target, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[workspace-mirror] interrupted while syncing {}", target);
        } catch (Exception e) {
            log.warn("[workspace-mirror] failed to read workspace from {}: {}", containerId, e.getMessage());
        } finally {
            deleteRecursivelyQuietly(staging);
        }
    }

    static void extractTarToStaging(InputStream tarStream, Path staging) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new BufferedInputStream(tarStream))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                // The framework's `tar -cf - -C /workspace .` produces entries like
                // `./file1`, `./subdir/file2`. Strip the leading `./` so the mirror ends up
                // directly under staging (which already represents /workspace).
                String stripped;
                if (name.startsWith("./")) {
                    stripped = name.substring(2);
                } else if (name.startsWith("/")) {
                    stripped = name.substring(1);
                } else {
                    stripped = name;
                }
                // A bind mount whose container target reuses the host path can leave a literal
                // `E:/...` (or `C:/...`) directory inside the sandbox workspace. On Windows,
                // `Path.resolve("E:/myskills/...")` discards `staging` and returns the absolute
                // path, which then fails the startsWith check below and the entries are skipped
                // forever. Strip the drive letter so the suffix lands as a sibling under staging.
                if (stripped.length() >= 2
                        && Character.isLetter(stripped.charAt(0))
                        && stripped.charAt(1) == ':') {
                    stripped = stripped.substring(2);
                    if (!stripped.isEmpty()
                            && (stripped.charAt(0) == '/' || stripped.charAt(0) == '\\')) {
                        stripped = stripped.substring(1);
                    }
                }
                if (stripped.isEmpty()) continue;
                Path target = staging.resolve(stripped).normalize();
                if (!target.startsWith(staging)) {
                    log.warn("[workspace-mirror] skipping entry that escapes staging: {}", name);
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (OutputStream out = Files.newOutputStream(target)) {
                        tar.transferTo(out);
                    }
                }
            }
        }
    }

    static void replaceMirrorDirectory(Path staging, Path target) throws IOException {
        Path normalizedStaging = staging.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null
                || normalizedTarget.getFileName() == null
                || normalizedTarget.equals(normalizedTarget.getRoot())
                || !normalizedStaging.getParent().equals(parent)) {
            throw new IOException("Unsafe workspace mirror replacement target");
        }
        // Renaming a directory that contains a file still held by an editor, AV scanner, or file
        // indexer is unreliable on Windows. The old publish protocol moved the current mirror
        // away first, so a failed second rename made the UI momentarily (and sometimes
        // permanently) see an empty workspace. Synchronising the staged snapshot in place keeps
        // the visible directory stable while still making deleted sandbox files disappear.
        try {
            Files.createDirectories(normalizedTarget);
            copyStagingIntoMirror(normalizedStaging, normalizedTarget);
            removeEntriesAbsentFromStaging(normalizedStaging, normalizedTarget);
        } finally {
            deleteRecursivelyQuietly(normalizedStaging);
        }
    }

    private static void copyStagingIntoMirror(Path staging, Path target) throws IOException {
        try (var entries = Files.walk(staging)) {
            for (Path source : entries.sorted().toList()) {
                Path relative = staging.relativize(source);
                if (relative.toString().isEmpty()) {
                    continue;
                }
                Path destination = target.resolve(relative).normalize();
                if (!destination.startsWith(target)) {
                    throw new IOException("Workspace mirror entry escapes target: " + relative);
                }
                if (Files.isDirectory(source)) {
                    if (Files.exists(destination) && !Files.isDirectory(destination)) {
                        Files.delete(destination);
                    }
                    Files.createDirectories(destination);
                    continue;
                }
                if (Files.isDirectory(destination)) {
                    deleteRecursivelyQuietly(destination);
                }
                Path destinationParent = destination.getParent();
                if (destinationParent != null) {
                    Files.createDirectories(destinationParent);
                }
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void removeEntriesAbsentFromStaging(Path staging, Path target) throws IOException {
        try (var entries = Files.walk(target)) {
            for (Path destination : entries.sorted(Comparator.reverseOrder()).toList()) {
                if (destination.equals(target)) {
                    continue;
                }
                Path relative = target.relativize(destination);
                if (!Files.exists(staging.resolve(relative))) {
                    Files.delete(destination);
                }
            }
        }
    }

    private static void deleteRecursivelyQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var entries = Files.walk(path)) {
            entries.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // Best-effort cleanup of staging/backup directories.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of staging/backup directories.
        }
    }

    @FunctionalInterface
    private interface SandboxAction<T> {
        T apply(Sandbox sandbox) throws Exception;
    }
}
