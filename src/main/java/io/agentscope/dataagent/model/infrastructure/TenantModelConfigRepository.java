package io.agentscope.dataagent.model.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantModelConfigRepository extends JpaRepository<TenantModelConfigEntity, Long> {
    List<TenantModelConfigEntity> findByTenantIdOrderByLogicalModelId(String tenantId);
    Optional<TenantModelConfigEntity> findByTenantIdAndLogicalModelId(String tenantId, String logicalModelId);
    void deleteByTenantIdAndLogicalModelId(String tenantId, String logicalModelId);
}
