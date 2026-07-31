package io.agentscope.dataagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapConfigPromptTest {

    @TempDir Path tempDir;

    @Test
    void resolvesClasspathPromptFromExecutableFriendlyClassLoader() {
        String prompt = BootstrapConfig.resolvePrompt("classpath:/prompts/system.md");

        assertThat(prompt).contains("run_sql_preview");
        assertThat(prompt).doesNotContain("classpath:/prompts/system.md");
    }

    @Test
    void repairsLiteralClasspathPromptWrittenByOlderMigration() throws Exception {
        Path agentsMd = tempDir.resolve("AGENTS.md");
        Files.writeString(
                agentsMd,
                "# Data Agent\n\nclasspath:/prompts/system.md\n\n## How this folder works\n\nKeep me.\n");

        BootstrapConfig.upgradeGeneratedWorkspacePrompt(agentsMd, "resolved prompt");

        assertThat(agentsMd).hasContent(
                "# Data Agent\n\nresolved prompt\n\n## How this folder works\n\nKeep me.\n");
    }
}
