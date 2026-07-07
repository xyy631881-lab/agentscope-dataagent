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
package io.agentscope.dataagent.workspace.domain;

import io.agentscope.harness.agent.sandbox.Sandbox;

/**
 * Abstraction for a per-(userId, agentId) sandbox container pool.
 *
 * <p>Allows swapping the concrete implementation without touching call-sites that only need to
 * {@link #borrow borrow}, {@link #invalidate invalidate}, or {@link #shutdownAll shut down}
 * sandboxes.
 *
 * <p>The default implementation is {@code UserSandboxPool}. It no longer hand-rolls the Docker
 * lifecycle — the <em>real</em> lifecycle (create / resume / stop / destroy, isolation-key
 * scoping, state persistence) is delegated to the AgentScope 2.0 framework's
 * {@code io.agentscope.harness.agent.sandbox.SandboxManager}. The pool keeps the application-level
 * concerns the framework does not cover: long-lived holding (browser + agent share one container),
 * idle eviction, invalidate-broadcast on contribution approval, and multi-tenant lifecycle audit.
 */
public interface SandboxPool {

    /**
     * Borrows (creating if necessary) the sandbox for the given user and agent.
     *
     * @param userId  tenant user identifier; must not be null or blank
     * @param agentId stable agent identifier; must not be null or blank
     * @return the live {@link Sandbox} for this (userId, agentId) pair
     */
    Sandbox borrow(String userId, String agentId);

    /**
     * Invalidates (tears down) sandboxes matching the given user and agent.
     *
     * <p>When {@code userId} is {@code null} or blank, <em>all</em> sandboxes for
     * the given {@code agentId} are invalidated; otherwise only the specific
     * (userId, agentId) sandbox is torn down.
     *
     * @param userId  tenant user identifier, or {@code null}/{@code ""} for all users
     * @param agentId stable agent identifier; must not be null or blank
     */
    void invalidate(String userId, String agentId);

    /**
     * Shuts down every pooled sandbox, releasing all container resources.
     * Called on application shutdown.
     */
    void shutdownAll();
}