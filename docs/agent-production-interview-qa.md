# 生产级 Agent 系统面试问答指南

> 结合 agentscope-dataagent 项目经验 + 业界通用实践

---

## 1. 本地 demo 跑得通，生产环境并发量一高，Agent 就会出现 panic，核心原因是什么？

**核心原因：共享可变状态缺乏并发保护。**

Demo 是单线程串行执行，生产环境多线程并发访问时，以下几类问题集中爆发：

### 常见根因

| 类别 | 具体表现 | 对应我们项目 |
|------|---------|-------------|
| **Agent 实例状态污染** | 多个请求共享同一个 Agent 对象，内部 conversation history / tool state 交叉覆盖 | `DataAgentBootstrap` 用 `ConcurrentHashMap` 存储 agent 实例，但 Agent 内部的 `Memory` 如果非线程安全就会串会话 |
| **沙箱/执行环境复用冲突** | Docker 容器被多请求同时写入，文件系统竞争 | `UserSandboxPool` 按用户池化容器，需保证同一容器内操作串行化 |
| **LLM 连接池耗尽** | 默认 HTTP 连接池太小，高并发下线程阻塞等待连接 | 我们的 `OllamaChatModel` / `OpenAIChatModel` 底层 HttpClient 连接池配置 |
| **数据库连接池打满** | 事务时间过长，连接无法归还 | JPA `GlobalAgentOverrideStore` 事务 |
| **SSE 流写入竞争** | 多个 observer 同时向同一 `SseEmitter` 写数据 | `ConnectableFlux.publish().connect()` 解决了，但如果没做好 backpressure 仍可能 OOM |

### 修复策略

```
1. Agent 实例 → 请求级隔离（每次请求 clone 或 factory 创建）
2. 共享状态 → ConcurrentHashMap / ReadWriteLock / 无锁 CAS
3. 外部调用 → 信号量限流 + 超时 + 熔断
4. 连接池 → 合理配置 maxTotal / maxPerRoute / timeout
```

**项目结合点**：我们的 `rebuildGlobalAgent` 通过原子交换 `HarnessGateway` 避免并发读写不一致；`AgentStateStore` 从 InMemory 切换到 Redis 就是典型的「消除本地可变状态」思路。

---

## 2. Agent 服务最常见的内存泄露有哪些？如何排查修复？

### 五种典型泄露

**① ThreadLocal 未清理**
Tomcat 线程池复用线程，ThreadLocal 不 remove 会导致上次请求的大对象无法 GC。
```java
// 修复：finally { threadLocal.remove(); }
```

**② Agent Memory / Conversation History 无限增长**
多轮对话 context 只追加不截断，百万 token 级别的历史消息常驻堆内存。
```java
// 修复：滑动窗口 + Token 预算制
memory.truncate(maxTokens: 32_000, strategy: "summarize_oldest");
```

**③ 未关闭的 SSE 连接 / WebSocket**
客户端断开后服务端 `SseEmitter` 未超时回收，累积大量半开连接。
```java
// 修复：SseEmitter 设超时 + onCompletion/onTimeout 回调清理
emitter.onTimeout(() -> registry.remove(sessionId));
```

**④ 沙箱/Docker 资源泄露**
创建后不释放的容器、挂载卷、临时文件。
```java
// 修复：UserSandboxPool.close() 在 evict 时调用
// 我们项目的 UserSandboxPool 已经实现了 holder close on evict
```

**⑤ 第三方 SDK 内部缓存**
如 OkHttp 的 ResponseBody 未 close、Jackson ObjectMapper 缓存无上限。

### 排查方法

```
1. heap dump: jmap -dump:live,format=b,file=heap.bin <pid>
2. MAT/Eclipse Analyzer: 看 Dominator Tree → 最大对象 → GC Root 路径
3. Arthas: dashboard 看堆内存趋势 + heapdump 在线分析
4. JFR (JDK Flight Recorder): 持续采样，找 Old Gen 持续增长的对象类型
```

---

## 3. LLM 响应慢导致整个 Agent 链路超时，分层超时的熔断策略是什么？高并发下如何解决 LLM 限流？

### 分层超时设计

```
┌─────────────────────────────────────────────────────┐
│  用户请求总超时: 120s                                 │
│  ├─ LLM Call #1 超时: 60s                           │
│  │   ├─ 连接超时: 5s                                 │
│  │   ├─ 首 token 超时: 15s                           │
│  │   └─ token 间超时: 30s                            │
│  ├─ Tool Call 超时: 30s × N                         │
│  ├─ LLM Call #2 超时: 60s                           │
│  └─ 熔断器: 连续 5 次超时 → 熔断 30s                 │
└─────────────────────────────────────────────────────┘
```

### 熔断策略（用 Resilience4j 举例）

```java
// 两段熔断器：控制面 + 数据面
CircuitBreakerConfig llmConfig = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)          // 50% 失败率触发熔断
    .slidingWindowSize(20)            // 20 次调用窗口
    .waitDurationInOpenState(Duration.ofSeconds(30))  // 熔断 30s
    .permittedNumberOfCallsInHalfOpenState(3)         // 半开探测 3 次
    .build();

// 分层 fallback
CompletableFuture<LLMResponse> future = circuitBreaker
    .executeCompletionStage(() -> callLLM(prompt))
    .exceptionally(ex -> {
        if (ex instanceof TimeoutException) return callFallbackModel(prompt);
        if (ex instanceof CallNotPermittedException) return cachedResponse(prompt);
        throw new AgentException("LLM unavailable");
    });
```

### 高并发下的 LLM 限流

**三层限流体系：**

| 层级 | 手段 | 说明 |
|------|------|------|
| **客户端限流（本地）** | 信号量 / RateLimiter | 控制单节点对 LLM API 的并发数 |
| **分布式限流** | Redis + 滑动窗口 | 多节点共享配额，如每分钟 1000 次 |
| **LLM 提供商侧** | API Key 级别 quota | 无法控制但需感知，429 时退避重试 |

```java
// 本地信号量限流
Semaphore llmSemaphore = new Semaphore(20); // 单节点最多 20 并发
llmSemaphore.tryAcquire(5, TimeUnit.SECONDS);

// 分布式限流（Redis Lua）
// 或直接用 Sentinel: SphU.entry("llm-api", EntryType.OUT, "longcat");
```

**429 退避策略**：
```
1st retry: 1s 后
2nd retry: 2s 后
3rd retry: 4s 后（指数退避，最多 3 次）
超过 → 降级到本地 Ollama 模型
```

**项目结合点**：我们已经实现了 `local`(Ollama) ↔ `longcat`(API) 双模型切换，天然具备降级能力。高并发时可以通过 `AgentRuntimeConfigurer` 的 `fallbackModelId` 自动 fallback。

---

## 4. 含检索工具和多轮推理的 Agent 链路，如何把 P95 RT 降下来？

### 瓶颈分析

```
典型 Agent 链路耗时分布（P95）:
├─ LLM 推理: 40-60%（最重）
├─ 检索工具 RAG: 20-30%
├─ 代码执行/沙箱: 10-15%
├─ 序列化/网络: 5-10%
└─ 其他: 5%
```

### 优化手段（按收益排序）

**① Prompt Cache（收益最大，30-50% 减少）**
```yaml
# Anthropic / OpenAI 均支持
# 将不变的 system prompt + tool definitions 放到请求开头
# 命中缓存时首 token 延迟降低 80%+
messages:
  - role: system
    content: "<cache>大量不变的 system prompt...</cache>"
```

**② Tool Call 并行化**
大多数 tool call 之间无依赖，改为并行执行：
```java
// 而非串行
List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
    .map(tc -> CompletableFuture.supplyAsync(() -> executeTool(tc), toolExecutor))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

**③ 检索预加载 / 异步预热**
```java
// 在 LLM 推理期间预取可能的检索结果
// LLM 输出 tool_call 时数据已在缓存
cache.warmup("knowledge_base", predictedQueries);
```

**④ 流式 Token 处理（缩短 TTFB 感知）**
你项目已经做了：`ConnectableFlux` SSE 流式推送，用户感知延迟大幅下降。

**⑤ 推测性执行**
LLM 推理时同时跑两个候选检索策略，先返回的先用：
```java
CompletableFuture.anyOf(
    searchWithEmbedding(query),
    searchWithKeyword(query)
);
```

**⑥ 模型蒸馏 / 小模型路由**
简单意图（查询、总结）→ 小模型（快 5-10x）；复杂推理 → 大模型。

**量化指标**：
| 优化手段 | P95 RT 收益 | 复杂度 |
|---------|------------|--------|
| Prompt Cache | -30~50% | 低 |
| Tool 并行化 | -20~30% | 低 |
| 小模型路由 | -40~60% | 中 |
| 检索预热 | -15~25% | 中 |
| SSE 流式 | TTFB -80% | 低 |

---

## 5. 生产环境完整的 Agent 缓存体系

### 缓存金字塔

```
               ┌──────────┐
               │  L1: 本地 │  Caffeine, TTL 5min, max 1000
               │  JVM 内存 │
               ├──────────┤
               │  L2: 分布 │  Redis, TTL 30min
               │  式缓存   │
               ├──────────┤
               │  L3: 语义 │  向量 DB + 相似度匹配
               │  缓存     │
               └──────────┘
```

### 具体缓存策略

| 数据类型 | 是否可缓存 | 缓存层级 | TTL | 依据 |
|---------|-----------|---------|-----|------|
| **LLM 响应（确定性 prompt）** | ✅ 可以 | L1+L2+语义 | 1h | 相同 system+user message → 相同 response |
| **语义相似但字面不同** | ✅ 可以 | 语义缓存(L3) | 1h | 向量相似度 > 0.95 命中 |
| **Embedding 向量** | ✅ 可以 | L2 | 24h | 文本不变则 embedding 不变 |
| **Tool 调用结果（如天气）** | ⚠️ 条件 | L1 | 业务决定 | 实时数据 TTL 短，历史数据可长 |
| **知识库文档切片** | ✅ 可以 | L2 | 版本更新时失效 |
| **Agent 配置/定义** | ✅ 可以 | L1+L2 | 热重载时失效 | 对应项目 `GlobalAgentOverrideStore` |
| **用户会话/Session** | ✅ 可以 | 用户级 L2 | 30min idle | 对应项目 `AgentStateStore` Redis |
| **流式输出** | ❌ 不能 | - | - | 每次推理结果不同（temperature > 0）|
| **带 tool_call 的多步推理** | ❌ 不能 | - | - | 非确定性链路 |
| **用户敏感数据/PII** | ❌ 不能 | - | - | 安全合规要求 |
| **实时金融/股票数据** | ⚠️ 条件 | L1 | 按业务窗口 | 见项目工具返回 |

### 语义缓存实现要点

```java
// 核心逻辑：不是精确匹配，而是相似匹配
public Optional<CachedResponse> semanticCache(String prompt, float[] embedding) {
    // 1. 向量相似度搜索
    List<CacheEntry> candidates = vectorDB.search(embedding, topK=3);
    // 2. 相似度阈值过滤
    for (CacheEntry e : candidates) {
        if (cosineSimilarity(embedding, e.embedding) > 0.95) {
            // 3. 用大模型验证语义等价（可选，成本权衡）
            if (semanticallyEquivalent(prompt, e.originalPrompt)) {
                return Optional.of(e.response);
            }
        }
    }
    return Optional.empty();
}
```

**项目结合点**：我们的 `GlobalAgentOverrideStore` 就是 Agent 配置的持久化缓存；`AgentStateStore`（Redis）是会话状态的分布式缓存。可以扩展加入 LLM 响应的语义缓存层。

---

## 6. Agent Token 成本持续增长，有哪些降本优化手段？

### 降本金字塔（从易到难，从效果大到小）

```
优先级 1: Prompt 工程优化          → 节省 20-40%
优先级 2: 上下文窗口治理           → 节省 30-50%
优先级 3: 模型分层路由             → 节省 40-60%
优先级 4: 缓存体系                 → 节省 10-30%（仅确定性部分）
优先级 5: 推理优化（量化/蒸馏）     → 节省 20-30%（需自部署）
```

### 具体手段

**① Prompt 瘦身**
```
优化前 System Prompt: 5000 tokens（充满冗余说明、示例、格式描述）
优化后: 800 tokens（精简指令 + JSON schema）
节省: 每次调用 -4200 input tokens
```

**② 上下文窗口智能截断**
```java
// 不是简单截断最后 N 条，而是
public List<Message> smartTruncate(List<Message> history, int budget) {
    // 1. 保留 system message（最高优先级）
    // 2. 保留最近 3 轮对话（最高优先级）
    // 3. 中间轮次做摘要压缩（而非丢弃）
    // 4. Tool call 结果只保留关键字段（去除原始 JSON 冗余）
    String summary = summaryModel.summarize(middleMessages);
    return [system, summary_message, recent_3_rounds];
}
```

**③ 模型分层路由（Model Router）**
```java
// 分类器决定用哪个模型
public Model selectModel(Task task) {
    return switch (task.intent) {
        case CHITCHAT, SUMMARIZE, TRANSLATE  -> cheapModel;  // GPT-4o-mini / Ollama
        case CODE_GEN, MATH, COMPLEX_REASONING -> expensiveModel; // GPT-4o / LongCat
        case RAG -> mediumModel;  // 夹在中间
    };
}
```

**④ 结构化输出减少浪费**
要求 LLM 返回 JSON 而非自然语言，减少 output token：
```
自然语言: "根据分析，这个方案的成本大约是每年500万元..." (~50 tokens)
结构化:   {"cost": 5000000, "currency": "CNY", "period": "year"} (~15 tokens)
```

**⑤ Prompt Cache 复用**
参见第 4 题，相同 system prompt 只计一次 input token 费用（Anthropic 优惠 90%）。

**⑥ 缓存 + 预计算**
高频问题的答案预先生成并缓存。

### 成本监控体系

```yaml
# 必须监控的指标
metrics:
  - total_tokens_per_day        # 日 token 总量
  - tokens_per_session_p95      # P95 单会话 token 量（找异常）
  - model_cost_breakdown        # 按模型拆分的费用
  - cache_hit_rate              # 缓存命中率
  - truncation_ratio            # 截断比例（过高说明 context 管理有问题）
```

**项目结合点**：我们已经实现了 `local`(Ollama) ↔ `longcat`(API) 双模型，就是「模型分层路由」的基础。下一步可以加一个 `ModelRouter` 组件，根据任务意图自动选择便宜或昂贵的模型。

---

## 7. 多用户并发时 Agent 会话隔离如何工程化落地？如何避免串会话？

### 隔离层次

```
┌────────────────────────────────────────────┐
│  租户级隔离: tenant_id → 独立 DB schema /   │
│              Redis DB index / K8s namespace │
├────────────────────────────────────────────┤
│  用户级隔离: user_id → 独立 session         │
│             conversation_id → 独立 context  │
├────────────────────────────────────────────┤
│  请求级隔离: request_id → ThreadLocal       │
│             Agent 实例每次 clone/factory    │
└────────────────────────────────────────────┘
```

### 工程落地关键点

**① Agent 实例绝不共享**
```java
// ❌ 错误：全局单例 Agent 被所有请求共享
@Bean
public Agent dataAgent() { return agent; }

// ✅ 正确：Agent 作用域 = 请求/会话
public Agent getOrCreateAgent(String agentId, String sessionId) {
    // 从 AgentStateStore 恢复会话级内存
    Agent agent = agentFactory.create(agentId);
    Memory memory = agentStateStore.loadMemory(sessionId);
    agent.setMemory(memory);  // 每个会话独立 Memory
    return agent;
}
```

**② 所有状态外挂到分布式存储**
```java
// Conversation History → Redis (sessionId 为 key)
// Agent Memory → Redis
// Tool 执行上下文 → 独立的 Sandbox/容器
// ThreadLocal 仅用于参数透传，不存储 Agent 状态
```

**③ 会话 ID 作为全局染色标识**
```java
// 所有日志/链路/缓存 key 都带 sessionId
MDC.put("sessionId", sessionId);
MDC.put("userId", userId);
MDC.put("agentId", agentId);

// Redis key:  agent:{agentId}:session:{sessionId}:memory
// 日志格式:   [user-123][agent-data-agent][session-abc] LLM call...
```

**④ 防御性校验**
```java
// 每个 API 入口校验所有权
public AgentSession getSession(String sessionId) {
    AgentSession session = store.get(sessionId);
    if (!session.getUserId().equals(currentUserId())) {
        throw new ForbiddenException("Session does not belong to user");
    }
    return session;
}
```

**项目结合点**：我们的 `AgentStateStore` 从 InMemory → Redis 正是「状态外挂」的关键步骤。`HarnessGateway` 按 `agentId+conversationId` 路由，天然支持会话隔离。如果做多租户，需要在所有 key 上加 `tenantId` 前缀。

---

## 8. 生产环境大量异步任务堆积，如何做任务队列治理？

### 架构设计

```
┌──────────┐    ┌─────────────┐    ┌──────────┐
│  API 层   │───→│  消息队列    │───→│  Worker  │
│ 快速返回  │    │  Kafka/Pulsar│    │  消费执行  │
│ task_id   │    │  持久化+分区  │    │  幂等处理  │
└──────────┘    └─────────────┘    └──────────┘
                      │
              ┌───────┴───────┐
              │  死信队列(DLQ) │  异常任务隔离
              │  + 重试策略    │
              └───────────────┘
```

### 治理维度

**① 队列可视化与监控**
```
必看指标:
- queue_depth: 队列积压量（> 阈值告警）
- consumer_lag: 消费延迟
- processing_time_p95: 单任务处理 P95 时间
- dlq_size: 死信队列大小
- retry_count_distribution: 重试次数分布
```

**② 优先级分级**
```
P0 (高优): 用户同步等待的任务 → 独立队列/partition，保证低延迟
P1 (普通): Agent 工具调用、检索、沙箱执行 → 主队列
P2 (低优): 日志写入、统计聚合、异步通知 → 低优队列
```

**③ 反压与限流**
```java
// Worker 侧自我保护
@KafkaListener(topics = "agent-tasks", concurrency = "10")
public void onMessage(ConsumerRecord record, Acknowledgment ack) {
    // 信号量控制单节点并发
    if (!semaphore.tryAcquire(100, MILLISECONDS)) {
        // 不 ack，让 Kafka 重新投递到其他节点
        return;
    }
    try {
        process(record.value());
        ack.acknowledge();
    } finally {
        semaphore.release();
    }
}
```

**④ 可观测性**
每个任务带 traceId → 完整链路追踪：
```
[Task Created] traceId=xxx → [Queue Enqueued] → [Worker Picked] 
→ [LLM Call #1] → [Tool Call] → [LLM Call #2] → [Task Completed/Failed]
```

**项目结合点**：你项目当前是同步链路（用户请求→Agent 执行→SSE 返回），如果要做异步化，可以把 Agent 执行包装成任务推 Kafka，用户通过 taskId 轮询结果或 WebSocket 推送。

---

## 9. 小模型推理弱但成本便宜，大模型准但贵，如何平衡？生产环境如何做模型调度？

### 调度策略矩阵

```
                    任务复杂度
                简单          复杂
          ┌──────────────┬──────────────┐
    低    │  小模型 ✅    │  小模型 ❌    │
延        │  (摘要/翻译)  │  (复杂推理)   │
迟  ──────┼──────────────┼──────────────┤
要        │  小模型 ✅    │  大模型 ✅    │
求 高     │  (实时对话)   │  (代码生成)   │
          └──────────────┴──────────────┘
```

### 实现 Model Router

```java
public class ModelRouter {
    
    public Model select(RequestContext ctx) {
        // 规则 1: 意图分类
        if (ctx.intent == CHITCHAT || ctx.intent == SUMMARIZE) {
            return cheapModel;  // GPT-4o-mini / Ollama qwen2.5
        }
        
        // 规则 2: Token 预算检查
        if (ctx.estimatedTokens < 1000 && ctx.complexity == LOW) {
            return cheapModel;
        }
        
        // 规则 3: 用户 SLA 等级
        if (ctx.userTier == PREMIUM) {
            return expensiveModel;  // GPT-4o / LongCat 大模型
        }
        
        // 规则 4: 可用性 fallback
        if (expensiveModel.isOverloaded() || expensiveModel.isCircuitOpen()) {
            return mediumModel;  // 降级
        }
        
        return expensiveModel;  // 默认大模型
    }
    
    // 级联兜底
    public Response executeWithCascade(Prompt prompt) {
        try {
            return expensiveModel.call(prompt);
        } catch (TimeoutException | CircuitBreakerOpenException e) {
            try {
                return mediumModel.call(prompt);
            } catch (Exception e2) {
                return cheapModel.call(prompt);  // 最终兜底
            }
        }
    }
}
```

### 质量保障机制

```java
// 小模型输出质量校验
if (isCheapModel(response)) {
    // 1. 事实性校验（检索结果对比）
    if (!factualityCheck(response.claims, retrievalResults)) {
        return expensiveModel.call(prompt);  // 升级到大模型
    }
    // 2. 格式校验（JSON 结构完整性）
    // 3. 置信度打分（低于阈值则升级）
    if (response.confidence < 0.7) {
        return expensiveModel.call(prompt);
    }
}
```

**项目结合点**：你们的 `dataagent.model.active` 切换 + Agent 级 Model 选择已经是调度基础。下一步加 `ModelRouter` + 意图分类器，就能实现自动降级升级。

---

## 10. 大量检索 RT 越来越高，百万级知识库如何做性能优化？

### 检索架构

```
写入链路:                    查询链路:
Document → Chunk → Embed    Query → Embed
    ↓          ↓                ↓
 元数据存储  向量存储          向量DB检索(top-K)
    ↓                           ↓
 倒排索引(可选)              重排序(cross-encoder)
                                ↓
                            结果融合 → 返回
```

### 优化手段

**① 向量索引优化（最大的性能杠杆）**
```
暴力搜索(Flat):    100ms @ 100万 → 不可接受
IVF (倒排索引):    10-20ms  @ 100万 → 可用
HNSW (图索引):     1-5ms   @ 100万 → 最优（内存换速度）
DiskANN:          5-10ms   @ 10亿  → 超大规模

推荐: Milvus/Qdrant/Weaviate 默认 HNSW
参数: M=16, efConstruction=200, efSearch=64
```

**② 多路召回 + 融合排序**
```
通路 1: 向量语义检索 (HNSW, top-100)
通路 2: BM25 关键词检索 (Elasticsearch, top-100)
通路 3: 结构化过滤 (元数据精确匹配)
         ↓
融合排序: RRF (Reciprocal Rank Fusion)
         ↓
精排: Cross-encoder 重排序 top-10 (小模型，轻量)
```

**③ 分片与分区**
```
按知识库分区: knowledge_base_1, knowledge_base_2, ...
按时间分区: 近 30 天热数据 + 历史冷数据
按租户分区: tenant_A, tenant_B, ...（多租户隔离）
```

**④ 查询改写与缓存**
```python
# 查询改写：把口语化问题转为检索友好的 query
user_query: "上次那个关于成本优化的方案是咋说的来着？"
rewritten:  "成本优化方案 降本 Token 消耗"

# 结果缓存：相似 query → 复用结果
cache_key = hash(query_embedding)  # 量化后 hash
```

**⑤ 动态 Top-K 策略**
```
简单 query (单概念):  top-K=20
复杂 query (多概念):  top-K=100 → 重排 → 取 10
```

### 性能分级目标

| 知识库规模 | 索引类型 | P95 延迟目标 |
|-----------|---------|-------------|
| < 10 万 | Flat / HNSW | < 5ms |
| 10 万 - 100 万 | HNSW | < 10ms |
| 100 万 - 1000 万 | HNSW + 分区 | < 20ms |
| > 1000 万 | DiskANN + 分层 | < 50ms |

---

## 11. Agent 服务的起停设计

### 启动流程（Graceful Startup）

```
Phase 1: 基础设施就绪
├─ 数据库连接池初始化
├─ Redis 连接检查
├─ 消息队列 Consumer 注册（先不消费）
├─ Docker Daemon 连通性检查
└─ 健康检查端点返回 DOWN

Phase 2: 核心组件初始化
├─ Agent Bootstrap（加载配置 + 构建 Agent 实例）
├─ Model Registry 注册（Ollama + LongCat）
├─ Tool Registry 注册
├─ SandboxPool 预热（预创建容器池）
└─ 健康检查端点返回 STARTING

Phase 3: 流量接入
├─ 注册到服务发现（Nacos / K8s Service）
├─ 标记 Readiness Probe 为 Ready
├─ 开始消费 MQ
└─ 健康检查端点返回 UP
```

### 停止流程（Graceful Shutdown）

```java
@Component
public class GracefulShutdown implements ApplicationListener<ContextClosedEvent> {
    
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Starting graceful shutdown...");
        
        // Step 1: 停止接收新流量（30s 内完成）
        // - 从服务发现摘除（Nacos deregister）
        // - K8s: preStop hook → sleep 5 → 让 Service 先摘除 endpoint
        // - 返回 503 给新请求
        
        // Step 2: 等待进行中的请求完成（最长 30s）
        awaitTermination(30, SECONDS);
        
        // Step 3: 保存运行中 Agent 状态
        agentStateStore.flushAll();  // 内存 → Redis
        
        // Step 4: 关闭资源
        sandboxPool.closeAll();      // Docker 容器清理
        modelRegistry.shutdown();    // 关闭 LLM 连接
        threadPools.shutdown();      // 线程池优雅关闭
        
        // Step 5: 断开基础设施
        dataSource.close();
        redisConnection.close();
        
        log.info("Graceful shutdown complete");
    }
}
```

### Spring Boot 配置

```yaml
server:
  shutdown: graceful  # 优雅关闭，不等 kill -9
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### K8s 配置

```yaml
spec:
  terminationGracePeriodSeconds: 60
  containers:
  - lifecycle:
      preStop:
        exec:
          command: ["/bin/sh", "-c", "sleep 5"]  # 等 Service 摘除
    readinessProbe:  # 只有 ready 后才接流量
      httpGet:
        path: /actuator/health/readiness
      initialDelaySeconds: 15  # 给 Bootstrap 预留时间
```

**项目结合点**：`DataAgentBootstrap` 在启动时构建所有 Agent；`UserSandboxPool` 管理容器生命周期；需要在 shutdown 时加上 `agentStateStore.flushAll()` 和 `sandboxPool.closeAll()`。

---

## 12. 如何避免发布期间导致用户体验报错？

### 滚动发布 + 优雅上下线

```
┌──────────────────────────────────────────────────┐
│              负载均衡 / API Gateway               │
│     健康检查 → 只路由到 Ready 的 Pod              │
└──────┬───────────────┬───────────────┬───────────┘
       │               │               │
    Pod v1          Pod v2          Pod v3
  (待下线)        (已上线)        (已上线)
  标记 draining    Ready           Ready
  等 30s           接新流量         接新流量
```

### 关键策略

**① 蓝绿部署（最安全，成本高）**
```
蓝环境 (旧版本) ──── 100% 流量
绿环境 (新版本) ──── 0% → 验证 → 100% 流量切换
回滚: 一键切回蓝环境，秒级完成
```

**② 滚动更新 + 连接排空（K8s 默认）**
```yaml
strategy:
  rollingUpdate:
    maxSurge: 1           # 最多多 1 个 Pod
    maxUnavailable: 0     # 不能有不可用 Pod（关键！）
```

**③ 客户端侧重试 + 幂等**
```typescript
// 前端 fetch 加重试
async function callAgentAPI(request, maxRetries = 3) {
    for (let i = 0; i < maxRetries; i++) {
        try {
            return await fetch('/api/agent/chat', { body: request });
        } catch (e) {
            if (i === maxRetries - 1) throw e;
            await sleep(1000 * Math.pow(2, i));  // 指数退避
        }
    }
}
```

**④ SSE 长连接处理**
```
发布时 Pod 被终止 → SSE 连接断开 → 前端自动 re-attach
（你们的 SSE re-attach 功能就是为了这个场景！）
```

**⑤ 灰度发布 / 金丝雀**
```
新版本先放 5% 流量，观察 10 分钟：
- 错误率 < 0.1% → 扩大到 25%
- 25% 观察 10 分钟 → 扩大到 100%
- 任意阶段指标异常 → 自动回滚
```

**项目结合点**：你们的 SSE re-attach + `attachToRunningStream()` 确保了发布时 SSE 连接的连续性。`AgentStateStore` 如果切到 Redis，Pod 重启后会话状态不丢。

---

## 13. 如何解决 Agent 长任务执行失败，需要从头跑的问题？

### 检查点 机制（Checkpoint）

```java
public class CheckpointManager {
    
    // 每一步执行完后保存检查点
    public void saveCheckpoint(String taskId, TaskState state) {
        // state 包含:
        // - 当前步骤序号
        // - LLM 对话历史
        // - Tool 执行结果
        // - 中间产物引用
        // - 已消耗 Token 数
        redis.set("task:" + taskId + ":checkpoint", serialize(state), 24, HOURS);
    }
    
    // 失败后从检查点恢复
    public TaskState restoreCheckpoint(String taskId) {
        TaskState state = redis.get("task:" + taskId + ":checkpoint");
        if (state == null) throw new NoCheckpointException();
        return state;
    }
}
```

### Agent 执行器改造

```java
public class CheckpointableAgentExecutor {
    
    public Result execute(AgentPlan plan, String taskId) {
        TaskState state;
        
        // 尝试恢复
        try {
            state = checkpointManager.restoreCheckpoint(taskId);
            log.info("Resuming task {} from step {}", taskId, state.currentStep);
        } catch (NoCheckpointException e) {
            state = TaskState.initial(taskId);
        }
        
        // 从上次失败的位置继续
        while (state.currentStep < plan.totalSteps()) {
            try {
                Step step = plan.getStep(state.currentStep);
                StepResult result = executeStep(step, state.context);
                state.update(result);
                checkpointManager.saveCheckpoint(taskId, state);
                state.currentStep++;
            } catch (RetryableException e) {
                // 失败不丢失进度，下次从检查点继续
                log.warn("Step {} failed, checkpoint saved", state.currentStep, e);
                throw e;  // 上层决定是否重试
            }
        }
        
        // 完成后清理检查点
        checkpointManager.clear(taskId);
        return state.toResult();
    }
}
```

### 幂等性保证

```java
// 每个步骤必须幂等
public StepResult executeStep(Step step, Context ctx) {
    // 如果该步骤已经执行过（有 sid），直接用缓存结果
    if (step.hasResult()) {
        return step.getResult();
    }
    
    // 执行 + 记录 sid
    String sid = generateStepId(step);
    StepResult result = doExecute(step, ctx);
    cache.set(sid, result, 24, HOURS);
    return result;
}
```

**项目结合点**：你们的 `AgentActivityStore.record` 可以参考改造成 checkpointer，每个 tool_call 执行后自动保存。

---

## 14. Agent 工程中哪些逻辑必须工程化硬编码，不能交给 LLM 自主判断？

### 硬编码原则：凡是影响**安全/数据正确性/资源控制**的，必须硬编码

| 领域 | 必须硬编码 | 原因 |
|------|-----------|------|
| **权限控制** | 用户能否访问某数据、能否调用某工具 | LLM 可能被 prompt injection 绕过 |
| **数据写入** | 哪些数据可以修改、修改范围 | LLM 可能幻觉导致数据破坏 |
| **资源配额** | Token 上限、工具调用次数上限、执行时间上限 | LLM 无法感知资源消耗 |
| **敏感操作** | 删除、发布、转账等 | 必须人工确认 (HITL) |
| **路由与调度** | 请求该走哪个模型、哪个工具 | 工程确定性能最优路径 |
| **结果校验** | JSON schema 校验、数据格式校验 | LLM 输出不稳定 |
| **限额与风控** | API 调用频率、费用上限 | 防止成本失控 |
| **用户身份** | 认证、鉴权 | 不能把决策权交给 LLM |

### 工程化实现

```java
// ✅ 正确：权限由代码层检查
public ToolResult executeTool(String toolName, Map<String, Object> params) {
    // 1. 权限检查（硬编码，不由 LLM 判断）
    if (!aclService.canExecute(currentUser, toolName, params)) {
        throw new AccessDeniedException();
    }
    // 2. 参数校验（硬编码）
    validator.validate(toolName, params);
    // 3. 配额检查（硬编码）
    quotaService.checkAndDeduct(currentUser, toolName);
    // 4. 实际执行
    return tool.execute(params);
}

// ✅ Human-in-the-Loop 关键操作
if (tool.isDestructive()) {
    UserConfirmation confirm = hitlService.request(user, "确认执行 " + toolName + "?");
    if (!confirm.isApproved()) {
        return new RejectedResult();
    }
}
```

**项目结合点**：你们的 `AgentAclService.tierFor` 就是硬编码的权限控制；HITL 确认弹窗就是关键操作的人类确认机制。这些都是正确的工程化实践。

---

## 15. Agent 性能、成本、稳定性的核心指标

### 三大维度 + 核心指标

#### 性能指标（Performance）

| 指标 | 计算方式 | 告警阈值 |
|------|---------|---------|
| **P50/P95/P99 RT** | 端到端响应时间分位数 | P95 > 30s 告警 |
| **TTFT** (Time to First Token) | 首 token 生成时间 | > 3s 告警 |
| **Tool Call 延迟** | 单次工具调用耗时 | > 10s 告警 |
| **检索延迟** | RAG 检索 P95 | > 1s 告警 |
| **吞吐量** | RPS / RPM | 按容量规划 |

#### 成本指标（Cost）

| 指标 | 计算方式 |
|------|---------|
| **单次对话成本** | (input_tokens × input_price + output_tokens × output_price) / conversation |
| **日/月 Token 总量** | 聚合所有会话 |
| **Token 浪费率** | (冗余 context + 重试消耗) / 总 token |
| **缓存命中率** | cache_hit / total_requests |
| **模型使用分布** | 大模型 vs 小模型调用比例 |

#### 稳定性指标（Reliability）

| 指标 | 计算方式 | 告警阈值 |
|------|---------|---------|
| **可用性 (SLA)** | (总请求 - 5xx) / 总请求 | < 99.9% |
| **错误率** | 各错误码占比 | > 1% 告警 |
| **LLM 调用成功率** | (成功 / 总调用) | < 99% 告警 |
| **熔断器状态** | 各 Circuit Breaker open/close 比例 | open 占比 > 5% |
| **任务重试率** | 需重试的任务 / 总任务 | > 10% 告警 |
| **死信队列积压** | DLQ 消息数 | > 0 告警 |

### 监控看板设计

```
Grafana Dashboard 三层:

L1 - 业务大盘（给 PM/运营看）
├─ 日活用户、每日会话数
├─ 日 Token 消耗趋势、日成本趋势
└─ 用户满意度（点赞/点踩比例）

L2 - 技术健康（给开发看）
├─ P50/P95/P99 延迟趋势（按 API 端点拆分）
├─ 错误率趋势（按错误码拆分）
├─ LLM API 调用成功率 & 延迟
└─ 各模型 Token 消耗分布

L3 - 细粒度诊断（调试用）
├─ 单请求 Trace（Jaeger/Zipkin）
├─ 单 Agent 链路火焰图
└─ 工具调用成功率/延迟明细
```

**项目结合点**：可以基于 Spring Actuator + Micrometer 埋点，Prometheus 采集，Grafana 展示。

---

## 16. Agent 批量处理任务时 CPU/内存瞬时打满，如何解决？

### 根因

批量任务通常是同步调度 N 个 Agent 实例并行执行，每个 Agent 内又可能并行调用多个 Tool → M×N 的并发爆炸。

### 解决方案

**① 消费者并发控制（最关键）**
```java
// 不是无限制地 fork-join
// 而是控制全局最大并发 Agent 数
private final Semaphore agentConcurrency = new Semaphore(10);  // 全局 10 个

public void processBatch(List<Task> tasks) {
    tasks.parallelStream()
        .map(task -> {
            agentConcurrency.acquire();
            try {
                return agent.execute(task);
            } finally {
                agentConcurrency.release();
            }
        })
        .toList();
}
```

**② 分批 + 限流（Rate Limiting）**
```
不是 1000 个任务一起丢进线程池
而是
Batch 1: 100 个 → 完成 →
Batch 2: 100 个 → 完成 →
...

配合: 每批间隔 1s（让 GC 喘口气）
```

**③ 背压机制（Reactive）**
```java
// 使用 Reactor / RxJava 的背压
Flux.fromIterable(tasks)
    .flatMap(task -> executeAgent(task)
        .subscribeOn(Schedulers.boundedElastic()),  // 限制并发
        concurrency = 10,                             // 最多 10 并发
        prefetch = 1                                  // 背压
    )
    .subscribe();
```

**④ 资源预留 + 弹性伸缩**
```
K8s HPA:
- CPU > 70% → 扩容 2x Pod
- Memory > 80% → 扩容 2x Pod
- 提前预热（批处理前 5min 扩容）

JVM:
- -Xmx 设置合理上限（容器内存的 75%）
- 避免 swap，设 -XX:+UseContainerSupport
```

**⑤ 工具复用而非重复创建**
```java
// ❌ 每次创建新的 HttpClient、新的 Embedding 模型
// ✅ 预热复用
@Bean
public EmbeddingModel embeddingModel() {
    // 单例，线程安全，一次加载
    return new OnnxEmbeddingModel("model.onnx");
}
```

---

## 17. Agent 多轮对话上下文越来越大，如何通过工程化做治理？

### 上下文膨胀的根因

```
第 1 轮: System(2k) + User(200) + Assistant(500) = 2.7k tokens
第 5 轮: ... + 前 4 轮历史 + Tool 结果 = 15k tokens
第 10 轮: ... + 前 9 轮历史 = 40k tokens
第 20 轮: ... = 80k tokens → 撞窗口上限 → 截断 → 丢失关键信息
```

### 治理策略（分层）

```
L1: 滑动窗口（最简单）
    保留最近 N 轮，丢弃更早的

L2: 摘要压缩（推荐）
    中间轮次 → 小模型生成摘要 → 摘要替代原始对话
    只保留最近 3 轮的完整内容

L3: 选择性保留（最优）
    标识关键轮次（产生重要结论/决策的）→ 完整保留
    非关键轮次 → 摘要
    过时信息 → 丢弃
```

### 工程实现

```java
public class ContextManager {
    
    private static final int RECENT_ROUNDS = 3;      // 保留最近 3 轮完整
    private static final int MAX_SUMMARY_TOKENS = 500; // 摘要上限
    
    public List<Message> optimize(List<Message> fullHistory, int tokenBudget) {
        List<Message> system = extractSystem(fullHistory);
        List<List<Message>> rounds = splitIntoRounds(fullHistory);
        
        if (rounds.size() <= RECENT_ROUNDS) {
            return fullHistory;  // 对话还短，不需要处理
        }
        
        List<Message> recent = rounds.subList(rounds.size() - RECENT_ROUNDS, rounds.size());
        List<Message> older = rounds.subList(0, rounds.size() - RECENT_ROUNDS);
        
        // 对旧的轮次做摘要
        String summary = summarizeModel.summarize(
            flattenMessages(older),
            maxTokens = MAX_SUMMARY_TOKENS
        );
        
        // 组装: system + 历史摘要 + 最近 3 轮完整
        List<Message> optimized = new ArrayList<>();
        optimized.addAll(system);
        optimized.add(new SystemMessage("历史对话摘要: " + summary));
        optimized.addAll(flattenMessages(recent));
        
        return truncateToBudget(optimized, tokenBudget);
    }
    
    // Token 预算控制
    private List<Message> truncateToBudget(List<Message> msgs, int budget) {
        int tokens = countTokens(msgs);
        if (tokens <= budget) return msgs;
        
        // 从旧的开始裁剪
        for (int i = 1; i < msgs.size() - RECENT_ROUNDS * 2; i++) {
            msgs.remove(i);
            tokens = countTokens(msgs);
            if (tokens <= budget) break;
        }
        return msgs;
    }
}
```

### 记忆分层架构

```
Working Memory (当前上下文)  → 实时，2000 tokens
Short-term Memory (会话级)  → Redis, 会话生命周期
Long-term Memory (跨会话)   → 向量 DB + 语义检索
                             用户偏好、历史决策、常见问题
```

**项目结合点**：AgentScope 框架的 `Memory` 接口可以做这个改造；你们的 `AgentStateStore` 承载了会话级记忆。

---

## 18. 第三方 LLM 接口不稳定，如何保证 Agent 系统高可用？

### 多层防御体系

```
                      ┌─────────────┐
                      │  用户请求    │
                      └──────┬──────┘
                             │
                    ┌────────▼────────┐
                    │   重试策略(3次)  │ 指数退避
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │   熔断器        │ 短路保护
                    └────────┬────────┘
                             │
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
    ┌───────────┐    ┌───────────┐    ┌───────────┐
    │ Provider A │    │ Provider B │    │ Fallback  │
    │ (LongCat)  │    │ (OpenAI)   │    │ (Ollama)  │
    └───────────┘    └───────────┘    └───────────┘
```

### 具体策略

**① 多 Provider 冗余（最重要）**
```java
public class MultiProviderLLMService {
    
    private final List<LLMProvider> providers = List.of(
        new LongCatProvider(),    // 优先级 1
        new OpenAIProvider(),     // 优先级 2
        new OllamaProvider()      // 优先级 3 (本地兜底)
    );
    
    public LLMResponse call(Prompt prompt) {
        for (LLMProvider provider : providers) {
            if (provider.circuitBreaker.isOpen()) {
                continue;  // 跳过已熔断的
            }
            try {
                return provider.call(prompt, timeout);
            } catch (Exception e) {
                log.warn("Provider {} failed: {}", provider.name(), e.getMessage());
                provider.recordFailure();
                // 继续下一个 provider
            }
        }
        throw new AllProvidersExhaustedException();
    }
}
```

**② 重试策略**
```java
@Retryable(
    retryFor = {TimeoutException.class, IOException.class, RateLimitException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2.0)  // 1s, 2s, 4s
)
public LLMResponse callWithRetry(Prompt prompt) { ... }
```

**③ 客户端超时分层（见第 3 题）**

**④ 健康检查 + 自动摘除**
```java
// 定时探测每个 provider
@Scheduled(fixedDelay = 30_000)
public void healthCheck() {
    for (LLMProvider p : providers) {
        boolean healthy = p.ping();  // 轻量健康检查
        if (!healthy && !p.circuitBreaker.isOpen()) {
            p.circuitBreaker.transitionToOpenState();
            alertService.send("LLM Provider {} is down!", p.name());
        }
    }
}
```

**⑤ 响应缓存（降低调用量）**
```
确定性 prompt → 缓存结果 → 减少对 LLM API 的依赖
缓存命中时完全绕过外部调用
```

**⑥ 降级策略分级**
```
Level 1: 换 Provider（LongCat → OpenAI → Ollama）
Level 2: 换模型（GPT-4o → GPT-4o-mini，牺牲质量保可用）
Level 3: 返回缓存/静态兜底（"服务繁忙，请稍后重试"）
Level 4: 排队异步处理（告诉用户"任务已提交，完成后通知"）
```

**项目结合点**：你们的 `local` ↔ `longcat` 双模型 + `fallbackModelId` 已经是多 Provider 冗余的基础。加上熔断器和重试就完整了。

---

## 19. 企业级 Agent 权限和数据隔离 / 多租户设计

### 隔离模型

| 模型 | 隔离程度 | 成本 | 适用场景 |
|------|---------|------|---------|
| **Schema 隔离** | 最高 | 最高 | 金融/医疗/政务 |
| **行级隔离** | 高 | 中 | SaaS 产品 |
| **应用级隔离** | 中 | 低 | 内网/开发测试 |

### 推荐架构：行级隔离 + 关键数据 Schema 隔离

```sql
-- 所有表带上 tenant_id
CREATE TABLE agent_conversation (
    id BIGINT PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL,  -- 租户标识
    user_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    ...
    INDEX idx_tenant (tenant_id)
);

-- 查询时强制过滤（MyBatis 拦截器或 JPA Filter）
@Where(clause = "tenant_id = #{currentTenantId}")
public interface ConversationRepository extends JpaRepository<...> {}
```

### 权限模型

```java
// RBAC + ABAC 混合
public class AgentPermissionSystem {
    
    // 角色层面
    enum Role {
        TENANT_ADMIN,    // 租户管理员：管理租户内所有资源
        AGENT_EDITOR,    // Agent 编辑者：可编辑 Agent 配置
        AGENT_USER,      // Agent 使用者：只能使用 Agent
        VIEWER           // 只读
    }
    
    // 属性层面（ABAC）
    public boolean canAccess(User user, Resource resource) {
        // 1. 租户隔离
        if (!user.tenantId.equals(resource.tenantId)) return false;
        
        // 2. 角色判断
        if (user.role == TENANT_ADMIN) return true;
        
        // 3. 资源所有权
        if (resource.ownerId.equals(user.id)) return true;
        
        // 4. 显式授权（如 Agent Share）
        if (shareStore.isGranted(user.id, resource.id)) return true;
        
        return false;
    }
}
```

### 多租户数据流隔离

```
租户 A 的请求:
  Request Header: X-Tenant-Id: tenant-a
  → Filter 拦截设置 TenantContext.set("tenant-a")
  → 所有 DB 查询自动带 tenant_id = 'tenant-a'
  → LLM API Key 使用 tenant-a 的专属 key
  → 沙箱容器标签: tenant=tenant-a
  → 向量数据库命名空间: tenant-a
  → 日志/Metrics 打上 tenant=tenant-a 标签

租户 B 同理，完全不可见 A 的数据
```

### 工程落地清单

```
1. 网关层: TenantInterceptor 解析 X-Tenant-Id
2. 持久层: JPA Filter / MyBatis Interceptor 自动拼 tenant_id
3. 缓存层: Redis key prefix = "tenant:{tenantId}:..."
4. LLM 层: 每个租户独立 API Key，独立计费
5. 沙箱层: Docker label / K8s namespace 隔离
6. 文件层: 对象存储按 tenant_id 分目录
7. 可观测性: 所有日志/metrics/trace 含 tenant_id
8. 限额: 按租户配 quota（并发数、Token 量、存储量）
```

**项目结合点**：你们的 `GlobalAgentOverrideStore` 按 `agent_id` 存储 + `ownerId` 归属 + `AgentAclService` 权限分级，再往上加一层 `tenant_id` 就是完整的多租户体系。

---

## 20. 从零搭建生产级企业 Agent 平台的核心工程顺序

### Phase 1: 基础设施（Week 1-2）

```
目标: 可运行的最小闭环

1. 项目骨架
   - Java: Spring Boot + Maven 多模块
   - 分层: api / application / domain / infrastructure
   - CI: GitHub Actions / 工蜂 CI

2. Agent 框架集成
   - 选型: LangChain4j / AgentScope / 自研
   - 协议: 统一 Agent 接口 (build → execute → stream)
   - 工具注册: Tool 规范 + 动态注册

3. 基础运行环境
   - LLM 接入（至少 2 个 Provider 冗余）
   - Docker 沙箱（代码执行隔离）
   - Redis（会话状态）

4. 最简前端
   - 聊天界面 + SSE 流式
   - Agent 管理后台（CRUD）
```

### Phase 2: 可靠性与安全（Week 3-4）

```
5. 高可用
   - 多 Provider + 熔断 + 重试 + 降级
   - 健康检查 + Readiness/Liveness Probe
   - 优雅起停

6. 安全与隔离
   - 认证鉴权（Spring Security + JWT）
   - 会话隔离（Agent 实例不共享）
   - 沙箱隔离（Docker per session）
   - HITL 关键操作确认

7. 上下文管理
   - Memory 分层（Working / Short-term / Long-term）
   - 上下文截断 + 摘要压缩
   - Token 预算控制
```

### Phase 3: 成本与性能（Week 5-6）

```
8. 性能优化
   - 模型路由（大/小模型分级调度）
   - Tool 并行执行
   - Prompt Cache
   - 检索加速（HNSW + 多路召回）

9. 缓存体系
   - LLM 响应缓存（语义缓存）
   - Embedding 缓存
   - Agent 配置缓存
   - 检索结果缓存

10. 成本控制
    - Token 监控 + 预警
    - 上下文智能截断
    - 模型路由降本
    - 按租户/用户计费
```

### Phase 4: 规模化（Week 7-8）

```
11. 多租户
    - 数据隔离（行级/租户级）
    - 独立 LLM API Key + 计费
    - 租户级配额（并发/Token/存储）

12. 任务队列
    - 异步任务持久化
    - Checkpoint 恢复
    - 死信队列 + 重试

13. 知识库
    - 向量数据库（Milvus/Qdrant）
    - 文档管理 + 切片 + 索引
    - 检索质量评估

14. 可观测性
    - Metrics: Prometheus + Grafana
    - Tracing: Jaeger/Zipkin
    - Logging: ELK + 结构化日志
    - Alerting: 告警规则 + 通知
```

### 从一开始就要做的事（贯穿全程）

```
- 结构化日志（每条日志带 traceId/sessionId/tenantId）
- 错误处理规范（统一异常码 + 错误信息脱敏）
- 配置外置（环境变量 / Nacos / ConfigMap）
- 灰度发布能力（Feature Flag）
- 压测（持续做，不是上线前做一次）
```

### 面试话术

> "我会按四个阶段推进：先用最小闭环跑通 Agent 核心链路，验证技术可行性；再补安全和高可用，确保不丢会话、不串数据；然后做性能和成本优化，把 P95 延迟和大模型成本降下来；最后规模化——多租户、知识库、任务队列、完整可观测性。贯穿全程的底线是：结构化日志、统一错误处理、配置外置、持续压测。"

---

## 面试答题技巧总结

1. **先说根因，再说方案** — 每个问题先讲清楚"为什么会这样"，再讲"怎么解决"
2. **分层表达** — "这个问题要从 X 个层面来解决：基础设施层做 A，应用层做 B，数据层做 C"
3. **量化思维** — 能说数字就说数字："P95 从 60s 降到 15s""缓存命中率提升 40%"
4. **结合项目** — 提到你在 agentscope-dataagent 中实际做过的（hot-reload、SSE re-attach、双模型切换、沙箱隔离等）
5. **补漏话术** — 对没做过的部分："在目前项目里还没遇到这个量级，但我的设计思路是..."
6. **框架思维** — 不求每个点都完美，但求覆盖全面、逻辑自洽

---

*本文档结合 agentscope-dataagent 项目实际架构编写，面试时可根据自身经历灵活调整案例。*
