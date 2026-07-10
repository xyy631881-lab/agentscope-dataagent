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

import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对 {@link DockerSandbox} 的 {@code start()} 增加重试 + 容器状态探测 + 工作区目录预创建，
 * 解决 Docker Desktop (WSL2) 上容器刚创建后 {@code docker exec} 尚未 ready 或
 * <b>Windows 路径分隔符转义</b> 导致的 {@link SandboxException.WorkspaceStartException}。
 *
 * <p><b>根因</b>：框架 {@code DockerSandbox.doSetupWorkspace()} 内部用 Java {@code Path}
 * API 解析 workspace 路径。在 Windows 上，{@code Path.of("/workspace")} 会被转换为
 * {@code \workspace}（反斜杠）。当这个路径传给
 * {@code docker exec <cid> mkdir -p \workspace} 时，Linux 容器内的 sh 把 {@code \w}
 * 解释为转义序列 → 实际创建的是 {@code $PWD/workspace}，不是 {@code /workspace}。
 * 后续 {@code docker tar /workspace} 找不到根级 {@code /workspace} → 报错。
 * 重试 N 次都是同样的路径问题，所以单纯重试无效。
 *
 * <p><b>修复</b>：在 {@code super.start()} <b>前后各调用一次</b>
 * {@code ensureWorkspaceDirExists()}，用 {@code ProcessBuilder} 直接执行
 * {@code docker exec <cid> mkdir -p /workspace}，其中路径是<b>硬编码的正斜杠字符串</b>，
 * 完全绕过 Java {@code Path} API 的 Windows 路径转换。
 *
 * <ul>
 *   <li><b>super.start() 之前</b>：重试时（i&gt;0）containerId 已由上一次失败的
 *       super.start() 设置，可以预创建 /workspace，让 doSetupWorkspace() 幂等通过。</li>
 *   <li><b>super.start() 之后</b>：首次成功时（i=0）上面的调用因 containerId=null 被跳过，
 *       这里补创建正确的 /workspace。</li>
 * </ul>
 *
 * <p>{@code mkdir -p /workspace} 是幂等操作，不会影响已有的子目录结构。
 */
public class RetryStartDockerSandbox extends DockerSandbox {

    private static final Logger log = LoggerFactory.getLogger(RetryStartDockerSandbox.class);

    /** Linux 容器内的绝对 workspace 路径。必须用正斜杠硬编码，绕过 Windows Path 转换。 */
    private static final String CONTAINER_WORKSPACE = "/workspace";

    /** 最大重试次数。 */
    private static final int MAX_RETRIES = 8;
    /** 重试间隔。 */
    private static final long RETRY_DELAY_MS = 4000;
    /** 连续多少次"容器已创建但立刻退出"后放弃。 */
    private static final int MAX_CONSECUTIVE_EXIT = 3;

    public RetryStartDockerSandbox(DockerSandboxState state) {
        super(state);
    }

    @Override
    public void start() throws Exception {
        Exception last = null;
        int consecutiveExit = 0;
        for (int i = 0; i < MAX_RETRIES; i++) {
            // 重试时（i>0）containerId 已由上一次失败的 super.start() 设置，
            // 可以在 super.start() 之前预创建 /workspace，让 doSetupWorkspace() 幂等通过。
            ensureWorkspaceDirExists();

            try {
                super.start();
                // super.start() 成功后 containerId 一定已设置。
                // 首次成功时（i=0）上面的 ensureWorkspaceDirExists() 因 containerId=null 被跳过，
                // 这里补创建正确的 /workspace（绕过框架内部的 Windows Path 转换 bug）。
                ensureWorkspaceDirExists();
                if (i > 0) {
                    log.info("[sandbox-retry] start() succeeded after {} retries", i);
                }
                return;
            } catch (SandboxException.WorkspaceStartException e) {
                last = e;

                ContainerStatus status = inspectContainerStatus();
                if (status == ContainerStatus.NOT_FOUND) {
                    consecutiveExit++;
                    log.warn(
                            "[sandbox-retry] Container was created but immediately exited/removed"
                                    + " (consecutiveExit={}/{})."
                                    + " Docker Desktop WSL2 may be in a bad state;"
                                    + " consider restarting Docker Desktop.",
                            consecutiveExit,
                            MAX_CONSECUTIVE_EXIT);
                    if (consecutiveExit >= MAX_CONSECUTIVE_EXIT) {
                        throw new SandboxException.WorkspaceStartException(
                                java.nio.file.Path.of(
                                        ((DockerSandboxState) getState()).getWorkspaceRoot()),
                                new IllegalStateException(
                                        "Container created but immediately exited "
                                                + consecutiveExit
                                                + " times in a row."
                                                + " Docker Desktop WSL2 backend may need restart."
                                                + " Original cause: "
                                                + e.getMessage(),
                                        e));
                    }
                } else if (status == ContainerStatus.RUNNING) {
                    consecutiveExit = 0;
                    log.debug(
                            "[sandbox-retry] Container is RUNNING but docker exec failed;"
                                    + " init process not fully ready yet.");
                } else {
                    consecutiveExit = 0;
                }

                Throwable cause = e.getCause();
                if (cause != null) {
                    log.warn(
                            "[sandbox-retry] Underlying cause: {} {}",
                            cause.getClass().getSimpleName(),
                            cause.getMessage());
                    if (isUnrecoverable(cause)) {
                        log.error(
                                "[sandbox-retry] Unrecoverable snapshot error detected;"
                                        + " aborting retry loop. Check SandboxSnapshotConfig"
                                        + " (is Redis/JDBC snapshot backend properly configured?)");
                        throw e;
                    }
                }

                if (i < MAX_RETRIES - 1) {
                    log.warn(
                            "[sandbox-retry] start() failed (attempt {}/{}), retrying in {}ms: {}",
                            i + 1,
                            MAX_RETRIES,
                            RETRY_DELAY_MS,
                            e.getMessage());
                    Thread.sleep(RETRY_DELAY_MS);
                }
            }
        }
        throw last;
    }

    /**
     * Avoid the framework's Windows Path conversion before it invokes {@code docker exec}. The
     * rest of the Docker implementation already uses the state value {@code /workspace}, so setup
     * must create that same POSIX directory.
     */
    @Override
    protected void doSetupWorkspace() throws Exception {
        DockerSandboxState state = (DockerSandboxState) getState();
        String containerId = state.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalStateException("Cannot create workspace without a container id");
        }
        Process process =
                new ProcessBuilder("docker", "exec", containerId, "mkdir", "-p", CONTAINER_WORKSPACE)
                        .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out creating " + CONTAINER_WORKSPACE);
        }
        if (process.exitValue() != 0) {
            String stderr =
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            throw new IllegalStateException(
                    "Failed to create " + CONTAINER_WORKSPACE + ": " + stderr);
        }
    }

    /**
     * 预创建容器内的 {@code /workspace} 目录。
     *
     * <p>关键：路径必须是<b>硬编码正斜杠字符串</b>{@code "/workspace"}，不能用任何 Java
     * {@code Path} API（如 {@code Path.of}、{@code Paths.get}）——它们在 Windows 上会把
     * 正斜杠反转为反斜杠，导致 Linux 容器内的 shell 把 {@code \workspace} 中的 {@code \w}
     * 当作转义序列。
     */
    private void ensureWorkspaceDirExists() {
        DockerSandboxState state = (DockerSandboxState) getState();
        String containerId = state.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            log.debug("[sandbox-retry] No containerId yet, skipping ensureWorkspaceDirExists");
            return;
        }
        try {
            List<String> cmd =
                    List.of("docker", "exec", containerId, "mkdir", "-p", CONTAINER_WORKSPACE);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();
            boolean exited = p.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                log.warn("[sandbox-retry] ensureWorkspaceDirExists timed out for {}", containerId);
                return;
            }
            if (p.exitValue() != 0) {
                String stderr =
                        new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                                .trim();
                log.warn(
                        "[sandbox-retry] ensureWorkspaceDirExists failed for {}: {}",
                        containerId,
                        stderr);
            } else {
                log.debug(
                        "[sandbox-retry] Pre-created {} in container {}",
                        CONTAINER_WORKSPACE,
                        containerId);
            }
        } catch (IOException | InterruptedException e) {
            log.warn(
                    "[sandbox-retry] ensureWorkspaceDirExists exception for {}: {}",
                    containerId,
                    e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -----------------------------------------------------------------
    //  不可恢复错误检测
    // -----------------------------------------------------------------

    private boolean isUnrecoverable(Throwable cause) {
        if (cause instanceof NullPointerException) {
            String msg = cause.getMessage();
            if (msg != null && msg.contains("this.client")) {
                return true;
            }
        }
        for (Throwable t = cause.getCause(); t != null; t = t.getCause()) {
            if (t instanceof NullPointerException) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("this.client")) {
                    return true;
                }
            }
        }
        return false;
    }

    // -----------------------------------------------------------------
    //  容器状态探测
    // -----------------------------------------------------------------

    private enum ContainerStatus {
        RUNNING,
        STOPPED,
        NOT_FOUND,
        UNKNOWN
    }

    private ContainerStatus inspectContainerStatus() {
        DockerSandboxState state = (DockerSandboxState) getState();
        String containerId = state.getContainerId();
        if (containerId == null || containerId.isBlank()) {
            return ContainerStatus.NOT_FOUND;
        }
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(
                            "docker", "inspect", "-f", "{{.State.Running}}", containerId);
            Process p = pb.start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return ContainerStatus.UNKNOWN;
            }
            if (p.exitValue() != 0) {
                String stderr =
                        new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                                .trim();
                if (stderr.contains("No such container")) {
                    return ContainerStatus.NOT_FOUND;
                }
                return ContainerStatus.UNKNOWN;
            }
            String stdout =
                    new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return "true".equals(stdout) ? ContainerStatus.RUNNING : ContainerStatus.STOPPED;
        } catch (Exception e) {
            log.debug(
                    "[sandbox-retry] Failed to inspect container {}: {}",
                    containerId,
                    e.getMessage());
            return ContainerStatus.UNKNOWN;
        }
    }
}
