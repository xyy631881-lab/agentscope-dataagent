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
package io.agentscope.dataagent.agent.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.agent.domain.AgentShareGrant;
import io.agentscope.dataagent.agent.domain.GlobalAgentOverrideStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link GlobalAgentOverrideStore}. Always wired in by {@link
 * io.agentscope.dataagent.config.JpaPersistenceConfig}.
 *
 * <p>Reads and writes go through a single transaction per call. Share grants are persisted as
 * child rows with {@code orphanRemoval=true}, so saving an override with a new share list replaces
 * the previous grants atomically.
 */
@Transactional
public class JpaGlobalAgentOverrideStore implements GlobalAgentOverrideStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final GlobalOverrideRepository repository;

    public JpaGlobalAgentOverrideStore(GlobalOverrideRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GlobalOverride> findById(String agentId) {
        return repository.findByAgentId(agentId).map(JpaGlobalAgentOverrideStore::toOverride);
    }

    @Override
    public GlobalOverride save(GlobalOverride entry) {
        GlobalOverrideEntity entity =
                repository
                        .findByAgentId(entry.id())
                        .orElseGet(
                                () -> {
                                    GlobalOverrideEntity fresh = new GlobalOverrideEntity();
                                    fresh.setAgentId(entry.id());
                                    return fresh;
                                });

        entity.setName(entry.name());
        entity.setDescription(entry.description());
        entity.setSysPrompt(entry.sysPrompt());
        entity.setModel(entry.model());
        entity.setMaxIters(entry.maxIters());
        entity.setToolsAllowJson(writeList(entry.toolsAllow()));
        entity.setToolsDenyJson(writeList(entry.toolsDeny()));
        entity.setIdentityName(entry.identityName());
        entity.setIdentityEmoji(entry.identityEmoji());
        entity.setGroupChatMentionPatternsJson(writeList(entry.groupChatMentionPatterns()));
        entity.setGroupChatRequireMention(entry.groupChatRequireMention());
        entity.setSkillsAllowJson(writeList(entry.skillsAllow()));
        entity.setSkillsDenyJson(writeList(entry.skillsDeny()));
        entity.setRunAs(entry.runAs());
        entity.setSandboxMode(entry.sandboxMode());
        entity.setSandboxScope(entry.sandboxScope());
        entity.setCreatedAt(entry.createdAt());
        entity.setUpdatedAt(entry.updatedAt());

        entity.getShares().clear();
        if (entry.shares() != null) {
            for (AgentShareGrant g : entry.shares()) {
                entity.getShares()
                        .add(
                                new GlobalOverrideShareEntity(
                                        entity,
                                        g.granteeType(),
                                        g.granteeId(),
                                        g.tier(),
                                        g.createdAt(),
                                        g.createdBy()));
            }
        }

        GlobalOverrideEntity saved = repository.save(entity);
        return toOverride(saved);
    }

    @Override
    public void delete(String agentId) {
        repository.findByAgentId(agentId).ifPresent(repository::delete);
    }

    // -----------------------------------------------------------------
    //  Mapping helpers
    // -----------------------------------------------------------------

    private static GlobalOverride toOverride(GlobalOverrideEntity e) {
        return new GlobalOverride(
                e.getAgentId(),
                e.getName(),
                e.getDescription(),
                e.getSysPrompt(),
                e.getModel(),
                e.getMaxIters(),
                readList(e.getToolsAllowJson()),
                readList(e.getToolsDenyJson()),
                e.getIdentityName(),
                e.getIdentityEmoji(),
                readList(e.getGroupChatMentionPatternsJson()),
                e.getGroupChatRequireMention(),
                readList(e.getSkillsAllowJson()),
                readList(e.getSkillsDenyJson()),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                mapShares(e.getShares()),
                e.getRunAs(),
                e.getSandboxMode(),
                e.getSandboxScope());
    }

    private static List<AgentShareGrant> mapShares(List<GlobalOverrideShareEntity> shares) {
        if (shares == null || shares.isEmpty()) {
            return null;
        }
        List<AgentShareGrant> out = new ArrayList<>(shares.size());
        for (GlobalOverrideShareEntity s : shares) {
            out.add(
                    new AgentShareGrant(
                            s.getGranteeType(),
                            s.getGranteeId(),
                            s.getTier(),
                            s.getCreatedAt(),
                            s.getCreatedBy()));
        }
        return out;
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String writeList(List<String> list) {
        if (list == null) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
