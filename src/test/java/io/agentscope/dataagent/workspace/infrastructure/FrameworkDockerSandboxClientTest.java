/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.workspace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import org.junit.jupiter.api.Test;

class FrameworkDockerSandboxClientTest {

    @Test
    void keepsOwnedContainerReferenceDuringDeferredCleanup() {
        FrameworkDockerSandboxClient client = client();
        DockerSandboxState live = state(true);

        String json = client.serializeState(live);

        assertThat(live.getContainerId()).isEqualTo("container-123");
        assertThat(live.getContainerName()).isEqualTo("agentscope-sandbox-test");
        assertThat(live.isWorkspaceRootReady()).isTrue();

        assertThat(json).contains("container-123");
        assertThat(json).contains("agentscope-sandbox-test");
    }

    @Test
    void preservesUserManagedContainerReference() {
        FrameworkDockerSandboxClient client = client();
        DockerSandboxState live = state(false);

        String json = client.serializeState(live);

        assertThat(json).contains("container-123");
        assertThat(json).contains("agentscope-sandbox-test");
        assertThat(live.isWorkspaceRootReady()).isTrue();
    }

    private static FrameworkDockerSandboxClient client() {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        NoopSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
        return new FrameworkDockerSandboxClient(new DockerSandboxClient(mapper), snapshotSpec);
    }

    private static DockerSandboxState state(boolean owned) {
        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId("test-session");
        state.setContainerId("container-123");
        state.setContainerName("agentscope-sandbox-test");
        state.setContainerOwned(owned);
        state.setImage("ubuntu:22.04");
        state.setWorkspaceRoot("/workspace");
        state.setWorkspaceRootReady(true);
        return state;
    }
}
