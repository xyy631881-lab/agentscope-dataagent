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
 * 过期清理规则（多久清、最多留多少）
 *
 * @param enabled 是否在 session 创建/更新时自动运行维护
 * @param pruneAfterMs 移除此持续时间内未更新的 session（0 = 禁用）
 * @param maxEntries 存储中 session 总数的上限（0 = 无限制）
 */
public record SessionMaintenanceConfig(boolean enabled, long pruneAfterMs, int maxEntries) {

    /** 默认禁用维护。 */
    public static SessionMaintenanceConfig disabled() {
        return new SessionMaintenanceConfig(false, 0, 0);
    }

    /**
     * @param pruneAfterMs 清理早于此毫秒数的 session（例如 7 天 = 604_800_000）
     * @param maxEntries 最多保留的 session 数（淘汰最旧的）；0 = 无限制
     */
    public static SessionMaintenanceConfig enabled(long pruneAfterMs, int maxEntries) {
        return new SessionMaintenanceConfig(true, pruneAfterMs, maxEntries);
    }
}
