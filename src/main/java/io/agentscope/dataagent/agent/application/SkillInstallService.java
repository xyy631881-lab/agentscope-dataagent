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
package io.agentscope.dataagent.agent.application;
import io.agentscope.dataagent.agent.api.AgentSkillsController;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.dataagent.agent.domain.ActivityEvent;
import io.agentscope.dataagent.capability.marketplace.domain.DataAgentMarketplace;
import io.agentscope.dataagent.capability.marketplace.domain.MarketSkillContent;
import io.agentscope.dataagent.capability.marketplace.application.UserMarketplaceRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles repository browsing and skill installation, extracted from
 * {@link AgentSkillsController}.
 *
 * <p>Each public method expects a pre-resolved
 * {@link WorkspaceResolutionService.ResolvedWorkspace} (for install operations);
 * security ({@code guard}) checks and workspace resolution remain the
 * controller's responsibility.
 *
 * <p>File I/O (writing SKILL.md, resources, install metadata) is delegated to
 * {@link SkillFileService}.
 *
 * @see AgentSkillsController
 * @see SkillFileService
 */
@Service
public class SkillInstallService {

    private final AgentCatalogService catalogService;
    private final UserMarketplaceRegistry marketplaceRegistry;
    private final AgentActivityStore activity;
    private final AgentLifecycleService lifecycleService;

    public SkillInstallService(
            AgentCatalogService catalogService,
            UserMarketplaceRegistry marketplaceRegistry,
            AgentActivityStore activity,
            AgentLifecycleService lifecycleService) {
        this.catalogService = catalogService;
        this.marketplaceRegistry = marketplaceRegistry;
        this.activity = activity;
        this.lifecycleService = lifecycleService;
    }

    // -----------------------------------------------------------------
    //  Repository browsing
    // -----------------------------------------------------------------

    /**
     * Returns the list of skill repositories bound to the running agent.
     *
     * @param userId  caller user id
     * @param agentId agent id from the URL path
     * @return non-null list (empty if the agent has no repositories)
     * @throws ResponseStatusException 404 if the agent is not running
     */
    public List<AgentSkillRepository> repositoriesFor(String userId, String agentId) {
        HarnessAgent agent = catalogService.getRunningAgent(userId, agentId);
        if (agent == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent not running: " + agentId);
        }
        List<AgentSkillRepository> repos = agent.getSkillRepositories();
        return repos != null ? repos : List.of();
    }

    /**
     * Returns the repository at the given index.
     *
     * @throws ResponseStatusException 404 if the index is out of range
     */
    public AgentSkillRepository repoAt(String userId, String agentId, int index) {
        List<AgentSkillRepository> repos = repositoriesFor(userId, agentId);
        if (index < 0 || index >= repos.size()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Repository index out of range: " + index);
        }
        return repos.get(index);
    }

    /**
     * Converts a repository to its DTO representation.
     */
    public static AgentSkillsController.RepositoryInfo toRepositoryInfo(
            int index, AgentSkillRepository repo) {
        AgentSkillRepositoryInfo info = repo.getRepositoryInfo();
        String type = info != null ? info.getType() : repo.getClass().getSimpleName();
        String location = info != null ? info.getLocation() : "";
        boolean writable = info != null ? info.isWritable() : repo.isWriteable();
        return new AgentSkillsController.RepositoryInfo(index, type, location, writable, repo.getSource());
    }

    // -----------------------------------------------------------------
    //  Skill installation
    // -----------------------------------------------------------------

    /**
     * Installs a skill from a bound repository into the agent's workspace.
     *
     * @param ctx     pre-resolved workspace context
     * @param userId  caller user id
     * @param agentId agent id from the URL path
     * @param req     install request (repoIndex, skillName, targetName, overwrite)
     * @return info about the newly installed workspace skill
     */
    public AgentSkillsController.WorkspaceSkillInfo installFromRepository(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String userId,
            String agentId,
            AgentSkillsController.InstallRequest req) {
        AgentSkillRepository repo = repoAt(userId, agentId, req.repoIndex());
        AgentSkill skill;
        try {
            skill = repo.getSkill(req.skillName());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Repository read failed: " + e.getMessage());
        }
        if (skill == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Skill not found in repository: " + req.skillName());
        }
        String targetName =
                (req.targetName() != null && !req.targetName().isBlank())
                        ? req.targetName()
                        : skill.getName();
        SkillFileService.validateSkillName(targetName);

        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(ctx.ownerId()).build();
        if (fs.exists(runtimeContext, "/skills/" + targetName)
                && !Boolean.TRUE.equals(req.overwrite())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Workspace skill already exists: " + targetName);
        }
        if (fs.exists(runtimeContext, "/skills/" + targetName)) {
            fs.delete(runtimeContext, "/skills/" + targetName);
        }
        String markdown = skill.getSkillContent();
        if (markdown == null || markdown.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Repository returned empty SKILL.md for: " + req.skillName());
        }
        WorkspaceManager wsm = ctx.manager();
        wsm.writeUtf8WorkspaceRelative(
                runtimeContext, "skills/" + targetName + "/SKILL.md", markdown);
        SkillFileService.writeResources(wsm, runtimeContext, targetName, skill.getResources());
        AgentSkillRepositoryInfo repoInfo = repo.getRepositoryInfo();
        AgentSkillsController.SkillMarketplaceMeta meta =
                new AgentSkillsController.SkillMarketplaceMeta(
                        repoInfo != null ? repoInfo.getType() : "unknown",
                        repoInfo != null ? repoInfo.getLocation() : "",
                        skill.getName(),
                        Instant.now().toString());
        SkillFileService.writeInstallMeta(wsm, runtimeContext, targetName, meta);
        activity.record(
                ctx.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.CREATE_FILE,
                "skills/" + targetName,
                Map.of(
                        "source", "repository",
                        "repoType", meta.repoType(),
                        "originalName", meta.originalName()));
        lifecycleService.invalidateUca(ctx.ownerId(), agentId);
        return SkillFileService.readWorkspaceSkill(fs, runtimeContext, targetName);
    }

    /**
     * Installs a skill from the caller's marketplace into the agent's workspace.
     *
     * <p>Uses 404 (not 403) for cross-user lookups so the existence of another user's
     * marketplace ids is not leaked.
     *
     * @param ctx     pre-resolved workspace context
     * @param userId  caller user id (the marketplace is scoped to this user)
     * @param agentId agent id from the URL path
     * @param req     install request (marketplaceId, skillName, targetName, overwrite)
     * @return info about the newly installed workspace skill
     */
    public AgentSkillsController.WorkspaceSkillInfo installFromMarketplace(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String userId,
            String agentId,
            AgentSkillsController.MarketplaceInstallRequest req) {
        DataAgentMarketplace mp =
                marketplaceRegistry
                        .find(userId, req.marketplaceId())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Marketplace not registered: "
                                                        + req.marketplaceId()));
        MarketSkillContent content;
        try {
            content = mp.fetch(req.skillName());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Marketplace fetch failed: " + e.getMessage());
        }
        if (content == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Skill not found in marketplace: " + req.skillName());
        }
        String targetName =
                (req.targetName() != null && !req.targetName().isBlank())
                        ? req.targetName()
                        : content.name();
        SkillFileService.validateSkillName(targetName);

        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(ctx.ownerId()).build();
        if (fs.exists(runtimeContext, "/skills/" + targetName)
                && !Boolean.TRUE.equals(req.overwrite())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Workspace skill already exists: " + targetName);
        }
        if (fs.exists(runtimeContext, "/skills/" + targetName)) {
            fs.delete(runtimeContext, "/skills/" + targetName);
        }
        if (content.markdown() == null || content.markdown().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Marketplace returned empty SKILL.md for: " + req.skillName());
        }
        WorkspaceManager wsm = ctx.manager();
        wsm.writeUtf8WorkspaceRelative(
                runtimeContext,
                "skills/" + targetName + "/SKILL.md",
                content.markdown());
        SkillFileService.writeResources(wsm, runtimeContext, targetName, content.resources());
        AgentSkillsController.SkillMarketplaceMeta meta =
                new AgentSkillsController.SkillMarketplaceMeta(
                        mp.type(),
                        mp.displayLocation(),
                        content.name(),
                        Instant.now().toString());
        SkillFileService.writeInstallMeta(wsm, runtimeContext, targetName, meta);
        activity.record(
                ctx.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.CREATE_FILE,
                "skills/" + targetName,
                Map.of(
                        "source", "marketplace",
                        "marketplaceId", req.marketplaceId(),
                        "marketplaceType", meta.repoType(),
                        "originalName", meta.originalName()));
        lifecycleService.invalidateUca(ctx.ownerId(), agentId);
        return SkillFileService.readWorkspaceSkill(fs, runtimeContext, targetName);
    }
}
