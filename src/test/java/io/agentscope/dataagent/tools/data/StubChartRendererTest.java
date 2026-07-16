package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StubChartRendererTest {

    private final StubChartRenderer renderer = new StubChartRenderer();

    @Test
    void acceptsInlineVegaLiteData() {
        String result = renderer.render(
                "line",
                """
                {"mark":"line","data":{"values":[{"day":"2024-01-01","sales":12}]},
                 "encoding":{"x":{"field":"day","type":"temporal"},
                 "y":{"field":"sales","type":"quantitative"}}}
                """);

        assertThat(result).startsWith("ok: validated Vega-Lite chart");
    }

    @Test
    void rejectsRemoteDataUrls() {
        String result = renderer.render(
                "bar",
                "{" + "\"data\":{\"url\":\"https://example.test/data.csv\"}" + "}");

        assertThat(result).contains("data.values");
    }

    @Test
    void rejectsChartsWithoutInlineValues() {
        String result = renderer.render("area", "{\"mark\":\"area\"}");

        assertThat(result).contains("data.values");
    }

    @Test
    void rejectsUnsupportedChartTypes() {
        String result = renderer.render("pie", "{\"data\":{\"values\":[]}}");

        assertThat(result).contains("chart_type");
    }
}
