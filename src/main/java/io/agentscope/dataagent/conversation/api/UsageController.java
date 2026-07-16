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
package io.agentscope.dataagent.conversation.api;

import io.agentscope.dataagent.conversation.application.UsageStore;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only usage metrics for the signed-in user and the administrator dashboard. */
@RestController
@RequestMapping("/api")
public class UsageController {

    private final UsageStore usageStore;

    public UsageController(UsageStore usageStore) {
        this.usageStore = usageStore;
    }

    @GetMapping("/usage/me/summary")
    public UsageStore.UsageSummary summaryForCurrentUser(Authentication auth) {
        return usageStore.summaryForUser(userId(auth));
    }

    @GetMapping("/usage/me/hourly")
    public List<UsageStore.BucketCount> hourlyForCurrentUser(
            @RequestParam(defaultValue = "24") int hours, Authentication auth) {
        return usageStore.hourlyTurnsForUser(userId(auth), hours);
    }

    @GetMapping("/usage/me/daily")
    public List<UsageStore.BucketCount> dailyForCurrentUser(
            @RequestParam(defaultValue = "14") int days, Authentication auth) {
        return usageStore.dailyTurnsForUser(userId(auth), days);
    }

    @GetMapping("/usage/me/models")
    public List<UsageStore.ModelUsage> modelsForCurrentUser(
            @RequestParam(defaultValue = "30") int days, Authentication auth) {
        return usageStore.modelUsageForUser(userId(auth), clamp(days, 1, 90));
    }

    @GetMapping("/admin/usage/summary")
    public UsageStore.UsageSummary summary(Authentication auth) {
        requireAdmin(auth);
        return usageStore.summary();
    }

    @GetMapping("/admin/usage/hourly")
    public List<UsageStore.BucketCount> hourly(
            @RequestParam(defaultValue = "24") int hours, Authentication auth) {
        requireAdmin(auth);
        return usageStore.hourlyTurns(hours);
    }

    @GetMapping("/admin/usage/daily")
    public List<UsageStore.BucketCount> daily(
            @RequestParam(defaultValue = "14") int days, Authentication auth) {
        requireAdmin(auth);
        return usageStore.dailyTurns(days);
    }

    @GetMapping("/admin/usage/top-users")
    public List<UsageStore.GroupCount> topUsers(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int n,
            Authentication auth) {
        requireAdmin(auth);
        return usageStore.topUsersByTurns(clamp(days, 1, 90), clamp(n, 1, 100));
    }

    @GetMapping("/admin/usage/top-agents")
    public List<UsageStore.GroupCount> topAgents(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int n,
            Authentication auth) {
        requireAdmin(auth);
        return usageStore.topAgentsByTurns(clamp(days, 1, 90), clamp(n, 1, 100));
    }

    @GetMapping("/admin/usage/models")
    public List<UsageStore.ModelUsage> models(
            @RequestParam(defaultValue = "30") int days, Authentication auth) {
        requireAdmin(auth);
        return usageStore.modelUsage(clamp(days, 1, 90));
    }

    private static String userId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return (String) auth.getPrincipal();
    }

    private static void requireAdmin(Authentication auth) {
        if (auth == null
                || auth.getAuthorities() == null
                || auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .noneMatch("ROLE_ADMIN"::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
