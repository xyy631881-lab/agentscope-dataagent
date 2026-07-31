/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.dataagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.dataagent.tools.data.DataAgentToolkit;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRuntimeConfigurerTest {

    @Test
    void builtInSubagentsHaveNarrowToolAllowLists() {
        AgentRuntimeConfigurer configurer =
                new AgentRuntimeConfigurer(
                        mock(AgentStateStore.class),
                        mock(SandboxClient.class),
                        "test-model",
                        null,
                        mock(SandboxSnapshotSpec.class),
                        mock(SandboxExecutionGuard.class),
                        mock(DataAgentToolkit.class));

        Map<String, java.util.List<String>> tools =
                configurer.defaultSubagentDeclarations().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        declaration -> declaration.getName(),
                                        declaration -> declaration.getTools()));

        assertThat(tools.get("data-explorer"))
                .containsExactly("list_data_sources", "describe_table");
        assertThat(tools.get("report-writer")).containsExactly("write_file");
    }

    @Test
    void dataExplorerFactoryDoesNotExposeExecutionOrFilesystemTools() {
        var toolkit =
                AgentRuntimeConfigurer.restrictedDataExplorerToolkit(mock(DataAgentToolkit.class));

        assertThat(toolkit.getToolNames())
                .contains("list_data_sources", "describe_table")
                .doesNotContain(
                        "run_sql_preview",
                        "render_chart",
                        "execute",
                        "list_files",
                        "read_file",
                        "write_file");
    }
}
