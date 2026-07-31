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
import io.agentscope.dataagent.agent.application.command.AgentCreateRequest;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.dataagent.agent.domain.AgentShareGrant;
import io.agentscope.dataagent.agent.domain.GlobalAgentOverrideStore;
import io.agentscope.dataagent.agent.domain.UserAgentDefinitionStore;
import io.agentscope.dataagent.common.WorkspaceCopier;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.workspace.application.WorkspaceScaffolder;
import io.agentscope.dataagent.capability.template.application.TemplateRegistry;
import io.agentscope.dataagent.workspace.infrastructure.WorkspaceManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import io.agentscope.dataagent.agent.application.command.AgentDraft;
import io.agentscope.dataagent.agent.application.command.NamedFile;

/**
 * Mutation operations for the agent catalog: create, update, delete, clone, and share-grant
 * management for user-custom agents. Query and visibility logic (listVisible, findVisible,
 * findOwnerOf, findStoredEntry) lives in {@link AgentCatalogService}.
 *
 * <p>This service owns the write-path side of the catalog: it validates requests, allocates
 * ids, persists entries to {@link UserAgentDefinitionStore}, scaffolds workspaces, and
 * invalidates cached UCA registrations via {@link AgentLifecycleService} when definitions
 * change.
 *
 * @see AgentCatalogService for query/visibility logic
 */
@Service
public class AgentMutationService {

    private static final Logger log = LoggerFactory.getLogger(AgentMutationService.class);

    private final DataAgentBootstrap builderBootstrap;
    private final UserAgentDefinitionStore store;
    private final GlobalAgentOverrideStore overrideStore;
    private final AgentLifecycleService lifecycleService;
    private final TemplateRegistry templateRegistry;
    private final WorkspaceManagerFactory workspaceManagerFactory;

    public AgentMutationService(
            DataAgentBootstrap builderBootstrap,
            UserAgentDefinitionStore store,
            GlobalAgentOverrideStore overrideStore,
            AgentLifecycleService lifecycleService,
            TemplateRegistry templateRegistry,
            WorkspaceManagerFactory workspaceManagerFactory) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.overrideStore = overrideStore;
        this.lifecycleService = lifecycleService;
        this.templateRegistry = templateRegistry;
        this.workspaceManagerFactory = workspaceManagerFactory;
    }

    // -----------------------------------------------------------------
    //  Definition mutations
    // -----------------------------------------------------------------

    /** Creates a new user-custom agent definition for the given user. */
    public AgentDefinition createUserAgent(String userId, AgentCreateRequest req) {
        AgentMutationSupport.validateRequest(req);

        String id =
                AgentMutationSupport.sanitizeId(
                        req.id() != null && !req.id().isBlank()
                                ? req.id()
                                : UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        if (store.findById(userId, id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent with id '" + id + "' already exists");
        }
        if (isGlobal(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent id '" + id + "' conflicts with a global agent");
        }

        long now = System.currentTimeMillis();
        String workspacePath = AgentMutationSupport.normalizeWorkspacePathInput(req.workspacePath());
        if (workspacePath == null) {
            workspacePath = id + WORKSPACE_DIR_SUFFIX;
        }
        UserAgentDefinitionStore.StoredEntry entry =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        req.name() != null ? req.name() : id,
                        req.description(),
                        req.sysPrompt(),
                        req.model(),
                        req.maxIters(),
                        req.toolsAllow(),
                        req.toolsDeny(),
                        req.identityName(),
                        req.identityEmoji(),
                        req.groupChatMentionPatterns(),
                        req.groupChatRequireMention(),
                        req.skillsAllow(),
                        req.skillsDeny(),
                        now,
                        now,
                        null, // shares — new agents start unshared
                        AgentDefinition.RUN_AS_INVOKER,
                        null,
                        workspacePath,
                        req.skillRepositories(),
                        req.sandboxMode(),
                        req.sandboxScope());
        store.save(userId, entry);
        log.info("User '{}' created custom agent '{}'", userId, id);

        // Workspace scaffolding. Template wins over AI draft if both are supplied; otherwise fall
        // back to the WorkspaceScaffolder default. Failures are logged but do not roll back the
        // save — the workspace is regenerable from the catalog at any time.
        Path workspace = userWorkspacePath(userId, entry);
        try {
            if (req.templateId() != null && !req.templateId().isBlank()) {
                boolean ok = templateRegistry.instantiate(req.templateId(), workspace);
                if (!ok) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Unknown templateId: " + req.templateId());
                }
            } else if (req.aiDraft() != null) {
                AgentMutationSupport.writeDraftFiles(workspace, req.aiDraft(), entry);
            } else {
                WorkspaceScaffolder.scaffold(workspace, entry.name(), entry.sysPrompt());
            }
            copyPublicDataSkills(workspace);
            markPublicDataSkillsSeeded(workspace);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            log.warn(
                    "Failed to scaffold workspace for user-custom agent '{}/{}' at {}: {}",
                    userId,
                    id,
                    workspace,
                    e.getMessage());
        }

        return entry.toDefinition(userId);
    }

    /**
     * Brings an existing private Agent forward to the public data-skill baseline without replacing
     * any file the owner has already authored. This is used for Agents created before the baseline
     * copy was introduced.
     */
    public void ensurePublicDataSkills(String userId, String agentId) {
        UserAgentDefinitionStore.StoredEntry entry = store.findById(userId, agentId).orElse(null);
        if (entry == null) {
            return;
        }
        try {
            Path workspace = userWorkspacePath(userId, entry);
            if (Files.isRegularFile(publicSkillsSeedMarker(workspace))) {
                return;
            }
            if (!Files.isRegularFile(workspace.resolve("AGENTS.md"))) {
                WorkspaceScaffolder.scaffold(workspace, entry.name(), entry.sysPrompt());
            }
            // Existing private agents that already contain skills predate the marker but are
            // initialized. Do not copy missing baseline skills back after an explicit uninstall.
            if (!hasAnySkillDirectory(workspace)) {
                copyPublicDataSkills(workspace);
            }
            markPublicDataSkillsSeeded(workspace);
        } catch (IOException e) {
            log.warn(
                    "Failed to seed public data skills for user-custom agent '{}/{}': {}",
                    userId,
                    agentId,
                    e.getMessage());
        }
    }

    /** Updates an existing user-custom agent definition. Only the owner may update. */
    public AgentDefinition updateUserAgent(String userId, String agentId, AgentCreateRequest req) {
        AgentMutationSupport.validateRequest(req);
        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry updated =
                new UserAgentDefinitionStore.StoredEntry(
                        agentId,
                        req.name() != null ? req.name() : existing.name(),
                        req.description() != null ? req.description() : existing.description(),
                        req.sysPrompt() != null ? req.sysPrompt() : existing.sysPrompt(),
                        req.model() != null ? req.model() : existing.model(),
                        req.maxIters() != null ? req.maxIters() : existing.maxIters(),
                        req.toolsAllow() != null ? req.toolsAllow() : existing.toolsAllow(),
                        req.toolsDeny() != null ? req.toolsDeny() : existing.toolsDeny(),
                        req.identityName() != null ? req.identityName() : existing.identityName(),
                        req.identityEmoji() != null
                                ? req.identityEmoji()
                                : existing.identityEmoji(),
                        req.groupChatMentionPatterns() != null
                                ? req.groupChatMentionPatterns()
                                : existing.groupChatMentionPatterns(),
                        req.groupChatRequireMention() != null
                                ? req.groupChatRequireMention()
                                : existing.groupChatRequireMention(),
                        req.skillsAllow() != null ? req.skillsAllow() : existing.skillsAllow(),
                        req.skillsDeny() != null ? req.skillsDeny() : existing.skillsDeny(),
                        existing.createdAt(),
                        now,
                        existing.shares(), // sharing is managed via the share API, not settings
                        existing.runAs() != null
                                ? existing.runAs()
                                : AgentDefinition.RUN_AS_INVOKER,
                        existing.forkOf(),
                        existing.workspacePath(), // workspacePath is creation-only
                        req.skillRepositories() != null
                                ? req.skillRepositories()
                                : existing.skillRepositories(),
                        req.sandboxMode() != null ? req.sandboxMode() : existing.sandboxMode(),
                        req.sandboxScope() != null ? req.sandboxScope() : existing.sandboxScope());
        store.save(userId, updated);

        // Invalidate the cached UCA so the next access rebuilds from the updated definition.
        lifecycleService.invalidateUca(userId, agentId);

        log.info("User '{}' updated custom agent '{}'", userId, agentId);
        return updated.toDefinition(userId);
    }

    /** Deletes a user-custom agent definition. Only the owner may delete. */
    public void deleteUserAgent(String userId, String agentId) {
        if (!store.delete(userId, agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        // Invalidate the cached UCA registration.
        lifecycleService.invalidateUca(userId, agentId);
        log.info("User '{}' deleted custom agent '{}'", userId, agentId);
    }

    // -----------------------------------------------------------------
    //  Global-agent overrides (admin-editable bootstrap agents)
    // -----------------------------------------------------------------

    /**
     * Persists an administrator's edit to a global (bootstrap-registered) agent. The change is
     * stored as an override delta and immediately hot-applied to the running agent instance via
     * {@link io.agentscope.dataagent.runtime.DataAgentBootstrap#rebuildGlobalAgent} (no restart
     * needed); the catalog read-path also reflects it instantly. Returns the merged definition.
     *
     * @param adminId the administrator performing the edit (used only for the activity record)
     * @param agentId the global agent id (must exist in the bootstrap registry)
     */
    public AgentDefinition updateGlobalAgent(
            String adminId, String agentId, AgentCreateRequest req) {
        AgentMutationSupport.validateRequest(req);
        if (!isGlobal(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Not a global agent: " + agentId);
        }

        GlobalAgentOverrideStore.GlobalOverride existing =
                overrideStore.findById(agentId).orElse(null);
        long now = System.currentTimeMillis();
        long createdAt = existing != null ? existing.createdAt() : now;

        GlobalAgentOverrideStore.GlobalOverride updated =
                new GlobalAgentOverrideStore.GlobalOverride(
                        agentId,
                        req.name() != null
                                ? req.name()
                                : (existing != null && existing.name() != null
                                        ? existing.name()
                                        : agentId),
                        req.description() != null ? req.description() : existingOrNull(existing, o -> o.description()),
                        req.sysPrompt() != null ? req.sysPrompt() : existingOrNull(existing, o -> o.sysPrompt()),
                        req.model() != null ? req.model() : existingOrNull(existing, o -> o.model()),
                        req.maxIters() != null ? req.maxIters() : existingOrNull(existing, o -> o.maxIters()),
                        req.toolsAllow() != null ? req.toolsAllow() : existingOrNull(existing, o -> o.toolsAllow()),
                        req.toolsDeny() != null ? req.toolsDeny() : existingOrNull(existing, o -> o.toolsDeny()),
                        req.identityName() != null ? req.identityName() : existingOrNull(existing, o -> o.identityName()),
                        req.identityEmoji() != null ? req.identityEmoji() : existingOrNull(existing, o -> o.identityEmoji()),
                        req.groupChatMentionPatterns() != null
                                ? req.groupChatMentionPatterns()
                                : existingOrNull(existing, o -> o.groupChatMentionPatterns()),
                        req.groupChatRequireMention() != null
                                ? req.groupChatRequireMention()
                                : existingOrNull(existing, o -> o.groupChatRequireMention()),
                        req.skillsAllow() != null ? req.skillsAllow() : existingOrNull(existing, o -> o.skillsAllow()),
                        req.skillsDeny() != null ? req.skillsDeny() : existingOrNull(existing, o -> o.skillsDeny()),
                        createdAt,
                        now,
                        existing != null ? existing.shares() : null,
                        existing != null && existing.runAs() != null
                                ? existing.runAs()
                                : AgentDefinition.RUN_AS_INVOKER,
                        req.sandboxMode() != null ? req.sandboxMode() : existingOrNull(existing, o -> o.sandboxMode()),
                        req.sandboxScope() != null ? req.sandboxScope() : existingOrNull(existing, o -> o.sandboxScope()));
        GlobalAgentOverrideStore.GlobalOverride saved = overrideStore.save(updated);
        // 让运行中的 Agent 立即热生效（无需重启）：重建并原子替换网关中的实例。
        builderBootstrap.rebuildGlobalAgent(agentId);
        log.info("Admin '{}' updated global agent override '{}'", adminId, agentId);
        return saved.toDefinition();
    }

    /**
     * Appends or updates a share grant on a global agent's override. Globals have no owner, so any
     * administrator (EDIT tier) may manage their shares; the grant's {@code createdBy} is the
     * acting admin.
     */
    public AgentDefinition grantShareGlobal(String agentId, ShareGrantRequest req) {
        AgentMutationSupport.validateShareGrantRequest(req);
        if (!isGlobal(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Not a global agent: " + agentId);
        }

        GlobalAgentOverrideStore.GlobalOverride existing =
                overrideStore.findById(agentId).orElse(null);
        long now = System.currentTimeMillis();
        long createdAt = existing != null ? existing.createdAt() : now;

        String granteeId =
                AgentShareGrant.GRANTEE_WORKSPACE.equals(req.granteeType())
                        ? AgentShareGrant.WORKSPACE_ID
                        : req.granteeId();

        List<AgentShareGrant> oldShares =
                existing != null && existing.shares() != null
                        ? existing.shares()
                        : List.of();
        List<AgentShareGrant> newShares = new ArrayList<>(oldShares.size() + 1);
        boolean updated = false;
        for (AgentShareGrant g : oldShares) {
            if (g.granteeType().equals(req.granteeType()) && g.granteeId().equals(granteeId)) {
                newShares.add(
                        new AgentShareGrant(
                                g.granteeType(),
                                g.granteeId(),
                                req.tier().trim().toUpperCase(),
                                now,
                                g.createdBy()));
                updated = true;
            } else {
                newShares.add(g);
            }
        }
        if (!updated) {
            newShares.add(
                    new AgentShareGrant(
                            req.granteeType(),
                            granteeId,
                            req.tier().trim().toUpperCase(),
                            now,
                            "admin"));
        }

        GlobalAgentOverrideStore.GlobalOverride saved =
                overrideStore.save(applyShares(agentId, existing, createdAt, newShares));
        log.info(
                "Granted {} on global agent {} to {}/{}",
                req.tier(),
                agentId,
                req.granteeType(),
                granteeId);
        return saved.toDefinition();
    }

    /**
     * Revokes a share grant from a global agent's override. Exact match on (granteeType,
     * granteeId).
     */
    public AgentDefinition revokeShareGlobal(
            String agentId, String granteeType, String granteeId) {
        if (!AgentShareGrant.GRANTEE_USER.equals(granteeType)
                && !AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid granteeType: " + granteeType);
        }
        String normalizedId =
                AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)
                        ? AgentShareGrant.WORKSPACE_ID
                        : granteeId;
        if (!isGlobal(agentId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Not a global agent: " + agentId);
        }

        GlobalAgentOverrideStore.GlobalOverride existing =
                overrideStore
                        .findById(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Global agent has no override: " + agentId));

        List<AgentShareGrant> oldShares = existing.shares();
        if (oldShares == null || oldShares.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No shares to revoke on global agent " + agentId);
        }
        List<AgentShareGrant> newShares = new ArrayList<>(oldShares.size());
        boolean removed = false;
        for (AgentShareGrant g : oldShares) {
            if (g.granteeType().equals(granteeType) && g.granteeId().equals(normalizedId)) {
                removed = true;
                continue;
            }
            newShares.add(g);
        }
        if (!removed) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Share grant not found: " + granteeType + "/" + normalizedId);
        }

        GlobalAgentOverrideStore.GlobalOverride saved =
                overrideStore.save(applyShares(agentId, existing, existing.createdAt(), newShares));
        log.info(
                "Revoked share on global agent {} from {}/{}",
                agentId,
                granteeType,
                normalizedId);
        return saved.toDefinition();
    }

    // -----------------------------------------------------------------
    //  Clone/fork
    // -----------------------------------------------------------------

    /**
     * Materializes a clone of {@code (srcOwnerId, srcAgentId)} in {@code newOwnerId}'s namespace.
     * The clone copies settings (name/description/sysPrompt/tools/skills/identity) and
     * marks {@code forkOf = srcAgentId}. Shares, sessions, and channel bindings start empty.
     *
     * <p>Caller is responsible for invoking {@link
     * io.agentscope.dataagent.common.WorkspaceCopier#copy} to copy files; this method only writes
     * the catalog entry.
     *
     * @param requestedId optional preferred id; if blank or already taken in newOwner's namespace,
     *     a short random id is generated.
     * @param requestedName optional preferred display name; defaults to "{src.name} (copy)".
     */
    public StoredEntryAndDefinition prepareClone(
            String srcOwnerId,
            String srcAgentId,
            String newOwnerId,
            String requestedId,
            String requestedName) {
        UserAgentDefinitionStore.StoredEntry src =
                store.findById(srcOwnerId, srcAgentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Source agent not found: " + srcAgentId));

        String id;
        if (requestedId != null && !requestedId.isBlank()) {
            id = AgentMutationSupport.sanitizeId(requestedId);
            if (store.findById(newOwnerId, id).isPresent() || isGlobal(id)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Agent id '" + id + "' already taken");
            }
        } else {
            id = uniqueIdInNamespace(newOwnerId, srcAgentId);
        }

        String name =
                requestedName != null && !requestedName.isBlank()
                        ? requestedName
                        : (src.name() != null ? src.name() + " (copy)" : id);

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry clone =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        name,
                        src.description(),
                        src.sysPrompt(),
                        src.model(),
                        src.maxIters(),
                        src.toolsAllow(),
                        src.toolsDeny(),
                        src.identityName(),
                        src.identityEmoji(),
                        src.groupChatMentionPatterns(),
                        src.groupChatRequireMention(),
                        src.skillsAllow(),
                        src.skillsDeny(),
                        now,
                        now,
                        null, // shares — clones start unshared
                        src.runAs(),
                        srcAgentId, // forkOf
                        id + WORKSPACE_DIR_SUFFIX, // workspacePath — clone uses its own id +
                        // suffix
                        src.skillRepositories(),
                        src.sandboxMode(),
                        src.sandboxScope());
        store.save(newOwnerId, clone);
        log.info(
                "User '{}' cloned agent '{}/{}' as '{}/{}'",
                newOwnerId,
                srcOwnerId,
                srcAgentId,
                newOwnerId,
                id);
        return new StoredEntryAndDefinition(clone, clone.toDefinition(newOwnerId));
    }

    private String uniqueIdInNamespace(String owner, String preferredBase) {
        String base = AgentMutationSupport.sanitizeId(preferredBase);
        if (!store.findById(owner, base).isPresent() && !isGlobal(base)) {
            return base + "-copy";
        }
        for (int i = 0; i < 16; i++) {
            String candidate =
                    base + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            if (!store.findById(owner, candidate).isPresent() && !isGlobal(candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Could not allocate a unique agent id for clone");
    }

    // -----------------------------------------------------------------
    //  Share grants
    // -----------------------------------------------------------------

    /**
     * Appends (or updates) a share grant on a user-custom agent.
     * If the (granteeType, granteeId) pair already exists, the tier is updated;
     * otherwise a new grant entry is added.
     *
     * @param ownerId  Agent owner (storage namespace), resolved by the controller
     * @param agentId  Target agent ID
     * @param req      Share grant request body (granteeType / granteeId / tier)
     * @return The updated AgentDefinition with the new shares list
     */
    public AgentDefinition grantShare(String ownerId, String agentId, ShareGrantRequest req) {
        AgentMutationSupport.validateShareGrantRequest(req);

        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        String granteeId =
                AgentShareGrant.GRANTEE_WORKSPACE.equals(req.granteeType())
                        ? AgentShareGrant.WORKSPACE_ID
                        : req.granteeId();

        long now = System.currentTimeMillis();
        List<AgentShareGrant> oldShares =
                existing.shares() != null ? existing.shares() : List.of();
        List<AgentShareGrant> newShares = new ArrayList<>(oldShares.size() + 1);
        boolean updated = false;
        for (AgentShareGrant g : oldShares) {
            if (g.granteeType().equals(req.granteeType())
                    && g.granteeId().equals(granteeId)) {
                newShares.add(
                        new AgentShareGrant(
                                g.granteeType(),
                                g.granteeId(),
                                req.tier().trim().toUpperCase(),
                                now,
                                g.createdBy()));
                updated = true;
            } else {
                newShares.add(g);
            }
        }
        if (!updated) {
            newShares.add(
                    new AgentShareGrant(
                            req.granteeType(),
                            granteeId,
                            req.tier().trim().toUpperCase(),
                            now,
                            ownerId));
        }

        UserAgentDefinitionStore.StoredEntry saved =
                store.save(ownerId, AgentMutationSupport.withShares(existing, newShares));
        log.info(
                "Granted {} on agent {}/{} to {}/{}",
                req.tier(),
                ownerId,
                agentId,
                req.granteeType(),
                granteeId);
        return saved.toDefinition(ownerId);
    }

    /**
     * Revokes a share grant. Exact match on (granteeType, granteeId).
     *
     * @param ownerId      Agent owner
     * @param agentId      Target agent ID
     * @param granteeType  USER or WORKSPACE
     * @param granteeId    For USER: the userId; for WORKSPACE: normalized to "*"
     * @return The updated AgentDefinition
     */
    public AgentDefinition revokeShare(
            String ownerId, String agentId, String granteeType, String granteeId) {
        if (!AgentShareGrant.GRANTEE_USER.equals(granteeType)
                && !AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid granteeType: " + granteeType);
        }
        String normalizedId =
                AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)
                        ? AgentShareGrant.WORKSPACE_ID
                        : granteeId;

        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        List<AgentShareGrant> oldShares = existing.shares();
        if (oldShares == null || oldShares.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No shares to revoke on agent " + agentId);
        }
        List<AgentShareGrant> newShares = new ArrayList<>(oldShares.size());
        boolean removed = false;
        for (AgentShareGrant g : oldShares) {
            if (g.granteeType().equals(granteeType)
                    && g.granteeId().equals(normalizedId)) {
                removed = true;
                continue;
            }
            newShares.add(g);
        }
        if (!removed) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Share grant not found: " + granteeType + "/" + normalizedId);
        }

        UserAgentDefinitionStore.StoredEntry saved =
                store.save(ownerId, AgentMutationSupport.withShares(existing, newShares));
        log.info(
                "Revoked share on agent {}/{} from {}/{}",
                ownerId,
                agentId,
                granteeType,
                normalizedId);
        return saved.toDefinition(ownerId);
    }

    // -----------------------------------------------------------------
    //  Static helpers
    // -----------------------------------------------------------------

    /** Suffix automatically appended to the final segment of user-supplied workspace paths. */
    public static final String WORKSPACE_DIR_SUFFIX = "-workspace";
    private static final String PUBLIC_SKILLS_SEED_MARKER = ".dataagent/public-skills-v1";

    private Path userWorkspacePath(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        return workspaceManagerFactory.userWorkspacePath(userId, entry.id());
    }

    private static Path publicSkillsSeedMarker(Path workspace) {
        return workspace.resolve(PUBLIC_SKILLS_SEED_MARKER).normalize();
    }

    private static boolean hasAnySkillDirectory(Path workspace) throws IOException {
        Path skills = workspace.resolve("skills").normalize();
        if (!Files.isDirectory(skills)) {
            return false;
        }
        try (var entries = Files.list(skills)) {
            return entries.anyMatch(Files::isDirectory);
        }
    }

    private static void markPublicDataSkillsSeeded(Path workspace) throws IOException {
        Path marker = publicSkillsSeedMarker(workspace);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "seeded\n", StandardCharsets.UTF_8);
    }

    /**
     * A private Agent starts with the team's data-analysis playbooks, then owns its copied files.
     * Existing files are deliberately never overwritten, so later administrator edits do not
     * erase a user's customisation.
     */
    private static void copyPublicDataSkills(Path workspace) throws IOException {
        Path source = Paths.get("shared", "agents", "data-agent", "skills")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(source)) {
            return;
        }
        Path targetRoot = workspace.resolve("skills").normalize();
        try (var sourceFiles = Files.walk(source)) {
            for (Path sourceFile : sourceFiles.toList()) {
                Path target = targetRoot.resolve(source.relativize(sourceFile)).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IOException("Public skill path escapes private workspace");
                }
                if (Files.isDirectory(sourceFile)) {
                    Files.createDirectories(target);
                } else if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(sourceFile, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * Trims user-supplied workspace path input. Returns {@code null} for blank input (let the
     * resolver fall back to the agent id at runtime). Absolute paths are passed through unchanged.
     * Relative paths are rejected if they contain {@code ..} traversal segments. If the final
     * path segment does not already end with {@code -workspace}, the suffix is appended so all
     * agent workspaces share a consistent on-disk naming convention.
     */

    /**
     * Materializes an AI-suggested agent into the workspace folder: {@code AGENTS.md} from
     * {@code (name, description, sysPrompt)}, {@code tools.json} from {@code suggestedTools},
     * one skill file per {@code suggestedSkills} entry, one subagent file per
     * {@code suggestedSubagents} entry, and a {@code memory/.gitkeep}.
     */


    /**
     * Rebuilds a StoredEntry with the given shares list.
     * Empty lists are converted to null to match the new-agent convention.
     */


    /**
     * Returns true if the agent id corresponds to a global (bootstrap-registered) agent.
     * This is a private copy of {@link AgentCatalogService#isGlobal(String)} so that mutation
     * operations do not depend back on the catalog service.
     */
    private boolean isGlobal(String agentId) {
        return builderBootstrap.agents().containsKey(agentId);
    }

    /**
     * Returns {@code fn.apply(existing)} when an override already exists, otherwise {@code null}.
     * Used so that a null field in an update request keeps the previously-stored override value.
     */
    private static <T> T existingOrNull(
            GlobalAgentOverrideStore.GlobalOverride existing,
            Function<GlobalAgentOverrideStore.GlobalOverride, T> fn) {
        return existing != null ? fn.apply(existing) : null;
    }

    /**
     * Rebuilds a global-agent override preserving every metadata field from {@code existing} (or
     * {@code null} when no override exists yet) but with the supplied share list and timestamps.
     */
    private static GlobalAgentOverrideStore.GlobalOverride applyShares(
            String agentId,
            GlobalAgentOverrideStore.GlobalOverride existing,
            long createdAt,
            List<AgentShareGrant> newShares) {
        long now = System.currentTimeMillis();
        return new GlobalAgentOverrideStore.GlobalOverride(
                agentId,
                existing != null ? existing.name() : null,
                existing != null ? existing.description() : null,
                existing != null ? existing.sysPrompt() : null,
                existing != null ? existing.model() : null,
                existing != null ? existing.maxIters() : null,
                existing != null ? existing.toolsAllow() : null,
                existing != null ? existing.toolsDeny() : null,
                existing != null ? existing.identityName() : null,
                existing != null ? existing.identityEmoji() : null,
                existing != null ? existing.groupChatMentionPatterns() : null,
                existing != null ? existing.groupChatRequireMention() : null,
                existing != null ? existing.skillsAllow() : null,
                existing != null ? existing.skillsDeny() : null,
                createdAt,
                now,
                newShares.isEmpty() ? null : newShares,
                existing != null ? existing.runAs() : AgentDefinition.RUN_AS_INVOKER,
                existing != null ? existing.sandboxMode() : null,
                existing != null ? existing.sandboxScope() : null);
    }

    // -----------------------------------------------------------------
    //  Records
    // -----------------------------------------------------------------

    /** Holder for the freshly-cloned entry + its API view. */
    public record StoredEntryAndDefinition(
            UserAgentDefinitionStore.StoredEntry entry, AgentDefinition definition) {}

    /** Request body for granting a share on a user-custom agent. */
    public record ShareGrantRequest(String granteeType, String granteeId, String tier) {}
}
