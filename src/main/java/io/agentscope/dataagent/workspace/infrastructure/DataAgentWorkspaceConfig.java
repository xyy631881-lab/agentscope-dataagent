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
import io.agentscope.dataagent.config.BootstrapConfig;
import io.agentscope.dataagent.workspace.domain.SandboxPool;
import io.agentscope.dataagent.workspace.domain.SharedWorkspaceProjection;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.config.properties.WorkspaceProperties;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the per-tenant workspace filesystem used by every per-agent
 * {@code WorkspaceManager}.
 *
 * <p>DataAgent is a multi-tenant deployable. Both the browser workspace controllers and the agent
 * runtime read/write through one live Docker {@link
 * io.agentscope.harness.agent.sandbox.Sandbox} per {@code (userId, agentId)} owned by
 * {@link UserSandboxPool}. This is what makes the workspace user-isolated — every other route
 * the old {@code CompositeFilesystem} fell through to a shared {@code LocalFilesystem}, which
 * leaked content across tenants.
 *
 * <p>The shared, read-only seed content (AGENTS.md / skills/ / subagents/ / knowledge/) lives
 * under {@code ${cwd}/shared/} on the host and is projected into every fresh container via the
 * registry's {@code __workspace_projection__} entry.
 *
 * <p>Multi-replica deployments must use sticky load-balancing by {@code userId} so a user's
 * traffic lands on the same pod — the pool's isolation-state store is in-memory by default, and
 * two pods otherwise spin up independent containers for the same user (override the
 * {@code AgentStateStore} bean with a distributed backend to share state across pods).
 */
@Configuration
public class DataAgentWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentWorkspaceConfig.class);

    @Value("${dataagent.sandbox.idle-ttl-min:15}")
    private long idleTtlMinutes;

    @Value("${dataagent.sandbox.eviction-poll-sec:60}")
    private long evictionPollSeconds;

    /**
     * Default {@link SandboxClient} bean — a no-arg {@link DockerSandboxClient}. Operators can
     * override by declaring their own {@code SandboxClient<DockerSandboxClientOptions>} bean.
     */
    @Bean
    @ConditionalOnMissingBean(SandboxClient.class)
    public SandboxClient<DockerSandboxClientOptions> sandboxClient() {
        log.info("Wiring default DockerSandboxClient for per-user workspace sandboxes");
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new DockerSandboxClient(mapper); // ← 直接调用 docker 命令
    }

    @Bean
    public SharedWorkspaceProjection sharedWorkspaceProjection(WorkspaceProperties workspaceProps) {
        Path sharedRoot = resolveCwd(workspaceProps).resolve("shared");
        log.info("SharedWorkspaceProjection: hostWorkspaceRoot={}", sharedRoot);
        return new SharedWorkspaceProjection(sharedRoot);
    }

    /**
     * Framework isolation-state backend for the sandbox pool.
     *
     * <p>Defaults to an in-process {@link InMemoryAgentStateStore}, which is correct for a
     * single-replica DataAgent or any deployment that pins a user to one pod via sticky
     * load-balancing (the sandbox containers are themselves in-memory and pod-local). For a
     * multi-replica deployment that must recover a user's container after a pod failure, override
     * this bean with a distributed {@link AgentStateStore} — and pair it with a real
     * {@code SandboxSnapshotSpec} backend, otherwise a recovered container starts with an empty
     * workspace.
     */
    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore agentStateStore() {
        return new InMemoryAgentStateStore();
    }

    @Bean
    public SandboxPool sandboxPool(
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            SharedWorkspaceProjection projection,
            SandboxLifecycleObserver lifecycleObserver,
            AgentStateStore agentStateStore) {
        Duration idleTtl = Duration.ofMinutes(idleTtlMinutes);
        Duration evictionPoll = Duration.ofSeconds(evictionPollSeconds);
        log.info(
                "DataAgent sandbox pool: idleTtl={}, evictionPoll={}",
                idleTtl,
                evictionPoll);
        return new UserSandboxPool(
                sandboxClient, projection, idleTtl, evictionPoll, lifecycleObserver, agentStateStore);
    }

    /**
     * Resolves the workspace root from {@link WorkspaceProperties} — same source as
     * {@code BootstrapConfig} uses. Injecting the properties bean directly (instead of
     * {@code DataAgentBootstrap}) avoids a circular dependency: the bootstrap depends
     * on this pool's {@link UserSandboxPool} bean.
     */
    private Path resolveCwd(WorkspaceProperties props) {
        String root = props.getRoot();
        if (root != null && !root.isBlank()) {
            return Paths.get(root).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    @Bean
    public WorkspaceManagerFactory workspaceManagerFactory(SandboxPool registry) {
        return new WorkspaceManagerFactory(registry);
    }
}