package io.agentscope.dataagent.observability.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceRunRepository extends JpaRepository<TraceRunEntity, Long> {
    Optional<TraceRunEntity> findByTraceId(String traceId);
    List<TraceRunEntity> findByUserIdOrderByStartedAtMsDesc(String userId, Pageable pageable);
}
