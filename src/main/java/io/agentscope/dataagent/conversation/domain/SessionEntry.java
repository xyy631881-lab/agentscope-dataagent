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
package io.agentscope.dataagent.conversation.domain;

/**
 * 数据模型（纯数据结构）
 * 每条 SessionEntry 就是一份"会话档案"——记录了谁在什么时候跟哪个 Agent 聊了天、聊到哪了、对话记录存在哪。
 */
public record SessionEntry(
        String sessionKey,       // 会话主键："sess-abc-123"
        String agentId,          // Agent ID："uca-userA-data-analyst"
        String sessionId,        // 会话实例ID："main-uuid-456"（重置后会变）
        String label,            // 用户自定义标签："数据分析会话"
        SessionKind kind,        // 会话类型：MAIN（主会话）或 SUBAGENT（子代理会话）
        String spawnedBy,        // 谁创建的这个会话（父会话key）
        int spawnDepth,          // 嵌套深度（0=顶层）
        long createdAtMs,        // 创建时间
        long lastActivityMs,     // 最后活跃时间
        String sessionFilePath,  // 对话日志文件路径
        String spawnRunId,       // 子代理运行的批次ID
        String gateKey,          // 网关路由键（用于反查）
        String userId            // 所属用户
) {}