package io.agentscope.dataagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.agent.domain.GlobalAgentOverrideStore;
import io.agentscope.dataagent.agent.domain.UserAgentDefinitionStore;
import io.agentscope.dataagent.capability.template.application.TemplateRegistry;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.workspace.infrastructure.WorkspaceManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentMutationServiceSkillSeedTest {

    @TempDir Path workspace;

    @Test
    void neverRestoresAnExplicitlyRemovedBaselineSkillAfterMigration() throws Exception {
        UserAgentDefinitionStore store = mock(UserAgentDefinitionStore.class);
        WorkspaceManagerFactory workspaces = mock(WorkspaceManagerFactory.class);
        UserAgentDefinitionStore.StoredEntry entry = entry();
        when(store.findById("tester", "personal-agent")).thenReturn(Optional.of(entry));
        when(workspaces.userWorkspacePath("tester", "personal-agent")).thenReturn(workspace);

        Path keptSkill = workspace.resolve("skills/sql-analysis");
        Files.createDirectories(keptSkill);
        Files.writeString(keptSkill.resolve("SKILL.md"), "---\nname: sql-analysis\n---\n");

        AgentMutationService service =
                new AgentMutationService(
                        mock(DataAgentBootstrap.class),
                        store,
                        mock(GlobalAgentOverrideStore.class),
                        mock(AgentLifecycleService.class),
                        mock(TemplateRegistry.class),
                        workspaces);

        service.ensurePublicDataSkills("tester", "personal-agent");
        assertThat(workspace.resolve(".dataagent/public-skills-v1")).exists();

        service.ensurePublicDataSkills("tester", "personal-agent");

        assertThat(workspace.resolve("skills/chart-rendering")).doesNotExist();
        assertThat(keptSkill).exists();
    }

    private static UserAgentDefinitionStore.StoredEntry entry() {
        return new UserAgentDefinitionStore.StoredEntry(
                "personal-agent",
                "Tester",
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
                "personal-agent-workspace",
                null,
                null,
                null);
    }
}
