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
package io.agentscope.dataagent.agent.catalog;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder;
import io.agentscope.dataagent.agent.sharing.AgentAclService;
import io.agentscope.dataagent.agent.sharing.AgentShareGrant;
import io.agentscope.dataagent.web.template.TemplateRegistry;
import io.agentscope.dataagent.infrastructure.workspace.WorkspaceManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import io.agentscope.dataagent.agent.catalog.draft.AgentDraft;
import io.agentscope.dataagent.agent.catalog.draft.NamedFile;

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
    private final AgentLifecycleService lifecycleService;
    private final TemplateRegistry templateRegistry;
    private final WorkspaceManagerFactory workspaceManagerFactory;

    public AgentMutationService(
            DataAgentBootstrap builderBootstrap,
            UserAgentDefinitionStore store,
            AgentLifecycleService lifecycleService,
            TemplateRegistry templateRegistry,
            WorkspaceManagerFactory workspaceManagerFactory) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.lifecycleService = lifecycleService;
        this.templateRegistry = templateRegistry;
        this.workspaceManagerFactory = workspaceManagerFactory;
    }

    // -----------------------------------------------------------------
    //  Definition mutations
    // -----------------------------------------------------------------

    /** Creates a new user-custom agent definition for the given user. */
    public AgentDefinition createUserAgent(String userId, AgentCreateRequest req) {
        validateRequest(req);

        String id =
                sanitizeId(
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
        String workspacePath = normalizeWorkspacePathInput(req.workspacePath());
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
                writeDraftFiles(workspace, req.aiDraft(), entry);
            } else {
                WorkspaceScaffolder.scaffold(workspace, entry.name(), entry.sysPrompt());
            }
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

    /** Updates an existing user-custom agent definition. Only the owner may update. */
    public AgentDefinition updateUserAgent(String userId, String agentId, AgentCreateRequest req) {
        validateRequest(req);
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
    //  Clone/fork
    // -----------------------------------------------------------------

    /**
     * Materializes a clone of {@code (srcOwnerId, srcAgentId)} in {@code newOwnerId}'s namespace.
     * The clone copies settings (name/description/sysPrompt/tools/skills/identity) and
     * marks {@code forkOf = srcAgentId}. Shares, sessions, and channel bindings start empty.
     *
     * <p>Caller is responsible for invoking {@link
     * io.agentscope.dataagent.web.util.WorkspaceCopier#copy} to copy files; this method only writes
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
            id = sanitizeId(requestedId);
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
        String base = sanitizeId(preferredBase);
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
        validateShareGrantRequest(req);

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
                store.save(ownerId, withShares(existing, newShares));
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
                store.save(ownerId, withShares(existing, newShares));
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
    private static final String WORKSPACE_DIR_SUFFIX = "-workspace";

    private Path userWorkspacePath(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        return workspaceManagerFactory.resolveAgentDataPath(entry.workspacePath(), entry.id());
    }

    /**
     * Trims user-supplied workspace path input. Returns {@code null} for blank input (let the
     * resolver fall back to the agent id at runtime). Absolute paths are passed through unchanged.
     * Relative paths are rejected if they contain {@code ..} traversal segments. If the final
     * path segment does not already end with {@code -workspace}, the suffix is appended so all
     * agent workspaces share a consistent on-disk naming convention.
     */
    private static String normalizeWorkspacePathInput(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Path p = Paths.get(trimmed);
        if (!p.isAbsolute()) {
            for (Path seg : p) {
                if ("..".equals(seg.toString())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Relative workspace path must not contain '..' segments");
                }
            }
        }
        Path fileName = p.getFileName();
        if (fileName == null) {
            return trimmed;
        }
        String leaf = fileName.toString();
        if (leaf.endsWith(WORKSPACE_DIR_SUFFIX)) {
            return trimmed;
        }
        String suffixed = leaf + WORKSPACE_DIR_SUFFIX;
        Path parent = p.getParent();
        Path rebuilt = parent != null ? parent.resolve(suffixed) : Paths.get(suffixed);
        return rebuilt.toString();
    }

    /**
     * Materializes an AI-suggested agent into the workspace folder: {@code AGENTS.md} from
     * {@code (name, description, sysPrompt)}, {@code tools.json} from {@code suggestedTools},
     * one skill file per {@code suggestedSkills} entry, one subagent file per
     * {@code suggestedSubagents} entry, and a {@code memory/.gitkeep}.
     */
    private static void writeDraftFiles(
            Path workspace, AgentDraft draft, UserAgentDefinitionStore.StoredEntry entry)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        String displayName =
                draft.name() != null && !draft.name().isBlank()
                        ? draft.name()
                        : (entry.name() != null ? entry.name() : entry.id());
        String description =
                draft.description() != null && !draft.description().isBlank()
                        ? draft.description()
                        : (entry.description() != null ? entry.description() : "");
        String sysPrompt =
                draft.sysPrompt() != null && !draft.sysPrompt().isBlank()
                        ? draft.sysPrompt()
                        : (entry.sysPrompt() != null
                                ? entry.sysPrompt()
                                : "You are a helpful assistant.");

        StringBuilder agentsMd = new StringBuilder();
        agentsMd.append("# ").append(displayName).append("\n\n");
        if (!description.isEmpty()) {
            agentsMd.append("> ").append(description.trim()).append("\n\n");
        }
        agentsMd.append(sysPrompt.trim()).append("\n");
        writeIfMissing(workspace.resolve("AGENTS.md"), agentsMd.toString());

        // tools.json
        if (draft.suggestedTools() != null && !draft.suggestedTools().isEmpty()) {
            StringBuilder tools = new StringBuilder();
            tools.append("{\n  \"allow\": [\n");
            for (int i = 0; i < draft.suggestedTools().size(); i++) {
                String t = draft.suggestedTools().get(i);
                if (t == null) continue;
                tools.append("    \"").append(escapeJson(t)).append("\"");
                if (i < draft.suggestedTools().size() - 1) tools.append(",");
                tools.append("\n");
            }
            tools.append("  ],\n  \"deny\": []\n}\n");
            writeIfMissing(workspace.resolve("tools.json"), tools.toString());
        }

        // Skills
        if (draft.suggestedSkills() != null) {
            for (NamedFile sk : draft.suggestedSkills()) {
                if (sk == null || sk.name() == null || sk.name().isBlank()) continue;
                Path skillDir = workspace.resolve("skills").resolve(sanitizeName(sk.name()));
                Files.createDirectories(skillDir);
                writeIfMissing(
                        skillDir.resolve("SKILL.md"), sk.content() != null ? sk.content() : "");
            }
        }

        // Subagents
        if (draft.suggestedSubagents() != null) {
            for (NamedFile sa : draft.suggestedSubagents()) {
                if (sa == null || sa.name() == null || sa.name().isBlank()) continue;
                Path file = workspace.resolve("subagents").resolve(sanitizeName(sa.name()) + ".md");
                writeIfMissing(file, sa.content() != null ? sa.content() : "");
            }
        }

        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    private static String sanitizeName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Rebuilds a StoredEntry with the given shares list.
     * Empty lists are converted to null to match the new-agent convention.
     */
    private static UserAgentDefinitionStore.StoredEntry withShares(
            UserAgentDefinitionStore.StoredEntry e, List<AgentShareGrant> newShares) {
        return new UserAgentDefinitionStore.StoredEntry(
                e.id(),
                e.name(),
                e.description(),
                e.sysPrompt(),
                e.model(),
                e.maxIters(),
                e.toolsAllow(),
                e.toolsDeny(),
                e.identityName(),
                e.identityEmoji(),
                e.groupChatMentionPatterns(),
                e.groupChatRequireMention(),
                e.skillsAllow(),
                e.skillsDeny(),
                e.createdAt(),
                e.updatedAt(),
                newShares == null || newShares.isEmpty() ? null : newShares,
                e.runAs(),
                e.forkOf(),
                e.workspacePath(),
                e.skillRepositories(),
                e.sandboxMode(),
                e.sandboxScope());
    }

    private static void validateShareGrantRequest(ShareGrantRequest req) {
        if (req == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Share grant request body is required");
        }
        if (!AgentShareGrant.GRANTEE_USER.equals(req.granteeType())
                && !AgentShareGrant.GRANTEE_WORKSPACE.equals(req.granteeType())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid granteeType: " + req.granteeType());
        }
        if (AgentShareGrant.GRANTEE_USER.equals(req.granteeType())
                && (req.granteeId() == null || req.granteeId().isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "granteeId is required for USER grant");
        }
        if (req.tier() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier is required");
        }
        try {
            AgentAclService.Tier.valueOf(req.tier().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid tier: " + req.tier());
        }
    }

    private static void validateRequest(AgentCreateRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'name' is required");
        }
    }

    private static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    /**
     * Returns true if the agent id corresponds to a global (bootstrap-registered) agent.
     * This is a private copy of {@link AgentCatalogService#isGlobal(String)} so that mutation
     * operations do not depend back on the catalog service.
     */
    private boolean isGlobal(String agentId) {
        return builderBootstrap.agents().containsKey(agentId);
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
