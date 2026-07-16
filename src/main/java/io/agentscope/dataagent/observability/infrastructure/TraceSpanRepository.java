package io.agentscope.dataagent.observability.infrastructure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceSpanRepository extends JpaRepository<TraceSpanEntity, Long> {
    boolean existsBySpanId(String spanId);
    List<TraceSpanEntity> findByTraceIdOrderByStartedAtMsAsc(String traceId);
}
