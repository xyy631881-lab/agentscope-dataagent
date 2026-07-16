package io.agentscope.dataagent.model.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Encrypted provider connection and pricing policy owned by one tenant. */
@Entity
@Table(
        name = "tenant_model_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_tenant_model", columnNames = {"tenant_id", "logical_model_id"}),
        indexes = @Index(name = "ix_tenant_model_tenant", columnList = "tenant_id"))
public class TenantModelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "config_id")
    private Long configId;

    @Column(name = "tenant_id", length = 128, nullable = false)
    private String tenantId;

    @Column(name = "logical_model_id", length = 128, nullable = false)
    private String logicalModelId;

    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    @Column(name = "model_name", length = 256, nullable = false)
    private String modelName;

    @Column(name = "base_url", length = 1024)
    private String baseUrl;

    @Column(name = "endpoint_path", length = 512)
    private String endpointPath;

    @Column(name = "api_key_ciphertext", length = 4096)
    private String apiKeyCiphertext;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Micro USD per million tokens. Zero means the provider price is intentionally not configured. */
    @Column(name = "input_microusd_per_million", nullable = false)
    private long inputMicrousdPerMillion;

    @Column(name = "cached_input_microusd_per_million", nullable = false)
    private long cachedInputMicrousdPerMillion;

    @Column(name = "output_microusd_per_million", nullable = false)
    private long outputMicrousdPerMillion;

    @Column(name = "updated_at_ms", nullable = false)
    private long updatedAtMs;

    public TenantModelConfigEntity() {}

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getLogicalModelId() { return logicalModelId; }
    public void setLogicalModelId(String logicalModelId) { this.logicalModelId = logicalModelId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public void setApiKeyCiphertext(String apiKeyCiphertext) { this.apiKeyCiphertext = apiKeyCiphertext; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getInputMicrousdPerMillion() { return inputMicrousdPerMillion; }
    public void setInputMicrousdPerMillion(long inputMicrousdPerMillion) { this.inputMicrousdPerMillion = inputMicrousdPerMillion; }
    public long getCachedInputMicrousdPerMillion() { return cachedInputMicrousdPerMillion; }
    public void setCachedInputMicrousdPerMillion(long cachedInputMicrousdPerMillion) { this.cachedInputMicrousdPerMillion = cachedInputMicrousdPerMillion; }
    public long getOutputMicrousdPerMillion() { return outputMicrousdPerMillion; }
    public void setOutputMicrousdPerMillion(long outputMicrousdPerMillion) { this.outputMicrousdPerMillion = outputMicrousdPerMillion; }
    public long getUpdatedAtMs() { return updatedAtMs; }
    public void setUpdatedAtMs(long updatedAtMs) { this.updatedAtMs = updatedAtMs; }
}
