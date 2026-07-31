package io.agentscope.dataagent.workspace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import org.junit.jupiter.api.Test;

class WorkspaceManagerFactoryTest {

    @Test
    void givesUsersWithTheSamePrivateAgentIdDifferentDurableWorkspaces() {
        WorkspaceManagerFactory factory =
                new WorkspaceManagerFactory(
                        mock(SandboxClient.class),
                        mock(AgentStateStore.class),
                        new NoopSnapshotSpec(),
                        mock(SandboxExecutionGuard.class),
                        null);

        assertThat(factory.userWorkspacePath("alice", "personal-agent"))
                .isNotEqualTo(factory.userWorkspacePath("bob", "personal-agent"));
        assertThat(factory.userWorkspacePath("alice", "personal-agent").toString())
                .endsWith(".agentscope\\users\\alice\\agents\\personal-agent");
    }
}
