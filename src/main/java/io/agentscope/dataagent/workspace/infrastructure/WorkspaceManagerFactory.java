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
import io.agentscope.dataagent.workspace.domain.SharedWorkspaceProjection;
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
    private final SharedWorkspaceProjection projection;
    private final AgentStateStore stateStore;
    private final SandboxSnapshotSpec snapshotSpec;
    private final SandboxExecutionGuard executionGuard;

    public WorkspaceManagerFactory(
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            SharedWorkspaceProjection projection,
            AgentStateStore stateStore,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard) {
        this.sandboxClient = Objects.requireNonNull(sandboxClient, "sandboxClient");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.snapshotSpec = Objects.requireNonNull(snapshotSpec, "snapshotSpec");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
    }

    public WorkspaceManager forAgent(String ownerId, String agentId) {
        return forAgent(ownerId, agentId, null);
    }

    public WorkspaceManager forAgent(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return new WorkspaceManager(
                resolveAgentDataPath(workspacePath, agentId), filesystem(ownerId, agentId));
    }

    public WorkspaceManager forGlobalAgent(String userId, String agentId) {
        return forAgent(userId, agentId, null);
    }

    public WorkspaceManager forGlobalAgent(String userId, String agentId, String workspacePath) {
        return forAgent(userId, agentId, workspacePath);
    }

    public AbstractFilesystem userDataFs(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return filesystem(ownerId, agentId);
    }

    public String userDataPathPrefix(String ownerId, String agentId, String workspacePath) {
        validateSegment("ownerId", ownerId);
        validateSegment("agentId", agentId);
        return "/";
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

    private AbstractFilesystem filesystem(String ownerId, String agentId) {
        return new SharedSandboxFilesystem(
                sandboxClient,
                projection,
                stateStore,
                snapshotSpec,
                executionGuard,
                ownerId,
                agentId);
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
