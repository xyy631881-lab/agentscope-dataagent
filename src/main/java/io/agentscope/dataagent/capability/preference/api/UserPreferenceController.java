/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.dataagent.capability.preference.api;

import io.agentscope.dataagent.agent.application.AgentAccessGuard;
import io.agentscope.dataagent.agent.application.AgentAclService.Tier;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.capability.preference.application.UserPreferenceService;
import io.agentscope.dataagent.capability.preference.application.UserPreferenceService.PreferenceSummary;
import io.agentscope.dataagent.capability.preference.application.UserPreferenceService.SqlPatternPage;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents/{agentId}/preferences")
public class UserPreferenceController {

    private final AgentAccessGuard guard;
    private final UserPreferenceService preferences;
    private final AgentLifecycleService lifecycleService;

    public UserPreferenceController(
            AgentAccessGuard guard,
            UserPreferenceService preferences,
            AgentLifecycleService lifecycleService) {
        this.guard = guard;
        this.preferences = preferences;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    public PreferenceSummary get(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        return preferences.getSummary(userId, agentId);
    }

    @GetMapping("/sql-patterns")
    public SqlPatternPage sqlPatterns(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        return preferences.getSqlPatterns(userId, agentId, page, size);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        preferences.clear(userId, agentId);
        lifecycleService.invalidateUca(userId, agentId);
    }
}
