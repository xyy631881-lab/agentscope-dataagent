package io.agentscope.dataagent.conversation.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.config.properties.ConversationHistoryProperties;
import io.agentscope.dataagent.conversation.domain.SessionKind;
import io.agentscope.dataagent.conversation.infrastructure.ConversationHistoryPreferenceRepository;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntity;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntityRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationHistorySettingsServiceTest {

    @Test
    void evictsTheOldestSessionsWhenThePerAgentCapIsLowered() {
        ConversationHistoryPreferenceRepository preferenceRepository = mock(ConversationHistoryPreferenceRepository.class);
        SessionEntityRepository sessionRepository = mock(SessionEntityRepository.class);
        ConversationHistoryProperties properties = new ConversationHistoryProperties();
        ConversationHistorySettingsService service = new ConversationHistorySettingsService(
                preferenceRepository, sessionRepository, properties);

        SessionEntity newest = new SessionEntity();
        SessionEntity middle = new SessionEntity();
        SessionEntity oldest = new SessionEntity();
        when(preferenceRepository.findByUserIdAndAgentId("user-1", "data-agent"))
                .thenReturn(Optional.empty());
        when(sessionRepository.findByUserIdAndAgentIdAndKindOrderByLastActivityMsDesc(
                        "user-1", "data-agent", SessionKind.MAIN.getValue()))
                .thenReturn(List.of(newest, middle, oldest));

        service.updateMaxSessions("user-1", "data-agent", 2);

        verify(sessionRepository).deleteAll(List.of(oldest));
    }
}
