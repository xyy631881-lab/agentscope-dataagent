package io.agentscope.dataagent.capability.contribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarketContributionControllerTest {

    @Test
    void preservesSkillBundleRelativePaths() {
        assertThat(
                        MarketContributionController.skillBundleRelativePaths(
                                List.of(
                                        "skills/sql-analysis/SKILL.md",
                                        "skills/sql-analysis/templates/query.sql")))
                .containsExactly("SKILL.md", "templates/query.sql");
    }

    @Test
    void rejectsFilesFromDifferentSkillBundles() {
        assertThatThrownBy(
                        () ->
                                MarketContributionController.skillBundleRelativePaths(
                                        List.of(
                                                "skills/sql-analysis/SKILL.md",
                                                "skills/other/templates/query.sql")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same bundle directory");
    }

    @Test
    void rejectsSkillFolderWithoutManifest() {
        assertThatThrownBy(
                        () ->
                                MarketContributionController.skillBundleRelativePaths(
                                        List.of("skills/skill-creator/scripts/package_skill.py")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must include SKILL.md");
    }
}
