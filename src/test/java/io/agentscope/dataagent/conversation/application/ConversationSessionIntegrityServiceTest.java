package io.agentscope.dataagent.conversation.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.conversation.infrastructure.SessionEntity;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntityRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationSessionIntegrityServiceTest {

    @Test
    void keepsTheMostRecentlyActiveRecordForOneGatewayRoute() {
        SessionEntityRepository repository = mock(SessionEntityRepository.class);
        SessionEntity older = session("user-1", "gate-1", 10L, "older");
        SessionEntity newer = session("user-1", "gate-1", 20L, null);
        when(repository.findAll()).thenReturn(List.of(older, newer));
        ConversationSessionIntegrityService service =
                new ConversationSessionIntegrityService(repository);

        service.repairDuplicateGateRecords();

        verify(repository).save(newer);
        verify(repository).deleteAllInBatch(List.of(older));
    }

    private static SessionEntity session(String userId, String gateKey, long lastActivity, String label) {
        SessionEntity entity = new SessionEntity();
        entity.setUserId(userId);
        entity.setGateKey(gateKey);
        entity.setLastActivityMs(lastActivity);
        entity.setLabel(label);
        return entity;
    }
}
