# 简历项目模块 - 多 Agent 数据智能体平台

> 目标岗位：2026 秋招 Agent 开发（后端 + 多 Agent 复合方向）
>
> 项目角色建议：核心开发 / 后端与 Agent 运行时设计
>
> 当前口径基于 2026-07-20 的 `main` 分支代码核验。项目周期请按真实时间补充。

---

# A. 可直接投递的简历正文

> 本部分严格保留“技术栈、项目整体介绍、个人核心工作”三个板块。  
> 一页简历优先保留个人核心工作的前 5 点；第 6 点通过专项测试后再作为替换项。

## 一、技术栈

Java 17、Spring Boot 3.3、Spring MVC、Spring Security、Spring Data JPA、AgentScope Java 2.0 HarnessAgent、MySQL、Redis、Docker、Reactor、SSE、OpenTelemetry、Ollama、OpenAI 兼容模型、AES-GCM、Nacos / Git Skill Marketplace、React 18、TypeScript、Vega-Lite、Maven。

## 二、项目整体介绍

基于 AgentScope Java 2.0 构建面向多用户隔离的企业数据分析 Agent 平台，用户通过自然语言驱动主 Agent 完成任务规划，并调用隔离子 Agent 与数据工具，实现“数据源发现 -> 表结构探查 -> SQL 预览 -> Vega-Lite 图表 -> 分析报告”的闭环。平台支持全局与用户自定义 Agent 的创建、克隆、共享、在线配置和能力安装，以用户 ID 作为过渡租户边界，通过 MySQL 管理业务事实、Redis 管理运行状态、Workspace 管理 Agent 定义与产物、Docker Sandbox 隔离执行环境。项目包含 **12 个业务域、172 个 Java 源文件、24 个 REST Controller 和 74 个前端 TS/TSX 文件**。

## 三、个人核心工作

### 1. 多 Agent 统一运行时与上下文治理

设计 `AgentRuntimeConfigurer` 统一装配全局 Agent 与用户自定义 Agent，将 Plan Mode、上下文压缩、长期记忆、子 Agent、工具权限及模型容错收敛至单一入口；配置 **30 条消息触发压缩并保留最近 10 条、10 分钟节流刷新记忆、最多 2 次模型重试及 2 个隔离子 Agent**，避免两类 Agent 出现运行能力和配置语义不一致。

### 2. 分布式状态与用户级 Docker 沙箱

将运行时收敛到 AgentScope 托管生命周期，统一接入 Redis `AgentStateStore`、Workspace Snapshot 和分布式 `ExecutionGuard`，按 `IsolationScope.USER` 隔离执行环境；工作区 API 与 Agent 工具共用 `acquire -> start -> persist -> release` 链路，避免双容器池造成状态分叉。定位 Windows Docker 命令封装对嵌套 `$()` 解析异常导致目录文件写入失败，在 Java 侧计算 POSIX 父目录并保留分块 Base64 上传与原子 `mv`，同时让工作区只读浏览优先使用本地镜像，避免失效容器恢复阻塞报告查看。

### 3. 数据工具、HITL 与流式事件链

实现 `list_data_sources`、`describe_table`、`run_sql_preview`、`render_chart` 四类 Agent 工具；SQL 工具采用应用层 `SELECT/WITH` 白名单、DDL/DML 关键词拦截和 JDBC `setMaxRows` 限流，默认返回 100 行、硬上限 500 行，并通过权限引擎将 SQL 执行设置为人工确认。基于 SSE 映射文本、工具调用、工具结果和确认事件，支持主动取消、**180 秒事件流空闲超时及 300 秒连接超时**；图表限定 4 种类型与内联 `data.values`，禁止远程 URL。

### 4. Agent 生命周期、热重建与多模型路由

实现全局 Agent 热重建：合并文件基础配置与 MySQL 覆盖配置，重建 Agent 后重新挂载实例绑定工具并切换 Gateway 注册关系，使提示词、模型和工具配置 **无需重启即可生效**；基于 `ModelRegistry + ModelCreationContext` 接入 Ollama 与 OpenAI 兼容模型，为用户自定义 Agent 按用户边界动态解析模型连接，并在配置变更后失效运行实例缓存；模型 API Key 使用 AES-GCM 加密存储且只写不回显。

### 5. Agent 请求级可观测与成本核算

接入 AgentScope `OtelTracingMiddleware`，构建请求、Agent、模型与工具的 Trace 树；使用异步批处理持久化 Span，配置 **500ms 调度间隔、2048 队列容量和 128 批大小**，避免链路记录同步阻塞推理请求。按流式请求聚合全部 `ModelCallEndEvent`，记录输入、输出、缓存 Token、耗时、执行状态和微美元成本，并采用属性白名单避免提示词及模型正文进入 Trace 索引。

### 6. Agent 能力沉淀与权限治理（备选，完成 P1 测试后再放简历）

实现私有 Workspace 到共享能力库的贡献闭环，支持 Skill、Subagent、Memory、`AGENTS.md`、Knowledge、MCP Server **6 类资产**经过 `PENDING -> APPROVED / REJECTED` 审批后发布，并保留原始内容、审核修改稿与审核记录；接入用户级 Git / Nacos Marketplace 完成 Skill 安装和 Agent 缓存失效，同时通过 `CLONE / RUN / EDIT` 三级 ACL 管理全局、私有及共享 Agent 权限。

---

# B. 简历点优先级与当前完善度

## 1. 评估口径

- **可直接写**：代码主链路已经存在，表述不依赖尚未完成的功能；仍需补专项测试作为面试证据。
- **测试后强化**：功能基本完整，但并发、故障或端到端证据不足，简历中不能写“完全解决”“高可用”“无感”等绝对结论。
- **暂不主推**：基础实现存在，但测试被跳过、缺少版本治理或生产边界，不适合作为前 5 个核心亮点。

## 2. 当前成熟度总表

| 优先级 | 简历点 | 代码实现 | 自动化证据 | 当前判断 | 主要缺口 |
|---|---|---|---|---|---|
| P0-1 | 多 Agent 统一运行时 | 较完整，真实委派、会话树、共享报告和双 Agent 切换已走通 | 基础回归已补 | **可放第 1 点** | 缺少压缩、记忆和 fallback 专项测试 |
| P0-2 | Redis 状态 + Docker 沙箱 | 主链路完整，嵌套报告写入与镜像读取已验证 | 嵌套写入、镜像优先单测已补 | **可放第 2 点** | 缺少 Redis 重启恢复、多实例互斥及故障注入测试 |
| P0-3 | 数据工具 + HITL + SSE | 基础功能完整 | 部分覆盖 | **可放第 3 点，但需补安全边界** | SQL 仍是字符串过滤；缺只读账号、AST 校验、查询超时及 HITL 端到端测试 |
| P0-4 | 热重建 + 多模型路由 | 主链路完整 | 部分覆盖 | **可放第 4 点，避免“在线会话完全无感”** | 缺在途回合、并发更新、Gateway 双写窗口和旧实例回收测试 |
| P1-1 | OTel Trace + Token/成本 | 主链路完整 | 较弱 | **可放第 5 点** | 缺多模型调用聚合、Trace 父子关系、Exporter 故障和脱敏测试 |
| P1-2 | 能力贡献 + Marketplace | 基础实现存在 | 7 个测试被跳过 | **暂作备选** | 缺版本不可变、回滚、审批文件/数据库一致性和恶意 Skill 检测 |
| P2-1 | RBAC + Agent ACL | 基础实现存在 | 较弱 | 作为治理细节追问 | 缺权限矩阵、越权、授权传播和管理员身份模型测试 |
| P2-2 | package-by-feature 分层 | 结构已落地 | 无架构约束测试 | 不占核心 bullet | 缺 ArchUnit / Maven Enforcer，岗位区分度低于 Agent 运行时能力 |

## 3. 已确认的基础证据

- 后端当前执行 `mvn test`：**49 个测试，0 Failure，0 Error，11 Skipped**。
- 前端当前执行 `npm.cmd run build`：TypeScript 检查与 Vite 生产构建通过。
- 代码规模：主代码 **172 个 Java 源文件**、**12 个一级业务域**、**24 个 REST Controller**、前端 **74 个 TS/TSX 文件**。
- 已用真实前端完成 `Data Agent -> Insight Agent` 切换；URL、标题、描述、输入提示、模型和会话作用域同步变化，`Insight Agent` 的回复及 Gateway 日志证明请求进入独立运行时。
- 已通过真实 `agent_spawn(agent_id=report-writer, task=..., timeout_seconds=120)` 在共享工作区生成 `reports/shared-handoff-test.md`；主 Agent 可回读，前端 `/workspace?agent=data-agent` 可见并能打开完整正文。
- 当前没有可信的 QPS、P95/P99 延迟、并发用户数、SQL 正确率、工具选择准确率或端到端任务成功率数据，简历中暂不填写这些指标。

---

# C. 两周面试优先实施计划

## 1. 当前目标与取舍

当前目标不是把项目补成可上线平台，而是让前 5 个简历点同时满足：核心方案真实实现、能从前端完成一条业务操作链、能讲清调用流程与踩坑、能主动说明方案边界。两周内暂缓大规模自动化测试、Kubernetes、多 Pod 压测、完整 SQL AST 安全、Skill 版本治理和持久层迁移。原有自动化计划保留在 D 部分，面试主链完成后再做。

## 2. 五个简历点的面试准备状态

| 简历点 | 当前实现判断 | 前端体验入口 | 两周内收口重点 |
|---|---|---|---|
| 多 Agent 统一运行时与上下文治理 | 统一配置、真实委派、子 Agent 会话树及共享报告已走通 | `/configure/subagents`、`/chat`、`/admin/sessions`、`/workspace` | 补上下文压缩、长期记忆与模型 fallback 的面试体验 |
| 数据工具、HITL 与流式事件链 | 五点中最接近完整 | `/configure/tools`、`/chat`、`/workspace` | 跑顺四工具、批准/拒绝、取消、图表与错误状态 |
| Agent 生命周期、热重建与多模型路由 | 后端主链存在，统一 Agent 选择器及第二个可见 Agent 的真实切换已验证 | `/chat`、`/configure/settings`、`/models`、`/traces` | 继续验证配置热重建、在途请求边界和租户模型切换 |
| 分布式状态与用户级 Docker 沙箱 | 框架托管架构已落地，嵌套报告写入、共享镜像落盘及前端读取已闭环 | `/chat`、`/workspace`、`/admin/sessions` | 验证同用户重启恢复、不同用户隔离和 Redis 状态互斥 |
| Agent 请求级可观测与成本核算 | Trace、Usage 页面与存储链路已存在 | `/traces`、`/usage`、`/admin/usage`、`/models` | 用一次真实请求串起 Agent/Model/Tool Span、Token、耗时和成本 |

## 3. 必须先修的两个问题

### 3.1 子 Agent 定义口径统一（已完成）

统一 `AgentRuntimeConfigurer.defaultSubagentDeclarations()`、`shared/agents/data-agent/subagents/*.md` 和 `prompts/system.md` 为 `data-explorer + report-writer`。同步委派监听框架转发的子 Agent `AgentStartEvent`，异步委派解析 `agent_spawn` 稳定返回头，两条路径共同投影到 MySQL 会话读模型；登记 `SUBAGENT/spawnedBy/spawnDepth/spawnRunId`，补齐 `/api/admin/sessions/{sessionKey}/tree`，让管理页展示真实调用树。

### 3.2 前端从固定 Agent 改为可切换 Agent（已完成）

由 `AppShell` 统一管理当前 Agent ID，并写入 URL `?agent=` 和 localStorage；聊天头部增加 Agent 下拉框；聊天、会话侧栏、工作区和配置页共享同一 Agent Context；切换 Agent 时清除旧会话参数并加载新 Agent。已注册用户级 `insight-agent` 完成真实验证：选择器可见 `Data Agent / Insight Agent`，切换后 URL、标题、描述、输入提示、模型和会话作用域同步更新，真实回复与 Gateway 日志确认请求路由至 `uca-admin-insight-agent` 并构建独立运行时。

## 4. 十四天安排

### 第 1-2 天：多 Agent 运行时

- 完成上面两个必修问题。
- 明确委派规则：未知表/字段/关联交给 `data-explorer`；数字和查询已核验后，报告交给 `report-writer`。
- 使用 `/chat` 发起“数据探索 -> SQL 分析 -> 报告生成”任务。
- 在 `/admin/sessions` 查看主/子 Agent 树，在 `/workspace` 查看计划和报告。

完成标准：前端能够切换 Agent，稳定出现至少一次子 Agent 调用，子 Agent 树包含真实 child session，主 Agent 能整合子 Agent 结果。

### 第 3-4 天：数据工具、HITL 与 SSE

- 体验 `list_data_sources -> describe_table -> run_sql_preview -> render_chart`。
- SQL 人工确认分别执行拒绝和批准；发起长任务后点击停止。
- 只修影响演示的问题，例如 rowLimit 边界、查询超时、错误信息和图表渲染。

完成标准：四工具、HITL 批准/拒绝、主动取消、图表和文件链接均能从聊天页观察。

### 第 5-7 天：生命周期、热重建与多模型路由

- 修改系统提示词并验证无需重启生效。
- 在一个标签页执行长回合，另一个标签页修改全局 Agent，观察旧回合和新请求边界。
- 在聊天头部切换 `local/longcat`；在 `/models` 配置用户连接，切换到个人 Agent 验证实际模型。

完成标准：能够分别讲清全局 Agent 热重建与用户 Agent 懒加载/缓存失效，Trace 中能看到实际模型。

### 第 8-10 天：Redis 与 Docker 沙箱

- 用户 A 生成报告，从 `/workspace` 查看；重启后端后恢复原会话和文件。
- 用户 B 使用另一浏览器登录，确认看不到用户 A 的文件。
- 从 `/admin/sessions` 对照 userId、agentId、session 与工作区路径。

完成标准：能证明“同用户可恢复、不同用户隔离、聊天工具与工作区 API 共用框架沙箱状态”。不宣称已经完成多 Pod 高可用验证。

### 第 11-12 天：Trace、Token 与成本

- 在 `/models` 配置单价，完成一次包含 SQL、图表和子 Agent 的请求。
- 在 `/traces` 展开 Agent/Model/Tool Span，在 `/usage` 查看 Token、耗时和成本。
- 主动取消一次请求并制造一次可控错误，观察 `CANCELLED/ERROR`。

完成标准：能从一次聊天请求追踪到子 Agent、工具、模型、Token 和成本，并解释为什么 Trace 不保存 Prompt 正文。

### 第 13-14 天：修复与面试演练

每个简历点整理一张面试卡：一句话价值、核心调用链、关键类、一个真实困难、根因、解决方案、方案取舍、当前局限。至少完整演练两次从登录到 Trace/Usage 的端到端操作。

## 5. 多 Agent 代码阅读路线

不要按包名从头到尾读，按一次请求的执行链阅读。

### 第一遍：建立主链

1. `ChatController.stream/executeChatStream`：HTTP 请求怎样变成 AgentScope 事件流。
2. `AgentLifecycleService.resolveGatewayAgentId/buildAndRegisterUca`：全局 Agent 和用户 Agent怎样选择、构建和注册。
3. `DataAgentBootstrap.Builder.build`：全局 Agent、Gateway、Channel 怎样在启动期装配。
4. `AgentRuntimeConfigurer.accept/defaultSubagentDeclarations`：Plan、Compaction、Memory、Permission、Sandbox 和两个子 Agent 怎样统一注入。
5. `prompts/system.md`：主 Agent 在什么条件下决定委派。
6. `shared/agents/data-agent/subagents/*.md`：子 Agent 接收什么输入、允许做什么、返回什么。

第一遍只回答三个问题：谁创建 Agent、谁决定委派、子 Agent 的结果怎样回到主 Agent。

### 第二遍：理解框架事件与会话树

1. AgentScope `SubagentsMiddleware`：如何向模型暴露 `agent_spawn/agent_send/agent_list`。
2. AgentScope `AgentSpawnTool`：如何创建子 Agent、分配 sessionId、传递 RuntimeContext 和转发子事件。
3. `ChatController.recordSubagentStart`：如何识别带 source 的子 Agent `AgentStartEvent`。
4. `ConversationService.recordSubagentSession/sessionTree`：如何把框架运行事件投影为业务会话树。
5. `RuntimeController.sessionTree` 与前端 `admin/SessionsPage`：后端树怎样显示到页面。

这里要分清：AgentScope runtime state 是执行事实；MySQL session tree 是方便产品查询与排障的读模型。

### 第三遍：理解上下文与隔离

1. `ChatController` 构造 `InboundMessage` 时的 userId、accountId、preferredAgentId。
2. `HarnessAgent` 使用 `(userId, sessionId)` 隔离 AgentState。
3. `AgentRuntimeConfigurer` 的 Compaction、Memory，以及 `data-explorer=ISOLATED`、`report-writer=SHARED` 的差异化工作区治理。
4. `WorkspaceManagerFactory/SharedSandboxFilesystem` 如何把 userId、agentId 映射到 Workspace 与 Sandbox。
5. `/admin/sessions`、`/workspace`、`/traces` 分别观察会话树、文件和调用链。

读完后自己画一张：`Browser -> ChatController -> ChatUiChannel -> HarnessGateway -> Main Agent -> agent_spawn -> Subagent -> result -> SSE`，并能在每个箭头上说出传递的关键 ID。

## 6. Day 1-2 实施结果（2026-07-20）

### 6.1 已完成

- 子 Agent 口径已统一为 `data-explorer + report-writer`，运行时声明、系统委派规则、工作区定义和前端展示一致；探索过程使用隔离工作区，报告交付使用共享工作区。
- 前端已取消固定 `data-agent`：`AppShell` 统一维护 `activeAgentId`，URL `?agent=`、localStorage、聊天、会话侧栏、工作区和配置页共用同一上下文；已注册用户级 `insight-agent`，从选择器切换后真实收到“Insight Agent 已接管本会话。”，并由 Gateway 路由日志确认不是仅切换页面文案。
- 已补 `/api/admin/sessions/{sessionKey}/tree`，同步子 Agent 通过带 `source` 的 `AgentStartEvent` 投影；异步 `timeout_seconds=0` 不转发 child event，因此额外从 `agent_spawn` 稳定返回头解析 `agent_id/session_id`。两条路径按 `subagent:{sessionId}` 幂等写入 `SUBAGENT/spawnedBy/spawnDepth/spawnRunId`。
- 已从前端走通 `data-explorer -> task_output -> 主 Agent SQL 校验 -> HITL 批准 -> report-writer -> 最终管理简报`；管理页展开主会话后可看到真实 `data-explorer` 和 `report-writer` child session。
- `report-writer` 已调整为 `WorkspaceMode.SHARED`，真实调用 `agent_spawn(agent_id=report-writer, task=..., timeout_seconds=120)` 后成功写入并回读 `reports/shared-handoff-test.md`；文件同步到 `local-workspaces/admin/data-agent`，前端工作区约 1.5 秒加载完成并可打开完整正文。
- 已将 `RUN_SESSION` 活动记录移到 SSE 完成、异常或取消之后，由单守护线程和 `SynchronousQueue` 有界提交；MySQL 会话与 Trace 继续作为权威记录，审计写入不再与主 Agent 抢占 Redis 用户级执行锁。

### 6.2 本次真实踩坑与面试讲法

1. **异步子 Agent 会话树缺节点**：最初只监听 `AgentStartEvent`；真实联调发现模型默认使用 `timeout_seconds=0`，后台任务走 `invokeAgent`，不会把子事件转发给父 SSE。最终保留同步事件路径，同时基于 `agent_spawn` 的稳定结果契约补异步投影，并用 toolCallId 作为 spawnRunId。这里可追问同步/异步事件语义、为何是读模型、幂等键和失败隔离。
2. **本地小模型不适合演示复杂工具编排**：`qwen2.5:1.5b` 在纯 CPU、4096 上下文下约 65 秒才出现首个工具调用，且复杂参数遵循不稳定；面试演示使用 LongCat，多模型路由仍保留本地模型用于离线和降级说明。这里可追问首 Token 延迟、上下文长度、模型能力与路由策略。
3. **沙箱冷启动影响首请求**：容器快照中的旧 containerId 失效时会先触发 retry-create，首请求增加约 1-2 秒；这是后续 Day 8-10 的生命周期与恢复重点，不在 Day 1-2 扩大修复范围。
4. **报告写成功但父 Agent 不可见**：真实日志显示 `report-writer.write_file` 成功，但它原先写入自己的隔离工作区，父 Agent 随后的 `read_file/glob_files` 无法找到。修复为 `data-explorer=ISOLATED`、`report-writer=SHARED`，让探索 scratch 隔离、最终 Markdown 对父 Agent 和 `/workspace` 可见。这里可追问为什么不把所有子 Agent 都共享、共享工作区的并发写风险和文件命名约束。
5. **Windows 下嵌套文件写入失败**：`write_file` 原先通过 `mkdir -p "$(dirname 'path')"` 创建父目录，Windows/Docker 命令封装在嵌套 `$()` 时截断 Shell 表达式，报 `expecting ")"`，同时影响会话树文件上传。修复为 Java 侧归一化并计算 POSIX 父目录，Shell 只执行无命令替换的确定性命令，同时保留分块 Base64 上传和临时文件原子 `mv`。这里可追问为什么不是简单替换斜杠、如何防路径穿越、分块与原子替换解决什么问题。
6. **报告已落盘但工作区页面持续转圈**：工作区读接口原先先恢复沙箱，再合并本地镜像；当 Redis 快照残留失效 containerId 时，恢复调用约 32 秒循环，镜像 fallback 无法及时执行。修复为本地镜像存在时 `tree/readFile` 直接走宿主机只读路径，仅镜像不存在时访问沙箱；写、移动、删除仍走沙箱，保持运行时事实来源不变。这里可追问镜像与沙箱的一致性边界、为何只对读取镜像优先、陈旧镜像如何处理。
7. **会话审计阻塞首个 SSE 事件**：`RUN_SESSION` 原先在 `ChatController` 内同步写入沙箱，冷恢复会让 SSE 在 Trace 和订阅建立前卡住；简单改为开流时异步仍会与 Agent 执行竞争同一个 Redis `ExecutionGuard`。最终把审计提交延后到流结束，并使用单线程、零容量交接队列避免积压。这里可追问为什么数据库会话是权威事实、审计允许丢弃的取舍及 Reactor `doFinally` 时机。

### 6.3 边操作边读代码

1. 在 `/chat` 新建会话，记录 URL 中的 `agent` 和 `session`；断点放在 `ChatController.stream()`、`executeChatStream()`，观察 `userId/agentId/conversationId/gateKey` 如何进入 `InboundMessage`。
2. 在 `/configure/subagents` 对照两个定义，阅读 `AgentRuntimeConfigurer.defaultSubagentDeclarations()` 和 `prompts/system.md`，回答“有哪些子 Agent、各自边界是什么、主 Agent 为什么委派”。
3. 发送只包含一次 `agent_spawn` 的短任务，展开前端执行轨迹；同步对照 AgentScope `AgentSpawnTool.agentSpawn/execLocalSync`，重点看 `agent_key/session_id/task_id`、`timeout_seconds=0` 与非 0 两条路径。
4. 断点放在 `ChatController.recordSubagentStart()` 和 `recordSubagentSpawnResult()`；分别解释同步 child event 与异步 tool result 为什么需要两种投影入口。
5. 打开 `/admin/sessions` 展开树，继续读 `ConversationService.recordSubagentSession/sessionTree` 和 `RuntimeController.sessionTree`，用同一个 sessionId 对上 SSE、MySQL 行与页面节点。
6. 最后再看上下文治理：沿 `AgentRuntimeConfigurer -> HarnessAgent -> AgentStateStore -> WorkspaceMode.ISOLATED`，整理主 Agent 与子 Agent共享什么、隔离什么、状态由谁持有。不要一开始从包目录顺序通读。

---

# D. 后续自动化证据计划（两周面试准备后再做）

## 0. 每个简历点统一产出

建议每完成一个点，在 `docs/resume-evidence/` 下建立一份证据文档，固定记录：

1. 简历原句和它对应的代码入口。
2. 测试环境、依赖版本、启动参数和数据集。
3. 正常、边界、并发、故障四类测试用例。
4. 测试命令、预期结果、实际结果及 JUnit/日志/Trace 截图。
5. 发现的问题、修复方案、修复前后对比。
6. 可量化指标及统计口径。
7. 面试时 3 分钟讲解稿和潜在追问答案。

不得只保存“测试通过”结论，必须保留可重复执行的命令、断言和原始结果。

## 1. 多 Agent 统一运行时测试

### 必测用例

- 校验全局 Agent 与用户自定义 Agent 均装配 Plan Mode、Compaction、Memory、Permission、Sandbox 和 Subagent。
- 校验默认包含 2 个子 Agent，模型 ID、最大迭代次数和 `WorkspaceMode.ISOLATED` 与配置一致。
- 构造主模型连续失败场景，验证最多 2 次重试后进入 fallback，记录实际调用次数与事件顺序。
- 构造超过 30 条消息的会话，验证压缩触发且最近 10 条消息得到保留。
- 验证 10 分钟节流刷新的边界：重复触发、进程重启和并发写入时不覆盖有效记忆。
- 让主 Agent 调用子 Agent 完成一个可断言任务，验证输入上下文、隔离文件和返回结果。

### 通过标准

- 全局与用户 Agent 的横切能力无缺项，配置快照可对比。
- fallback 的触发原因、重试次数和最终模型可从事件或 Trace 中还原。
- 子 Agent 不能读取未显式传递的其他隔离 Workspace 文件。
- 测试能够重复执行，不依赖真实收费模型；优先使用可控 Stub Model。

### 重点追问

1. Compaction 与长期 Memory 的职责边界是什么？
2. 模型重试如何避免非幂等工具重复执行？
3. 子 Agent 隔离后如何传递任务上下文？
4. 为什么统一配置器采用 Builder Consumer，而不是继承框架 Agent？

## 2. Redis 状态与 Docker 沙箱测试

### 必测用例

- 用户 A/B 在同名路径分别写文件，验证双方不能互相读取。
- 同一用户连续两次请求以及应用重启后，验证 Workspace 文件和 Agent 状态能够恢复。
- 启动两个应用实例并发操作同一隔离槽，验证 Redis ExecutionGuard 将执行串行化。
- 在持锁实例退出、Redis 短暂不可用、Snapshot 缺失或损坏时验证错误行为和恢复路径。
- 验证 `acquire -> start -> persist -> release -> lease.close` 在成功、超时和异常三条路径均执行完整。
- 在 Windows Docker Desktop 上复现路径转换问题，验证 POSIX 路径预创建与最多 8 次重试。

### 通过标准

- 用户间文件、状态和容器标识不存在串扰。
- 重启或切换应用实例后，已持久化状态可恢复且不会创建错误的第二状态槽。
- 并发操作期间同一隔离槽同时执行数始终不超过 1。
- 失败后不存在长期未释放 Lease；测试结束后容器与 Redis Key 数量符合预期。

### 重点追问

1. AgentState、Sandbox State 与 Workspace Snapshot 分别保存什么？
2. 30 分钟锁租约到期时如何避免双执行？
3. 为什么选择 USER 而不是 AGENT / SESSION 隔离？
4. Snapshot 反序列化后为什么需要重新绑定 Redis Client？

## 3. 数据工具、HITL 与 SSE 测试

### 必测用例

- 覆盖未知数据源、空 SQL、合法 SELECT/CTE、DDL/DML、注释绕过、多语句、大小写及异常 rowLimit。
- 验证默认 100 行、最大 500 行，并增加 Statement Timeout 防止慢查询长期占用连接。
- 使用真正的只读分析账号验证 INSERT/UPDATE/DELETE/DDL 即使绕过应用校验也会被数据库拒绝。
- 评估引入 SQL Parser/AST 校验，明确 MySQL CTE、子查询、函数和注释的兼容边界。
- 验证 `run_sql_preview` 先产生确认事件：拒绝时 SQL 不执行，批准时仅执行一次。
- 验证 SSE 的 token/tool/result/confirm/done/error 顺序、客户端取消、断网、180 秒空闲超时和 300 秒连接超时。
- 验证 Vega-Lite 仅接受 line/bar/area/scatter、合法 JSON 和内联 `data.values`，拒绝任意层级 `url`。

### 通过标准

- 只读约束至少形成“只读数据库账号 + AST/语句校验 + 行数限制 + 查询超时 + HITL”多层防护。
- 用户拒绝后数据库审计中不存在查询执行记录，批准后恰好执行一次。
- SSE 终止后 Reactor Subscription、pending confirm 和 active stream 均被清理。
- 工具错误以结构化结果返回，不导致整个 Agent 事件流永久挂起。

### 重点追问

1. 为什么关键词过滤不等于 SQL 安全？
2. `WITH` 为什么不一定只读？
3. HITL 恢复时如何关联原始 toolCallId？
4. SseEmitter 与 Reactor Flux 的取消和上下文如何双向传递？

## 4. 热重建与多模型路由测试

### 必测用例

- 修改全局 Agent 的提示词、模型、工具和技能配置，验证无需重启且下一次请求使用新配置。
- 在旧 Agent 执行流式回合时触发热重建，验证旧回合正常结束，新请求进入新实例。
- 连续或并发更新同一全局 Agent，验证本地 Map 与 Gateway 最终指向同一实例。
- 记录旧实例数量和资源占用，补充可验证的退休实例回收策略。
- 为两个用户配置不同 ModelCreationContext，验证 Base URL、模型名、缓存 ID 与凭据严格隔离。
- 更新或删除租户模型配置后，验证用户自定义 Agent 缓存失效并使用新模型或静态 fallback。
- 验证 API Key 加密、随机 IV、篡改检测、只写不回显以及错误密钥解密失败。

### 通过标准

- 热重建期间无永久阻塞会话，后续请求能够继续使用原会话状态。
- 并发更新后 Gateway 与 Agent Map 不出现长期不一致；失败路径能够回滚或明确报错。
- 退休实例最终可回收，不能仅依赖进程退出。
- 任一用户不能通过 API、日志、Trace 或缓存读取另一用户的模型密钥和连接信息。

### 重点追问

1. Gateway 与本地 Map 是两次写入，如何处理不一致窗口？
2. 为什么旧 Agent 不能在热重建请求中立即关闭？
3. 配置合并如何区分 null、空值和显式清空？
4. 模型缓存键为什么包含配置更新时间？

## 5. OTel Trace、Token 与成本测试

### 必测用例

- 构造一次请求包含多次主模型、子 Agent 和工具调用，验证 Token 按请求正确聚合且不重复。
- 覆盖普通输入 Token、缓存 Token、输出 Token及不同单价，验证微美元成本舍入公式。
- 验证 SUCCESS、ERROR、CANCELLED、TIMEOUT 各状态均产生一致的 Usage 与 Trace 终态。
- 验证 Trace 的 request -> agent -> model/tool 父子关系、traceId/spanId 和 Reactor Context 传播。
- 向 Prompt 和工具参数写入敏感标记，验证白名单导出的 Span 不包含正文和密钥。
- 模拟数据库写入失败、Exporter 抛异常和队列压力，验证主 Agent 请求不因观测链路失败。

### 通过标准

- Usage 汇总与底层 ModelCall 事件逐项相加结果一致。
- 成本计算覆盖缓存输入价格，误差不超过既定微美元取整规则。
- Trace 可以还原调用顺序，但不包含 Prompt、模型正文和 API Key。
- Exporter 故障只产生告警，不改变正常 Agent 请求结果。

### 重点追问

1. 子 Agent 的 Token 如何归属到根请求？
2. Reactor 切换线程后为什么会丢失 OTel Context？
3. BatchSpanProcessor 队列满时如何取舍数据完整性与主链路可用性？
4. 模型价格变化后历史成本如何保持可解释？

## 6. 能力贡献、Marketplace 与 ACL 测试

### 必测用例

- 首先取消 `MarketContributionServiceTest` 的跳过状态，使现有 7 个用例真实执行并通过。
- 覆盖 6 类贡献资产的 PENDING、APPROVED、REJECTED 状态转换及重复审批。
- 覆盖 `../`、绝对路径、反斜杠、符号链接和跨 Agent 路径逃逸。
- 验证原始 Payload、审核编辑稿、审核人、审核意见和最终落盘内容一致可追溯。
- 模拟数据库保存成功但文件写入失败，以及文件写入成功但数据库更新失败，设计补偿或 Outbox。
- 补充 Skill 版本不可变、版本列表、回滚和灰度安装后，再把“版本治理”写入简历。
- 覆盖 owner、global admin、普通用户、USER grant、WORKSPACE grant 的 CLONE/RUN/EDIT 权限矩阵。
- 对恶意 Skill 的 Prompt Injection、Shell 命令、远程资源和敏感文件访问建立静态扫描或沙箱验证。

### 通过标准

- 7 个现有跳过测试恢复执行，新增状态机、路径安全和一致性用例全部通过。
- 任一审批失败都不会留下“数据库已批准但共享文件不存在”的静默中间态。
- 已发布版本不可被覆盖，能够回滚到指定版本并记录审计事件。
- 未授权用户无法通过 ID 猜测、共享传播或 Workspace grant 越权访问其他 Agent。

### 重点追问

1. 数据库事务与文件系统写入如何保证最终一致性？
2. Skill 为什么需要版本不可变和回滚？
3. 如何防止 Skill Prompt Injection 与供应链投毒？
4. ACL 等级蕴含和多条授权冲突时如何裁决？

---

# E. 后续自动化推荐执行顺序

1. **第 1 轮：多 Agent 统一运行时**  
   先证明项目确实存在可控的主/子 Agent 编排、上下文治理和 fallback，这是 Agent 岗位最核心的区分点。
2. **第 2 轮：Redis + Docker 沙箱**  
   补真实依赖集成测试，形成“状态、快照、锁、隔离、恢复”完整证据链。
3. **第 3 轮：数据工具 + HITL + SSE**  
   优先加固只读账号、AST 校验和查询超时，再测试人工确认与流式取消。
4. **第 4 轮：热重建 + 多模型路由**  
   专门处理在途请求、并发更新、旧实例回收和租户模型隔离。
5. **第 5 轮：OTel + Token/成本**  
   形成可展示的 Trace、成本核算和故障隔离证据。
6. **第 6 轮：能力贡献 + Marketplace + ACL**  
   恢复 7 个跳过测试并补版本治理后，再决定是否替换简历第 5 点。

每轮完成后，只在有自动化测试、可复现日志和明确统计口径的前提下升级简历措辞。例如：

- “实现”升级为“通过 X 类用例验证”；
- “支持多副本”升级为“在 2 实例并发测试中验证同一隔离槽串行执行”；
- “避免阻塞”升级为“压测下观测写入未进入请求同步路径”；
- “安全只读”升级为“数据库只读账号 + AST 校验 + HITL 三层验证”。

---

# F. 综合面试追问

1. 为什么选 AgentScope Java 2.0，而不是 LangChain4j、Spring AI 或 LangGraph？
2. 项目中的“多 Agent”与“一个 Agent 调多个 Tool”本质区别是什么？
3. 如果重新设计，哪些能力会继续交给框架，哪些能力必须由应用层掌控？
4. 当前以 userId 作为租户边界有什么局限？组织级多租户如何迁移？
5. 如何建立 Agent 离线评测集，衡量 SQL 正确率、工具选择准确率和任务成功率？
6. 这个项目最大的失败或返工是什么？从自建容器池收敛到框架生命周期学到了什么？
7. 生物医学工程背景如何转化为 Agent 开发优势？你如何补齐操作系统、网络、数据库和并发基础？

> 最终原则：简历只写已经能通过代码、自动化测试和运行证据共同解释的结论。项目当前已经具备较完整的产品与工程骨架，但离“生产级 Agent 平台”仍主要缺少端到端评测、真实多实例验证、故障注入、安全纵深和版本治理。
