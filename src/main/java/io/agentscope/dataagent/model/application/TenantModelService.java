package io.agentscope.dataagent.model.application;

import io.agentscope.core.model.CachePolicy;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.dataagent.model.infrastructure.TenantModelConfigEntity;
import io.agentscope.dataagent.model.infrastructure.TenantModelConfigRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a tenant-scoped provider connection through AgentScope's ModelCreationContext.
 *
 * <p>The current product has no separate tenant table yet, so the authenticated user id is the
 * tenant id at this boundary. This keeps the storage/API contract tenant-safe while a future
 * organisation table can replace {@link #tenantForUser(String)} without a model migration.
 */
@Service
public class TenantModelService {

    public static final String PROVIDER_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE";
    public static final String PROVIDER_OLLAMA = "OLLAMA";

    private final TenantModelConfigRepository repository;
    private final TenantModelCredentialCipher cipher;

    public TenantModelService(
            TenantModelConfigRepository repository, TenantModelCredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public static String tenantForUser(String userId) {
        return userId;
    }

    /** Uses a tenant configuration when present; otherwise preserves the existing static model. */
    public Model resolve(String userId, String logicalModelId) {
        String tenantId = tenantForUser(userId);
        return repository.findByTenantIdAndLogicalModelId(tenantId, normalizeLogicalId(logicalModelId))
                .filter(TenantModelConfigEntity::isEnabled)
                .map(this::resolveConfigured)
                .orElseGet(() -> ModelRegistry.resolve(normalizeLogicalId(logicalModelId)));
    }

    public long calculateCostMicrousd(
            String userId, String logicalModelId, long inputTokens, long outputTokens, long cachedPromptTokens) {
        Optional<TenantModelConfigEntity> configured = repository.findByTenantIdAndLogicalModelId(
                tenantForUser(userId), normalizeLogicalId(logicalModelId));
        if (configured.isEmpty()) return 0L;
        TenantModelConfigEntity config = configured.get();
        long standardInput = Math.max(0L, inputTokens - cachedPromptTokens);
        return perMillion(standardInput, config.getInputMicrousdPerMillion())
                + perMillion(Math.max(0L, cachedPromptTokens), config.getCachedInputMicrousdPerMillion())
                + perMillion(Math.max(0L, outputTokens), config.getOutputMicrousdPerMillion());
    }

    public String effectiveModelLabel(String userId, String logicalModelId) {
        return repository.findByTenantIdAndLogicalModelId(
                        tenantForUser(userId), normalizeLogicalId(logicalModelId))
                .filter(TenantModelConfigEntity::isEnabled)
                .map(TenantModelConfigEntity::getModelName)
                .orElse(normalizeLogicalId(logicalModelId));
    }

    public List<ModelConfigView> list(String userId) {
        return repository.findByTenantIdOrderByLogicalModelId(tenantForUser(userId)).stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public ModelConfigView upsert(String userId, String logicalModelId, UpsertModelConfig request) {
        String tenantId = tenantForUser(userId);
        String normalized = normalizeLogicalId(logicalModelId);
        TenantModelConfigEntity entity = repository
                .findByTenantIdAndLogicalModelId(tenantId, normalized)
                .orElseGet(TenantModelConfigEntity::new);
        entity.setTenantId(tenantId);
        entity.setLogicalModelId(normalized);
        entity.setProvider(normalizeProvider(request.provider()));
        entity.setModelName(required(request.modelName(), "modelName"));
        entity.setBaseUrl(blankToNull(request.baseUrl()));
        entity.setEndpointPath(blankToNull(request.endpointPath()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setInputMicrousdPerMillion(nonNegative(request.inputMicrousdPerMillion()));
        entity.setCachedInputMicrousdPerMillion(nonNegative(request.cachedInputMicrousdPerMillion()));
        entity.setOutputMicrousdPerMillion(nonNegative(request.outputMicrousdPerMillion()));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKeyCiphertext(cipher.encrypt(request.apiKey()));
        }
        if (PROVIDER_OPENAI_COMPATIBLE.equals(entity.getProvider())
                && (entity.getApiKeyCiphertext() == null || entity.getApiKeyCiphertext().isBlank())) {
            throw new IllegalArgumentException("An API key is required for an OpenAI-compatible tenant model");
        }
        entity.setUpdatedAtMs(System.currentTimeMillis());
        return view(repository.save(entity));
    }

    @Transactional
    public void delete(String userId, String logicalModelId) {
        repository.deleteByTenantIdAndLogicalModelId(tenantForUser(userId), normalizeLogicalId(logicalModelId));
    }

    private Model resolveConfigured(TenantModelConfigEntity config) {
        ModelCreationContext context = ModelCreationContext.builder()
                .apiKey(cipher.decrypt(config.getApiKeyCiphertext()))
                .baseUrl(config.getBaseUrl())
                .endpointPath(config.getEndpointPath())
                .stream(Boolean.TRUE)
                .cachePolicy(CachePolicy.ENABLED)
                .cacheId(config.getTenantId() + ":" + config.getLogicalModelId() + ":" + config.getUpdatedAtMs())
                .option("modelName", config.getModelName())
                .build();
        String factoryId = PROVIDER_OLLAMA.equals(config.getProvider()) ? "tenant-ollama" : "tenant-openai";
        return ModelRegistry.resolve(factoryId, context);
    }

    private ModelConfigView view(TenantModelConfigEntity entity) {
        return new ModelConfigView(
                entity.getLogicalModelId(),
                entity.getProvider(),
                entity.getModelName(),
                entity.getBaseUrl(),
                entity.getEndpointPath(),
                entity.isEnabled(),
                entity.getApiKeyCiphertext() != null && !entity.getApiKeyCiphertext().isBlank(),
                entity.getInputMicrousdPerMillion(),
                entity.getCachedInputMicrousdPerMillion(),
                entity.getOutputMicrousdPerMillion(),
                entity.getUpdatedAtMs());
    }

    private static long perMillion(long tokens, long microUsdPerMillion) {
        if (tokens == 0 || microUsdPerMillion == 0) return 0L;
        return Math.round((double) tokens * microUsdPerMillion / 1_000_000d);
    }

    private static String normalizeLogicalId(String value) { return required(value, "logicalModelId").toLowerCase(Locale.ROOT); }
    private static String normalizeProvider(String value) {
        String provider = required(value, "provider").toUpperCase(Locale.ROOT);
        if (!PROVIDER_OPENAI_COMPATIBLE.equals(provider) && !PROVIDER_OLLAMA.equals(provider)) {
            throw new IllegalArgumentException("provider must be OPENAI_COMPATIBLE or OLLAMA");
        }
        return provider;
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static long nonNegative(Long value) { return value == null ? 0L : Math.max(0L, value); }

    public record UpsertModelConfig(
            String provider,
            String modelName,
            String baseUrl,
            String endpointPath,
            String apiKey,
            Boolean enabled,
            Long inputMicrousdPerMillion,
            Long cachedInputMicrousdPerMillion,
            Long outputMicrousdPerMillion) {}

    public record ModelConfigView(
            String logicalModelId,
            String provider,
            String modelName,
            String baseUrl,
            String endpointPath,
            boolean enabled,
            boolean apiKeyConfigured,
            long inputMicrousdPerMillion,
            long cachedInputMicrousdPerMillion,
            long outputMicrousdPerMillion,
            long updatedAtMs) {}
}
