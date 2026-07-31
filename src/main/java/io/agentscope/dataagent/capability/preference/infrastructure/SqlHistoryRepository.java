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
package io.agentscope.dataagent.capability.preference.infrastructure;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * SQL 历史记录的 JPA repository。
 *
 * <p>{@link #findTopSqlPatterns} 按用户 + agent 分组统计最常使用的 SQL 模式，
 * 返回 {@code [sqlText, useCount]} 二元组列表，用于偏好注入。
 */
public interface SqlHistoryRepository extends JpaRepository<SqlHistoryEntity, Long> {

    /**
     * 统计指定用户 + agent 下最常使用的 SQL 模式（按截断后的 sqlText 精确分组），
     * 按使用次数降序返回前 {@code limit} 条。
     *
     * <p>只统计成功的查询（{@code success = true}）——失败的不应作为"偏好"。
     */
    @Query(
            "SELECT e.sqlText AS sqlText, COUNT(e) AS useCount "
                    + "FROM SqlHistoryEntity e "
                    + "WHERE e.userId = :userId "
                    + "AND e.agentId = :agentId "
                    + "AND e.success = true "
                    + "GROUP BY e.sqlText "
                    + "ORDER BY useCount DESC")
    List<SqlPatternProjection> findTopSqlPatterns(
            @Param("userId") String userId, @Param("agentId") String agentId);

    @Query(
            value =
                    "SELECT e.sqlText AS sqlText, COUNT(e) AS useCount "
                            + "FROM SqlHistoryEntity e "
                            + "WHERE e.userId = :userId "
                            + "AND e.agentId = :agentId "
                            + "AND e.success = true "
                            + "GROUP BY e.sqlText "
                            + "ORDER BY useCount DESC",
            countQuery =
                    "SELECT COUNT(DISTINCT e.sqlText) "
                            + "FROM SqlHistoryEntity e "
                            + "WHERE e.userId = :userId "
                            + "AND e.agentId = :agentId "
                            + "AND e.success = true")
    Page<SqlPatternProjection> findSqlPatterns(
            @Param("userId") String userId, @Param("agentId") String agentId, Pageable pageable);

    /** 删除指定用户 + agent 的全部历史记录（偏好清空）。 */
    void deleteByUserIdAndAgentId(String userId, String agentId);

    /** 删除指定用户在所有 agent 下的全部历史记录。 */
    void deleteByUserId(String userId);

    interface SqlPatternProjection {
        String getSqlText();

        Long getUseCount();
    }
}
