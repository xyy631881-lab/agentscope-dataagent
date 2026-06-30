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
package io.agentscope.dataagent.runtime.outbound;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 由 Agent 发起/外部出站消息的 HTTP 入口。
 *
 * <pre>
 * POST /api/outbound/send
 * { "channelId": "dingtalk-prod", "peerKind": "DIRECT", "peerId": "staff_xyz",
 *   "agentId": "support", "markdown": "..." }
 * </pre>
 *
 * <p>DataAgent v1 未实现每个 Agent 的 ACL 层接口；全局的 {@code SecurityConfig} 拥有
 * 此端点的认证/角色强制，而 {@link OutboundService} 执行基于 channel 路由的检查，
 * 防止一个 Agent 通过属于另一个 Agent 的绑定发送消息。
 */
@RestController
@RequestMapping("/api/outbound")
public class OutboundController {

    private final OutboundService outboundService;

    public OutboundController(DataAgentBootstrap bootstrap) {
        this.outboundService = new OutboundService(bootstrap.channelManager());
    }

    @PostMapping("/send")
    public Mono<ResponseEntity<Map<String, Object>>> send(@RequestBody OutboundRequest req) {
        return Mono.fromRunnable(() -> outboundService.send(req))
                .thenReturn(ResponseEntity.ok(Map.<String, Object>of("status", "ok")))
                .onErrorResume(
                        ResponseStatusException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(e.getStatusCode())
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getReason() == null
                                                                        ? e.getMessage()
                                                                        : e.getReason()))))
                .onErrorResume(
                        IllegalArgumentException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getMessage()))))
                .onErrorResume(
                        IllegalStateException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getMessage()))));
    }
}
