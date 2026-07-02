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
package io.agentscope.dataagent.web.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 这个类是一个日志翻译器——把 Agent 框架产生的原始 JSONL 日志文件，
 * 翻译成前端能直接使用的结构化对话轮次列表。
 */
public final class SessionTurnParser {

    private static final Logger log = LoggerFactory.getLogger(SessionTurnParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionTurnParser() {}

    /**
     * 从一堆会议记录里，只挑出"谁说了什么"的发言记录，跳过"会议开始""会议暂停"这种状态标记。
     * 为什么只处理 type=message？ 前端只需要对话内容，不需要内部状态信息，所以用 type 过滤。
     */
    public static List<TurnEntry> parse(String jsonl) {
        List<TurnEntry> turns = new ArrayList<>();
        // ① 空内容直接返回空列表
        if (jsonl == null || jsonl.isBlank()) return turns;
        // ② 按换行符拆分，逐行解析 JSON 对象
        for (String line : jsonl.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                // ③ 只处理 type=message 的行，跳过 type=status 等其他行
                String type = text(node, "type");
                if (!"message".equals(type)) continue;

                // ④ 提取基本字段
                String id = text(node, "id");
                String parentId = text(node, "parentId");
                String role = text(node, "role");
                String content = text(node, "content");

                // ⑤ 时间戳转换：秒级浮点 → 毫秒级整数
                double ts = node.path("timestamp").asDouble(0);
                long timestampMs = (long) (ts * 1000);

                // ⑥ 提取工具调用相关字段
                String toolName = text(node, "toolName");
                String toolInput = node.has("toolInput") ? node.get("toolInput").toString() : null;
                String toolResult =
                        node.has("toolResult") ? node.get("toolResult").toString() : null;

                // ⑦ 构造 TurnEntry 加入列表
                turns.add(
                        new TurnEntry(
                                id,
                                parentId,
                                role,
                                content,
                                timestampMs,
                                toolName,
                                toolInput,
                                toolResult));
            } catch (Exception e) {
                // ⑧ 解析失败的行直接跳过，不打断整个解析
                log.debug("Skipping unparseable session line: {}", e.getMessage());
            }
        }
        return turns;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /**
     * A single parsed entry from a session JSONL transcript.
     *
     * @param id entry id
     * @param parentId parent entry id (for threading)
     * @param role {@code USER}, {@code ASSISTANT}, or {@code TOOL}
     * @param content text content of the message (null for pure tool-call entries)
     * @param timestampMs epoch milliseconds
     * @param toolName name of the tool invoked (null for non-tool entries)
     * @param toolInput JSON string of the tool invocation arguments (null if not applicable)
     * @param toolResult JSON string of the tool result (null if not applicable)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TurnEntry(
            String id,           // 消息ID："m1"
            String parentId,     // 父消息ID："m1"（用于对话树结构）
            String role,         // 角色："USER" / "ASSISTANT" / "TOOL"
            String content,      // 文字内容："帮我查一下数据库里有多少用户"
            long timestampMs,    // 时间戳（毫秒）：1719849500123
            String toolName,     // 工具名："sql_query"（非工具消息为 null）
            String toolInput,    // 工具输入："{\"sql\":\"SELECT...\"}"（非工具消息为 null）
            String toolResult    // 工具结果："{\"count\":100}"（非工具消息为 null）
    ) {}
}
