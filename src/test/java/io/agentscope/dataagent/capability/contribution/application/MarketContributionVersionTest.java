package io.agentscope.dataagent.capability.contribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.capability.contribution.domain.FileEntry;
import io.agentscope.dataagent.capability.contribution.infrastructure.ContributionEntity;
import io.agentscope.dataagent.capability.contribution.infrastructure.ContributionRepository;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.workspace.application.SandboxStateInvalidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketContributionVersionTest {

    @TempDir Path tempDir;

    @Test
    void approvalAssignsVersionArchivesPayloadAndRollbackRestoresIt() throws Exception {
        ContributionRepository repository = mock(ContributionRepository.class);
        DataAgentBootstrap bootstrap = mock(DataAgentBootstrap.class);
        AgentCatalogService catalog = mock(AgentCatalogService.class);
        AgentStateStore stateStore = mock(AgentStateStore.class);
        ObjectMapper mapper = new ObjectMapper();
        ContributionEntity entity = pendingSkill(mapper, "version-one");
        when(bootstrap.cwd()).thenReturn(tempDir);
        when(catalog.isGlobal("data-agent")).thenReturn(true);
        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        when(repository.findMaxVersion(
                        "data-agent", "skill", "sales-analysis", ContributionEntity.STATUS_APPROVED))
                .thenReturn(4);
        when(repository.save(any(ContributionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateStore.listSessionIds(null)).thenReturn(Set.of());
        MarketContributionService service =
                new MarketContributionService(
                        repository,
                        bootstrap,
                        mapper,
                        new SandboxStateInvalidator(stateStore),
                        catalog);

        ContributionEntity approved = service.approve(7L, "admin", "approved", null);

        assertThat(approved.getVersion()).isEqualTo(5);
        Path live =
                tempDir.resolve(
                        "shared/agents/data-agent/skills/sales-analysis/SKILL.md");
        Path archived =
                tempDir.resolve(
                        "shared/agents/data-agent/.versions/v5/skills/sales-analysis/SKILL.md");
        assertThat(Files.readString(live, StandardCharsets.UTF_8)).isEqualTo("version-one");
        assertThat(Files.readString(archived, StandardCharsets.UTF_8)).isEqualTo("version-one");

        Files.writeString(live, "newer-live-content", StandardCharsets.UTF_8);
        service.rollback(7L, "admin");
        assertThat(Files.readString(live, StandardCharsets.UTF_8)).isEqualTo("version-one");
    }

    @Test
    void rejectsSelfReviewAndPrivateContributionTarget() throws Exception {
        ContributionRepository repository = mock(ContributionRepository.class);
        DataAgentBootstrap bootstrap = mock(DataAgentBootstrap.class);
        AgentCatalogService catalog = mock(AgentCatalogService.class);
        AgentStateStore stateStore = mock(AgentStateStore.class);
        ObjectMapper mapper = new ObjectMapper();
        ContributionEntity entity = pendingSkill(mapper, "version-one");
        when(bootstrap.cwd()).thenReturn(tempDir);
        when(repository.findById(7L)).thenReturn(Optional.of(entity));
        MarketContributionService service =
                new MarketContributionService(
                        repository,
                        bootstrap,
                        mapper,
                        new SandboxStateInvalidator(stateStore),
                        catalog);

        assertThatThrownBy(() -> service.approve(7L, "alice", "self review", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot approve");
        assertThatThrownBy(() -> service.reject(7L, "alice", "self review"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot approve");
        entity.setStatus(ContributionEntity.STATUS_APPROVED);
        assertThatThrownBy(() -> service.rollback(7L, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot approve");
        assertThatThrownBy(
                        () ->
                                service.submit(
                                        "alice",
                                        "analyst-agent",
                                        "private-agent",
                                        ContributionEntity.TARGET_SKILL,
                                        "sales-analysis",
                                        null,
                                        List.of(new FileEntry("", "# Skill"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("global team agent");
    }

    private static ContributionEntity pendingSkill(ObjectMapper mapper, String content)
            throws Exception {
        ContributionEntity entity = new ContributionEntity();
        entity.setId(7L);
        entity.setStatus(ContributionEntity.STATUS_PENDING);
        entity.setSourceUserId("alice");
        entity.setSourceAgentId("analyst-agent");
        entity.setTargetAgentId("data-agent");
        entity.setTargetType(ContributionEntity.TARGET_SKILL);
        entity.setTargetPath("sales-analysis");
        entity.setPayload(mapper.writeValueAsString(List.of(new FileEntry("", content))));
        entity.setCreatedAt(1L);
        entity.setUpdatedAt(1L);
        return entity;
    }
}
