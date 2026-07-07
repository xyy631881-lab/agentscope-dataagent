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
package io.agentscope.dataagent.agent.api.dto;

import java.util.List;

/**
 * Optional AI-generated draft attached to a creation request. Carries the suggested
 * configuration plus optional skill/subagent files to scaffold into the new agent's workspace.
 */
public record AgentDraft(
        String name,
        String description,
        String sysPrompt,
        List<String> suggestedTools,
        List<NamedFile> suggestedSkills,
        List<NamedFile> suggestedSubagents) {}
