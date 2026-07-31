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

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker adapter for framework-owned sandbox lifecycle.
 *
 * <p>It only supplies Windows Docker Desktop and remote-snapshot compatibility. Lifecycle,
 * isolation, state persistence, and locking remain in AgentScope's {@code SandboxManager}.
 */
public final class FrameworkDockerSandboxClient
        implements SandboxClient<DockerSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(FrameworkDockerSandboxClient.class);

    private final DockerSandboxClient delegate;
    private final SandboxSnapshotSpec snapshotSpec;

    public FrameworkDockerSandboxClient(
            DockerSandboxClient delegate, SandboxSnapshotSpec snapshotSpec) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.snapshotSpec = Objects.requireNonNull(snapshotSpec, "snapshotSpec");
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec requestedSnapshotSpec,
            DockerSandboxClientOptions options) {
        return wrap(delegate.create(workspaceSpec, requestedSnapshotSpec, options).getState());
    }

    @Override
    public Sandbox resume(SandboxState state) {
        rebindSnapshot(state);
        discardMissingContainerReference(state);
        return wrap(state);
    }

    @Override
    public void delete(Sandbox sandbox) {
        delegate.delete(sandbox);
    }

    @Override
    public String serializeState(SandboxState state) {
        // RetryStartDockerSandbox keeps an owned container alive briefly after a release so the
        // framework's asynchronous session mirror can finish. Keep the live reference in the
        // persisted state during that window; otherwise a second lease creates a container with
        // the same deterministic name and Docker rejects it as a conflict. On a later lease,
        // discardMissingContainerReference() removes the id after deferred cleanup has run.
        return delegate.serializeState(state);
    }

    @Override
    public SandboxState deserializeState(String json) {
        SandboxState state = delegate.deserializeState(json);
        rebindSnapshot(state);
        discardMissingContainerReference(state);
        return state;
    }

    private Sandbox wrap(SandboxState state) {
        if (!(state instanceof DockerSandboxState dockerState)) {
            throw new IllegalArgumentException(
                    "Expected DockerSandboxState but got "
                            + (state == null ? "null" : state.getClass().getName()));
        }
        return new RetryStartDockerSandbox(dockerState);
    }

    /** Remote snapshots serialize their id but not their live Redis client. */
    private void rebindSnapshot(SandboxState state) {
        if (state == null) {
            return;
        }
        SandboxSnapshot snapshot = state.getSnapshot();
        if (snapshot != null && snapshot.getId() != null && !snapshot.getId().isBlank()) {
            state.setSnapshot(snapshotSpec.build(snapshot.getId()));
        }
    }

    /** Repairs states written by older builds without discarding a container that is still live. */
    private void discardMissingContainerReference(SandboxState state) {
        if (!(state instanceof DockerSandboxState dockerState)
                || dockerState.getContainerId() == null
                || dockerState.getContainerId().isBlank()) {
            return;
        }
        if (RetryStartDockerSandbox.inspectContainerStatus(dockerState)
                == RetryStartDockerSandbox.ContainerStatus.NOT_FOUND) {
            String staleContainerId = dockerState.getContainerId();
            clearContainerReference(dockerState);
            log.info("[sandbox-state] Discarded missing container reference {}", staleContainerId);
        }
    }

    private static void clearContainerReference(DockerSandboxState state) {
        state.setContainerId(null);
        state.setContainerName(null);
        state.setWorkspaceRootReady(false);
    }
}
