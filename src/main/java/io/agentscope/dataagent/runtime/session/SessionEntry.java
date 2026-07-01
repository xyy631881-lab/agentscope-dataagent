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
package io.agentscope.dataagent.runtime.session;

/**
 * 已注册的托管 session 的内部元数据（由 {@link SessionAgentManager} 使用）。
 *
 * @param gateKey 网关用于将此 session 映射回 channel 上下文的路由键；
 *     仅对 {@link SessionKind#MAIN} session 填充（可为 null）
 * @param userId 拥有此 session 的可选用户标识；由 HarnessAgent 的
 *     NamespaceFactory 用于每个用户的文件系统隔离（可为 null —— 表示单租户）
 */
public record SessionEntry(
        String sessionKey,
        String agentId,
        String sessionId,
        String label,
        SessionKind kind,
        String spawnedBy,
        int spawnDepth,
        long createdAtMs,
        long lastActivityMs,
        String sessionFilePath,
        String spawnRunId,
        String gateKey,
        String userId) {}
