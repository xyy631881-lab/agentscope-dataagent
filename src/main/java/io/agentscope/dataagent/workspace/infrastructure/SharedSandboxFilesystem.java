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
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给浏览器端 Controller 用的文件系统适配器——让 Web 界面能直接操作 Docker 容器里的文件
 * （看目录树、读写文件、上传下载、执行命令）。
 */
public final class SharedSandboxFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(SharedSandboxFilesystem.class);

    private final String fsId;  // 唯一 ID（"shared-sandbox-xxxxxxxx"）
    private final Sandbox sandbox;  // 容器沙箱实例

    public SharedSandboxFilesystem(Sandbox sandbox) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.fsId = "shared-sandbox-" + UUID.randomUUID().toString().substring(0, 8);
    }

    //返回文件系统唯一 ID，用于在 UI 显示和管理
    @Override
    public String id() {
        return fsId;
    }

    //在容器里执行命令，返回执行结果，
    // 前端要区分"命令失败"和"系统崩了"，前者是用户代码问题，后者要告警。
    @Override
    public ExecuteResponse execute(
            RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        try {
            ExecResult result = sandbox.exec(runtimeContext, command, timeoutSeconds);
            return new ExecuteResponse(
                    result.combinedOutput(), result.exitCode(), result.truncated());
        } catch (SandboxException.ExecTimeoutException e) {
            // 超时异常
            return new ExecuteResponse(e.getMessage(), 124, false);
        } catch (SandboxException.ExecException e) {
            String combined =
                    (e.getStdout() != null ? e.getStdout() : "")
                            + (e.getStderr() != null && !e.getStderr().isBlank()
                                    ? "\n" + e.getStderr()
                                    : "");
            // 执行异常
            return new ExecuteResponse(combined, e.getExitCode(), false);
        } catch (Exception e) {
            log.error("[shared-sandbox-fs] execute failed: {}", command, e);
            // 内部异常
            return new ExecuteResponse("Internal sandbox error: " + e.getMessage(), -1, false);
        }
    }

    //上传文件
    // Docker exec API 不支持直接传二进制流，只能传字符串。
    // Base64 编码后全是 ASCII，安全通过 shell 传输。
    // shell 引号转义统一复用框架的 FilesystemUtils.shellQuote()，与父类 ls/read/grep 行为对齐。
    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> results = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> file : files) {
            String path = file.getKey();
            byte[] content = file.getValue();
            try {
                String base64Content = Base64.getEncoder().encodeToString(content);
                String escapedPath = FilesystemUtils.shellQuote(path);
                // base64Content 也走 shellQuote 转义：当前 Base64 字符集不含单引号（安全），
                // 但万一未来换编码或数据异常，转义能兜底，更稳。
                String quotedB64 = FilesystemUtils.shellQuote(base64Content);
                String cmd =
                        "mkdir -p $(dirname "
                                + escapedPath
                                + ") && printf '%s' "
                                + quotedB64
                                + " | base64 -d > "
                                + escapedPath;
                ExecResult r = sandbox.exec(runtimeContext, cmd, null);
                if (r.ok()) {
                    results.add(FileUploadResponse.success(path));
                } else {
                    results.add(FileUploadResponse.fail(path, r.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileUploadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[shared-sandbox-fs] uploadFiles failed for path: {}", path, e);
                results.add(FileUploadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }

    //从容器下载文件，容器内 base64 命令把文件编码成 ASCII，传回宿主机后解码成 byte[]。
    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>(paths.size());
        for (String path : paths) {
            try {
                String cmd = "base64 " + FilesystemUtils.shellQuote(path);
                ExecResult r = sandbox.exec(runtimeContext, cmd, null);
                if (r.ok()) {
                    byte[] decoded =
                            Base64.getDecoder()
                                    .decode(r.stdout().trim().getBytes(StandardCharsets.UTF_8));
                    results.add(FileDownloadResponse.success(path, decoded));
                } else {
                    results.add(FileDownloadResponse.fail(path, r.combinedOutput()));
                }
            } catch (SandboxException.ExecException e) {
                String combined =
                        (e.getStdout() != null ? e.getStdout() : "")
                                + (e.getStderr() != null && !e.getStderr().isBlank()
                                        ? "\n" + e.getStderr()
                                        : "");
                results.add(FileDownloadResponse.fail(path, combined));
            } catch (Exception e) {
                log.warn("[shared-sandbox-fs] downloadFiles failed for path: {}", path, e);
                results.add(FileDownloadResponse.fail(path, e.getMessage()));
            }
        }
        return results;
    }
}
