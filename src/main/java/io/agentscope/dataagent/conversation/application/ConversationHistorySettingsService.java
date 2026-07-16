package io.agentscope.dataagent.conversation.application;

import io.agentscope.dataagent.config.properties.ConversationHistoryProperties;
import io.agentscope.dataagent.conversation.infrastructure.ConversationHistoryPreferenceEntity;
import io.agentscope.dataagent.conversation.infrastructure.ConversationHistoryPreferenceRepository;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntity;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntityRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists and applies the per-user, per-agent conversation history cap. */
@Service
public class ConversationHistorySettingsService {

    public static final int MIN_MAX_SESSIONS = 1;
    public static final int MAX_MAX_SESSIONS = 500;

    private final ConversationHistoryPreferenceRepository preferenceRepository;
    private final SessionEntityRepository sessionRepository;
    private final ConversationHistoryProperties properties;

    public ConversationHistorySettingsService(
            ConversationHistoryPreferenceRepository preferenceRepository,
            SessionEntityRepository sessionRepository,
            ConversationHistoryProperties properties) {
        this.preferenceRepository = preferenceRepository;
        this.sessionRepository = sessionRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public int maxSessions(String userId, String agentId) {
        return preferenceRepository
                .findByUserIdAndAgentId(userId, agentId)
                .map(ConversationHistoryPreferenceEntity::getMaxSessions)
                .orElseGet(this::defaultMaxSessions);
    }

    @Transactional
    public int updateMaxSessions(String userId, String agentId, int requestedMaxSessions) {
        int maxSessions = validate(requestedMaxSessions);
        ConversationHistoryPreferenceEntity preference =
                preferenceRepository
                        .findByUserIdAndAgentId(userId, agentId)
                        .orElseGet(ConversationHistoryPreferenceEntity::new);
        preference.setUserId(userId);
        preference.setAgentId(agentId);
        preference.setMaxSessions(maxSessions);
        preferenceRepository.save(preference);
        enforceRetention(userId, agentId, maxSessions);
        return maxSessions;
    }

    @Transactional
    public void enforceRetention(String userId, String agentId) {
        enforceRetention(userId, agentId, maxSessions(userId, agentId));
    }

    private void enforceRetention(String userId, String agentId, int maxSessions) {
        List<SessionEntity> sessions =
                sessionRepository.findByUserIdAndAgentIdOrderByLastActivityMsDesc(userId, agentId);
        if (sessions.size() <= maxSessions) {
            return;
        }
        sessionRepository.deleteAll(sessions.subList(maxSessions, sessions.size()));
    }

    private int defaultMaxSessions() {
        return validate(properties.getDefaultMaxSessions());
    }

    private static int validate(int value) {
        if (value < MIN_MAX_SESSIONS || value > MAX_MAX_SESSIONS) {
            throw new IllegalArgumentException(
                    "maxSessions must be between "
                            + MIN_MAX_SESSIONS
                            + " and "
                            + MAX_MAX_SESSIONS);
        }
        return value;
    }
}
