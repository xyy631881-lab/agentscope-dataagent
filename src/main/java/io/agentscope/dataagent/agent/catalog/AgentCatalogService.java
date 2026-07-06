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
package io.agentscope.dataagent.web.catalog;

import io.agentscope.core.model.Model;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentConfigEntry;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.auth.UserStore.UserRecord;
import io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder;
import io.agentscope.dataagent.web.share.AgentAclService;
import io.agentscope.dataagent.web.share.AgentShareGrant;
import io.agentscope.dataagent.web.template.TemplateRegistry;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Business logic for the agent catalog: merges global agent definitions (loaded from
 * {@code agentscope.json}) with per-user custom agent definitions, and dynamically instantiates
 * user-custom agents on demand.
 *
 * <h2>Visibility rules</h2>
 *
 * <ul>
 *   <li><b>Global agents</b> ({@code scope = "global"}): defined in {@code agentscope.json},
 *       registered in {@link HarnessGateway} at startup. All users can list and converse with
 *       them; each user's conversation is isolated via a separate session keyed by
 *       {@code (userId, agentId)}.
 *   <li><b>User-custom agents</b> ({@code scope = "user"}): stored per-user in
 *       {@code .agentscope/users/{userId}/agents.json}. Only the owning user can see,
 *       create, update, or delete them. On first use they are dynamically built and registered
 *       in the gateway under the namespace key {@code uca-{userId}-{agentId}}.
 * </ul>
 */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);

    /** Prefix for user-custom agent IDs when registered in the gateway. */
    public static final String UCA_PREFIX = "uca-";

    private final DataAgentBootstrap builderBootstrap;
    private final UserAgentDefinitionStore store;
    private final Model model;
    private final io.agentscope.dataagent.web.toolbus.ToolEventBus toolEventBus;
    private final TemplateRegistry templateRegistry;
    private final WorkspaceManagerFactory workspaceManagerFactory;
    private final UserStore userStore;
    private final AgentAclService aclService;

    /**
     * In-flight cache of dynamically-registered gateway agent IDs. Key: {@code {userId}/{agentId}},
     * Value: the gateway agent ID (e.g. {@code uca-{userId}-{agentId}}).
     */
    private final ConcurrentHashMap<String, String> registeredUcaIds = new ConcurrentHashMap<>();

    public AgentCatalogService(
            DataAgentBootstrap builderBootstrap,
            UserAgentDefinitionStore store,
            Optional<Model> modelOpt,
            io.agentscope.dataagent.web.toolbus.ToolEventBus toolEventBus,
            TemplateRegistry templateRegistry,
            WorkspaceManagerFactory workspaceManagerFactory,
            UserStore userStore,
            AgentAclService aclService) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.toolEventBus = toolEventBus;
        this.templateRegistry = templateRegistry;
        this.workspaceManagerFactory = workspaceManagerFactory;
        this.userStore = userStore;
        this.aclService = aclService;
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /**
     * 全局 Agent → 自己的 → 别人分享的。
     * Lists all agent definitions visible to the given user: global agents, the user's own
     * custom agents, and any user-custom agents shared with the user via a {@link
     * io.agentscope.dataagent.web.share.AgentShareGrant} (USER or WORKSPACE grantee).
     *
     * <p>Globals always appear first; user-custom agents follow in owner-insertion order.
     * Duplicates by id are collapsed (the first match wins, normally the user's own copy).
     */
    public List<AgentDefinition> listVisible(String userId) {
        List<AgentDefinition> result = new ArrayList<>(globalDefinitions());
        Map<String, AgentDefinition> visibleUserAgents = new LinkedHashMap<>();
        // The user's own agents first so they win id collisions over shared-in ones.
        for (AgentDefinition def : userDefinitions(userId)) {
            visibleUserAgents.put(def.id(), def);  // ① 自己的先 put，确保自己创建的 Agent 先出现
        }
        // Then everyone else's, filtered by ACL.
        for (UserRecord owner : userStore.listAll()) {
            if (owner.userId().equals(userId)) continue;
            for (UserAgentDefinitionStore.StoredEntry e : store.list(owner.userId())) {
                AgentDefinition def = e.toDefinition(owner.userId());
                if (aclService.tierFor(userId, def) != null) {
                    visibleUserAgents.putIfAbsent(def.id(), def);
                }
            }
        }
        result.addAll(visibleUserAgents.values());
        return result;
    }

    /**
     * Finds a single visible agent definition by id.
     * "可见"的判断逻辑：这个 Agent 满足以下任一条件，用户就能看到它：
     * 全局 Agent
     * 自己创建的 Agent
     * 别人分享给你的 Agent
     */
    public Optional<AgentDefinition> findVisible(String userId, String agentId) {
        return listVisible(userId).stream().filter(d -> d.id().equals(agentId)).findFirst();
    }

    /**
     * Returns the owner of a user-custom agent, or {@link Optional#empty()} for globals / unknown
     * ids. Used by share, clone, and EDIT-delegated-mutation flows to resolve the storage
     * namespace.
     */
    public Optional<String> findOwnerOf(String agentId) {
        if (isGlobal(agentId)) return Optional.empty();
        for (UserRecord owner : userStore.listAll()) {
            if (store.findById(owner.userId(), agentId).isPresent()) {
                return Optional.of(owner.userId());
            }
        }
        return Optional.empty();
    }

    /** Look up the on-disk store entry for a user-custom agent by id, across all owners. */
    public Optional<UserAgentDefinitionStore.StoredEntry> findStoredEntry(String agentId) {
        for (UserRecord owner : userStore.listAll()) {
            Optional<UserAgentDefinitionStore.StoredEntry> e =
                    store.findById(owner.userId(), agentId);
            if (e.isPresent()) return e;
        }
        return Optional.empty();
    }

    /** 就是看这个 agentId 在启动时注册的全局 Agent 列表里有没有。如果有，说明是系统内置的，不需要加前缀，直接用原名. */
    public boolean isGlobal(String agentId) {
        return builderBootstrap.agents().containsKey(agentId);
    }

    // -----------------------------------------------------------------
    //  Mutations (user-custom agents only)
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

    private Path userWorkspacePath(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        return workspaceManagerFactory.resolveAgentDataPath(entry.workspacePath(), entry.id());
    }

    /** Suffix automatically appended to the final segment of user-supplied workspace paths. */
    static final String WORKSPACE_DIR_SUFFIX = "-workspace";

    /**
     * Trims user-supplied workspace path input. Returns {@code null} for blank input (let the
     * resolver fall back to the agent id at runtime). Absolute paths are passed through unchanged.
     * Relative paths are rejected if they contain {@code ..} traversal segments. If the final
     * path segment does not already end with {@code -workspace}, the suffix is appended so all
     * agent workspaces share a consistent on-disk naming convention.
     */
    static String normalizeWorkspacePathInput(String raw) {
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

        // 用户更新了 Agent → 清缓存，下次访问时重新构建
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));

        log.info("User '{}' updated custom agent '{}'", userId, agentId);
        return updated.toDefinition(userId);
    }

    // -----------------------------------------------------------------
    //  分享授权（Share grants）
    // -----------------------------------------------------------------

    /**
     * 给一个 user-custom Agent 追加（或更新）一条分享授权，采用 upsert 语义：
     * 如果 (granteeType, granteeId) 已存在，就更新它的 tier；否则新增一条。
     *
     * <p>比喻：给某个 Agent 的"授权名单"上加一行——"给 alice 一张 RUN 卡"。
     * 如果名单上已经有 alice，就把她的卡升级（或降级）成新的 tier。
     *
     * <p>注意：调用方（Controller）负责权限校验——只有 owner 才能调用此方法。
     *
     * @param ownerId  Agent 的所有者（存储 namespace），由 Controller 从 def.ownerId() 取
     * @param agentId  目标 Agent ID
     * @param req      授权请求体（granteeType / granteeId / tier）
     * @return 更新后的 AgentDefinition（已带上新的 shares 列表）
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

        // 归一化 granteeId：WORKSPACE 类型强制为 "*"，避免前端传空或传错
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
                // 同 (granteeType, granteeId) 已存在 → 替换 tier，保留原 createdBy
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
            // 不存在 → 新增一条，createdBy 记 owner
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
     * 撤销一个分享授权。精确匹配 (granteeType, granteeId)，移除对应的那条 grant。
     *
     * <p>比喻：从"授权名单"上划掉一行——"收回 alice 的 RUN 卡"。其他人的卡不受影响。
     *
     * @param ownerId      Agent 的所有者
     * @param agentId      目标 Agent ID
     * @param granteeType  USER 或 WORKSPACE
     * @param granteeId    USER 时是 userId；WORKSPACE 时会被归一化成 "*"
     * @return 更新后的 AgentDefinition
     */
    public AgentDefinition revokeShare(
            String ownerId, String agentId, String granteeType, String granteeId) {
        // 校验 granteeType 合法
        if (!AgentShareGrant.GRANTEE_USER.equals(granteeType)
                && !AgentShareGrant.GRANTEE_WORKSPACE.equals(granteeType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid granteeType: " + granteeType);
        }
        // 归一化 granteeId：WORKSPACE 强制为 "*"
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
                removed = true; // 命中要撤销的那条 → 跳过（不加入 newShares）
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

    /**
     * 用新的 shares 列表重建 StoredEntry（record 不可变，只能整体复制）。
     * 空列表会被转成 null，和"新建/克隆 Agent 时 shares=null"的约定保持一致。
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

    /**
     * 校验分享授权请求体。三个字段都要合法：
     * granteeType ∈ {USER, WORKSPACE}；USER 时 granteeId 必填；tier ∈ {CLONE, RUN, EDIT}。
     */
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

    /**
     * Materializes a clone of {@code (srcOwnerId, srcAgentId)} in {@code newOwnerId}'s namespace.
     * The clone copies settings (name/description/sysPrompt/tools/skills/identity) and
     * marks {@code forkOf = srcAgentId}. Shares, sessions, and channel bindings start empty —
     * see plan §5.
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

    /** Holder for the freshly-cloned entry + its API view. */
    public record StoredEntryAndDefinition(
            UserAgentDefinitionStore.StoredEntry entry, AgentDefinition definition) {}

    /** Deletes a user-custom agent definition. Only the owner may delete. */
    public void deleteUserAgent(String userId, String agentId) {
        if (!store.delete(userId, agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        // 用户删除了 Agent → 清缓存
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));
        log.info("User '{}' deleted custom agent '{}'", userId, agentId);
    }

    /**
     * // 外部主动失效（比如配置热更新）
     * Drops the cached UCA registration for {@code (userId, agentId)} so the next chat call
     * rebuilds the {@link HarnessAgent} from the current {@link UserAgentDefinitionStore} entry.
     * Intended for controllers that mutate per-agent runtime resources (tools.json, skills/, etc.)
     * after the agent has already been instantiated.
     */
    public void invalidateUca(String userId, String agentId) {
        if (userId == null || agentId == null) return;
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));
    }

    /**
     * Resolves the running {@link HarnessAgent} for {@code (userId, agentId)}, building and
     * registering the UCA on first access. For globals, returns the bootstrap-registered instance
     * directly. Returns {@code null} if the user has no visibility on the agent. Intended for
     * controllers that need to introspect runtime state (skill repositories, toolkit) of the
     * actual agent the gateway would route to.
     */
    public HarnessAgent getRunningAgent(String userId, String agentId) {
        if (agentId == null) return null;
        if (isGlobal(agentId)) {
            return builderBootstrap.agents().get(agentId);
        }
        if (findVisible(userId, agentId).isEmpty()) {
            return null;
        }
        String gatewayId = resolveGatewayAgentId(userId, agentId);
        return builderBootstrap.gateway().findAgent(gatewayId);
    }

    // -----------------------------------------------------------------
    //  Gateway routing support
    // -----------------------------------------------------------------

    /**
     * 解析网关 Agent ID
     * 用户传的 agentId 可能是 "data-analyst" 这样的逻辑名称，但底层网关需要的是物理 ID
     * 把用户口中的"逻辑名称"翻译成网关能识别的"物理 ID"，并在首次访问时自动构建和注册 Agent。
     * @throws ResponseStatusException 404 if the agent is not visible to the user
     */
    public String resolveGatewayAgentId(String userId, String agentId) {
        if (isGlobal(agentId)) {
            return agentId;  // // ① 全局 Agent → 直接返回，不需要任何转换
        }
        // ② 用户自定义 Agent → 先查数据库，确认这个 Agent 确实存在且属于该用户
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(userId, agentId)  // 从数据库中查找 (userId, agentId) 对应的 Agent。如果找不到，直接抛 404 错误。
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found or not accessible: " + agentId));
        // ③ 查缓存，如果已经注册过就直接返回；没注册过就构建并注册 Agent
        String cacheKey = ucaCacheKey(userId, agentId);
        //computeIfAbsent 的逻辑是：缓存里有？ → 直接返回网关 ID，不用重新构建；
        // 没有？ →  调用 buildAndRegisterUca() 构建 Agent 并注册到网关，然后把网关 ID 存入缓存。
        return registeredUcaIds.computeIfAbsent(cacheKey, k -> buildAndRegisterUca(userId, entry));
    }

    /**
     * 它只是按规则拼出网关 ID，不会查数据库、不会构建 Agent、不会注册。用在只读场景（审计日志、会话过滤等），性能更好。
     */
    public String peekGatewayAgentId(String userId, String agentId) {
        if (agentId == null) return null;
        if (isGlobal(agentId)) return agentId;
        return UCA_PREFIX + userId + "-" + agentId;
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private List<AgentDefinition> globalDefinitions() {
        Map<String, AgentConfigEntry> fileAgents = builderBootstrap.loadedConfig().getAgents();
        List<AgentDefinition> result = new ArrayList<>();
        for (Map.Entry<String, HarnessAgent> e : builderBootstrap.agents().entrySet()) {
            String id = e.getKey();
            AgentConfigEntry cfg = fileAgents != null ? fileAgents.get(id) : null;
            String name = cfg != null && cfg.getName() != null ? cfg.getName() : id;
            String desc = cfg != null ? cfg.getDescription() : null;

            // HarnessAgent does not expose a public getToolkit(); report standard built-in tools.
            List<String> toolNames =
                    List.of(
                            "filesystem",
                            "shell_execute",
                            "memory_search",
                            "memory_get",
                            "session_search");

            AgentConfigEntry.ToolsConfig tc = cfg != null ? cfg.getTools() : null;
            AgentConfigEntry.IdentityConfig ic = cfg != null ? cfg.getIdentity() : null;
            AgentConfigEntry.GroupChatConfig gc = cfg != null ? cfg.getGroupChat() : null;
            AgentConfigEntry.SkillsConfig sk = cfg != null ? cfg.getSkills() : null;

            result.add(
                    new AgentDefinition(
                            id,
                            name,
                            desc,
                            null, // don't expose sysPrompt in global catalog
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolNames,
                            tc != null ? tc.getAllow() : null,
                            tc != null ? tc.getDeny() : null,
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            sk != null ? sk.getAllow() : null,
                            sk != null ? sk.getDeny() : null,
                            AgentDefinition.SCOPE_GLOBAL,
                            null,
                            0L,
                            0L,
                            null, // shares — globals are never shared individually
                            AgentDefinition.RUN_AS_INVOKER,
                            null, // forkOf
                            cfg != null ? cfg.getWorkspace() : null, // mirror runtime workspace
                            null, // sandboxMode — globals follow the platform default
                            null, // sandboxScope
                            null)); // tierForCurrentUser — populated by the controller
        }
        return result;
    }

    private List<AgentDefinition> userDefinitions(String userId) {
        return store.list(userId).stream().map(e -> e.toDefinition(userId)).toList();
    }

    /**
     * 真正构建 Agent 的地方
     */
    private String buildAndRegisterUca(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        // 1. 生成网关 ID
        String gatewayAgentId = UCA_PREFIX + userId + "-" + entry.id();
        // 2. 确定工作空间路径
        Path workspace = userWorkspacePath(userId, entry);
        // 3. 用 Builder 模式构建 Agent
        HarnessAgent.Builder b = HarnessAgent.builder();

        String name = entry.name() != null ? entry.name() : entry.id();
        b.name(name);

        if (entry.description() != null) {
            b.description(entry.description());
        }
        if (entry.sysPrompt() != null) {
            b.sysPrompt(entry.sysPrompt());
        }
        if (entry.maxIters() != null) {
            b.maxIters(entry.maxIters());
        }
        // Model: prefer per-agent override, fall back to bootstrap-level model.
        if (entry.model() != null && !entry.model().isBlank()) {
            b.model(entry.model());
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);

        // Layered skill repositories: workspace overlay is implicit; explicit entries from the
        // user's saved definition are appended in order so earlier entries win on name clashes.
        if (entry.skillRepositories() != null && !entry.skillRepositories().isEmpty()) {
            var repos =
                    io.agentscope.dataagent.runtime.config.SkillRepositorySupport.createAll(
                            workspace, entry.skillRepositories());
            if (!repos.isEmpty()) {
                b.skillRepositories(repos);
            }
        }

        // Pre-populate this user-custom agent's toolkit with the outbound-send tool so the agent
        // can proactively push messages into any registered IM channel (subject to per-agent
        // tier ACL enforced at OutboundController + channel-routing check in OutboundService).
        io.agentscope.core.tool.Toolkit ucaToolkit = new io.agentscope.core.tool.Toolkit();
        ucaToolkit.registerTool(
                new io.agentscope.dataagent.runtime.outbound.OutboundTool(
                        builderBootstrap.channelManager()));
        b.toolkit(ucaToolkit);

        // Inject ToolNotificationMiddleware so user-custom agents also publish tool-call events.
        b.middleware(
                new io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware(toolEventBus));

        HarnessAgent agent = b.build();  // 4. 构建 Agent 实例
        // 5. 注册 Agent 到网关
        HarnessGateway gateway = builderBootstrap.gateway();
        gateway.registerAgent(gatewayAgentId, agent);

        log.info(
                "Registered user-custom agent in gateway: userId={}, agentId={}, gatewayId={}",
                userId,
                entry.id(),
                gatewayAgentId);

        return gatewayAgentId;
    }

    private static String ucaCacheKey(String userId, String agentId) {
        return userId + "/" + agentId;
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

    // -----------------------------------------------------------------
    //  Request DTO
    // -----------------------------------------------------------------

    /** Request body for creating or updating a user-custom agent. */
    public record AgentCreateRequest(
            String id,
            String name,
            String description,
            String sysPrompt,
            String model,
            Integer maxIters,
            List<String> toolsAllow,
            List<String> toolsDeny,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            List<String> skillsAllow,
            List<String> skillsDeny,
            String workspacePath,
            String templateId,
            AgentDraft aiDraft,
            List<io.agentscope.dataagent.runtime.config.SkillRepositoryConfigEntry>
                    skillRepositories,
            String sandboxMode,
            String sandboxScope) {}

    /**
     * 分享授权请求体（POST /api/agents/{id}/shares 的请求 body）。
     *
     * <p>三个字段：
     * <ul>
     *   <li>{@code granteeType} — 授权对象类型，{@code USER}（指定用户）或
     *       {@code WORKSPACE}（所有登录用户）。见 {@link AgentShareGrant#GRANTEE_USER} /
     *       {@link AgentShareGrant#GRANTEE_WORKSPACE}。
     *   <li>{@code granteeId} — USER 时为目标用户的 userId；WORKSPACE 时忽略（会被归一化成
     *       {@link AgentShareGrant#WORKSPACE_ID} 即 "*"）。
     *   <li>{@code tier} — 授权级别，{@code CLONE} / {@code RUN} / {@code EDIT}
     *       （大小写不敏感，会被转大写存储）。见 {@link AgentAclService.Tier}。
     * </ul>
     */
    public record ShareGrantRequest(String granteeType, String granteeId, String tier) {}

    /**
     * Optional AI-generated draft attached to a creation request. Carries the suggested
     * configuration plus optional skill/subagent files to scaffold into the new agent's workspace.
     * Wiring into {@link #createUserAgent(String, AgentCreateRequest)} happens in a later phase.
     */
    public record AgentDraft(
            String name,
            String description,
            String sysPrompt,
            List<String> suggestedTools,
            List<NamedFile> suggestedSkills,
            List<NamedFile> suggestedSubagents) {}

    /** A named file (e.g. a markdown skill or subagent definition). */
    public record NamedFile(String name, String content) {}
}
