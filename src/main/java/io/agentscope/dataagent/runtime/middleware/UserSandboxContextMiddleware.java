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
package io.agentscope.dataagent.runtime.middleware;
import io.agentscope.dataagent.workspace.domain.SandboxPool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Injects the current user's Docker sandbox container into the {@link RuntimeContext} before an
 * Agent runs, so the Agent operates inside that user's isolated workspace.
 *
 * <p>Each user has a dedicated sandbox container (their "office") with their own files and tools.
 * The Agent is the clerk that needs to work inside the office; this middleware is the front desk
 * that, before the clerk enters, looks up which user is being served, borrows that user's sandbox
 * from the {@link SandboxPool}, and hands it to the Agent as the {@link SandboxContext}.
 */
public final class UserSandboxContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxContextMiddleware.class);

    private final SandboxPool registry;
    private final String agentId;

    /**
     * @param registry 每个用户的 sandbox 池
     * @param agentId  稳定的 Agent 标识（来自配置文件，如 "data-agent"），
     */
    public UserSandboxContextMiddleware(SandboxPool registry, String agentId) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // 检查：Agent 要运行了，中间件先看看当前运行上下文（RuntimeContext）里有没有已经设置好的沙箱信息。
        // 如果有，就不插手（"上游已经安排好了，我就不操心了"）。
        if (ctx.get(SandboxContext.class) == null) {
            //找用户：从上下文里取出 userId（当前是哪个用户在用）。
            String userId = ctx.getUserId();
            if (userId != null && !userId.isBlank()) {
                try {
                    //借沙箱：从注册表里借一个属于当前用户的沙箱容器。
                    Sandbox sb = registry.borrow(userId, agentId);
                    //创建沙箱容器上下文：把沙箱容器和隔离范围（USER）封装起来，形成一个上下文。
                    SandboxContext sandboxCtx =
                            SandboxContext.builder()
                                    .externalSandbox(sb)
                                    .isolationScope(IsolationScope.USER)
                                    .build();
                    ctx.put(SandboxContext.class, sandboxCtx);
                } catch (RuntimeException e) {
                    log.warn(
                            "[sandbox-mw] 借用 user={}, agent={} 的 sandbox 失败"
                                    + "——Agent 轮次将回退到默认 SandboxContext: {}",
                            userId,
                            agentId,
                            e.getMessage(),
                            e);
                }
            }
        }
        return next.apply(input);
    }
}