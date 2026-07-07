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
package io.agentscope.dataagent.runtime.config;
import io.agentscope.dataagent.conversation.application.SessionLifecycleScheduler;
import io.agentscope.dataagent.conversation.domain.SessionMaintenanceConfig;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code agentscope.json} 控制 session 生命周期和维护的可选 {@code session} 块。
 *
 * <h2>示例</h2>
 *
 * <pre>{@code
 * "session": {
 *   "reset": { "dailyAt": "04:00", "idleMinutes": 360 },
 *   "maintenance": { "mode": "prune", "pruneAfter": "7d", "maxEntries": 1000 }
 * }
 * }</pre>
 *
 * <p>镜像 OpenClaw 的 session 级别配置。运行时会映射到：
 *
 * <ul>
 *   <li>{@link io.agentscope.dataagent.conversation.domain.SessionMaintenanceConfig} 用于清理/上限策略
 *   <li>触发重置事件的定时任务（{@code SessionLifecycleScheduler}）
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionLifecycleConfig {

    @JsonProperty("reset")
    private ResetConfig reset;

    @JsonProperty("maintenance")
    private MaintenanceConfig maintenance;

    public ResetConfig getReset() {
        return reset;
    }

    public void setReset(ResetConfig reset) {
        this.reset = reset;
    }

    public MaintenanceConfig getMaintenance() {
        return maintenance;
    }

    public void setMaintenance(MaintenanceConfig maintenance) {
        this.maintenance = maintenance;
    }

    // -----------------------------------------------------------------
    //  重置配置（自动重置触发器）
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResetConfig {

        /**
         * 24 小时制时间（{@code "HH:mm"}），所有 session 在此时间每天自动重置。
         * null/空白表示禁用。时间在 JVM 的默认时区中解释。
         */
        @JsonProperty("dailyAt")
        private String dailyAt;

        /**
         * 如果 session 空闲（无活动）达到此分钟数，则在下一个入站消息时被视为可自动重置。
         * {@code 0} 表示禁用。
         */
        @JsonProperty("idleMinutes")
        private Integer idleMinutes;

        public String getDailyAt() {
            return dailyAt;
        }

        public void setDailyAt(String dailyAt) {
            this.dailyAt = dailyAt;
        }

        public Integer getIdleMinutes() {
            return idleMinutes;
        }

        public void setIdleMinutes(Integer idleMinutes) {
            this.idleMinutes = idleMinutes;
        }
    }

    // -----------------------------------------------------------------
    //  维护配置（后台清理/上限）
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MaintenanceConfig {

        /** {@code "off"}（无操作）、{@code "prune"}（基于时间）或 {@code "cap"}（基于条目数）。 */
        @JsonProperty("mode")
        private String mode;

        /**
         * session 被视为过期并被移除前的最大存活时间。接受 {@code "7d"}、{@code "24h"}、
         * {@code "60m"} 等持续时间字符串，或原始的毫秒 long 值。
         */
        @JsonProperty("pruneAfter")
        private String pruneAfter;

        /** Session 条目数的硬上限；超出时淘汰最旧的条目。 */
        @JsonProperty("maxEntries")
        private Integer maxEntries;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getPruneAfter() {
            return pruneAfter;
        }

        public void setPruneAfter(String pruneAfter) {
            this.pruneAfter = pruneAfter;
        }

        public Integer getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(Integer maxEntries) {
            this.maxEntries = maxEntries;
        }

        /** 将 {@code "7d"} 或 {@code "60m"} 等持续时间字符串解析为毫秒。 */
        public long pruneAfterMs() {
            if (pruneAfter == null || pruneAfter.isBlank()) return 0L;
            String s = pruneAfter.trim().toLowerCase();
            try {
                if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2).trim());
                if (s.endsWith("s")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 1_000L;
                }
                if (s.endsWith("m")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 60_000L;
                }
                if (s.endsWith("h")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 3_600_000L;
                }
                if (s.endsWith("d")) {
                    return Long.parseLong(s.substring(0, s.length() - 1).trim()) * 86_400_000L;
                }
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }
}