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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAware;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows-safe variant of the AgentScope sandbox filesystem.
 *
 * <p>AgentScope 2.0-SNAPSHOT uploads a file by placing its complete Base64 payload in the
 * {@code docker exec ... sh -c} argument. Windows rejects that process once the command line is
 * too long (error 206), which breaks asynchronous session-tree JSONL mirroring. This class keeps
 * the framework's normal sandbox lifecycle and changes only the file-transfer transport: large
 * payloads are decoded into a temporary file in bounded chunks and atomically moved into place.
 *
 * <p>This compatibility override should be removed once the upstream harness streams upload
 * content through {@code docker exec -i} or otherwise avoids command-line payloads.
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);
    private static final int UPLOAD_CHUNK_BYTES = 8 * 1024;

    private final String fsId;
    private volatile Sandbox sandbox;

    public SandboxBackedFilesystem() {
        this.fsId = "sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public Sandbox getSandbox() {
        return sandbox;
    }

    @Override
    public String id() {
        return fsId;
    }

    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        Sandbox active = requireSandbox();
        try {
            ExecResult result = active.exec(runtimeContext, command, timeoutSeconds);
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            return new ExecuteResponse(combinedOutput(e), e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        Sandbox active = requireSandbox();
        List<FileUploadResponse> results = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> file : files) {
            results.add(uploadFile(active, runtimeContext, file.getKey(), file.getValue()));
        }
        return results;
    }

    private FileUploadResponse uploadFile(
            Sandbox active, RuntimeContext runtimeContext, String path, byte[] content) {
        String escapedPath = shellSingleQuote(path);
        String temporaryPath = path + ".agentscope-upload-" + UUID.randomUUID();
        String escapedTemporaryPath = shellSingleQuote(temporaryPath);
        try {
            ExecResult setup =
                    active.exec(
                            runtimeContext,
                            "mkdir -p $(dirname "
                                    + escapedPath
                                    + ") && : > "
                                    + escapedTemporaryPath,
                            null);
            if (!setup.ok()) {
                return FileUploadResponse.fail(path, setup.combinedOutput());
            }

            for (int offset = 0; offset < content.length; offset += UPLOAD_CHUNK_BYTES) {
                int length = Math.min(UPLOAD_CHUNK_BYTES, content.length - offset);
                byte[] chunk = java.util.Arrays.copyOfRange(content, offset, offset + length);
                String encoded = Base64.getEncoder().encodeToString(chunk);
                ExecResult append =
                        active.exec(
                                runtimeContext,
                                "printf '%s' '"
                                        + encoded
                                        + "' | base64 -d >> "
                                        + escapedTemporaryPath,
                                null);
                if (!append.ok()) {
                    deleteTemporaryFile(active, runtimeContext, escapedTemporaryPath);
                    return FileUploadResponse.fail(path, append.combinedOutput());
                }
            }

            ExecResult commit =
                    active.exec(
                            runtimeContext,
                            "mv " + escapedTemporaryPath + " " + escapedPath,
                            null);
            if (!commit.ok()) {
                deleteTemporaryFile(active, runtimeContext, escapedTemporaryPath);
                return FileUploadResponse.fail(path, commit.combinedOutput());
            }
            return FileUploadResponse.success(path);
        } catch (SandboxException.ExecException e) {
            deleteTemporaryFile(active, runtimeContext, escapedTemporaryPath);
            return FileUploadResponse.fail(path, combinedOutput(e));
        } catch (Exception e) {
            deleteTemporaryFile(active, runtimeContext, escapedTemporaryPath);
            log.warn("[sandbox-fs] uploadFiles failed for path: {}", path, e);
            return FileUploadResponse.fail(path, e.getMessage());
        }
    }

    private static void deleteTemporaryFile(
            Sandbox sandbox, RuntimeContext runtimeContext, String escapedTemporaryPath) {
        try {
            sandbox.exec(runtimeContext, "rm -f " + escapedTemporaryPath, null);
        } catch (Exception ignored) {
            // The original upload failure is the useful error to preserve.
        }
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        Sandbox active = requireSandbox();
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());
        for (String path : paths) {
            try {
                String escapedPath = shellSingleQuote(path);
                ExecResult result = active.exec(runtimeContext, "base64 " + escapedPath, null);
                results.add(
                        result.ok()
                                ? FileDownloadResponse.success(
                                        path,
                                        Base64.getDecoder()
                                                .decode(
                                                        result.stdout()
                                                                .trim()
                                                                .getBytes(StandardCharsets.UTF_8)))
                                : FileDownloadResponse.fail(path, result.combinedOutput()));
            } catch (SandboxException.ExecException e) {
                results.add(FileDownloadResponse.fail(path, combinedOutput(e)));
            } catch (Exception e) {
                log.warn("[sandbox-fs] downloadFiles failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }

    private Sandbox requireSandbox() {
        Sandbox active = sandbox;
        if (active == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "No active sandbox — sandbox filesystem used outside of a call context");
        }
        return active;
    }

    private static String combinedOutput(SandboxException.ExecException e) {
        return (e.getStdout() != null ? e.getStdout() : "")
                + (e.getStderr() != null && !e.getStderr().isBlank()
                        ? "\n" + e.getStderr()
                        : "");
    }

    private static String shellSingleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
