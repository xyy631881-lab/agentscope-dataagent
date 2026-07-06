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
package io.agentscope.dataagent.web.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.web.catalog.AgentCatalogService.AgentDraft;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 辅助的起始 Agent 配置草稿。给定一句话描述，使用低温度提示调用配置的
 * {@link Model} 一次，并将 JSON 响应解析为 {@link AgentDraft}。
 *
 * <p>当没有可用的 {@link Model} bean 时返回 503；{@code GET /api/auth/me} 上的
 * {@code aiAvailable} 标志反映此状态，以便前端可以隐藏或禁用 AI 标签页。
 */
@Service
public class AgentDraftService {

    private static final Logger log = LoggerFactory.getLogger(AgentDraftService.class);
    private static final String PROMPT_RESOURCE = "classpath:prompts/agent-draft.md";
    private static final String FALLBACK_PROMPT =
            "You are an agent designer. Given a one-sentence description, return strict JSON with"
                    + " keys name, description, sysPrompt, suggestedTools (array of strings),"
                    + " suggestedSkills (array of {name,content}), suggestedSubagents (array of"
                    + " {name,content}). User description: {{DESCRIPTION}}. Output JSON only.";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final Model model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String promptTemplate;

    public AgentDraftService(Optional<Model> modelOpt, ResourceLoader resourceLoader) {
        this.model = modelOpt.orElse(null);
        this.promptTemplate = loadPrompt(resourceLoader);
    }

    private static String loadPrompt(ResourceLoader resourceLoader) {
        try {
            Resource res = resourceLoader.getResource(PROMPT_RESOURCE);
            if (!res.exists()) {
                log.warn(
                        "AgentDraftService: 在 {} 未找到提示资源，使用回退提示",
                        PROMPT_RESOURCE);
                return FALLBACK_PROMPT;
            }
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn(
                    "AgentDraftService: 加载提示资源 {} 失败: {}（使用回退）",
                    PROMPT_RESOURCE,
                    e.getMessage());
            return FALLBACK_PROMPT;
        }
    }

    /**
     * 从自由文本描述生成 Agent 配置草稿。错误：
     *
     * <ul>
     *   <li>如果未配置 model 则返回 503，
     *   <li>如果 description 为空则返回 400，
     *   <li>如果 model 返回格式错误的 JSON 或无文本内容则返回 502。
     * </ul>
     */
    public Mono<AgentDraft> draft(String description) {
        if (description == null || description.isBlank()) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "description 是必填项"));
        }
        if (model == null) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "AI 草稿不可用——请配置一个 model"));
        }

        String prompt = promptTemplate.replace("{{DESCRIPTION}}", description.trim());
        Msg userMsg = new UserMessage(TextBlock.builder().text(prompt).build());

        return Mono.fromCallable(() -> callModelBlocking(userMsg))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::parseDraft);
    }

    /**
     * 订阅 model 的流式响应并将每个发出的文本块连接成一个字符串。
     * 阻塞调用方，直到流完成或超时。
     */
    private String callModelBlocking(Msg userMsg) {
        try {
            List<ChatResponse> responses =
                    model.stream(List.of(userMsg), null, null).collectList().block(CALL_TIMEOUT);
            if (responses == null || responses.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Model 未返回响应");
            }
            StringBuilder sb = new StringBuilder();
            for (ChatResponse r : responses) {
                if (r == null || r.getContent() == null) continue;
                for (ContentBlock cb : r.getContent()) {
                    if (cb instanceof TextBlock tb) {
                        String txt = tb.getText();
                        if (txt != null) sb.append(txt);
                    }
                }
            }
            String raw = sb.toString();
            if (raw.isBlank()) {
                // 某些供应商仅在最后一帧发送最终内容；如果每一帧都是增量且我们仍然没有内容，
                // 则抛出 502 以便 UI 可以重试。
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Model 返回空内容");
            }
            return raw;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Model 调用失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    e);
        }
    }

    /**
     * 宽松的 JSON 解析：去除 ```json 围栏以及任何前导/尾随文本，
     * 然后反序列化为 {@link AgentDraft}。失败时抛出 502 并附带原始输出。
     */
    private Mono<AgentDraft> parseDraft(String raw) {
        String stripped = stripCodeFence(raw).trim();
        // 如果 model 包含前后文本，尝试找到最外层的 JSON 对象。
        int firstBrace = stripped.indexOf('{');
        int lastBrace = stripped.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            stripped = stripped.substring(firstBrace, lastBrace + 1);
        }
        try {
            AgentDraft draft = mapper.readValue(stripped, AgentDraft.class);
            return Mono.just(draft);
        } catch (Exception e) {
            log.warn("AgentDraftService: 解析 model 输出失败: {}", e.getMessage());
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Model 返回了非 JSON 输出: "
                                    + (raw.length() > 500 ? raw.substring(0, 500) + "..." : raw)));
        }
    }

    private static String stripCodeFence(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int closing = trimmed.lastIndexOf("```");
            if (closing >= 0) {
                trimmed = trimmed.substring(0, closing);
            }
        }
        return trimmed;
    }
}
