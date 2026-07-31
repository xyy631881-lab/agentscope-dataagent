package io.agentscope.dataagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.dataagent.agent.api.AgentWorkspaceController;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileServiceTest {

    @TempDir Path tempDir;

    @Test
    void readsTreeAndFileFromLocalMirrorWithoutRestoringSandbox() throws Exception {
        Path report = tempDir.resolve("reports/shared-handoff-test.md");
        Files.createDirectories(report.getParent());
        Files.writeString(
                report,
                "# Shared Handoff Test\n\n报告共享落盘验证成功。\n",
                StandardCharsets.UTF_8);

        WorkspaceManager manager = mock(WorkspaceManager.class);
        WorkspaceResolutionService.ResolvedWorkspace workspace =
                new WorkspaceResolutionService.ResolvedWorkspace(
                        manager, "admin", tempDir.toString());
        WorkspaceFileService service = new WorkspaceFileService(mock(AgentActivityStore.class));

        List<AgentWorkspaceController.FileNode> tree = service.tree(workspace, true);

        assertThat(tree).extracting(AgentWorkspaceController.FileNode::name).contains("reports");
        AgentWorkspaceController.FileNode reports =
                tree.stream()
                        .filter(node -> "reports".equals(node.name()))
                        .findFirst()
                        .orElseThrow();
        assertThat(reports.children())
                .extracting(AgentWorkspaceController.FileNode::path)
                .contains("reports/shared-handoff-test.md");
        assertThat(service.readFile(workspace, "reports/shared-handoff-test.md"))
                .isEqualTo("# Shared Handoff Test\n\n报告共享落盘验证成功。\n");
        verifyNoInteractions(manager);
    }

    @Test
    void omitsThePlatformActivityLedgerFromWorkspaceTree() throws Exception {
        Files.writeString(tempDir.resolve("activity.jsonl"), "internal event\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("activity-1722266000000.jsonl"), "older event\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("notes.md"), "visible", StandardCharsets.UTF_8);

        WorkspaceManager manager = mock(WorkspaceManager.class);
        WorkspaceResolutionService.ResolvedWorkspace workspace =
                new WorkspaceResolutionService.ResolvedWorkspace(
                        manager, "admin", tempDir.toString());
        WorkspaceFileService service = new WorkspaceFileService(mock(AgentActivityStore.class));

        List<AgentWorkspaceController.FileNode> tree = service.tree(workspace, true);

        assertThat(tree)
                .extracting(AgentWorkspaceController.FileNode::name)
                .containsExactly("notes.md");
        verifyNoInteractions(manager);
    }
}
