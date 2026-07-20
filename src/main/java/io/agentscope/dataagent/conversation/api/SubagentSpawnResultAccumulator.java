/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.dataagent.conversation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Collects the stable spawn header returned by AgentScope's {@code agent_spawn} tool. */
final class SubagentSpawnResultAccumulator {

    private static final String SPAWN_TOOL = "agent_spawn";
    private static final int MAX_CAPTURE_CHARS = 65_536;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, StringBuffer> buffers = new ConcurrentHashMap<>();

    Optional<SpawnResult> accept(AgentEvent event) {
        // A forwarded child event belongs to that child's tree, not the current root session.
        if (event.getSource() != null && !event.getSource().isBlank()) {
            return Optional.empty();
        }
        if (event instanceof ToolResultStartEvent start
                && SPAWN_TOOL.equals(start.getToolCallName())) {
            buffers.put(start.getToolCallId(), new StringBuffer());
            return Optional.empty();
        }
        if (event instanceof ToolResultTextDeltaEvent delta
                && SPAWN_TOOL.equals(delta.getToolCallName())) {
            StringBuffer buffer = buffers.get(delta.getToolCallId());
            if (buffer != null && buffer.length() < MAX_CAPTURE_CHARS && delta.getDelta() != null) {
                int remaining = MAX_CAPTURE_CHARS - buffer.length();
                buffer.append(delta.getDelta(), 0, Math.min(remaining, delta.getDelta().length()));
            }
            return Optional.empty();
        }
        if (event instanceof ToolResultEndEvent end
                && SPAWN_TOOL.equals(end.getToolCallName())) {
            StringBuffer buffer = buffers.remove(end.getToolCallId());
            return buffer == null
                    ? Optional.empty()
                    : parse(buffer.toString(), end.getToolCallId());
        }
        return Optional.empty();
    }

    private Optional<SpawnResult> parse(String payload, String toolCallId) {
        String normalized = decodeJsonString(payload);
        String agentId = headerValue(normalized, "agent_id");
        String sessionId = headerValue(normalized, "session_id");
        if (agentId == null || sessionId == null) {
            // Tool results can arrive as a JSON string split across several delta events.
            normalized = payload.replace("\\r\\n", "\n").replace("\\n", "\n");
            agentId = headerValue(normalized, "agent_id");
            sessionId = headerValue(normalized, "session_id");
        }
        if (agentId == null || sessionId == null) {
            return Optional.empty();
        }
        return Optional.of(new SpawnResult(agentId, sessionId, toolCallId));
    }

    private String decodeJsonString(String payload) {
        if (payload == null || payload.isBlank() || payload.charAt(0) != '"') {
            return payload;
        }
        try {
            return OBJECT_MAPPER.readValue(payload, String.class);
        } catch (Exception ignored) {
            return payload;
        }
    }

    private static String headerValue(String payload, String key) {
        if (payload == null) return null;
        String prefix = key + ":";
        for (String line : payload.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(prefix)) continue;
            String value = trimmed.substring(prefix.length()).trim();
            if (value.endsWith("\"")) value = value.substring(0, value.length() - 1).trim();
            return value.isBlank() ? null : value;
        }
        return null;
    }

    record SpawnResult(String agentId, String sessionId, String toolCallId) {}
}
