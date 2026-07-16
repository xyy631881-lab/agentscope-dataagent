package io.agentscope.dataagent.model.api;

import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.model.application.TenantModelService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Tenant-owned model connection settings. API keys are write-only. */
@RestController
@RequestMapping("/api/tenant/models")
public class TenantModelController {

    private final TenantModelService models;
    private final AgentLifecycleService lifecycleService;

    public TenantModelController(TenantModelService models, AgentLifecycleService lifecycleService) {
        this.models = models;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    public List<TenantModelService.ModelConfigView> list(Authentication authentication) {
        return models.list(userId(authentication));
    }

    @PutMapping("/{logicalModelId}")
    public TenantModelService.ModelConfigView upsert(
            @PathVariable String logicalModelId,
            @RequestBody TenantModelService.UpsertModelConfig request,
            Authentication authentication) {
        String userId = userId(authentication);
        try {
            TenantModelService.ModelConfigView result = models.upsert(userId, logicalModelId, request);
            lifecycleService.invalidateAllForUser(userId);
            return result;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/{logicalModelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String logicalModelId, Authentication authentication) {
        String userId = userId(authentication);
        models.delete(userId, logicalModelId);
        lifecycleService.invalidateAllForUser(userId);
    }

    private static String userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof String userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return userId;
    }
}
