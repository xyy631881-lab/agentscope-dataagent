package io.agentscope.dataagent.workspace.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.agent.application.WorkspaceResolutionService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Writes successful chart tool calls into the agent's managed workspace. */
@Service
public class WorkspaceArtifactService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceArtifactService.class);
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private final WorkspaceResolutionService workspaceResolutionService;
    private final ObjectMapper objectMapper;

    public WorkspaceArtifactService(
            WorkspaceResolutionService workspaceResolutionService, ObjectMapper objectMapper) {
        this.workspaceResolutionService = workspaceResolutionService;
        this.objectMapper = objectMapper;
    }

    public void persistCharts(String userId, String agentId, List<ChartArtifactRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        WorkspaceResolutionService.ResolvedWorkspace workspace;
        try {
            workspace = workspaceResolutionService.resolve(userId, agentId);
        } catch (Exception e) {
            log.warn("[workspace-artifact] unable to resolve workspace for {}/{}: {}", userId, agentId, e.getMessage());
            return;
        }
        RuntimeContext runtimeContext = RuntimeContext.builder().userId(workspace.ownerId()).build();
        for (ChartArtifactRequest request : requests) {
            try {
                StoredChart chart = parse(request);
                if (chart == null) {
                    continue;
                }
                ObjectNode document = objectMapper.createObjectNode();
                document.put("kind", "vega-lite-chart");
                document.put("chartType", chart.chartType());
                document.put("createdAt", Instant.now().toString());
                document.put("sourceTool", "render_chart");
                document.set("spec", chart.spec());

                String callId = request.toolCallId() == null ? "chart" : request.toolCallId();
                String filename = FILE_TIME.format(Instant.now()) + "-" + safeSegment(callId) + ".vl.json";
                workspace.manager().writeUtf8WorkspaceRelative(
                        runtimeContext,
                        "artifacts/charts/" + filename,
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
                log.info("[workspace-artifact] saved chart {} for user={}, agent={}", filename, userId, agentId);
            } catch (Exception e) {
                log.warn("[workspace-artifact] chart artifact save failed for {}/{}: {}", userId, agentId, e.getMessage());
            }
        }
    }

    private StoredChart parse(ChartArtifactRequest request) {
        if (request == null || request.toolInput() == null || request.toolInput().isBlank()) {
            return null;
        }
        try {
            JsonNode call = objectMapper.readTree(request.toolInput());
            JsonNode specRaw = call.path("vega_lite_spec");
            if (specRaw.isMissingNode()) {
                specRaw = call.path("vegaLiteSpec");
            }
            JsonNode spec = specRaw.isTextual() ? objectMapper.readTree(specRaw.asText()) : specRaw;
            if (!spec.isObject() || containsUrl(spec) || !containsInlineValues(spec)) {
                return null;
            }
            String chartType = call.path("chart_type").asText("chart");
            return new StoredChart(chartType, spec);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean containsUrl(JsonNode node) {
        if (node.isObject()) {
            if (node.has("url")) return true;
            for (JsonNode child : node) if (containsUrl(child)) return true;
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsUrl(child)) return true;
        }
        return false;
    }

    private static boolean containsInlineValues(JsonNode node) {
        if (node.isObject()) {
            JsonNode data = node.get("data");
            if (data != null && data.isObject() && data.path("values").isArray()) return true;
            for (JsonNode child : node) if (containsInlineValues(child)) return true;
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsInlineValues(child)) return true;
        }
        return false;
    }

    private static String safeSegment(String raw) {
        String safe = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isBlank() ? "chart" : safe;
    }

    public record ChartArtifactRequest(String toolCallId, String toolInput) {}

    private record StoredChart(String chartType, JsonNode spec) {}
}
