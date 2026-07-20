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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
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
 * AgentScope 沙箱文件系统的 Windows 兼容变体
 * AgentScope 2.0-SNAPSHOT 上传文件时，会把完整的 Base64 编码负载直接拼进 docker exec ... sh -c 的命令行参数中。
 * 当文件过大时，命令行长度超过 Windows 的限制（error 206），导致进程被拒绝执行。
 * 这个类是专门为解决 Windows 平台下大文件上传失败而设计的传输层修补，
 * 它保持了框架原有的沙箱生命周期，只改变了文件传输方式。
 */
public class SandboxBackedFilesystem extends BaseSandboxFilesystem implements SandboxAware {

    private static final Logger log = LoggerFactory.getLogger(SandboxBackedFilesystem.class);
    private static final int UPLOAD_CHUNK_BYTES = 8 * 1024;  // 上传文件的分块大小，8KB

    private final String fsId;  // 文件系统 ID
    private volatile Sandbox sandbox;  // 持有的沙箱引用，volatile 保证多线程可见性

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

    /**
     * 执行命令并返回结果。
     * 流程如下：
     * 调用 requireSandbox() 获取当前沙箱（若为 null 则抛异常）
     * 委托 sandbox.exec() 执行命令
     * 根据异常类型分类处理：
     * 超时（ExecTimeoutException）→ 返回退出码 124（Unix timeout 命令的标准退出码）
     * 执行异常（ExecException）→ 合并 stdout + stderr 返回
     * 其他异常 → 记录日志，返回 -1 退出码
     * @param runtimeContext 运行时上下文
     * @param command        要执行的命令
     * @param timeoutSeconds 超时时间（秒）
     * @return 命令执行结果
     */
    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        Sandbox active = requireSandbox();
        try {
            ExecResult result = active.exec(runtimeContext, command, timeoutSeconds);
            if (!result.ok()) {
                log.warn(
                        "[sandbox-fs] command failed: exit={}, command={}, output={}",
                        result.exitCode(),
                        abbreviate(command, 240),
                        abbreviate(result.combinedOutput(), 500));
            }
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            log.warn(
                    "[sandbox-fs] command raised ExecException: exit={}, command={}, message={}, output={}",
                    e.getExitCode(),
                    abbreviate(command, 240),
                    e.getMessage(),
                    abbreviate(combinedOutput(e), 500));
            return new ExecuteResponse(combinedOutput(e), e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[sandbox-fs] execute failed: {}", command, e);
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    /**
     * 这是该类最关键的创新点。
     * 与 SharedSandboxFilesystem 中直接将整个 Base64 字符串拼入命令行的方式不同，
     * 这里采用了分块传输 + 原子替换策略：
     * 设计亮点：
     * 每个 docker exec 命令只携带约 8KB 的 Base64 数据（编码后约 11KB），远低于 Windows 命令行长度限制
     * 使用 >> 追加重定向，分块写入同一临时文件
     * 最后用 mv 原子性地将临时文件替换到目标路径，避免部分写入被读取
     * 异常安全：任何步骤失败都会清理临时文件，不留下垃圾
     *
     * 上传文件并返回结果。
     * @param runtimeContext
     * @param files
     * @return
     */
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

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String path, String content) {
        log.info(
                "[sandbox-fs] write requested: fsId={}, path={}, contentBytes={}",
                fsId,
                path,
                content != null ? content.getBytes(StandardCharsets.UTF_8).length : 0);
        WriteResult result;
        try {
            AbstractFilesystem.validatePath(path);
            String escapedPath = shellSingleQuote(path);
            ExecResult exists =
                    requireSandbox()
                            .exec(
                                    runtimeContext,
                                    "if [ -e "
                                            + escapedPath
                                            + " ]; then printf '%s' 'EXISTS';"
                                            + " else printf '%s' 'MISSING'; fi",
                                    null);
            if (exists.stdout() != null && exists.stdout().contains("EXISTS")) {
                result =
                        WriteResult.fail(
                                "Cannot write to "
                                        + path
                                        + " because it already exists. Read and then make an edit,"
                                        + " or write to a new path.");
            } else {
                List<FileUploadResponse> uploads =
                        uploadFiles(
                                runtimeContext,
                                List.of(
                                        Map.entry(
                                                path,
                                                (content != null ? content : "")
                                                        .getBytes(StandardCharsets.UTF_8))));
                if (!uploads.isEmpty() && uploads.get(0).isSuccess()) {
                    result = WriteResult.ok(path);
                } else {
                    String error =
                            uploads.isEmpty() ? "upload returned no response" : uploads.get(0).error();
                    result = WriteResult.fail("Failed to write file '" + path + "': " + error);
                }
            }
        } catch (SandboxException.ExecException e) {
            log.warn(
                    "[sandbox-fs] write preflight failed: path={}, exit={}, message={}, output={}",
                    path,
                    e.getExitCode(),
                    e.getMessage(),
                    abbreviate(combinedOutput(e), 500));
            result = WriteResult.fail("Failed to write file '" + path + "': " + e.getMessage());
        } catch (Exception e) {
            log.warn("[sandbox-fs] write failed before upload: path={}", path, e);
            result = WriteResult.fail("Failed to write file '" + path + "': " + e.getMessage());
        }
        log.info(
                "[sandbox-fs] write completed: fsId={}, path={}, success={}, error={}",
                fsId,
                path,
                result.isSuccess(),
                result.error());
        return result;
    }

    private FileUploadResponse uploadFile(
            Sandbox active, RuntimeContext runtimeContext, String path, byte[] content) {
        String escapedPath = shellSingleQuote(path);
        String escapedParent = shellSingleQuote(parentDirectory(path));
        String temporaryPath = path + ".agentscope-upload-" + UUID.randomUUID();
        String escapedTemporaryPath = shellSingleQuote(temporaryPath);
        String stage = "setup";
        try {
            ExecResult setup =
                    active.exec(
                            runtimeContext,
                            "mkdir -p " + escapedParent + " && : > " + escapedTemporaryPath,
                            null);
            if (!setup.ok()) {
                log.warn(
                        "[sandbox-fs] upload setup failed: path={}, exit={}, output={}",
                        path,
                        setup.exitCode(),
                        abbreviate(setup.combinedOutput(), 500));
                return FileUploadResponse.fail(path, setup.combinedOutput());
            }

            for (int offset = 0; offset < content.length; offset += UPLOAD_CHUNK_BYTES) {
                stage = "append@" + offset;
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
                    log.warn(
                            "[sandbox-fs] upload append failed: path={}, offset={}, exit={}, output={}",
                            path,
                            offset,
                            append.exitCode(),
                            abbreviate(append.combinedOutput(), 500));
                    deleteTemporaryFile(active, runtimeContext, escapedTemporaryPath);
                    return FileUploadResponse.fail(path, append.combinedOutput());
                }
            }

            stage = "commit";
            ExecResult commit =
                    active.exec(
                            runtimeContext,
                            "mv " + escapedTemporaryPath + " " + escapedPath,
                            null);
            if (!commit.ok()) {
                log.warn(
                        "[sandbox-fs] upload commit failed: path={}, exit={}, output={}",
                        path,
                        commit.exitCode(),
                        abbreviate(commit.combinedOutput(), 500));
                deleteTemporaryFile(active, runtimeContext, escapedTemporaryPath);
                return FileUploadResponse.fail(path, commit.combinedOutput());
            }
            return FileUploadResponse.success(path);
        } catch (SandboxException.ExecException e) {
            log.warn(
                    "[sandbox-fs] upload raised ExecException: path={}, stage={}, exit={}, message={}, output={}",
                    path,
                    stage,
                    e.getExitCode(),
                    e.getMessage(),
                    abbreviate(combinedOutput(e), 500));
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

    private static String parentDirectory(String path) {
        String posix = path.replace('\\', '/');
        int slash = posix.lastIndexOf('/');
        if (slash < 0) return ".";
        if (slash == 0) return "/";
        return posix.substring(0, slash);
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "...";
    }
}
