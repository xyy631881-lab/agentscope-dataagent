# OpenTelemetry 面试准备指南

> 简历点：「接入 OpenTelemetry 构建请求级 Trace，异步聚合 Token、耗时、状态，支持异常定位和模型开销分析」

---

## 第一部分：OpenTelemetry 核心概念（面试必知）

### 1.1 什么是 OpenTelemetry？

OpenTelemetry（简称 OTel）是 **CNCF（云原生计算基金会）** 的观测性标准。它不是产品，而是一套 **规范 + SDK + 采集器**，让你用统一的方式生成和导出 Traces（链路）、Metrics（指标）、Logs（日志）三大信号。

**一句话理解**：以前你想用 Jaeger 看链路，用 Prometheus 看指标，用 ELK 看日志——三个系统，三种接入方式。OpenTelemetry 一统天下：你用同一套 API 产生数据，想发到哪里就发到哪里，换后端不用改代码。

### 1.2 三大核心信号

| 信号 | 是什么 | 回答什么问题 |
|------|--------|-------------|
| **Trace（链路）** | 一次请求的完整调用链 | "这个请求经过了哪些环节，哪里慢了？" |
| **Metric（指标）** | 聚合数值（计数器、直方图等） | "过去 5 分钟有多少请求？P99 延迟是多少？" |
| **Log（日志）** | 离散的事件记录 | "在那个时间点发生了什么？" |

### 1.3 Trace 的核心概念（⭐⭐⭐ 面试重点）

```
一次请求 = 一个 Trace
  └── Span A（根节点，SERVER 类型）       ← 入口层
        ├── Span B（子节点，LLM 调用）    ← 模型层
        │     └── Span C（子节点，Tool 调用） ← 工具层
        └── Span D（子节点，另一个 LLM 调用）
```

- **Trace**：一次完整的请求链路，由唯一 `traceId` 标识
- **Span**：链路中的单个操作单元，有 `spanId` 和 `parentSpanId`
- **Span Context**：通过 HTTP Header（如 `traceparent: 00-{traceId}-{spanId}-01`，W3C 标准）在服务间传递上下文
- **Span Attributes**：键值对元数据，如 `gen_ai.request.model = "gpt-4o"`
- **Span Events**：时间点事件，如异常记录
- **Span Kind**：CLIENT / SERVER / INTERNAL / PRODUCER / CONSUMER
- **StatusCode**：OK / ERROR / UNSET

### 1.4 架构组件

```
应用代码 (API)
  ↓
Tracer Provider → Tracer → Span
  ↓
Span Processor（处理链）
  ├── SimpleSpanProcessor（同步）
  └── BatchSpanProcessor（异步）⭐ 我们用的这个
       ↓
Span Exporter（导出器）
  ├── OTLP Exporter → Jaeger / Grafana / 自建 Collector
  ├── Logging Exporter → 控制台
  └── 自定义 Exporter → 数据库（⭐ 我们的方案）
```

### 1.5 GenAI 语义约定（semantic conventions）

OpenTelemetry 定义了 LLM/AI Agent 领域的标准属性名，以 `gen_ai.*` 为前缀：

| 属性 | 含义 |
|------|------|
| `gen_ai.operation.name` | 操作类型：chat / embeddings / execute_tool |
| `gen_ai.request.model` | 请求的模型名：gpt-4o, ollama:qwen2.5 等 |
| `gen_ai.usage.input_tokens` | 输入 token 数 |
| `gen_ai.usage.output_tokens` | 输出 token 数 |
| `gen_ai.tool.name` | 工具名称 |
| `gen_ai.tool.call.id` | 工具调用 ID |
| `gen_ai.response.finish_reasons` | 模型停止原因：stop / tool_calls / length |

---

## 第二部分：本项目实现详解

### 2.1 整体架构数据流

```
┌─────────────────────────────────────────────────────┐
│  AgentScope Framework (agentscope-harness)           │
│  ┌───────────────────────────────────────────────┐  │
│  │  OtelTracingMiddleware                        │  │
│  │  ├─ Agent 执行 → 子 Span (agent.run)          │  │
│  │  ├─ LLM 调用   → 子 Span (gen_ai.chat)        │  │
│  │  └─ Tool 调用   → 子 Span (gen_ai.execute_tool)│  │
│  └───────────────────────────────────────────────┘  │
└─────────────────┬───────────────────────────────────┘
                  │ Span 产生后回调
                  ▼
┌─────────────────────────────────────────────────────┐
│  应用层 (agentscope-dataagent)                      │
│                                                     │
│  ChatController                                     │
│  ├─ 创建 Root Span (dataagent.chat.run, SERVER)    │
│  ├─ ContextPropagationOperator 传播 Reactor 上下文  │
│  ├─ 3 个 AtomicLong 累加 token (input/output/cached)│
│  └─ doFinally → UsageStore + TraceRunService        │
│                                                     │
│  OpenTelemetryConfig                                │
│  └─ SdkTracerProvider                              │
│       └─ BatchSpanProcessor (500ms/2048/128)       │
│            └─ JpaTraceSpanExporter                  │
│                 └─ TraceRunService.appendSpan()     │
│                      └─ trace_span 表 (11 属性白名单)│
│                                                     │
│  UsageStore (独立于 Trace 的用量账本)               │
│  ├─ 每次请求结束 → 写入 usage_event 表              │
│  ├─ input/output/cached tokens + duration + cost   │
│  └─ 聚合查询：按小时/天/模型/用户/Agent 维度       │
└─────────────────────────────────────────────────────┘
```

### 2.2 核心代码解析

#### 2.2.1 初始化 `OpenTelemetryConfig.java`

```java
@Configuration
public class OpenTelemetryConfig {

    @Bean(destroyMethod = "close")
    public OpenTelemetrySdk dataAgentOpenTelemetry(TraceRunService traceRuns) {
        // 1. 创建 TracerProvider，设置 BatchSpanProcessor
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(
                    BatchSpanProcessor.builder(new JpaTraceSpanExporter(traceRuns))
                        .setScheduleDelay(500, TimeUnit.MILLISECONDS)  // 每 500ms 导出一次
                        .setMaxQueueSize(2048)                         // 队列最多 2048 条
                        .setMaxExportBatchSize(128)                    // 每批最多 128 条
                        .build())
                .build();

        // 2. 构建 SDK 实例
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider).build();

        // 3. 设置为全局实例（任何地方都能获取到 Tracer）
        GlobalOpenTelemetry.set(sdk);
        return sdk;
    }
}
```

**面试可以说的要点**：
- 用了 **BatchSpanProcessor** 而不是 SimpleSpanProcessor，**异步批量导出**，不阻塞请求路径
- 调度延迟 500ms、最大队列 2048、每批 128 条——这是一个**内存与延迟的权衡**
- `GlobalOpenTelemetry.set(sdk)` 注册全局实例，AgentScope 框架的 `OtelTracingMiddleware` 自动通过 `GlobalOpenTelemetry.get()` 获取 Tracer

#### 2.2.2 创建 Root Span `ChatController.java`

```java
private TraceRunService.TraceScope startTrace(
        String userId, String agentId, String conversationId, String effectiveModelId) {
    // 获取 Tracer，创建一个 SERVER 类型的根 Span
    var rootSpan = GlobalOpenTelemetry.getTracer("io.agentscope.dataagent.chat")
            .spanBuilder("dataagent.chat.run")
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("dataagent.agent.id", agentId)
            .setAttribute("dataagent.session.key", conversationId)
            .setAttribute("gen_ai.request.model", effectiveModelId)
            .startSpan();

    // 在数据库中创建 trace_run 记录
    return traceRuns.start(rootSpan, userId, agentId, conversationId, effectiveModelId);
}
```

**面试可以说的要点**：
- **Root Span 在业务入口处手动创建**（不是自动注入的），对整个对话生命周期负责
- SpanKind.SERVER 表示这是一个"接收外部请求"的入口 Span
- 根 Span 打上业务属性：Agent ID、Session Key、Model ID

#### 2.2.3 Reactor 上下文传播

AgentScope 框架基于 **Project Reactor**，Trace 上下文必须跨线程传播：

```java
// 方式1：流式路径
ContextPropagationOperator.runWithContext(events, traceScope.otelContext())

// 方式2：同步路径
ContextPropagationOperator.runWithContext(chatUiChannel.dispatch(inbound), traceScope.otelContext())
```

**面试可以说的要点**：
- Project Reactor 是**响应式编程**，代码会在不同线程上执行
- OpenTelemetry 的 Trace 上下文默认存在 `ThreadLocal` 里，线程切换了就丢了
- `ContextPropagationOperator` 是 **Reactor 专用的上下文传播工具**，把 OTel Context 注入 Reactor Context，确保子 Span 能正确找到父 Span

#### 2.2.4 框架级自动子 Span `AgentRuntimeConfigurer.java`

```java
// 在构建每个 Agent 时注册 OtelTracingMiddleware
b.middleware(new OtelTracingMiddleware());
```

**面试可以说的要点**：
- 这里有一个**分工边界**：
  - **框架（AgentScope）** 负责产生子 Span：每次 Agent 执行、LLM 调用、Tool 调用自动生成对应的 Span
  - **我们的应用** 负责：创建根 Span、自定义 Exporter 持久化到数据库、聚合 Token/成本数据
- `OtelTracingMiddleware` 是 AgentScope 框架内置的 Middleware，类似 Express/Koa 中间件，在 Agent 执行链的每个环节自动创建 Span 并打上 `gen_ai.*` 标准属性

#### 2.2.5 自定义 JPA Span Exporter

```java
public class JpaTraceSpanExporter implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        try {
            spans.forEach(traceRuns::appendSpan);
            return CompletableResultCode.ofSuccess();
        } catch (RuntimeException exception) {
            log.warn("Unable to persist OpenTelemetry spans: {}", exception.getMessage());
            return CompletableResultCode.ofFailure();  // ⭐ 失败也不抛异常
        }
    }
}
```

**面试可以说的要点**：
- 实现了 `SpanExporter` 接口，但没有发到 Jaeger/Zipkin，而是**直接写入 MySQL**
- 为什么？我们这个场景的 Span 量不大，不需要额外的可观测后端，而且 Trace 数据和业务数据（运行记录页）是同源的
- **容错设计**：`ofFailure()` 只是返回失败，不抛异常——数据库挂了不影响 Agent 正常运行

#### 2.2.6 Span 落地 `TraceRunService.java`

```java
// 11 个白名单属性——不是所有属性都存
private static final Set<String> EXPORTED_ATTRIBUTES = Set.of(
    "gen_ai.operation.name",      // 操作类型
    "gen_ai.request.model",        // 模型名
    "gen_ai.request.messages.count", // 消息数
    "gen_ai.request.tools.count",  // 工具数
    "gen_ai.usage.input_tokens",   // 输入 Token ⭐
    "gen_ai.usage.output_tokens",  // 输出 Token ⭐
    "gen_ai.tool.name",            // 工具名
    "gen_ai.tool.call.count",      // 工具调用次数
    "gen_ai.tool.call.id",         // 工具调用 ID
    "agentscope.agent.reply_id",   // Agent 回复 ID
    "error.type");                 // 错误类型

public void appendSpan(SpanData span) {
    // 去重
    if (span.getSpanContext().getTraceId().isBlank() || spans.existsBySpanId(span.getSpanId())) return;
    // 白名单过滤
    Map<String, Object> attributes = new LinkedHashMap<>();
    span.getAttributes().asMap().forEach((key, value) -> {
        if (EXPORTED_ATTRIBUTES.contains(key.getKey())) attributes.put(key.getKey(), value);
    });
    // 计算耗时（纳秒 → 毫秒）
    long durationMs = Math.max(0L, (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000L);
    // 持久化
    spans.save(new TraceSpanEntity(...));
}
```

**面试可以说的要点**：
- **11 属性白名单机制**：不是 Span 上的所有属性都存，而是只存对业务分析有价值的属性
- 这样做的好处：**控制存储成本**、**保护隐私**（不存 prompt 内容）、**数据干净**
- Span 持续时间用 `Nanos` 计算，保证精度，存的时候转成 `ms`

#### 2.2.7 Token 聚合与成本分析 `UsageStore.java`

```java
public record UsageEvent(
    String tenantId, String userId, String agentId,
    String sessionKey, String modelId,
    long inputTokens,        // 输入 Token
    long outputTokens,       // 输出 Token
    long cachedPromptTokens, // 缓存命中 Token（AgentScope 2.0 的 prompt caching）
    long durationMs,         // 请求耗时
    long costMicrousd,       // 成本（微美元）
    String outcome,          // SUCCESS / CANCELLED / ERROR
    long recordedAtMs) {}
```

**三档成本计算** (`TenantModelService.java`)：

```java
public long calculateCostMicrousd(String userId, String logicalModelId,
        long inputTokens, long outputTokens, long cachedPromptTokens) {
    long standardInput = Math.max(0L, inputTokens - cachedPromptTokens);
    // 标准输入价格 + 缓存输入价格（更便宜）+ 输出价格
    return perMillion(standardInput, config.getInputMicrousdPerMillion())
         + perMillion(cachedPromptTokens, config.getCachedInputMicrousdPerMillion())
         + perMillion(outputTokens, config.getOutputMicrousdPerMillion());
}
```

**面试可以说的要点**：
- **Trace 和 Usage 是两条线**：Trace（trace_span 表）回答"发生了什么"，Usage（usage_event 表）回答"花了多少"
- 为什么要分开？用法不一样：Trace 是时序链路查询，Usage 是聚合统计和分析
- **成本计算三档**：标准输入、缓存输入、输出——AgentScope 2.0 支持 Anthropic 风格的 prompt caching，缓存命中的 token 价格更低
- 成本用 **microUSD（微美元，1/1,000,000 美元）** 存储，避免浮点数精度问题

### 2.3 数据库中实际的表

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `trace_run` | 每次对话请求的运行记录 | trace_id, user_id, agent_id, model_id, status, started_at_ms, ended_at_ms |
| `trace_span` | 每个操作单元的详情 | span_id, parent_span_id, trace_id, operation_name, duration_ms, attributes_json |
| `usage_event` | Token 用量和成本 | user_id, agent_id, model_id, input_tokens, output_tokens, cost_microusd |

---

## 第三部分：面试问题 & 回答要点

### Q1: "你简历里提到用 OpenTelemetry 做请求级 Trace，具体是怎么实现的？"

**回答框架（3 层 + 1 条线）**：

1. **入口层**：在 ChatController 创建 Root Span（`dataagent.chat.run`，SpanKind.SERVER），打上 Agent ID、Session Key、Model ID 等业务属性
2. **框架层**：AgentScope 的 `OtelTracingMiddleware` 自动拦截 Agent/LLM/Tool 的每次调用，生成子 Span，打上 `gen_ai.*` 标准属性（模型名、Token 数、工具名等）
3. **导出层**：自定义 `JpaTraceSpanExporter`，通过 `BatchSpanProcessor` 异步批量写入 MySQL（调度延迟 500ms）
4. **上下文传播**：因为框架底层是 Reactor 响应式，用 `ContextPropagationOperator` 确保 Trace 上下文跨线程不丢失

---

### Q2: "为什么要自己做 Span Exporter 而不是发到 Jaeger/Zipkin？"

1. **体量考虑**：这是一个内部使用的 Agent 平台，Span 量级不大（不是几百个微服务），上 Jaeger 是过度设计
2. **数据同源**：我们的"运行记录"功能需要展示 Trace 数据给用户看，直接写 MySQL 可以让业务查询和 Trace 查询走同一个数据源
3. **运维简单**：少一个中间件就少一个故障点
4. **如果量大了**：可以在 BatchSpanProcessor 后挂 OTLP Exporter 发到 Collector/Jaeger，改动量很小（换 Exporter 就行）

---

### Q3: "BatchSpanProcessor 是怎么工作的？为什么不用 SimpleSpanProcessor？"

- **SimpleSpanProcessor**：每个 Span 结束时同步调用 Exporter，**阻塞请求路径**。一个 LLM 调用可能产生多个 Span，每次都写数据库会让对话响应变慢
- **BatchSpanProcessor**：
  - Span 先进入**内存队列**（最大 2048 条）
  - **后台线程**每 500ms 拉取一批（最多 128 条），批量写入数据库
  - 即使数据库暂时不可用，Span 也只是丢（有 `ofFailure` 容错），不影响 Agent 正常运行
- **权衡**：500ms 延迟意味着 Trace 数据有一定滞后性，但对运行记录场景来说完全可接受

---

### Q4: "怎么解决"异常定位"的问题？"

1. **Trace 层面**：每个 Span 有 `StatusCode`（OK / ERROR），出错的 Span 直接标记为 ERROR
2. **业务层面**：`trace_run` 表有 `status` 字段（RUNNING / SUCCESS / CANCELLED / ERROR），出错的请求会记录 `error_message`
3. **关联分析**：通过 `traceId` 可以把同一个请求的所有 Span 串起来——看到是哪个 Tool 调用失败了，还是 LLM 返回异常了
4. **前端呈现**：`TraceRunsPage` 按 traceId 折叠展开，每个 Span 显示操作名、耗时、状态和关键属性，一眼定位问题环节

---

### Q5: "怎么实现模型开销分析？"

1. **数据采集**：每次模型调用，AgentScope 的 `OtelTracingMiddleware` 自动在 Span 上打 `gen_ai.usage.input_tokens` 和 `gen_ai.usage.output_tokens`
2. **异步聚合**：在 `ChatController` 中用 3 个 `AtomicLong`（线程安全的累加器）跨所有模型调用累加 Token
3. **成本计算**：`TenantModelService.calculateCostMicrousd()` 按**三档定价**计算：
   - 标准输入：`(input_tokens - cached) × 单价 / 1,000,000`
   - 缓存输入：`cached_tokens × 缓存单价 / 1,000,000`（更便宜）
   - 输出：`output_tokens × 输出单价 / 1,000,000`
4. **分析维度**：按用户/Agent/模型/时间（小时/天）聚合，生成趋势图和 Top-N 排名
5. **成本单位**：用 microUSD（微美元，整数存储），避免浮点数精度问题

---

### Q6: "OpenTelemetry 和 Micrometer 是什么关系？你们项目中用了哪个？"

- **Micrometer**：Spring Boot 默认的指标门面，主要管 Metrics（指标），比如 JVM 内存、线程池、HTTP 请求数
- **OpenTelemetry**：管三大信号（Traces + Metrics + Logs），是一套更完整的标准
- **关系**：OpenTelemetry 可以通过 Micrometer Bridge 把 Micrometer 的指标也纳入管理
- **本项目**：
  - **Trace**：用 OpenTelemetry SDK 原生 API（`opentelemetry-sdk` 1.37.0），没有走 Micrometer
  - **Metrics**：暴露了 Prometheus actuator 端点（生产环境），用于基础健康监控
  - 两者**互补而非替代**

---

### Q7: "Trace Context 在 Reactor/WebFlux 中怎么传播的？"

这是个好事，说明你懂响应式编程的坑：

- **问题**：OpenTelemetry 默认把 `SpanContext` 存在 `ThreadLocal` 里。但 Reactor 是异步非阻塞的，一个请求的处理会在多个线程上切换，换线程后 ThreadLocal 里的上下文就丢了
- **解决**：用 `ContextPropagationOperator` 把 OTel Context 注入 Reactor 的 `Context`（Reactor 自己的上下文传播机制），这样无论代码在哪个线程执行，都能拿到正确的 Trace 上下文
- **代码**：`ContextPropagationOperator.runWithContext(monoOrFlux, otelContext)`

---

### Q8: "如果数据库挂了，Trace 数据丢了怎么办？"

1. **业务优先**：我们的 `JpaTraceSpanExporter` 采用 best-effort 策略——导出失败只打 WARN 日志返回 `ofFailure()`，不抛异常，Agent 对话继续
2. **内存缓冲**：`BatchSpanProcessor` 有 2048 条队列，短期数据库抖动可以缓冲
3. **底线思维**：Trace 是"可观测"手段，不是业务正确性的前提。丢了某个 Span 只影响排查，不影响用户看到正确回复
4. **如果需求更高**：可以加一个文件 fallback exporter（这属于扩展方向）

---

### Q9: "你的方案和大厂的方案（如 LangSmith、LangFuse）有什么区别？"

- **LangSmith / LangFuse**：提供开箱即用的 LLM 观测 SaaS，有丰富的 UI，但数据流出去了
- **我们的方案**：**自建、内网、数据不外传**——适合企业内部敏感场景；而且和业务数据（Agent 管理、用户权限）天然打通
- **本质一样**：都是在 LLM Agent 调用链上插桩（instrumentation），采集 Token、耗时、状态等数据
- **扩展性**：如果以后需要发到外部的观测平台，换个 Exporter（OTLP → Jaeger/Grafana）就行

---

### Q10: "你提到用 11 个属性白名单，为什么不是全部保存？"

1. **存储成本**：一个 LLM 调用 Span 可能有几十个属性，很多对业务分析没用（如 SDK 内部配置、框架版本号）
2. **隐私安全**：Span 可能包含 prompt 内容，不保存可以避免敏感信息泄露到持久化存储
3. **查询效率**：只存关键属性，attributes_json 字段更小，前端渲染更快
4. **选这 11 个的原则**：足够定位问题（哪一步出错了）、计算成本（Token 数）、分析行为（用了什么工具、哪个模型）

---

## 第四部分：从零开始上手 OpenTelemetry（快速学习路径）

### Step 1：理解核心概念（30 分钟）
- 阅读 [OpenTelemetry 官方文档 - What is OpenTelemetry?](https://opentelemetry.io/docs/what-is-opentelemetry/)
- 理解 Trace、Span、SpanContext、Attributes、Semantic Conventions

### Step 2：跑一个 Demo（30 分钟）
- 按照 [官方 Java Getting Started](https://opentelemetry.io/docs/java/getting-started/) 跑一个 Spring Boot Demo
- 下载 `opentelemetry-javaagent.jar`，用 `-javaagent` 启动，看控制台输出的 Trace

### Step 3：理解本项目的实现（1 小时）
- 从头阅读这些文件：
  1. `OpenTelemetryConfig.java` — 初始化
  2. `ChatController.java` → `startTrace()` 方法 — 创建根 Span
  3. `JpaTraceSpanExporter.java` — 自定义导出
  4. `TraceRunService.java` — 落地到数据库
  5. `UsageStore.java` — Token 聚合
- 画一张数据流图

### Step 4：实战演练
- 在本项目的基础上，试着：
  - 加一个 `OtlpSpanExporter`，同时导出到 Jaeger
  - 给某个 Span 加自定义属性
  - 手动创建一个子 Span 包裹一段业务逻辑

---

## 第五部分：快速记忆卡（面试前 5 分钟过一遍）

| 维度 | 关键点 |
|------|--------|
| **用了什么** | `opentelemetry-sdk` 1.37.0，不依赖 Java Agent |
| **Root Span** | `dataagent.chat.run`，SpanKind.SERVER，手动创建于 ChatController |
| **子 Span** | AgentScope 的 `OtelTracingMiddleware` 自动生成 Agent/LLM/Tool 子 Span |
| **导出器** | 自定义 `JpaTraceSpanExporter` → MySQL，非 Jaeger/Zipkin |
| **导出策略** | `BatchSpanProcessor`：500ms 调度，2048 队列，128 批量 |
| **上下文传播** | `ContextPropagationOperator` 解决 Reactor 跨线程问题 |
| **Token 聚合** | 3 个 `AtomicLong` 跨模型调用累加 |
| **成本计算** | 三档定价：标准输入/缓存输入/输出，单位 microUSD |
| **属性白名单** | 11 个 gen_ai.* 属性过滤 |
| **异常定位** | Span StatusCode + trace_run error_message + 前端折叠展开 |
| **数据库表** | trace_run（运行记录）、trace_span（操作详情）、usage_event（用量成本） |
| **关键设计** | 异步非阻塞导出、容错（数据库挂了不影响业务）、Trace 与 Usage 分表存储 |
