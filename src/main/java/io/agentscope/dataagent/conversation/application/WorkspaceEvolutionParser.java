package io.agentscope.dataagent.conversation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extracts workspace-mutating tool calls from an AgentScope JSONL transcript. */
final class WorkspaceEvolutionParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_PREFIX = "[tool_call:";

    private WorkspaceEvolutionParser() {}

    static List<ParsedMutation> parse(String jsonl) {
        List<ParsedMutation> mutations = new ArrayList<>();
        if (jsonl == null || jsonl.isBlank()) return mutations;
        for (String line : jsonl.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                if (!"message".equals(node.path("type").asText())
                        || !"ASSISTANT".equalsIgnoreCase(node.path("role").asText())) continue;
                extractCalls(node.path("content").asText(""),
                        (long) (node.path("timestamp").asDouble(0D) * 1000D),
                        text(node, "toolCallId"), mutations);
            } catch (Exception ignored) {
                // One malformed line must not hide valid mutations from other lines.
            }
        }
        return mutations;
    }

    /** Parses one completed tool call captured by the live chat event stream. */
    static List<ParsedMutation> parseToolCall(
            String toolName, String toolInput, String toolCallId, long timestampMs) {
        MutationKind kind = mutationKind(toolName);
        if (kind == null || toolInput == null || toolInput.isBlank()) {
            return List.of();
        }
        try {
            JsonNode input = MAPPER.readTree(toolInput);
            String path = mutationPath(kind, input);
            if (path == null || path.isBlank()) {
                return List.of();
            }
            return List.of(new ParsedMutation(
                    timestampMs, toolCallId, toolName, path, kind.name(), 0L, contentSize(input)));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void extractCalls(String content, long timestampMs, String toolCallId,
            List<ParsedMutation> mutations) {
        int cursor = 0;
        while (cursor < content.length()) {
            int prefix = content.indexOf(TOOL_PREFIX, cursor);
            if (prefix < 0) return;
            int nameStart = prefix + TOOL_PREFIX.length();
            while (nameStart < content.length() && Character.isWhitespace(content.charAt(nameStart))) nameStart++;
            int openParen = content.indexOf('(', nameStart);
            if (openParen < 0) return;
            String toolName = content.substring(nameStart, openParen).trim();
            int closeParen = findClosingParen(content, openParen);
            if (closeParen < 0) return;
            cursor = closeParen + 1;
            MutationKind kind = mutationKind(toolName);
            if (kind == null) continue;
            JsonNode input;
            try {
                String raw = content.substring(openParen + 1, closeParen).trim();
                input = raw.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
            } catch (Exception ignored) { continue; }
            String path = mutationPath(kind, input);
            if (path == null || path.isBlank()) continue;
            mutations.add(new ParsedMutation(timestampMs, toolCallId, toolName, path,
                    kind.name(), 0L, contentSize(input)));
        }
    }

    private static int findClosingParen(String text, int openParen) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = openParen; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
                continue;
            }
            if (ch == '"') quoted = true;
            else if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static MutationKind mutationKind(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (name.equals("write_file") || name.equals("edit_file") || name.equals("file_write")) {
            return MutationKind.EDIT;
        }
        if (name.equals("create_file") || name.equals("create_directory") || name.equals("mkdir")) return MutationKind.CREATE;
        if (name.equals("delete_file") || name.equals("remove_file") || name.equals("file_delete")) return MutationKind.DELETE;
        if (name.equals("move_file") || name.equals("rename_file") || name.equals("file_move")) return MutationKind.MOVE;
        return null;
    }

    private static String mutationPath(MutationKind kind, JsonNode input) {
        String path = firstText(input, "path", "file_path", "filePath", "filename");
        if (kind != MutationKind.MOVE) return path;
        String from = firstText(input, "from", "source", "source_path", "sourcePath");
        String to = firstText(input, "to", "destination", "target_path", "targetPath");
        if (from != null && to != null) return from + " -> " + to;
        return to != null ? to : (from != null ? from : path);
    }

    private static long contentSize(JsonNode input) {
        for (String field : List.of("content", "data", "text")) {
            JsonNode value = input.path(field);
            if (value.isTextual()) return value.asText().getBytes(StandardCharsets.UTF_8).length;
        }
        return 0L;
    }

    private static String firstText(JsonNode input, String... fields) {
        for (String field : fields) {
            String value = text(input, field);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private enum MutationKind { CREATE, EDIT, DELETE, MOVE }

    record ParsedMutation(long timestampMs, String toolCallId, String toolName, String path,
            String kind, long preSize, long postSize) {}
}
