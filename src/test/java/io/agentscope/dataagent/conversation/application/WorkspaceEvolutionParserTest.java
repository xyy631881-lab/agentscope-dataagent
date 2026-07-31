package io.agentscope.dataagent.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspaceEvolutionParserTest {

    @Test
    void extractsWorkspaceMutationDetails() {
        String jsonl =
                """
                {"type":"message","timestamp":1784787000.125,"role":"ASSISTANT","content":"[tool_call: write_file({\\"path\\":\\"reports/ui-golden-flow.md\\",\\"content\\":\\"report body\\"})]","toolCallId":"write-1"}
                {"type":"message","timestamp":1784787001.250,"role":"ASSISTANT","content":"[tool_call: move_file({\\"from\\":\\"reports/draft.md\\",\\"to\\":\\"reports/final.md\\"})]","toolCallId":"move-1"}
                {"type":"message","timestamp":1784787002.500,"role":"ASSISTANT","content":"[tool_call: delete_file({\\"path\\":\\"reports/old.md\\"})]","toolCallId":"delete-1"}
                {"type":"message","timestamp":1784787003.750,"role":"ASSISTANT","content":"[tool_call: edit_file({\\"path\\":\\"reports/final.md\\",\\"old_text\\":\\"draft\\",\\"new_text\\":\\"final\\"})]","toolCallId":"edit-1"}
                """;

        List<WorkspaceEvolutionParser.ParsedMutation> events =
                WorkspaceEvolutionParser.parse(jsonl);

        assertThat(events).hasSize(4);
        assertThat(events.get(0))
                .extracting(
                        WorkspaceEvolutionParser.ParsedMutation::toolName,
                        WorkspaceEvolutionParser.ParsedMutation::path,
                        WorkspaceEvolutionParser.ParsedMutation::kind,
                        WorkspaceEvolutionParser.ParsedMutation::postSize)
                .containsExactly("write_file", "reports/ui-golden-flow.md", "EDIT", 11L);
        assertThat(events.get(1).path()).isEqualTo("reports/draft.md -> reports/final.md");
        assertThat(events.get(1).kind()).isEqualTo("MOVE");
        assertThat(events.get(2).kind()).isEqualTo("DELETE");
        assertThat(events.get(3))
                .extracting(
                        WorkspaceEvolutionParser.ParsedMutation::toolName,
                        WorkspaceEvolutionParser.ParsedMutation::path,
                        WorkspaceEvolutionParser.ParsedMutation::kind)
                .containsExactly("edit_file", "reports/final.md", "EDIT");
    }

    @Test
    void ignoresReadOnlyAndMalformedEntries() {
        String jsonl =
                """
                {"type":"message","timestamp":1,"role":"ASSISTANT","content":"[tool_call: read_file({\\"path\\":\\"reports/a.md\\"})]"}
                {not-json}
                {"type":"message","timestamp":2,"role":"USER","content":"[tool_call: write_file({\\"path\\":\\"reports/b.md\\"})]"}
                """;

        assertThat(WorkspaceEvolutionParser.parse(jsonl)).isEmpty();
    }

    @Test
    void parsesCompletedLiveToolCallWithoutDependingOnTranscriptFileName() {
        List<WorkspaceEvolutionParser.ParsedMutation> events =
                WorkspaceEvolutionParser.parseToolCall(
                        "edit_file",
                        "{\"path\":\"reports/ui-golden-flow.md\",\"old_text\":\"draft\",\"new_text\":\"done\"}",
                        "edit-2",
                        123L);

        assertThat(events).singleElement().extracting(
                WorkspaceEvolutionParser.ParsedMutation::toolCallId,
                WorkspaceEvolutionParser.ParsedMutation::path,
                WorkspaceEvolutionParser.ParsedMutation::kind,
                WorkspaceEvolutionParser.ParsedMutation::postSize)
                .containsExactly("edit-2", "reports/ui-golden-flow.md", "EDIT", 0L);
    }
}
