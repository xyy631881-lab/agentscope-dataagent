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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.dataagent.config.properties.WorkspaceProperties;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Framework adapters and browser workspace access. */
@Configuration
public class DataAgentWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentWorkspaceConfig.class);

    /**
     * The framework remains the sandbox lifecycle owner. This client only repairs Windows Docker
     * path handling and rebinds remote snapshots after JSON deserialization.
     */
    @Bean
    @ConditionalOnMissingBean(SandboxClient.class)
    public SandboxClient<DockerSandboxClientOptions> sandboxClient(
            SandboxSnapshotSpec snapshotSpec) {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new FrameworkDockerSandboxClient(new DockerSandboxClient(mapper), snapshotSpec);
    }

    @Bean
    public WorkspaceManagerFactory workspaceManagerFactory(
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            AgentStateStore stateStore,
            SandboxSnapshotSpec snapshotSpec,
            SandboxExecutionGuard executionGuard,
            WorkspaceProperties workspaceProps) {
        Path mirrorRoot = resolveLocalMirrorRoot(workspaceProps);
        log.info("Local workspace mirror: {}", mirrorRoot == null ? "disabled" : mirrorRoot);
        return new WorkspaceManagerFactory(
                sandboxClient, stateStore, snapshotSpec, executionGuard, mirrorRoot);
    }

    private Path resolveCwd(WorkspaceProperties props) {
        String root = props.getRoot();
        if (root != null && !root.isBlank()) {
            return Paths.get(root).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private Path resolveLocalMirrorRoot(WorkspaceProperties props) {
        if (!props.isLocalMirrorEnabled()) return null;
        String configured = props.getLocalMirrorRoot();
        if (configured == null || configured.isBlank()) return null;
        Path root = Paths.get(configured.trim());
        return (root.isAbsolute() ? root : resolveCwd(props).resolve(root)).normalize();
    }
}
