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
package io.agentscope.dataagent.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话元数据的 JPA Repository，替代原先的 SessionStore + SessionAgentManager 内存索引。
 *
 * <p>通过 Spring Data JPA 的方法命名约定自动生成查询，无需手写 SQL。
 * 所有查询直接走数据库索引，不再维护内存中的 ConcurrentHashMap。
 */
public interface SessionEntityRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findBySessionKey(String sessionKey);

    boolean existsBySessionKey(String sessionKey);

    Optional<SessionEntity> findByGateKeyAndUserId(String gateKey, String userId);

    List<SessionEntity> findByUserIdOrderByLastActivityMsDesc(String userId);

    List<SessionEntity> findByUserIdAndAgentIdOrderByLastActivityMsDesc(
            String userId, String agentId);

    List<SessionEntity> findByKind(String kind);

    /** 查询最后活跃时间早于 cutoff 的会话（用于空闲重置和过期清理）。 */
    List<SessionEntity> findByLastActivityMsBefore(long cutoff);

    /** 按用户和会话类型查询（替代 findByKind + 内存过滤）。 */
    List<SessionEntity> findByUserIdAndKind(String userId, String kind);

    /** 按最后活跃时间升序分页查询（用于 maxEntries 超量清理，只取最旧的 N 条）。 */
    List<SessionEntity> findAllByOrderByLastActivityMsAsc(Pageable pageable);

    @Transactional
    void deleteBySessionKey(String sessionKey);
}
