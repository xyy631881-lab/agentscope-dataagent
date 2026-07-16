package io.agentscope.dataagent.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.conversation.domain.SessionEntry;
import io.agentscope.dataagent.conversation.domain.SessionKind;
import org.junit.jupiter.api.Test;

class ConversationSupportTest {

    @Test
    void usesPersistedLogicalAgentIdWhenGatewayIsNotAvailable() {
        SessionEntry entry = entry("data-agent", "");

        assertThat(ConversationSupport.sessionMatchesAgent(entry, "data-agent", null)).isTrue();
        assertThat(ConversationSupport.sessionMatchesAgent(entry, "another-agent", null)).isFalse();
    }

    @Test
    void retainsGatewayKeyFallbackForLegacySessions() {
        SessionEntry legacy = entry(null, "chatui|x:agentId=runtime-agent|t:conversation-1");

        assertThat(ConversationSupport.sessionMatchesAgent(legacy, "data-agent", "runtime-agent")).isTrue();
    }

    private static SessionEntry entry(String agentId, String gateKey) {
        return new SessionEntry(
                "conversation-1",
                agentId,
                "main-1",
                null,
                SessionKind.MAIN,
                null,
                0,
                1L,
                1L,
                null,
                null,
                gateKey,
                "user-1");
    }
}
