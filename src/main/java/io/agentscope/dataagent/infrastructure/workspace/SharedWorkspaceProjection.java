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
package io.agentscope.dataagent.infrastructure.workspace;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link WorkspaceSpec} that projects a per-agent shared seed layer
 * (AGENTS.md / skills/ / subagents/ / knowledge/) into every fresh sandbox container.
 *
 * <p>Extracted from {@link UserSandboxRegistry} so that the registry can focus purely on
 * container pooling, while this class owns the projection-layout concern. The projection
 * roots and host-path resolution live here; swapping the layout strategy does not require
 * touching the pooling code.
 *
 * <p>Effect: each new container automatically receives its agent's shared content
 * (skills, subagents, knowledge, AGENTS.md) via a bind-mount projection, but the user's
 * own files start empty. Two agents on the same host never see each other's shared layer
 * because the projection is scoped to {@code <hostWorkspaceRoot>/agents/<agentId>/} only.
 */
public final class SharedWorkspaceProjection {

    private static final List<String> DEFAULT_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge");

    private final Path hostWorkspaceRoot;

    /**
     * @param hostWorkspaceRoot directory under which per-agent shared seed lives, organised as
     *     {@code <hostWorkspaceRoot>/agents/<agentId>/{AGENTS.md, skills/, subagents/, knowledge/}}.
     *     Each container is projected with the slice for its own {@code agentId} only, so two
     *     agents on the same host do not see each other's shared layer. May be {@code null} to
     *     skip projection entirely (every container starts empty).
     */
    public SharedWorkspaceProjection(Path hostWorkspaceRoot) {
        this.hostWorkspaceRoot = hostWorkspaceRoot;
    }

    /**
     * Builds the workspace spec that mounts the shared seed content for the given agent
     * into a fresh container.
     *
     * @param userId  the tenant user (reserved for future per-user projections; currently unused)
     * @param agentId the agent whose shared seed layer should be projected
     * @return a {@link WorkspaceSpec} with a {@code __workspace_projection__} entry, or an
     *         empty spec when {@code hostWorkspaceRoot} is {@code null}
     */
    public WorkspaceSpec buildSpec(String userId, String agentId) {
        WorkspaceSpec spec = new WorkspaceSpec();
        if (hostWorkspaceRoot == null) {
            return spec;
        }
        // Host path: {hostWorkspaceRoot}/agents/{agentId}/
        Path agentSlice =
                hostWorkspaceRoot
                        .resolve("agents")
                        .resolve(agentId)
                        .toAbsolutePath()
                        .normalize();
        try {
            Files.createDirectories(agentSlice);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to ensure per-agent shared dir " + agentSlice + ": " + e.getMessage(),
                    e);
        }
        // Project the specific subdirectories/files into the container
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(agentSlice.toString());
        projection.setIncludeRoots(DEFAULT_PROJECTION_ROOTS);
        Map<String, WorkspaceEntry> es = new LinkedHashMap<>();
        es.put("__workspace_projection__", projection);
        spec.setEntries(es);
        return spec;
    }
}
