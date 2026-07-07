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
import io.agentscope.dataagent.agent.api.AgentWorkspaceController;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.application.AgentCatalogService;
import io.agentscope.dataagent.agent.domain.AgentDefinition;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Encapsulates all subagent CRUD business logic extracted from
 * {@link AgentWorkspaceController}.
 *
 * <p>Each public method expects a pre-resolved
 * {@link WorkspaceResolutionService.ResolvedWorkspace}; security ({@code guard}) checks
 * and workspace resolution remain the controller's responsibility.
 */
@Service
public class SubagentService {

    private final AgentCatalogService catalogService;

    public SubagentService(AgentCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // -----------------------------------------------------------------
    //  Subagent CRUD
    // -----------------------------------------------------------------

    /**
     * Lists all subagents by scanning {@code /subagents/*.md}, parsing each file with
     * {@link AgentSpecLoader}, and sorting the results by name.
     *
     * @param ctx the resolved workspace to operate on
     * @return a sorted list of subagent info DTOs (empty if the directory is missing)
     */
    public List<AgentWorkspaceController.SubagentInfo> listSubagents(
            WorkspaceResolutionService.ResolvedWorkspace ctx) {
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        LsResult ls = fs.ls(rc, "/subagents");
        if (!ls.isSuccess() || ls.entries() == null) {
            return List.<AgentWorkspaceController.SubagentInfo>of();
        }
        List<AgentWorkspaceController.SubagentInfo> result = new ArrayList<>();
        for (FileInfo fi : ls.entries()) {
            String entryPath = fi.path();
            if (fi.isDirectory() || !entryPath.endsWith(".md")) {
                continue;
            }
            ReadResult rr = fs.read(rc, "subagents/" + fileName(entryPath), 0, 50000);
            if (!rr.isSuccess()) {
                continue;
            }
            String markdown = rr.fileData().content();
            String name = stripMdExtension(fileName(entryPath));
            SubagentDeclaration decl =
                    AgentSpecLoader.parse(markdown, name, ctx.workspace());
            if (decl != null) {
                result.add(toSubagentInfo(decl));
            }
        }
        result.sort(Comparator.comparing(AgentWorkspaceController.SubagentInfo::name));
        return result;
    }

    /**
     * Creates or replaces a subagent definition file. Validates the name, renders the
     * markdown, writes the file, and re-parses it to verify correctness.
     *
     * @param ctx  the resolved workspace to operate on
     * @param name the subagent name (becomes the filename without extension)
     * @param req  the upsert request carrying description, model, tools, etc.
     * @return the parsed subagent info DTO
     * @throws ResponseStatusException on validation or parse failure
     */
    public AgentWorkspaceController.SubagentInfo upsertSubagent(
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            String name,
            AgentWorkspaceController.SubagentUpsertRequest req) {
        if (req == null || req.description() == null || req.description().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "description is required");
        }
        validateSubagentName(name);
        String markdown = renderSubagentMarkdown(req);
        ctx.manager()
                .writeUtf8WorkspaceRelative(
                        RuntimeContext.empty(), "subagents/" + name + ".md", markdown);
        SubagentDeclaration decl =
                AgentSpecLoader.parse(markdown, name, ctx.workspace());
        if (decl == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Generated markdown failed to parse");
        }
        return toSubagentInfo(decl);
    }

    /**
     * Creates a subagent from an existing catalog agent. Looks up the source agent via
     * {@link AgentCatalogService#findVisible(String, String)}, builds an upsert request from
     * the source agent's fields, renders the markdown, writes the file, and re-parses it to
     * verify.
     *
     * @param userId the current user's id (used for agent visibility lookup)
     * @param ctx    the resolved workspace to operate on
     * @param req    the from-agent request carrying {@code sourceAgentId} and optional name
     * @return the parsed subagent info DTO
     * @throws ResponseStatusException if the source agent is not found or the name is invalid
     */
    public AgentWorkspaceController.SubagentInfo createSubagentFromAgent(
            String userId,
            WorkspaceResolutionService.ResolvedWorkspace ctx,
            AgentWorkspaceController.FromAgentRequest req) {
        if (req == null
                || req.sourceAgentId() == null
                || req.sourceAgentId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "sourceAgentId is required");
        }
        AgentDefinition source =
                catalogService
                        .findVisible(userId, req.sourceAgentId())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Source agent not found: "
                                                        + req.sourceAgentId()));
        String subName =
                (req.name() != null && !req.name().isBlank())
                        ? req.name()
                        : req.sourceAgentId();
        validateSubagentName(subName);

        String description =
                (source.description() != null && !source.description().isBlank())
                        ? source.description()
                        : source.name();
        AgentWorkspaceController.SubagentUpsertRequest upsert =
                new AgentWorkspaceController.SubagentUpsertRequest(
                        description,
                        source.model(),
                        source.maxIters(),
                        source.tools(),
                        "shared",
                        null,
                        source.sysPrompt(),
                        req.sourceAgentId());
        String markdown = renderSubagentMarkdown(upsert);
        ctx.manager()
                .writeUtf8WorkspaceRelative(
                        RuntimeContext.empty(),
                        "subagents/" + subName + ".md",
                        markdown);
        SubagentDeclaration decl =
                AgentSpecLoader.parse(markdown, subName, ctx.workspace());
        if (decl == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Generated markdown failed to parse");
        }
        return toSubagentInfo(decl);
    }

    /**
     * Deletes a subagent definition file. Validates the name, checks existence, and deletes
     * the {@code .md} file.
     *
     * @param ctx  the resolved workspace to operate on
     * @param name the subagent name (filename without extension)
     * @throws ResponseStatusException if the name is invalid or the subagent does not exist
     */
    public void deleteSubagent(
            WorkspaceResolutionService.ResolvedWorkspace ctx, String name) {
        validateSubagentName(name);
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        String path = "subagents/" + name + ".md";
        if (!fs.exists(RuntimeContext.empty(), path)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Subagent not found: " + name);
        }
        WriteResult wr = fs.delete(RuntimeContext.empty(), path);
        if (!wr.isSuccess()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Delete failed: " + wr.error());
        }
    }

    // -----------------------------------------------------------------
    //  Subagent helpers
    // -----------------------------------------------------------------

    private static AgentWorkspaceController.SubagentInfo toSubagentInfo(SubagentDeclaration decl) {
        return new AgentWorkspaceController.SubagentInfo(
                decl.getName(),
                decl.getDescription(),
                decl.getModel(),
                decl.getMaxIters() != 10 ? decl.getMaxIters() : null,
                decl.getTools().isEmpty() ? null : decl.getTools(),
                decl.getWorkspaceMode() == WorkspaceMode.SHARED ? "shared" : "isolated",
                decl.getWorkspacePath() != null ? decl.getWorkspacePath().toString() : null,
                decl.getInlineAgentsBody() != null && !decl.getInlineAgentsBody().isBlank(),
                null);
    }

    static String renderSubagentMarkdown(AgentWorkspaceController.SubagentUpsertRequest req) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("description: ").append(req.description().replace("\n", " ")).append("\n");
        if (req.workspaceMode() != null || req.workspacePath() != null) {
            sb.append("workspace:\n");
            sb.append("  mode: ")
                    .append(req.workspaceMode() != null ? req.workspaceMode() : "isolated")
                    .append("\n");
            if (req.workspacePath() != null && !req.workspacePath().isBlank()) {
                sb.append("  path: ").append(req.workspacePath()).append("\n");
            }
        }
        if (req.model() != null && !req.model().isBlank()) {
            sb.append("model: ").append(req.model()).append("\n");
        }
        if (req.maxIters() != null) {
            sb.append("maxIters: ").append(req.maxIters()).append("\n");
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            sb.append("tools: [").append(String.join(", ", req.tools())).append("]\n");
        }
        sb.append("---\n");
        if (req.inlineBody() != null && !req.inlineBody().isBlank()) {
            sb.append("\n").append(req.inlineBody().strip()).append("\n");
        }
        return sb.toString();
    }

    private static void validateSubagentName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subagent name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid subagent name: " + name);
        }
    }

    private static String stripMdExtension(String filename) {
        return filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}