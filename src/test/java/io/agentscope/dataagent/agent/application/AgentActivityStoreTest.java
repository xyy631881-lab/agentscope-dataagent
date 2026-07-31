package io.agentscope.dataagent.agent.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.agent.domain.UserAgentDefinitionStore;
import io.agentscope.dataagent.security.domain.UserStore;
import io.agentscope.dataagent.workspace.infrastructure.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentActivityStoreTest {

    @Test
    void recordsPrivateAgentEventsInTheDisplayNameSandboxNamespace() {
        WorkspaceManagerFactory factory = mock(WorkspaceManagerFactory.class);
        AbstractFilesystem filesystem = mock(AbstractFilesystem.class);
        UserStore users = mock(UserStore.class);
        UserAgentDefinitionStore agents = mock(UserAgentDefinitionStore.class);
        when(agents.findById("admin", "insight-agent"))
                .thenReturn(Optional.of(storedEntry("insight-agent", "Insight Agent")));
        when(factory.userDataFs("admin", "insight-agent", ".agentscope/insight", "Insight Agent"))
                .thenReturn(filesystem);

        AgentActivityStore store = new AgentActivityStore(factory, users, agents);
        store.record(
                "admin",
                "insight-agent",
                new AgentActivityStore.ActorRef("admin", "admin"),
                "UPLOAD_FILE");

        verify(factory)
                .userDataFs("admin", "insight-agent", ".agentscope/insight", "Insight Agent");
        verify(filesystem).uploadFiles(any(), any());
    }

    private static UserAgentDefinitionStore.StoredEntry storedEntry(String id, String name) {
        return new UserAgentDefinitionStore.StoredEntry(
                id,
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                0L,
                List.of(),
                null,
                null,
                ".agentscope/insight",
                null,
                null,
                null);
    }
}
