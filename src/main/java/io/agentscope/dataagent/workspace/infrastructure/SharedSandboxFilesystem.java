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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
        this.sandboxContext =
                SandboxContext.builder()
                        .client(sandboxClient)
                        .clientOptions(new DockerSandboxClientOptions())
                        // Match the agent's DockerFilesystemSpec exactly. A different projection
                        // hash causes the framework to allocate another sandbox state slot, making
                        // plan files visible in Docker but absent from the browser workspace.
                        .workspaceSpec(new WorkspaceSpec())
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
            String path = file.getKey();
            try {
                String quotedPath = FilesystemUtils.shellQuote(path);
                String quotedB64 =
                        FilesystemUtils.shellQuote(Base64.getEncoder().encodeToString(file.getValue()));
                String command =
                        "mkdir -p $(dirname "
                                + quotedPath
                                + ") && printf '%s' "
                                + quotedB64
                                + " | base64 -d > "
                                + quotedPath;
                ExecResult result = sandbox.exec(runtimeContext, command, null);
                results.add(
                        result.ok()
                                ? FileUploadResponse.success(path)
                                : FileUploadResponse.fail(path, result.combinedOutput()));
            } catch (SandboxException.ExecException e) {
                results.add(FileUploadResponse.fail(path, combinedOutput(e)));
            } catch (Exception e) {
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
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
        try {
            Files.createDirectories(localMirrorDirectory);
            String workspaceRoot = state.getWorkspaceRoot();
            if (workspaceRoot == null || workspaceRoot.isBlank()) workspaceRoot = "/workspace";
            Process process = new ProcessBuilder(
                    "docker", "cp", containerId + ":" + workspaceRoot + "/.", localMirrorDirectory.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                process.destroyForcibly();
                log.warn("[workspace-mirror] docker cp timed out for {}", containerId);
                return;
            }
            if (process.exitValue() != 0) {
                log.warn("[workspace-mirror] docker cp failed for {}: {}", containerId, output);
                return;
            }
            Files.writeString(
                    localMirrorDirectory.resolve(".dataagent-mirror"),
                    "Read-only mirror of sandbox /workspace. Last sync: " + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[workspace-mirror] failed to sync {}: {}", localMirrorDirectory, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[workspace-mirror] interrupted while syncing {}", localMirrorDirectory);
        }
    }

    @FunctionalInterface
    private interface SandboxAction<T> {
        T apply(Sandbox sandbox) throws Exception;
    }
}
