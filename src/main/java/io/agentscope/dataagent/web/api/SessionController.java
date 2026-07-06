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
package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.conversation.ConversationService;
import io.agentscope.dataagent.conversation.ConversationService.InboxEntry;
import io.agentscope.dataagent.conversation.ConversationService.ReadStateResult;
import io.agentscope.dataagent.conversation.ConversationService.ResetResult;
import io.agentscope.dataagent.web.session.SessionTurnParser.TurnEntry;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理端点（薄 Controller）。
 *
 * <p>三层架构的表现层：只管 HTTP 收发，所有业务逻辑委托给 {@link ConversationService}。
 *
 * <pre>
 * GET    /api/agents/{agentId}/sessions/inbox   → 收件箱列表
 * GET    /api/agents/{agentId}/sessions/{key}   → 对话轮次
 * POST   /api/agents/{agentId}/sessions/{key}/reset → 重置会话
 * PATCH  /api/agents/{agentId}/sessions/{key}/read  → 标记已读
 * DELETE /api/agents/{agentId}/sessions/{key}   → 删除会话
 * </pre>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private final ConversationService conversationService;

    public SessionController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/inbox")
    public List<InboxEntry> inbox(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return conversationService.inbox(userId, agentId, limit, unreadOnly);
    }

    @GetMapping("/{key}")
    public List<TurnEntry> turns(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return conversationService.getTurns(agentId, key, userId);
    }

    @PostMapping("/{key}/reset")
    public ResetResult reset(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return conversationService.reset(agentId, key, userId);
    }

    @PatchMapping("/{key}/read")
    public ReadStateResult markRead(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return conversationService.markRead(userId, agentId, key);
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String agentId, @PathVariable String key, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        conversationService.deleteSession(agentId, key, userId);
    }
}
