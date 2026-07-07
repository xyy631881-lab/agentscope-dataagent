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
package io.agentscope.dataagent.agent.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.agent.sharing.AgentAccessGuard;
import io.agentscope.dataagent.agent.sharing.AgentAclService.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic workspace file CRUD for an agent.
 *
 * <p>Thin HTTP layer — all business logic has been extracted to:
 * <ul>
 *   <li>{@link WorkspaceFileService} — file tree, read, write, create, move, delete, upload
 *   <li>{@link WorkspaceSummaryService} — summary, scaffold, memory view
 *   <li>{@link SubagentService} — subagent declaration CRUD
 * </ul>
 *
 * <p>Each endpoint follows the same pattern: extract userId → guard.require →
 * resolutionService.resolve → delegate to service.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace")
public class AgentWorkspaceController {

    private final WorkspaceResolutionService resolutionService;
    private final AgentAccessGuard guard;
    private final WorkspaceFileService fileService;
    private final WorkspaceSummaryService summaryService;
    private final SubagentService subagentService;

    public AgentWorkspaceController(
            WorkspaceResolutionService resolutionService,
            AgentAccessGuard guard,
            WorkspaceFileService fileService,
            WorkspaceSummaryService summaryService,
            SubagentService subagentService) {
        this.resolutionService = resolutionService;
        this.guard = guard;
        this.fileService = fileService;
        this.summaryService = summaryService;
        this.subagentService = subagentService;
    }

    // -----------------------------------------------------------------
    //  Summary + scaffold
    // -----------------------------------------------------------------

    @GetMapping
    public WorkspaceSummary summary(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        var ctx = resolutionService.resolve(userId, agentId);
        return summaryService.summary(agentId, ctx);
    }

    @PostMapping("/scaffold")
    public WorkspaceSummary scaffold(
            @PathVariable String agentId,
            @RequestParam(name = "name", defaultValue = "") String agentName,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        return summaryService.scaffold(agentId, agentName, ctx);
    }

    // -----------------------------------------------------------------
    //  Memory (read-only convenience view)
    // -----------------------------------------------------------------

    @GetMapping("/memory")
    public MemoryView memory(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        var ctx = resolutionService.resolve(userId, agentId);
        return summaryService.memory(ctx);
    }

    // -----------------------------------------------------------------
    //  Generic file CRUD
    // -----------------------------------------------------------------

    @GetMapping("/files")
    public List<FileNode> tree(
            @PathVariable String agentId,
            @RequestParam(name = "recursive", defaultValue = "true") boolean recursive,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        var ctx = resolutionService.resolve(userId, agentId);
        return fileService.tree(ctx, recursive);
    }

    @GetMapping("/file")
    public String readFile(
            @PathVariable String agentId, @RequestParam("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        var ctx = resolutionService.resolve(userId, agentId);
        return fileService.readFile(ctx, path);
    }

    @PutMapping("/file")
    public FileNode writeFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestBody WriteRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        return fileService.writeFile(ctx, agentId, userId, path, req != null && req.content() != null ? req.content() : "");
    }

    @PostMapping("/file")
    @ResponseStatus(HttpStatus.CREATED)
    public FileNode createNode(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestParam(name = "type", defaultValue = "file") String type,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        return fileService.createNode(ctx, agentId, userId, path, type);
    }

    @PostMapping("/file/move")
    public FileNode moveNode(
            @PathVariable String agentId, @RequestBody MoveRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        String from = req != null ? req.from() : null;
        String to = req != null ? req.to() : null;
        return fileService.moveNode(ctx, agentId, userId, from, to);
    }

    @DeleteMapping("/file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNode(
            @PathVariable String agentId, @RequestParam("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        fileService.deleteNode(ctx, agentId, userId, path);
    }

    @PostMapping("/upload")
    public FileNode upload(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        return fileService.upload(ctx, agentId, userId, path, file);
    }

    // -----------------------------------------------------------------
    //  Subagent CRUD
    // -----------------------------------------------------------------

    @GetMapping("/subagents")
    public List<SubagentInfo> listSubagents(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        var ctx = resolutionService.resolve(userId, agentId);
        return subagentService.listSubagents(ctx);
    }

    @PutMapping("/subagents/{name}")
    public SubagentInfo upsertSubagent(
            @PathVariable String agentId,
            @PathVariable String name,
            @RequestBody SubagentUpsertRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        return subagentService.upsertSubagent(ctx, name, req);
    }

    @PostMapping("/subagents/from-agent")
    @ResponseStatus(HttpStatus.CREATED)
    public SubagentInfo createSubagentFromAgent(
            @PathVariable String agentId, @RequestBody FromAgentRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        return subagentService.createSubagentFromAgent(userId, ctx, req);
    }

    @DeleteMapping("/subagents/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubagent(
            @PathVariable String agentId, @PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.EDIT);
        var ctx = resolutionService.resolve(userId, agentId);
        subagentService.deleteSubagent(ctx, name);
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileNode(
            String name, String path, String type, Long size, List<FileNode> children) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WriteRequest(String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MoveRequest(String from, String to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSummary(
            String agentId,
            String workspacePath,
            boolean exists,
            boolean agentsMdExists,
            boolean memoryMdExists,
            int skillCount,
            int subagentCount,
            int dailyMemoryCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemoryView(String memoryMd, List<DailyMemoryFile> dailyFiles) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DailyMemoryFile(String name, long sizeBytes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubagentInfo(
            String name,
            String description,
            String model,
            Integer maxIters,
            List<String> tools,
            String workspaceMode,
            String workspacePath,
            boolean hasInlineBody,
            String sourceAgentId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubagentUpsertRequest(
            String description,
            String model,
            Integer maxIters,
            List<String> tools,
            String workspaceMode,
            String workspacePath,
            String inlineBody,
            String sourceAgentId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FromAgentRequest(String sourceAgentId, String name) {}
}
