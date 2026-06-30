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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 在每次 Agent 调用前，从 {@link UserSandboxRegistry} 借用 {@code (userId, agentId)}
 * 对应的 Docker {@link Sandbox}，并将其作为 {@link SandboxContext#getExternalSandbox()}
 * 注入到 {@link RuntimeContext}，以便 {@code SandboxManager.acquire} 走 Priority-1 路径，
 * Agent 在与浏览器 workspace 控制器同读同写的同一个容器中运行。
 *
 * <p>替代了原先在自定义 {@code HarnessGateway.attachUserSandboxContext} 中的逻辑，
 * 使 sandbox 注入从网关层解耦到 middleware 层，便于后续切换到官方网关。
 *
 * <p>当 {@code userId} 缺失或注册表未配置时不做任何操作，Agent 回退到默认 SandboxContext。
 */
public final class UserSandboxContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxContextMiddleware.class);

    private final UserSandboxRegistry registry;
    private final String agentId;

    /**
     * @param registry 每个用户的 sandbox 注册表
     * @param agentId  稳定的 Agent 标识（来自配置文件，如 "data-agent"），
     *                 用于 {@link UserSandboxRegistry#borrow} 的容器 key，
     *                 避免随机 UUID 导致容器累积
     */
    public UserSandboxContextMiddleware(UserSandboxRegistry registry, String agentId) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // 仅在尚未设置 SandboxContext 时注入（允许上游显式覆盖）
        if (ctx.get(SandboxContext.class) == null) {
            String userId = ctx.getUserId();
            if (userId != null && !userId.isBlank()) {
                try {
                    Sandbox sb = registry.borrow(userId, agentId);
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