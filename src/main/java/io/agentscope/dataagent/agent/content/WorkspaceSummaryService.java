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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Extracts the summary, scaffold, and memory-view business logic from
 * {@link AgentWorkspaceController} so that the controller remains a thin HTTP
 * adapter.
 *
 * <p>All methods operate on a previously-resolved workspace context
 * ({@link WorkspaceResolutionService.ResolvedWorkspace}); access-control and
 * resolution are performed by the caller.
 */
@Service
public class WorkspaceSummaryService {

    /**
     * Probes the composite filesystem and returns a workspace summary reflecting
     * user-isolated routed content (MEMORY.md, memory/, skills/, subagents/).
     *
     * @param agentId the agent identifier (echoed back in the summary)
     * @param ctx the resolved workspace context
     * @return a {@link AgentWorkspaceController.WorkspaceSummary}
     */
    public AgentWorkspaceController.WorkspaceSummary summary(
            String agentId, WorkspaceResolutionService.ResolvedWorkspace ctx) {
        return summarize(agentId, ctx);
    }

    /**
     * Creates {@code AGENTS.md} if it is missing, then returns the workspace summary.
     *
     * <p>{@code skills/}, {@code subagents/}, and {@code memory/} are virtual via composite
     * routes so no {@code mkdir} is needed — only {@code AGENTS.md} requires materialisation.
     *
     * @param agentId the agent identifier
     * @param agentName display name for the agent (falls back to {@code agentId} when blank)
     * @param ctx the resolved workspace context
     * @return the workspace summary after scaffolding
     */
    public AgentWorkspaceController.WorkspaceSummary scaffold(
            String agentId,
            String agentName,
            WorkspaceResolutionService.ResolvedWorkspace ctx) {
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        // skills/, subagents/, memory/ are virtual via composite routes — no mkdir
        // needed. Only AGENTS.md needs materialisation, and only if missing.
        if (!fs.exists(rc, "AGENTS.md")) {
            String displayName = agentName.isBlank() ? agentId : agentName;
            ctx.manager()
                    .writeUtf8WorkspaceRelative(
                            rc,
                            "AGENTS.md",
                            "# " + displayName + "\n\nYou are " + displayName + ".\n");
        }
        return summarize(agentId, ctx);
    }

    /**
     * Returns the {@code MEMORY.md} content (up to 50&nbsp;KB) and the list of per-day
     * memory files found under {@code /memory}, sorted newest-first.
     *
     * @param ctx the resolved workspace context
     * @return a {@link AgentWorkspaceController.MemoryView}
     */
    public AgentWorkspaceController.MemoryView memory(WorkspaceResolutionService.ResolvedWorkspace ctx) {
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        String memoryContent = null;
        if (fs.exists(rc, "MEMORY.md")) {
            ReadResult rr = fs.read(rc, "MEMORY.md", 0, 50000);
            if (rr.isSuccess()) {
                memoryContent = rr.fileData().content();
            }
        }
        List<AgentWorkspaceController.DailyMemoryFile> dailyFiles = new ArrayList<>();
        LsResult ls = fs.ls(rc, "/memory");
        if (ls.isSuccess() && ls.entries() != null) {
            ls.entries().stream()
                    .filter(fi -> !fi.isDirectory() && fi.path().endsWith(".md"))
                    .sorted(Comparator.comparing(FileInfo::path).reversed())
                    .forEach(
                            fi ->
                                    dailyFiles.add(
                                            new AgentWorkspaceController.DailyMemoryFile(
                                                    fileName(fi.path()), fi.size())));
        }
        return new AgentWorkspaceController.MemoryView(memoryContent, dailyFiles);
    }

    // -----------------------------------------------------------------
    //  Static helpers
    // -----------------------------------------------------------------

    /**
     * Computes the summary via the composite filesystem so that user-isolated routed content
     * (MEMORY.md, memory/, sessions/, skills/, subagents/) is correctly reflected — disk-only
     * probes would miss everything stored in the
     * {@link io.agentscope.harness.agent.filesystem.remote.store.BaseStore}.
     */
    private static AgentWorkspaceController.WorkspaceSummary summarize(
            String agentId, WorkspaceResolutionService.ResolvedWorkspace ctx) {
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        boolean agentsMdExists = fs.exists(rc, "AGENTS.md");
        boolean memoryMdExists = fs.exists(rc, "MEMORY.md");
        int skillCount = countDirChildren(fs, rc, "/skills", true);
        int subagentCount = countMdLeafFiles(fs, rc, "/subagents");
        int dailyMemoryCount = countMdLeafFiles(fs, rc, "/memory");
        return new AgentWorkspaceController.WorkspaceSummary(
                agentId,
                ctx.workspace().toAbsolutePath().toString(),
                true,
                agentsMdExists,
                memoryMdExists,
                skillCount,
                subagentCount,
                dailyMemoryCount);
    }

    private static int countDirChildren(
            AbstractFilesystem fs, RuntimeContext rc, String absPath, boolean dirOnly) {
        LsResult ls = fs.ls(rc, absPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return 0;
        }
        int n = 0;
        for (FileInfo fi : ls.entries()) {
            if (!dirOnly || fi.isDirectory()) {
                n++;
            }
        }
        return n;
    }

    private static int countMdLeafFiles(AbstractFilesystem fs, RuntimeContext rc, String absPath) {
        LsResult ls = fs.ls(rc, absPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return 0;
        }
        int n = 0;
        for (FileInfo fi : ls.entries()) {
            if (!fi.isDirectory() && fi.path().endsWith(".md")) {
                n++;
            }
        }
        return n;
    }

    /**
     * Extracts the last path segment (basename) from a forward-slash path.
     *
     * <p>Shared utility — also used by {@code SubagentService}.
     */
    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
