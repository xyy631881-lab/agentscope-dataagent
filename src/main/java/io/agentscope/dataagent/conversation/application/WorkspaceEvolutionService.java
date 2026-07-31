package io.agentscope.dataagent.conversation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.agent.application.WorkspaceResolutionService;
import io.agentscope.dataagent.conversation.domain.SessionEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/** Builds the admin workspace-evolution view from mirrored session transcripts. */
@Service
public class WorkspaceEvolutionService {
    private final ConversationService conversations;
    private final WorkspaceResolutionService workspaces;
    private final ObjectMapper objectMapper;

    public WorkspaceEvolutionService(
            ConversationService conversations,
            WorkspaceResolutionService workspaces,
            ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.workspaces = workspaces;
        this.objectMapper = objectMapper;
    }

    public List<WorkspaceMutation> forSession(String sessionKey, int requestedLimit) {
        ConversationService.SessionTreeNode tree = conversations.sessionTree(sessionKey).orElse(null);
        if (tree == null) return List.of();
        int limit = Math.max(1, Math.min(requestedLimit, 1000));
        List<WorkspaceMutation> events = new ArrayList<>();
        collect(tree, resolveMirrorRoot(tree.session()), events);
        events.sort(Comparator.comparingLong(WorkspaceMutation::ts));
        Map<String, WorkspaceMutation> unique = new LinkedHashMap<>();
        for (WorkspaceMutation event : events) {
            String key = String.valueOf(event.toolCallId()) + "|" + event.kind() + "|" + event.path();
            unique.putIfAbsent(key, event);
        }
        events = new ArrayList<>(unique.values());
        if (events.size() <= limit) return List.copyOf(events);
        return List.copyOf(events.subList(events.size() - limit, events.size()));
    }

    /**
     * Persists an authoritative workspace mutation when a mutating tool has completed. Session
     * JSONL files use framework-generated names that are not the conversation session ids, so
     * reconstructing this view solely from transcripts loses valid report writes.
     */
    public void recordToolMutation(
            String userId,
            String agentId,
            String sessionKey,
            String toolCallId,
            String toolName,
            String toolInput) {
        if (userId == null || agentId == null || sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        List<WorkspaceEvolutionParser.ParsedMutation> mutations =
                WorkspaceEvolutionParser.parseToolCall(
                        toolName, toolInput, toolCallId, System.currentTimeMillis());
        if (mutations.isEmpty()) {
            return;
        }
        String sessionId = conversations.findByKey(sessionKey).map(SessionEntry::sessionId).orElse(null);
        Path mirrorRoot = resolveMirrorRoot(userId, agentId);
        if (mirrorRoot == null) {
            return;
        }
        Path log = eventLog(mirrorRoot, sessionKey);
        try {
            Files.createDirectories(log.getParent());
            StringBuilder lines = new StringBuilder();
            for (WorkspaceEvolutionParser.ParsedMutation mutation : mutations) {
                WorkspaceMutation event = new WorkspaceMutation(
                        mutation.timestampMs(),
                        sessionKey,
                        agentId,
                        sessionId,
                        mutation.toolCallId(),
                        mutation.toolName(),
                        mutation.path(),
                        mutation.kind(),
                        null,
                        null,
                        mutation.preSize(),
                        mutation.postSize());
                lines.append(objectMapper.writeValueAsString(event)).append('\n');
            }
            Files.writeString(
                    log,
                    lines.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Evolution is observability data; it must never make a successful tool call fail.
        }
    }

    private void collect(
            ConversationService.SessionTreeNode node,
            Path mirrorRoot,
            List<WorkspaceMutation> events) {
        SessionEntry session = node.session();
        events.addAll(readRecordedEvents(session, mirrorRoot));
        for (WorkspaceEvolutionParser.ParsedMutation mutation
                : WorkspaceEvolutionParser.parse(readTranscript(session, mirrorRoot))) {
            events.add(new WorkspaceMutation(
                    mutation.timestampMs(), session.sessionKey(), session.agentId(),
                    session.sessionId(), mutation.toolCallId(), mutation.toolName(), mutation.path(),
                    mutation.kind(), null, null, mutation.preSize(), mutation.postSize()));
        }
        for (ConversationService.SessionTreeNode child : node.children()) {
            collect(child, mirrorRoot, events);
        }
    }

    private Path resolveMirrorRoot(SessionEntry session) {
        SessionEntry candidate = session;
        for (int depth = 0; candidate != null && depth < 16; depth++) {
            try {
                String configured =
                        workspaces.resolve(candidate.userId(), candidate.agentId()).localMirrorPath();
                if (configured != null && !configured.isBlank()) {
                    return Path.of(configured);
                }
            } catch (RuntimeException ignored) {
                // Subagents are not independently configured workspace owners. Fall back to the
                // parent session, whose mirror contains all child-agent transcripts.
            }
            String parentKey = candidate.spawnedBy();
            candidate = parentKey == null || parentKey.isBlank()
                    ? null
                    : conversations.findByKey(parentKey).orElse(null);
        }
        return null;
    }

    private Path resolveMirrorRoot(String userId, String agentId) {
        try {
            String configured = workspaces.resolve(userId, agentId).localMirrorPath();
            return configured == null || configured.isBlank() ? null : Path.of(configured);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<WorkspaceMutation> readRecordedEvents(SessionEntry session, Path mirrorRoot) {
        if (mirrorRoot == null) {
            return List.of();
        }
        Path log = eventLog(mirrorRoot, session.sessionKey());
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        List<WorkspaceMutation> events = new ArrayList<>();
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank())
                    .forEach(
                            line -> {
                                try {
                                    events.add(objectMapper.readValue(line, WorkspaceMutation.class));
                                } catch (Exception ignored) {
                                    // A malformed observability line does not invalidate later events.
                                }
                            });
        } catch (Exception ignored) {
            return List.of();
        }
        return events;
    }

    private static Path eventLog(Path mirrorRoot, String sessionKey) {
        String safeKey = sessionKey.replaceAll("[^A-Za-z0-9._-]", "_");
        return mirrorRoot.resolve(".dataagent-evolution").resolve(safeKey + ".jsonl").normalize();
    }

    private String readTranscript(SessionEntry session, Path mirrorRoot) {
        String direct = readIfRegular(safePath(session.sessionFilePath()));
        if (!direct.isEmpty() || mirrorRoot == null) return direct;
        Path agentsRoot = mirrorRoot.resolve("agents").normalize();
        if (!agentsRoot.startsWith(mirrorRoot.normalize()) || !Files.isDirectory(agentsRoot)) {
            return "";
        }
        String logName = session.sessionId() + ".log.jsonl";
        String contextName = session.sessionId() + ".jsonl";
        try (Stream<Path> paths = Files.walk(agentsRoot, 4)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return logName.equals(name) || contextName.equals(name);
                    })
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString().equals(logName) ? 0 : 1))
                    .map(this::readIfRegular)
                    .filter(content -> !content.isEmpty())
                    .findFirst()
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Path safePath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String readIfRegular(Path path) {
        if (path == null || !Files.isRegularFile(path)) return "";
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public record WorkspaceMutation(
            long ts,
            String sessionKey,
            String agentId,
            String sessionId,
            String toolCallId,
            String toolName,
            String path,
            String kind,
            String preHash,
            String postHash,
            long preSize,
            long postSize) {}
}
