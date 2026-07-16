package io.agentscope.dataagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.agent.api.AgentWorkspaceController.FileNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceFileSupportTest {

    @Test
    void mergesSandboxAndLocalMirrorDirectoriesWithoutDroppingArtifacts() {
        FileNode sandboxPlans = new FileNode("plans", "plans", "dir", null, List.of());
        FileNode mirroredPlan = new FileNode("PLAN.md", "plans/PLAN.md", "file", 42L, null);
        FileNode mirroredArtifacts = new FileNode(
                "artifacts",
                "artifacts",
                "dir",
                null,
                List.of(new FileNode("chart.vl.json", "artifacts/chart.vl.json", "file", 80L, null)));

        List<FileNode> merged = WorkspaceFileSupport.mergeTrees(
                List.of(sandboxPlans),
                List.of(
                        new FileNode("plans", "plans", "dir", null, List.of(mirroredPlan)),
                        mirroredArtifacts));

        assertThat(merged).extracting(FileNode::path).containsExactly("artifacts", "plans");
        assertThat(merged.get(1).children()).extracting(FileNode::path).containsExactly("plans/PLAN.md");
        assertThat(merged.get(0).children()).extracting(FileNode::path).containsExactly("artifacts/chart.vl.json");
    }
}
