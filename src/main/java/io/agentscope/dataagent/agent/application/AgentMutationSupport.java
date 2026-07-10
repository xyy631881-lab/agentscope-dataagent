package io.agentscope.dataagent.agent.application;

import io.agentscope.dataagent.agent.application.command.AgentCreateRequest;
import io.agentscope.dataagent.agent.application.command.AgentDraft;
import io.agentscope.dataagent.agent.application.command.NamedFile;
import io.agentscope.dataagent.agent.domain.AgentShareGrant;
import io.agentscope.dataagent.agent.domain.UserAgentDefinitionStore;
import io.agentscope.dataagent.agent.application.AgentMutationService.ShareGrantRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure, stateless helpers backing {@link AgentMutationService}: request validation, id/path
 * sanitisation, share-list rebuild, and AI-draft materialisation into the workspace folder.
 *
 * <p>Kept apart from the service so the mutation orchestration reads as a sequence of intent,
 * not implementation. No instance state, no framework coupling.
 */
final class AgentMutationSupport {

    public static String normalizeWorkspacePathInput(String raw) {
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
        if (leaf.endsWith(AgentMutationService.WORKSPACE_DIR_SUFFIX)) {
            return trimmed;
        }
        String suffixed = leaf + AgentMutationService.WORKSPACE_DIR_SUFFIX;
        Path parent = p.getParent();
        Path rebuilt = parent != null ? parent.resolve(suffixed) : Paths.get(suffixed);
        return rebuilt.toString();
    }
    public static void writeDraftFiles(
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
    public static String sanitizeName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }
    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    public static void writeIfMissing(Path file, String content) throws IOException {
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
    public static UserAgentDefinitionStore.StoredEntry withShares(
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
    public static void validateShareGrantRequest(ShareGrantRequest req) {
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
    public static void validateRequest(AgentCreateRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'name' is required");
        }
    }
    public static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

}
