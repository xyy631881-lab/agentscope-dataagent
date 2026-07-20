package io.agentscope.dataagent.conversation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

class SubagentSpawnResultAccumulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsSpawnHeaderFromJsonEncodedToolResult() throws Exception {
        SubagentSpawnResultAccumulator tracker = new SubagentSpawnResultAccumulator();
        String callId = "call-1";
        String result =
                "agent_key: agent:data-explorer:abc\n"
                        + "agent_id: data-explorer\n"
                        + "session_id: sub-123\n"
                        + "status: accepted\n"
                        + "task_id: task-1";

        assertThat(tracker.accept(new ToolResultStartEvent("reply", callId, "agent_spawn")))
                .isEmpty();
        assertThat(
                        tracker.accept(
                                new ToolResultTextDeltaEvent(
                                        "reply",
                                        callId,
                                        "agent_spawn",
                                        objectMapper.writeValueAsString(result))))
                .isEmpty();

        assertThat(
                        tracker.accept(
                                new ToolResultEndEvent(
                                        "reply",
                                        callId,
                                        "agent_spawn",
                                        ToolResultState.SUCCESS)))
                .contains(
                        new SubagentSpawnResultAccumulator.SpawnResult(
                                "data-explorer", "sub-123", callId));
    }

    @Test
    void ignoresResultsFromOtherTools() {
        SubagentSpawnResultAccumulator tracker = new SubagentSpawnResultAccumulator();

        assertThat(tracker.accept(new ToolResultStartEvent("reply", "call-2", "task_output")))
                .isEmpty();
        assertThat(
                        tracker.accept(
                                new ToolResultTextDeltaEvent(
                                        "reply",
                                        "call-2",
                                        "task_output",
                                        "agent_id: data-explorer\nsession_id: sub-123")))
                .isEmpty();
        assertThat(
                        tracker.accept(
                                new ToolResultEndEvent(
                                        "reply",
                                        "call-2",
                                        "task_output",
                                        ToolResultState.SUCCESS)))
                .isEmpty();
    }

    @Test
    void ignoresSpawnResultsForwardedFromNestedAgents() {
        SubagentSpawnResultAccumulator tracker = new SubagentSpawnResultAccumulator();
        ToolResultStartEvent nested =
                (ToolResultStartEvent)
                        new ToolResultStartEvent("reply", "call-3", "agent_spawn")
                                .withSource("main/data-explorer");

        assertThat(tracker.accept(nested)).isEmpty();
        assertThat(
                        tracker.accept(
                                new ToolResultTextDeltaEvent(
                                                "reply",
                                                "call-3",
                                                "agent_spawn",
                                                "agent_id: nested\nsession_id: sub-nested")
                                        .withSource("main/data-explorer")))
                .isEmpty();
    }
}
