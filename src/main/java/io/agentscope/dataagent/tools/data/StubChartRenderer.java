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
package io.agentscope.dataagent.tools.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/**
 * Validates a client-rendered Vega-Lite specification.
 *
 * <p>The chart is rendered by the chat SPA from the original tool input. The server validates the
 * shape and requires inline data so an agent cannot cause the browser to fetch arbitrary URLs.
 */
public final class StubChartRenderer implements ChartRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_TYPES = Set.of("line", "bar", "area", "scatter");

    @Override
    public String render(String chartType, String vegaLiteSpec) {
        if (vegaLiteSpec == null || vegaLiteSpec.isBlank()) {
            return "error: vega-lite spec is empty";
        }
        if (chartType == null || !ALLOWED_TYPES.contains(chartType.trim().toLowerCase())) {
            return "error: chart_type must be one of line, bar, area, scatter";
        }
        try {
            JsonNode spec = MAPPER.readTree(vegaLiteSpec);
            if (!spec.isObject()) {
                return "error: vega-lite spec must be a JSON object";
            }
            if (containsUrl(spec)) {
                return "error: vega-lite spec must use inline data.values, not data.url";
            }
            if (!containsInlineValues(spec)) {
                return "error: vega-lite spec requires inline data.values";
            }
        } catch (Exception e) {
            return "error: invalid vega-lite JSON";
        }
        return "ok: validated Vega-Lite chart (type="
                + chartType
                + "); rendered in the chat response";
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
            if (data != null && data.isObject() && data.get("values") != null && data.get("values").isArray()) {
                return true;
            }
            for (JsonNode child : node) if (containsInlineValues(child)) return true;
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsInlineValues(child)) return true;
        }
        return false;
    }
}
