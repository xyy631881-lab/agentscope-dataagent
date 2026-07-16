package io.agentscope.dataagent.observability.config;

import io.agentscope.dataagent.observability.application.TraceRunService;
import io.agentscope.dataagent.observability.infrastructure.JpaTraceSpanExporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the SDK behind AgentScope's OtelTracingMiddleware. */
@Configuration
public class OpenTelemetryConfig {

    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk dataAgentOpenTelemetry(TraceRunService traceRuns) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault())
                // Export outside the request path. A temporary database slowdown must not add a
                // write round-trip to every model/tool span.
                .addSpanProcessor(
                        BatchSpanProcessor.builder(new JpaTraceSpanExporter(traceRuns))
                                .setScheduleDelay(500, TimeUnit.MILLISECONDS)
                                .setMaxQueueSize(2048)
                                .setMaxExportBatchSize(128)
                                .build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        GlobalOpenTelemetry.set(sdk);
        return sdk;
    }

    @Bean
    public OpenTelemetry openTelemetry(OpenTelemetrySdk dataAgentOpenTelemetry) {
        return dataAgentOpenTelemetry;
    }
}
