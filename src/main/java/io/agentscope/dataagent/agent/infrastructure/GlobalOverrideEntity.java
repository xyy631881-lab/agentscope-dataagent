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
package io.agentscope.dataagent.agent.infrastructure;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent representation of an administrator's editable override for a single global (bootstrap-
 * registered) agent. Keyed directly by the global agent id (the primary key), since there is at
 * most one override row per global agent.
 *
 * <p>List-shaped settings (tools allow/deny, skills allow/deny, group-chat mention patterns) are
 * stored as JSON strings, mirroring {@link AgentEntity} so the two stores share the same column
 * conventions.
 */
@Entity
@Table(name = "dataagent_global_override")
public class GlobalOverrideEntity {

    @Id
    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    @Column(name = "name", length = 200)
    private String name;

    @Lob
    @Column(name = "description")
    private String description;

    @Lob
    @Column(name = "sys_prompt")
    private String sysPrompt;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "max_iters")
    private Integer maxIters;

    @Lob
    @Column(name = "tools_allow_json")
    private String toolsAllowJson;

    @Lob
    @Column(name = "tools_deny_json")
    private String toolsDenyJson;

    @Column(name = "identity_name", length = 200)
    private String identityName;

    @Column(name = "identity_emoji", length = 32)
    private String identityEmoji;

    @Lob
    @Column(name = "group_chat_mention_patterns_json")
    private String groupChatMentionPatternsJson;

    @Column(name = "group_chat_require_mention")
    private Boolean groupChatRequireMention;

    @Lob
    @Column(name = "skills_allow_json")
    private String skillsAllowJson;

    @Lob
    @Column(name = "skills_deny_json")
    private String skillsDenyJson;

    @Column(name = "run_as", length = 20)
    private String runAs;

    @Column(name = "sandbox_mode", length = 16)
    private String sandboxMode;

    @Column(name = "sandbox_scope", length = 16)
    private String sandboxScope;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "updated_at")
    private long updatedAt;

    @OneToMany(
            mappedBy = "override",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<GlobalOverrideShareEntity> shares = new ArrayList<>();

    public GlobalOverrideEntity() {}

    // ----- accessors -----

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public String getToolsAllowJson() {
        return toolsAllowJson;
    }

    public void setToolsAllowJson(String toolsAllowJson) {
        this.toolsAllowJson = toolsAllowJson;
    }

    public String getToolsDenyJson() {
        return toolsDenyJson;
    }

    public void setToolsDenyJson(String toolsDenyJson) {
        this.toolsDenyJson = toolsDenyJson;
    }

    public String getIdentityName() {
        return identityName;
    }

    public void setIdentityName(String identityName) {
        this.identityName = identityName;
    }

    public String getIdentityEmoji() {
        return identityEmoji;
    }

    public void setIdentityEmoji(String identityEmoji) {
        this.identityEmoji = identityEmoji;
    }

    public String getGroupChatMentionPatternsJson() {
        return groupChatMentionPatternsJson;
    }

    public void setGroupChatMentionPatternsJson(String groupChatMentionPatternsJson) {
        this.groupChatMentionPatternsJson = groupChatMentionPatternsJson;
    }

    public Boolean getGroupChatRequireMention() {
        return groupChatRequireMention;
    }

    public void setGroupChatRequireMention(Boolean groupChatRequireMention) {
        this.groupChatRequireMention = groupChatRequireMention;
    }

    public String getSkillsAllowJson() {
        return skillsAllowJson;
    }

    public void setSkillsAllowJson(String skillsAllowJson) {
        this.skillsAllowJson = skillsAllowJson;
    }

    public String getSkillsDenyJson() {
        return skillsDenyJson;
    }

    public void setSkillsDenyJson(String skillsDenyJson) {
        this.skillsDenyJson = skillsDenyJson;
    }

    public String getRunAs() {
        return runAs;
    }

    public void setRunAs(String runAs) {
        this.runAs = runAs;
    }

    public String getSandboxMode() {
        return sandboxMode;
    }

    public void setSandboxMode(String sandboxMode) {
        this.sandboxMode = sandboxMode;
    }

    public String getSandboxScope() {
        return sandboxScope;
    }

    public void setSandboxScope(String sandboxScope) {
        this.sandboxScope = sandboxScope;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<GlobalOverrideShareEntity> getShares() {
        return shares;
    }

    public void setShares(List<GlobalOverrideShareEntity> shares) {
        this.shares = shares;
    }
}
