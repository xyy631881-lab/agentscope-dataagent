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
package io.agentscope.dataagent.conversation.application;

import io.agentscope.dataagent.agent.application.AgentActivityStore;
import io.agentscope.dataagent.agent.domain.ActivityEvent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the secondary RUN_SESSION activity event outside the chat request path.
 *
 * <p>The activity store lives in the Agent sandbox and can be delayed by container recovery. A
 * single daemon worker plus direct hand-off keeps that delay away from SSE startup without building
 * an unbounded backlog. When the worker is busy, the event is dropped and the caller may retry on
 * the next message; the database session and request trace remain the authoritative run records.
 */
@Component
public class RunSessionActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunSessionActivityRecorder.class);

    private final AgentActivityStore activity;
    private final ThreadPoolExecutor executor;

    public RunSessionActivityRecorder(AgentActivityStore activity) {
        this.activity = activity;
        this.executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        runnable -> {
                            Thread thread = new Thread(runnable, "run-session-activity");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    /** Returns {@code false} when another sandbox activity write is still in progress. */
    public boolean tryRecord(String ownerId, String agentId, String userId, String gateKey) {
        try {
            executor.execute(() -> record(ownerId, agentId, userId, gateKey));
            return true;
        } catch (RejectedExecutionException exception) {
            log.warn(
                    "Skipping RUN_SESSION activity while recorder is busy: ownerId={}, agentId={}, gateKey={}",
                    ownerId,
                    agentId,
                    gateKey);
            return false;
        }
    }

    private void record(String ownerId, String agentId, String userId, String gateKey) {
        try {
            activity.record(
                    ownerId,
                    agentId,
                    activity.actor(userId),
                    ActivityEvent.Action.RUN_SESSION,
                    gateKey,
                    null);
        } catch (RuntimeException exception) {
            log.warn(
                    "RUN_SESSION activity write failed for {}/{}: {}",
                    ownerId,
                    agentId,
                    exception.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
