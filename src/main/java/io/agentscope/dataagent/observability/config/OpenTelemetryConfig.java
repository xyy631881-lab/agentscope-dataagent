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

/**
 * Trace（追踪链路）：用户发起一个 Agent 任务 → 调用工具 → 调用大模型 → 持久化结果，整条流程就是一条 Trace，拥有唯一 traceId
 * Span（跨度，最核心）：Trace 里的每一段独立操作单元，拥有唯一 spanId，每个 Span 会记录包含操作名称、开始时间、结束耗时、异常信息、标签参数、父 spanId。
 *
 * SpanProcessor、SpanExporter 是干嘛的？
 * 业务代码创建并结束 span.end() → 交给 SpanProcessor
 * BatchSpanProcessor：批量异步处理器
 * 不会每条 Span 立刻入库，先放进内存队列，后台线程定时批量处理。
 * 【设计目的】防止数据库波动阻塞 Agent 业务流程，和代码注释完全对应。
 * JpaTraceSpanExporter：自定义 Exporter
 * Processor 攒够一批 Span 后，调用 Exporter，把 Span 对象转成数据库实体，通过 TraceRunService 存库。
 *
 *
 * 核心职责:
 * 初始化 OpenTelemetry SDK，设置全局 Tracer Provider
 * 配置批量异步导出（BatchSpanProcessor），避免阻塞请求路径
 * 设置导出延迟 500ms、最大队列 2048、每批 128 条
 * traceRuns：运行中的 trace
 *
 * Agent业务代码
 *     ↓
 * Tracer.spanBuilder().startSpan() → 执行业务 → span.end()
 *     ↓
 * Span投递到 BatchSpanProcessor 内存队列
 *     ↓（后台线程，每500ms执行一次）
 * JpaTraceSpanExporter → TraceRunService(JPA) → 数据库存储span记录
 *
 *
 * */
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
