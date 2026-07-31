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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 图表使用记录的 JPA repository。
 *
 * <p>{@link #findChartTypeCounts} 按用户 + agent 分组统计各图表类型的使用次数，
 * 返回 {@code [chartType, useCount]} 二元组列表，用于偏好注入。
 */
public interface ChartUsageRepository extends JpaRepository<ChartUsageEntity, Long> {

    /**
     * 统计指定用户 + agent 下各图表类型的使用次数，按使用次数降序返回。
     */
    @Query(
            "SELECT e.chartType AS chartType, COUNT(e) AS useCount "
                    + "FROM ChartUsageEntity e "
                    + "WHERE e.userId = :userId "
                    + "AND e.agentId = :agentId "
                    + "GROUP BY e.chartType "
                    + "ORDER BY useCount DESC")
    List<ChartTypeProjection> findChartTypeCounts(
            @Param("userId") String userId, @Param("agentId") String agentId);

    /** 删除指定用户 + agent 的全部图表使用记录（偏好清空）。 */
    void deleteByUserIdAndAgentId(String userId, String agentId);

    /** 删除指定用户在所有 agent 下的全部图表使用记录。 */
    void deleteByUserId(String userId);

    interface ChartTypeProjection {
        String getChartType();

        Long getUseCount();
    }
}
