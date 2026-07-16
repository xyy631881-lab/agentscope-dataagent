/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.conversation.application;

import io.agentscope.dataagent.conversation.infrastructure.SessionEntity;
import io.agentscope.dataagent.conversation.infrastructure.SessionEntityRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Repairs stale duplicate session metadata left by earlier conversation routing code. */
@Component
class ConversationSessionIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSessionIntegrityService.class);

    private final SessionEntityRepository sessionRepository;

    ConversationSessionIntegrityService(SessionEntityRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void repairDuplicateGateRecords() {
        Map<RouteKey, List<SessionEntity>> groups = new LinkedHashMap<>();
        for (SessionEntity session : sessionRepository.findAll()) {
            if (session.getUserId() == null
                    || session.getUserId().isBlank()
                    || session.getGateKey() == null
                    || session.getGateKey().isBlank()) {
                continue;
            }
            groups.computeIfAbsent(
                            new RouteKey(session.getUserId(), session.getGateKey()),
                            ignored -> new ArrayList<>())
                    .add(session);
        }

        int removed = 0;
        for (List<SessionEntity> duplicates : groups.values()) {
            if (duplicates.size() < 2) {
                continue;
            }
            duplicates.sort(Comparator.comparingLong(SessionEntity::getLastActivityMs).reversed());
            SessionEntity retained = duplicates.get(0);
            if (retained.getLabel() == null || retained.getLabel().isBlank()) {
                duplicates.stream()
                        .map(SessionEntity::getLabel)
                        .filter(label -> label != null && !label.isBlank())
                        .findFirst()
                        .ifPresent(retained::setLabel);
                sessionRepository.save(retained);
            }
            List<SessionEntity> stale = List.copyOf(duplicates.subList(1, duplicates.size()));
            sessionRepository.deleteAllInBatch(stale);
            removed += stale.size();
        }
        if (removed > 0) {
            log.warn("Removed {} duplicate conversation session metadata record(s)", removed);
        }
    }

    private record RouteKey(String userId, String gateKey) {}
}
