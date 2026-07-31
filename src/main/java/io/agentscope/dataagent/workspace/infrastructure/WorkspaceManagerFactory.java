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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Builds browser workspace managers backed by AgentScope's own sandbox lifecycle.
 *
 * <p>Each filesystem operation acquires the same user-scoped isolation slot used by an agent
 * call. There is no application-owned container cache or parallel lifecycle manager.
 */
public final class WorkspaceManagerFactory {

    private final SandboxClient<DockerSandboxClientOptions> sandboxClient;
    private final AgentStateStore stateStore;
    private final SandboxSnapshotSpec snapshotSpec;
    private final SandboxExecutionGuard executionGuard;
    private final Path localMirrorRoot;

    public WorkspaceManagerFactory(
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            AgentStateStore stateStore,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard,
            Path localMirrorRoot) {
        this.sandboxClient = Objects.requireNonNull(sandboxClient, "sandboxClient");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.snapshotSpec = Objects.requireNonNull(snapshotSpec, "snapshotSpec");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
        this.localMirrorRoot = localMirrorRoot;
    }

    public WorkspaceManager forAgent(String ownerId, String agentId) {
        return forAgent(ownerId, agentId, null);
    }

    public WorkspaceManager forAgent(String ownerId, String agentId, String workspacePath) {
        return forAgent(ownerId, agentId, workspacePath, agentId);
    }

    /**
     * Builds a browser manager for a logical agent while reusing the sandbox-state namespace of
     * its running HarnessAgent. Those values differ when an agent has a display name.
     */
    public WorkspaceManager forAgent(
            String ownerId, String agentId, String workspacePath, String sandboxStateNamespace) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        validateSegment("sandboxStateNamespace", sandboxStateNamespace);
        return new WorkspaceManager(
                resolveAgentDataPath(workspacePath, agentId),
                filesystem(ownerId, sandboxStateNamespace, agentId));
    }

    public WorkspaceManager forGlobalAgent(String userId, String agentId) {
        return forAgent(userId, agentId, null);
    }

    public WorkspaceManager forGlobalAgent(String userId, String agentId, String workspacePath) {
        return forAgent(userId, agentId, workspacePath);
    }

    public WorkspaceManager forGlobalAgent(
            String userId, String agentId, String workspacePath, String sandboxStateNamespace) {
        return forAgent(userId, agentId, workspacePath, sandboxStateNamespace);
    }

    public AbstractFilesystem userDataFs(String ownerId, String agentId, String workspacePath) {
        return userDataFs(ownerId, agentId, workspacePath, agentId);
    }

    /**
     * Returns the durable user-data filesystem using the namespace of the running harness agent.
     * Browser workspace calls and audit writes must use this same value; otherwise an agent with a
     * display name different from its id creates a second, empty sandbox slot and overwrites the
     * shared local mirror.
     */
    public AbstractFilesystem userDataFs(
            String ownerId, String agentId, String workspacePath, String sandboxStateNamespace) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        validateSegment("sandboxStateNamespace", sandboxStateNamespace);
        return filesystem(ownerId, sandboxStateNamespace, agentId);
    }

    public String userDataPathPrefix(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return "/";
    }

    public String localMirrorPath(String ownerId, String agentId) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return localMirrorRoot == null
                ? null
                : localMirrorRoot.resolve(ownerId).resolve(agentId).toAbsolutePath().toString();
    }

    /**
     * Returns the durable definition workspace for a private agent. Agent ids are only unique
     * within a user, so using the configured path directly would make two users' `personal-agent`
     * definitions share skills and subagent files.
     */
    public Path userWorkspacePath(String ownerId, String agentId) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return cwd.resolve(".agentscope")
                .resolve("users")
                .resolve(ownerId)
                .resolve("agents")
                .resolve(agentId)
                .normalize();
    }

    public Path resolveAgentDataPath(String workspacePath, String fallbackAgentId) {
        String raw =
                workspacePath != null && !workspacePath.isBlank()
                        ? workspacePath.trim()
                        : fallbackAgentId;
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "workspacePath and fallbackAgentId are both null/blank");
        }
        Path path = Paths.get(raw);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path agentScopeBase = cwd.resolve(".agentscope").normalize();
        Path resolvedAgainstCwd = cwd.resolve(path).normalize();
        return resolvedAgainstCwd.startsWith(agentScopeBase)
                ? resolvedAgainstCwd
                : agentScopeBase.resolve(path).normalize();
    }

    private AbstractFilesystem filesystem(
            String ownerId, String sandboxStateNamespace, String mirrorAgentId) {
        return new SharedSandboxFilesystem(
                sandboxClient,
                stateStore,
                snapshotSpec,
                executionGuard,
                ownerId,
                sandboxStateNamespace,
                localMirrorRoot == null
                        ? null
                        : localMirrorRoot.resolve(ownerId).resolve(mirrorAgentId));
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException(
                    label + " must not contain path separators or '..'");
        }
    }
}
