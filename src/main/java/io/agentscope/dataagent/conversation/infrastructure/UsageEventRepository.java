package io.agentscope.dataagent.conversation.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageEventRepository extends JpaRepository<UsageEventEntity, Long> {

    List<UsageEventEntity> findByUserIdAndRecordedAtMsGreaterThanEqual(
            String userId, long recordedAtMs);

    List<UsageEventEntity> findByRecordedAtMsGreaterThanEqual(long recordedAtMs);

    @Query(
            "select count(e), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0), "
                    + "coalesce(sum(e.cachedPromptTokens), 0), coalesce(sum(e.costMicrousd), 0), "
                    + "coalesce(avg(e.durationMs), 0), count(distinct e.userId) from UsageEventEntity e")
    Object[] aggregateAll();

    @Query(
            "select count(e), coalesce(sum(e.inputTokens), 0), coalesce(sum(e.outputTokens), 0), "
                    + "coalesce(sum(e.cachedPromptTokens), 0), coalesce(sum(e.costMicrousd), 0), "
                    + "coalesce(avg(e.durationMs), 0), count(distinct e.userId) from UsageEventEntity e "
                    + "where e.userId = :userId")
    Object[] aggregateForUser(@Param("userId") String userId);

    @Query("select count(e) from UsageEventEntity e where e.recordedAtMs >= :startMs")
    long countSince(@Param("startMs") long startMs);

    @Query("select count(e) from UsageEventEntity e where e.userId = :userId and e.recordedAtMs >= :startMs")
    long countForUserSince(@Param("userId") String userId, @Param("startMs") long startMs);
}
