package io.agentscope.dataagent.conversation.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationHistoryPreferenceRepository
        extends JpaRepository<ConversationHistoryPreferenceEntity, Long> {

    Optional<ConversationHistoryPreferenceEntity> findByUserIdAndAgentId(
            String userId, String agentId);
}
