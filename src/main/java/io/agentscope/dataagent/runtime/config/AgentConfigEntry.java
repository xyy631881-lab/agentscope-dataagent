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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code agentscope.json} 中 {@code agents.<agentId>} 下每个 Agent 的配置节。
 *
 * <p>Agent 构建完成后，{@link HarnessAgent} 会自动从解析的 {@link #workspace} 目录
 * 加载额外的 workspace 作用域配置（例如 {@code subagents/*.md}）。
 *
 * <p>字段镜像 OpenClaw 的 Agent 定义模式：
 *
 * <ul>
 *   <li>{@link #model} — 覆盖模型 ID（例如 {@code "anthropic/claude-opus-4-7"}）
 *   <li>{@link #tools} — 内置工具的允许/拒绝列表
 *   <li>{@link #identity} — 显示名称和表情符号覆盖
 *   <li>{@link #groupChat} — 群组/房间 channel 的提及门控
 *   <li>{@link #skills} — Skill 的白名单/黑名单
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfigEntry {

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("sysPrompt")
    private String sysPrompt;

    /**
     * 此 Agent 的 workspace 根目录。相对路径基于 bootstrap 的工作目录解析。
     */
    @JsonProperty("workspace")
    private String workspace;

    @JsonProperty("maxIters")
    private Integer maxIters;

    @JsonProperty("environmentMemory")
    private String environmentMemory;

    /**
     * 遗留的单一值 Skill 仓库。保留以支持旧 {@code agentscope.json} 文件的向后兼容反序列化；
     * 如果存在，在物化有效列表时将其折叠到 {@link #skillRepositories} 头部。
     *
     * @deprecated 推荐使用 {@link #skillRepositories}，它支持工作区 skills +
     *     市场安装 skills + 内置的分层模式。
     */
    @Deprecated
    @JsonProperty("skillRepository")
    private SkillRepositoryConfigEntry skillRepository;

    /**
     * 分层 Skill 仓库。每个条目按顺序追加到 Agent 的有效
     * 因此较早的条目在 Skill 名称冲突时优先。{@code workspace/skills/} 覆盖层是隐式的，
     * 由 {@link io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory} 自动添加——
     * 请勿在此列出。
     */
    @JsonProperty("skillRepositories")
    private List<SkillRepositoryConfigEntry> skillRepositories;

    /**
     * 可选的模型 ID 覆盖（例如 {@code "anthropic/claude-opus-4-7"}）。为空时使用
     * bootstrap 级别的模型。
     */
    @JsonProperty("model")
    private String model;

    /**
     * 工具的允许/拒绝列表。只有名称在 {@code allow} 中的工具才会提供给 Agent
     * （当非空时）。{@code deny} 中的工具无论 {@code allow} 如何都会被移除。
     */
    @JsonProperty("tools")
    private ToolsConfig tools;

    /** 显示身份覆盖（名称、表情符号）。 */
    @JsonProperty("identity")
    private IdentityConfig identity;

    /** 群聊门控配置（提及模式、requireMention）。 */
    @JsonProperty("groupChat")
    private GroupChatConfig groupChat;

    /** Skill 的允许/拒绝列表。 */
    @JsonProperty("skills")
    private SkillsConfig skills;

    // -----------------------------------------------------------------
    //  Getter / Setter
    // -----------------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public String getEnvironmentMemory() {
        return environmentMemory;
    }

    public void setEnvironmentMemory(String environmentMemory) {
        this.environmentMemory = environmentMemory;
    }

    @Deprecated
    public SkillRepositoryConfigEntry getSkillRepository() {
        return skillRepository;
    }

    @Deprecated
    public void setSkillRepository(SkillRepositoryConfigEntry skillRepository) {
        this.skillRepository = skillRepository;
    }

    public List<SkillRepositoryConfigEntry> getSkillRepositories() {
        return skillRepositories;
    }

    public void setSkillRepositories(List<SkillRepositoryConfigEntry> skillRepositories) {
        this.skillRepositories = skillRepositories;
    }

    /**
     * 返回有效的有序 Skill 仓库条目列表，将遗留的 {@link #skillRepository} 值（如果有）
     * 折叠到头部，以便旧配置继续正常加载。不会为 null；可能为空。
     */
    public List<SkillRepositoryConfigEntry> effectiveSkillRepositories() {
        List<SkillRepositoryConfigEntry> out = new ArrayList<>();
        if (skillRepository != null) {
            out.add(skillRepository);
        }
        if (skillRepositories != null) {
            for (SkillRepositoryConfigEntry e : skillRepositories) {
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public ToolsConfig getTools() {
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    public IdentityConfig getIdentity() {
        return identity;
    }

    public void setIdentity(IdentityConfig identity) {
        this.identity = identity;
    }

    public GroupChatConfig getGroupChat() {
        return groupChat;
    }

    public void setGroupChat(GroupChatConfig groupChat) {
        this.groupChat = groupChat;
    }

    public SkillsConfig getSkills() {
        return skills;
    }

    public void setSkills(SkillsConfig skills) {
        this.skills = skills;
    }

    // -----------------------------------------------------------------
    //  嵌套配置类型
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolsConfig {

        /** 当非空时，只有此列表中的工具才会提供给 Agent。 */
        @JsonProperty("allow")
        private List<String> allow;

        /** 此列表中的工具始终被移除，即使出现在 {@code allow} 中也是如此。 */
        @JsonProperty("deny")
        private List<String> deny;

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IdentityConfig {

        /** 显示名称覆盖（在聊天 UI 和日志中显示）。 */
        @JsonProperty("name")
        private String name;

        /** 快速视觉识别的表情符号简写。 */
        @JsonProperty("emoji")
        private String emoji;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmoji() {
            return emoji;
        }

        public void setEmoji(String emoji) {
            this.emoji = emoji;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GroupChatConfig {

        /**
         * 触发 Agent 在群组消息中响应的模式列表（精确字符串或前缀）。
         * 为空时 Agent 响应群组中的所有消息。
         */
        @JsonProperty("mentionPatterns")
        private List<String> mentionPatterns;

        /**
         * 当 {@code true} 时，Agent 仅在提及模式匹配时才响应。
         * 默认为 {@code false}（响应所有消息）。
         */
        @JsonProperty("requireMention")
        private Boolean requireMention;

        public List<String> getMentionPatterns() {
            return mentionPatterns;
        }

        public void setMentionPatterns(List<String> mentionPatterns) {
            this.mentionPatterns = mentionPatterns;
        }

        public Boolean getRequireMention() {
            return requireMention;
        }

        public void setRequireMention(Boolean requireMention) {
            this.requireMention = requireMention;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillsConfig {

        /** 当非空时，只有此列表中的 Skill 才会为 Agent 加载。 */
        @JsonProperty("allow")
        private List<String> allow;

        /** 此列表中的 Skill 永远不会加载，即使出现在 {@code allow} 中也是如此。 */
        @JsonProperty("deny")
        private List<String> deny;

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny;
        }
    }
}
