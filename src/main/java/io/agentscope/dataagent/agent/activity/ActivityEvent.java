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
package io.agentscope.dataagent.agent.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Agent 每个命名空间活动日志中的单个条目。
 *
 * <p>以追加方式存储，每个 JSON 对象一行，位于 Agent 命名空间的 workspace 中的
 * {@code activity.jsonl} 文件中（因此隔离规则自动适用）。参见 plan §1.6 / §G。
 *
 * @param id 稳定标识符（ULID 或随机十六进制）
 * @param timestampMs 事件记录时的纪元毫秒
 * @param actorUserId 执行操作的参与者 userId
 * @param actorUsername 解析的显示用户名，如果在记录时未知则为 {@code null}
 * @param action 高级别事件类别——参见 {@link Action} 获取规范集合
 * @param target 可选的事件特定目标（文件路径、channel ID、sessionKey、granteeId 等）
 * @param metadata 可选的结构化详情；序列化为 JSON 对象
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityEvent(
        String id,
        long timestampMs,
        String actorUserId,
        String actorUsername,
        String action,
        String target,
        Map<String, Object> metadata) {

    /** 规范动作标签。可以添加新的；读取方应容忍未知值。 */
    public static final class Action {
        public static final String CREATE = "CREATE";
        public static final String EDIT_SETTINGS = "EDIT_SETTINGS";
        public static final String DELETE_AGENT = "DELETE_AGENT";
        public static final String EDIT_FILE = "EDIT_FILE";
        public static final String CREATE_FILE = "CREATE_FILE";
        public static final String DELETE_FILE = "DELETE_FILE";
        public static final String RENAME_FILE = "RENAME_FILE";
        public static final String UPLOAD_FILE = "UPLOAD_FILE";
        public static final String GRANT_SHARE = "GRANT_SHARE";
        public static final String REVOKE_SHARE = "REVOKE_SHARE";
        public static final String CLONE_FROM = "CLONE_FROM";
        public static final String CLONE_TO = "CLONE_TO";
        public static final String BIND_CHANNEL = "BIND_CHANNEL";
        public static final String UNBIND_CHANNEL = "UNBIND_CHANNEL";
        public static final String EDIT_BINDING = "EDIT_BINDING";
        public static final String RUN_SESSION = "RUN_SESSION";
        // ---- 贡献 & 市场生命周期 ----
        /** 用户提交贡献（skill/subagent/memory/agents_md/knowledge/mcp_server）。 */
        public static final String CONTRIBUTE = "CONTRIBUTE";
        /** 管理员审批通过贡献。 */
        public static final String APPROVE_CONTRIBUTION = "APPROVE_CONTRIBUTION";
        /** 管理员驳回贡献。 */
        public static final String REJECT_CONTRIBUTION = "REJECT_CONTRIBUTION";
        /** 用户从市场或仓库安装技能。 */
        public static final String INSTALL_SKILL = "INSTALL_SKILL";
        /** 用户注册市场（git/nacos）。 */
        public static final String MARKETPLACE_CREATE = "MARKETPLACE_CREATE";
        /** 用户删除市场。 */
        public static final String MARKETPLACE_DELETE = "MARKETPLACE_DELETE";

        private Action() {}
    }
}
