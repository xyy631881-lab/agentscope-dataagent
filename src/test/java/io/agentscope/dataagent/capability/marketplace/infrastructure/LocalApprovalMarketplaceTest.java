package io.agentscope.dataagent.capability.marketplace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalApprovalMarketplaceTest {

    @TempDir Path tempDir;

    @Test
    void listsAndFetchesArchivedSkillVersionsNewestFirst() throws Exception {
        Path agentRoot = tempDir.resolve("shared/agents/data-agent");
        Path live = agentRoot.resolve("skills/sales-analysis/SKILL.md");
        Files.createDirectories(live.getParent());
        Files.writeString(live, "# Sales\n\nlatest\n", StandardCharsets.UTF_8);
        writeVersion(agentRoot, 1, "# Sales\n\nversion-one\n");
        writeVersion(agentRoot, 3, "# Sales\n\nversion-three\n");

        LocalApprovalMarketplace marketplace =
                new LocalApprovalMarketplace("team", agentRoot.resolve("skills"));

        assertThat(marketplace.list()).singleElement().satisfies(
                summary -> assertThat(summary.version()).isEqualTo("3"));
        assertThat(marketplace.listVersions("sales-analysis")).containsExactly(3, 1);
        assertThat(marketplace.fetchVersion("sales-analysis", 1).markdown())
                .contains("version-one");
    }

    private static void writeVersion(Path agentRoot, int version, String content) throws Exception {
        Path target =
                agentRoot.resolve(
                        ".versions/v" + version + "/skills/sales-analysis/SKILL.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
