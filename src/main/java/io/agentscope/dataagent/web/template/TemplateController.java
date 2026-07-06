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
package io.agentscope.dataagent.web.template;

import io.agentscope.dataagent.web.template.TemplateRegistry.TemplateDetail;
import io.agentscope.dataagent.web.template.TemplateRegistry.TemplateSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 浏览内置入门 Agent 模板的只读端点。
 *
 * <ul>
 *   <li>{@code GET /api/templates} — 列出每个可用模板的摘要
 *   <li>{@code GET /api/templates/{id}} — 返回模板的完整文件列表
 * </ul>
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final TemplateRegistry registry;

    public TemplateController(TemplateRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<TemplateSummary> list() {
        return registry.list();
    }

    @GetMapping("/{id}")
    public TemplateDetail get(@PathVariable String id) {
        return registry.get(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Template not found: " + id));
    }
}
