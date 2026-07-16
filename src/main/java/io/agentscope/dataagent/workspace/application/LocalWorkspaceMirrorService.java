package io.agentscope.dataagent.workspace.application;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.application.WorkspaceResolutionService;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Triggers a one-way host mirror through the same user-isolated browser filesystem. */
@Service
public class LocalWorkspaceMirrorService {

    private static final Logger log = LoggerFactory.getLogger(LocalWorkspaceMirrorService.class);

    private final WorkspaceResolutionService workspaceResolutionService;

    public LocalWorkspaceMirrorService(WorkspaceResolutionService workspaceResolutionService) {
        this.workspaceResolutionService = workspaceResolutionService;
    }

    public void synchronize(String userId, String agentId) {
        try {
            LsResult result = workspaceResolutionService
                    .resolveFilesystem(userId, agentId)
                    .ls(RuntimeContext.builder().userId(userId).build(), "/");
            if (!result.isSuccess()) {
                log.warn("[workspace-mirror] root listing failed for user={}, agent={}", userId, agentId);
            }
        } catch (Exception e) {
            log.warn(
                    "[workspace-mirror] unable to synchronize user={}, agent={}: {}",
                    userId,
                    agentId,
                    e.getMessage());
        }
    }
}
