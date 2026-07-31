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
package io.agentscope.dataagent.workspace.application;

import io.agentscope.core.state.AgentStateStore;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Invalidates persisted harness sandbox slots after durable workspace content changes. */
@Service
public class SandboxStateInvalidator {

    private static final Logger log = LoggerFactory.getLogger(SandboxStateInvalidator.class);
    private static final String SANDBOX_STATE_KEY = "_sandbox_state";

    private final AgentStateStore stateStore;

    public SandboxStateInvalidator(AgentStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    /**
     * Clears every user-isolated sandbox slot so the next acquisition projects current workspace
     * files into a newly-created container.
     *
     * <p>AgentScope's USER isolation slot id does not contain the logical agent id, so the current
     * state-store API cannot target one agent without decoding framework-owned keys. Workspace
     * installs and approvals are low-frequency control-plane operations, making this bounded
     * over-invalidation preferable to leaving a running container on stale assets.
     *
     * @return number of sandbox slots invalidated; zero when no slots exist or invalidation fails
     */
    public int invalidateAll() {
        try {
            Set<String> sessionIds = stateStore.listSessionIds(null);
            int invalidated = 0;
            for (String sessionId : sessionIds) {
                stateStore.delete(null, sessionId, SANDBOX_STATE_KEY);
                invalidated++;
            }
            if (invalidated > 0) {
                log.info("Invalidated {} persisted sandbox state slot(s)", invalidated);
            }
            return invalidated;
        } catch (Exception e) {
            log.warn(
                    "Failed to invalidate sandbox states; containers will refresh on normal TTL: {}",
                    e.getMessage(),
                    e);
            return 0;
        }
    }
}
