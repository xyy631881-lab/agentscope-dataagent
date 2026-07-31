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
package io.agentscope.dataagent.capability.contribution.api;
import io.agentscope.dataagent.capability.contribution.application.MarketContributionService;
import io.agentscope.dataagent.capability.contribution.domain.FileEntry;
import io.agentscope.dataagent.capability.contribution.infrastructure.ContributionEntity;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.application.WorkspaceResolutionService;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing REST surface for nominating workspace artifacts for promotion to the shared
 * workspace via admin approval.
 *
 * <ul>
 *   <li>{@code POST /api/me/contributions} — submit a new nomination whose payload the client
 *       already harvested itself (FileEntry list in the request body)
 *   <li>{@code POST /api/me/contributions/from-workspace} — submit by reference: server reads
 *       file content from the caller's sandbox using the listed source paths
 *   <li>{@code GET  /api/me/contributions} — list the current user's submissions
 * </ul>
 */
@RestController
@RequestMapping("/api/me/contributions")
public class MarketContributionController {
    private final MarketContributionService service;
    private final WorkspaceResolutionService workspaceResolutionService;

    public MarketContributionController(
            MarketContributionService service,
            WorkspaceResolutionService workspaceResolutionService) {
        this.service = service;
        this.workspaceResolutionService = workspaceResolutionService;
    }

    @PostMapping
    public ContributionView submit(@RequestBody SubmitRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();

        ContributionEntity saved =
                service.submit(
                        userId,
                        req.sourceAgentId(),
                        req.targetAgentId(),
                        req.targetType(),
                        req.targetPath(),
                        req.rationale(),
                        req.payload());
        return ContributionView.from(saved);
    }

    @PostMapping("/from-workspace")
    public ContributionView submitFromWorkspace(
            @RequestBody FromWorkspaceRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();

        List<FileEntry> payload =
                harvestFromUserSandbox(
                        userId,
                        req.sourceAgentId(),
                        req.targetType(),
                        req.sourcePaths());
        ContributionEntity saved =
                service.submit(
                        userId,
                        req.sourceAgentId(),
                        req.targetAgentId(),
                        req.targetType(),
                        req.targetPath(),
                        req.rationale(),
                        payload);
        return ContributionView.from(saved);
    }

    @GetMapping
    public List<ContributionView> listMine(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return service.listMine(userId).stream().map(ContributionView::from).toList();
    }

    private List<FileEntry> harvestFromUserSandbox(
            String userId, String sourceAgentId, String targetType, List<String> sourcePaths) {
        if (sourceAgentId == null || sourceAgentId.isBlank()) {
            throw new IllegalArgumentException("sourceAgentId is required");
        }
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            throw new IllegalArgumentException("sourcePaths must contain at least one entry");
        }
        boolean isSkillBundle = ContributionEntity.TARGET_SKILL.equals(targetType);
        if (!isSkillBundle && sourcePaths.size() != 1) {
            throw new IllegalArgumentException(
                    "target_type "
                    + targetType
                    + " requires exactly one source path (got "
                    + sourcePaths.size()
                    + ")");
        }
        WorkspaceManager wm = workspaceResolutionService.resolve(userId, sourceAgentId).manager();
        RuntimeContext rc = RuntimeContext.builder().userId(userId).build();
        List<FileEntry> out = new ArrayList<>(sourcePaths.size());
        List<String> skillRelPaths =
                isSkillBundle ? skillBundleRelativePaths(sourcePaths) : List.of();
        for (int i = 0; i < sourcePaths.size(); i++) {
            String path = sourcePaths.get(i);
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("sourcePaths entries must be non-blank");
            }
            String content = wm.readManagedWorkspaceFileUtf8(rc, path);
            if (content == null) {
                throw new IllegalArgumentException("source file is unreadable: " + path);
            }
            String relPath = isSkillBundle ? skillRelPaths.get(i) : "";
            out.add(new FileEntry(relPath, content));
        }
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidContribution(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "contribution request is invalid" : ex.getMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /**
     * Keeps a contributed skill's bundle layout instead of flattening every file to its basename.
     * All files must come from the same directory that contains {@code SKILL.md}.
     */
    static List<String> skillBundleRelativePaths(List<String> sourcePaths) {
        List<String> normalized = sourcePaths.stream()
                .map(path -> path == null ? "" : path.replace('\\', '/'))
                .toList();
        String manifest = normalized.stream()
                .filter(path -> path.endsWith("/SKILL.md") || "SKILL.md".equals(path))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "skill bundle selection must include SKILL.md"));
        int slash = manifest.lastIndexOf('/');
        String root = slash >= 0 ? manifest.substring(0, slash) : "";
        String prefix = root.isEmpty() ? "" : root + "/";
        List<String> relPaths = new ArrayList<>(normalized.size());
        for (String path : normalized) {
            if (path.isBlank() || (!prefix.isEmpty() && !path.startsWith(prefix))) {
                throw new IllegalArgumentException(
                        "all skill files must come from the same bundle directory");
            }
            String rel = prefix.isEmpty() ? path : path.substring(prefix.length());
            if (rel.isBlank() || rel.startsWith("/") || rel.contains("..")) {
                throw new IllegalArgumentException("invalid skill source path: " + path);
            }
            relPaths.add(rel);
        }
        return relPaths;
    }

    public record SubmitRequest(
            String sourceAgentId,
            String targetAgentId,
            String targetType,
            String targetPath,
            String rationale,
            List<FileEntry> payload) {}

    public record FromWorkspaceRequest(
            String sourceAgentId,
            String targetAgentId,
            String targetType,
            String targetPath,
            String rationale,
            List<String> sourcePaths) {}

    public record ContributionView(
            long id,
            String status,
            String sourceUserId,
            String sourceAgentId,
            String targetAgentId,
            String targetType,
            String targetPath,
            String rationale,
            String reviewerUserId,
            String reviewerNote,
            int version,
            long createdAt,
            long updatedAt) {
        public static ContributionView from(ContributionEntity e) {
            return new ContributionView(
                    e.getId(),
                    e.getStatus(),
                    e.getSourceUserId(),
                    e.getSourceAgentId(),
                    e.getTargetAgentId(),
                    e.getTargetType(),
                    e.getTargetPath(),
                    e.getRationale(),
                    e.getReviewerUserId(),
                    e.getReviewerNote(),
                    e.getVersion(),
                    e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }
}
