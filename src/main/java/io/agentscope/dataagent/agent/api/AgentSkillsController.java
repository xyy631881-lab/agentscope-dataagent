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
package io.agentscope.dataagent.agent.api;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.application.SkillFileService;
import io.agentscope.dataagent.agent.application.SkillInstallService;
import io.agentscope.dataagent.agent.application.WorkspaceResolutionService;
import io.agentscope.dataagent.agent.application.AgentAclService;
import io.agentscope.dataagent.capability.marketplace.application.UserMarketplaceRegistry;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.dataagent.agent.domain.ActivityEvent;
import io.agentscope.dataagent.agent.application.AgentActivityStore;
import io.agentscope.dataagent.agent.application.AgentLifecycleService;
import io.agentscope.dataagent.agent.application.AgentAccessGuard;
import io.agentscope.dataagent.agent.application.AgentAclService.Tier;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-agent skills management for the platform. Mirrors claw's {@code AgentSkillsController} in
 * URL/payload shape, but every operation is gated by {@link AgentAccessGuard} (RUN to browse, EDIT
 * to mutate) and every file I/O goes through the per-(owner, agent) filesystem returned by
 * {@link WorkspaceResolutionService#resolve} — writes land in the per-user Docker sandbox under
 * {@code skills/}, isolated to the owning {@code (userId, agentId)}.
 *
 * <p>Thin HTTP layer — business logic has been extracted to:
 * <ul>
 *   <li>{@link SkillFileService} — filesystem I/O and skill metadata helpers (all static)
 *   <li>{@link SkillInstallService} — repository browsing and skill installation
 * </ul>
 *
 * <p>Each endpoint follows the same pattern: extract userId → guard.require →
 * (resolve) → delegate to service.
 *
 * <p>Cross-user guardrails:
 *
 * <ul>
 *   <li>The agent must be visible to the caller; otherwise 404 (never 403, to avoid leaking
 *       existence). EDIT-tier operations additionally require EDIT.
 *   <li>Marketplace-install uses {@link UserMarketplaceRegistry#find(String, String)} against the
 *       caller's userId — referring to another user's private marketplace id returns 404.
 *   <li>Owner-side writes use {@link AgentCatalogService#findOwnerOf} so a shared-in editor's
 *       changes are persisted in the original owner's namespace (the same namespace the running
 *       {@link HarnessAgent} reads from).
 * </ul>
 *
 * <p>Activity: every write records an {@link ActivityEvent} via {@link AgentActivityStore} keyed
 * to the actual owner. After every write, the UCA cache is evicted via
 * {@link AgentCatalogService#invalidateUca} so the next chat call rebuilds the agent with the
 * updated skill set.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class AgentSkillsController {

    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;
    private final AgentLifecycleService lifecycleService;
    private final WorkspaceResolutionService resolutionService;
    private final SkillInstallService skillInstallService;

    public AgentSkillsController(
            AgentAccessGuard guard,
            AgentActivityStore activity,
            WorkspaceResolutionService resolutionService,
            AgentLifecycleService lifecycleService,
            SkillInstallService skillInstallService) {
        this.guard = guard;
        this.activity = activity;
        this.lifecycleService = lifecycleService;
        this.resolutionService = resolutionService;
        this.skillInstallService = skillInstallService;
    }

    // -----------------------------------------------------------------
    //  Workspace skills (per-agent overlay under skills/)
    // -----------------------------------------------------------------

    @GetMapping("/workspace")
    public List<WorkspaceSkillInfo> listWorkspaceSkills(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        var ctx = resolutionService.resolve(userId, agentId);
        AbstractFilesystem fs = ctx.filesystem();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(ctx.ownerId()).build();
        LsResult ls = fs.ls(runtimeContext, "/skills");
        if (ls == null || !ls.isSuccess() || ls.entries() == null) {
            return List.<WorkspaceSkillInfo>of();
        }
        List<WorkspaceSkillInfo> out = new ArrayList<>();
        for (FileInfo info : ls.entries()) {
            if (!info.isDirectory()) continue;
            String dirName = SkillFileService.leafName(info.path());
            if (dirName.isBlank()) continue;
            WorkspaceSkillInfo skill = SkillFileService.readWorkspaceSkill(fs, runtimeContext, dirName);
            if (skill != null) out.add(skill);
        }
        out.sort(Comparator.comparing(WorkspaceSkillInfo::name));
        return out;
    }

    @GetMapping("/workspace/{name}")
    public WorkspaceSkillDetail getWorkspaceSkill(
            @PathVariable String agentId, @PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        SkillFileService.validateSkillName(name);
        var ctx = resolutionService.resolve(userId, agentId);
        AbstractFilesystem fs = ctx.filesystem();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(ctx.ownerId()).build();
        String markdown = SkillFileService.readUtf8(fs, runtimeContext, "/skills/" + name + "/SKILL.md");
        if (markdown == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "SKILL.md missing for: " + name);
        }
        Map<String, String> resources = SkillFileService.collectResources(fs, runtimeContext, name);
        String description =
                SkillFileService.parseFrontMatterField(markdown, SkillFileService.DESCRIPTION_LINE);
        return new WorkspaceSkillDetail(name, description, markdown, resources);
    }

    @PutMapping("/workspace/{name}")
    public WorkspaceSkillInfo upsertWorkspaceSkill(
            @PathVariable String agentId,
            @PathVariable String name,
            @RequestBody WorkspaceSkillUpsertRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        SkillFileService.validateSkillName(name);
        if (req == null || req.markdown() == null || req.markdown().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "markdown is required");
        }
        var ctx = resolutionService.resolve(userId, agentId);
        WorkspaceManager wsm = ctx.manager();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(ctx.ownerId()).build();
        wsm.writeUtf8WorkspaceRelative(
                runtimeContext, "skills/" + name + "/SKILL.md", req.markdown());
        SkillFileService.writeResources(wsm, runtimeContext, name, req.resources());
        activity.record(
                ctx.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.EDIT_FILE,
                "skills/" + name,
                null);
        lifecycleService.invalidateUca(ctx.ownerId(), agentId);
        return SkillFileService.readWorkspaceSkill(wsm.getFilesystem(), runtimeContext, name);
    }

    @DeleteMapping("/workspace/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkspaceSkill(
            @PathVariable String agentId, @PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        SkillFileService.validateSkillName(name);
        var ctx = resolutionService.resolve(userId, agentId);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(ctx.ownerId()).build();
        if (!fs.exists(runtimeContext, "/skills/" + name)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Skill not found: " + name);
        }
        fs.delete(runtimeContext, "/skills/" + name);
        activity.record(
                ctx.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.DELETE_FILE,
                "skills/" + name,
                null);
        lifecycleService.invalidateUca(ctx.ownerId(), agentId);
    }

    // -----------------------------------------------------------------
    //  Repositories bound to the running agent (browse + per-repo install)
    // -----------------------------------------------------------------

    @GetMapping("/repositories")
    public List<RepositoryInfo> listRepositories(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        List<AgentSkillRepository> repos = skillInstallService.repositoriesFor(userId, agentId);
        List<RepositoryInfo> out = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            out.add(SkillInstallService.toRepositoryInfo(i, repos.get(i)));
        }
        return out;
    }

    @GetMapping("/repositories/{index}/skills")
    public List<MarketSkillInfo> listRepositorySkills(
            @PathVariable String agentId, @PathVariable int index, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        AgentSkillRepository repo = skillInstallService.repoAt(userId, agentId, index);
        List<AgentSkill> skills;
        try {
            skills = repo.getAllSkills();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Repository read failed: " + e.getMessage());
        }
        List<MarketSkillInfo> out = new ArrayList<>();
        if (skills != null) {
            for (AgentSkill s : skills) {
                if (s == null) continue;
                out.add(
                        new MarketSkillInfo(
                                s.getName(),
                                s.getDescription(),
                                s.getSource(),
                                s.getResources() != null
                                        ? s.getResources().size()
                                        : 0));
            }
        }
        out.sort(Comparator.comparing(MarketSkillInfo::name));
        return out;
    }

    @GetMapping("/repositories/{index}/skills/{name}")
    public MarketSkillDetail getRepositorySkill(
            @PathVariable String agentId,
            @PathVariable int index,
            @PathVariable String name,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        SkillFileService.validateSkillName(name);
        AgentSkillRepository repo = skillInstallService.repoAt(userId, agentId, index);
        AgentSkill skill;
        try {
            skill = repo.getSkill(name);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Repository read failed: " + e.getMessage());
        }
        if (skill == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Skill not found in repository: " + name);
        }
        return new MarketSkillDetail(
                skill.getName(),
                skill.getDescription(),
                skill.getSource(),
                skill.getSkillContent(),
                skill.getResources() != null
                        ? new LinkedHashMap<>(skill.getResources())
                        : Map.of());
    }

    @PostMapping("/workspace/install")
    public WorkspaceSkillInfo installFromRepository(
            @PathVariable String agentId, @RequestBody InstallRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        if (req == null || req.skillName() == null || req.skillName().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "skillName is required");
        }
        guard.require(userId, agentId, Tier.EDIT);
        SkillFileService.validateSkillName(req.skillName());
        var ctx = resolutionService.resolve(userId, agentId);
        return skillInstallService.installFromRepository(ctx, userId, agentId, req);
    }

    @PostMapping("/workspace/marketplace-install")
    public WorkspaceSkillInfo installFromMarketplace(
            @PathVariable String agentId,
            @RequestBody MarketplaceInstallRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        if (req == null
                || req.marketplaceId() == null
                || req.marketplaceId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "marketplaceId is required");
        }
        if (req.skillName() == null || req.skillName().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "skillName is required");
        }
        guard.require(userId, agentId, Tier.EDIT);
        SkillFileService.validateSkillName(req.skillName());
        var ctx = resolutionService.resolve(userId, agentId);
        return skillInstallService.installFromMarketplace(ctx, userId, agentId, req);
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    public record SkillSize(long totalBytes, int resourceCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillInfo(
            String dirName,
            String name,
            String description,
            long sizeBytes,
            int resourceCount,
            boolean hasReferences,
            boolean hasScripts,
            String origin,
            SkillMarketplaceMeta marketplace) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillMarketplaceMeta(
            String repoType, String repoLocation, String originalName, String installedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillDetail(
            String name, String description, String markdown, Map<String, String> resources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillUpsertRequest(String markdown, Map<String, String> resources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RepositoryInfo(
            int index, String type, String location, boolean writable, String source) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillInfo(
            String name, String description, String source, int resourceCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillDetail(
            String name,
            String description,
            String source,
            String content,
            Map<String, String> resources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstallRequest(
            int repoIndex, String skillName, String targetName, Boolean overwrite) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceInstallRequest(
            String marketplaceId, String skillName, String targetName, Boolean overwrite) {}
}
