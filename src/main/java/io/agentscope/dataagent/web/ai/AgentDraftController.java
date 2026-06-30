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
package io.agentscope.dataagent.web.ai;

import io.agentscope.dataagent.web.catalog.AgentCatalogService.AgentDraft;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * AI 辅助的 Agent 草稿端点。
 *
 * <ul>
 *   <li>{@code POST /api/agents/draft} — body {@code {description}}；返回一个填充好的
 *       {@link AgentDraft}，UI 可以使用它来预填充创建表单。没有持久的副作用。
 * </ul>
 *
 * <p>当没有配置 {@link io.agentscope.core.model.Model} bean 时返回 503。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentDraftController {

    private final AgentDraftService service;

    public AgentDraftController(AgentDraftService service) {
        this.service = service;
    }

    @PostMapping("/draft")
    public Mono<AgentDraft> draft(@RequestBody DraftRequest req, Authentication auth) {
        if (req == null || req.description() == null || req.description().isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "description 是必填项"));
        }
        return service.draft(req.description());
    }

    public record DraftRequest(String description) {}
}
