# agentscope-dataagent 项目完整文档

> 版本：2.0.0-SNAPSHOT | 更新时间：2026-07-09
>
> **当前状态校准（2026-07-10）：** 本历史总览中的沙箱章节仍描述了已删除的
> `UserSandboxPool`、`SandboxLock`、心跳和清理器等应用侧组件。当前代码直接使用
> AgentScope `SandboxManager`；这些旧细节只能作为历史参考，已核验的事实来源为
> [current-delivery-status-2026-07-10.md](current-delivery-status-2026-07-10.md)。
>
> - **2026-07-06**：WebFlux → Spring MVC 迁移（SseEmitter 替代 Flux<ServerSentEvent>）；提取 conversation 域：SessionAgentManager/SessionStore/SessionReadStateStore → ConversationService + JPA (SessionEntity)；拆分 BootstrapConfig（4 个 @ConfigurationProperties + 4 个 @Configuration）；提取 WorkspaceResolutionService；扩展贡献系统（MCP_SERVER 目标类型 + 市场生命周期审计）
> - **2026-07-07/08**：沙箱真实生命周期下沉到 AgentScope 框架（UserSandboxPool 委托框架 SandboxManager 管理 Docker 生命周期，自身仅保留常驻持有/空闲回收/invalidate 广播/DB 审计）；严格依赖方向收口（domain 不依赖 api/application/infrastructure，application 不依赖 api，AgentStateStore 单一装配源）；frontend 插件改为默认不激活的 `frontend` profile（后端构建彻底 Node-free）；本项目三层文档全部按当前代码重校
> - **2026-07-09（其二）**：沙箱加固——多副本并发与爆炸半径。①为 agent 的 `DockerFilesystemSpec` 注入 `SandboxSnapshotSpec` + `SandboxExecutionGuard`（由 `SandboxSnapshotConfig` 统一装配），修复**子代理（ISOLATED）沙箱无快照（`report-writer` 等产出文件回收即丢）**与 fallback 路径无快照两类 P0 隐患；②新增 `SandboxLock` 抽象（`InMemorySandboxLock` / `RedisSandboxLock`），由 `UserSandboxContextMiddleware` 在回合边界串行化同一 `(userId, agentId)`——这是 externalSandbox 路径（框架 `SandboxExecutionGuard` 被绕过）下**真正的多副本并发闸门**；③`UserSandboxPool.invalidate` 由"立即 docker rm 所有用户容器"改为**惰性 stale 标记**（下次 borrow 时再快照 + 重建），消除审批通过即打断活跃会话、峰值同时重建数十容器的爆炸半径；④新增管理台 `POST /api/agents/{id}/rebuild-workspace`（惰性重建），覆盖"绕过贡献流程手动改 shared/ 层"的共享层重载需求。详见 §14.7.6。
> 
>**注意**：本文档已更新对已删除的 SessionAgentManager/SessionStore 等类的引用，相关章节已标注删除或替换说明。

---

## 一、项目概览

### 1.1 一句话总结

dataagent 是一个**多租户、自进化的企业数据分析 Agent 平台**。每位数据分析师拥有一个私有 data agent（随个人习惯进化），团队把优秀的 SQL 技能、子智能体、图表模板通过审批流程沉淀到共享库，所有人都受益。

### 1.2 核心设计理念

- **多人并行进化、互不干扰。** 每个用户的 workspace 完全隔离（skills / memory / subagents / sessions），同一份初始 agent 在不同人手里会长成不同模样。
- **能力市场，不是大杂烩。** 磨出来的好内容（SQL 技能、子智能体、memory 备忘）可以提名 → 管理员审批 → 进入 `shared/` 共享库 → 下次所有人的 agent 自动看到。知识自下而上流动，但中间有道闸也就是需要管理员审核。
- **Sandbox 生命周期由应用方掌握。** Agent 执行的脚本在隔离 Docker 沙箱中运行，容器规格、回收策略、驱动工具链全部可由运维团队定制。
- **Agent 可分享。** 用户自建的 Agent 可通过 share API 授权给指定用户或全员（CLONE/RUN/EDIT 三级权限），不靠管理员中转。

### 1.3 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3 + Spring MVC (Tomcat/Servlet) |
| AI 引擎 | AgentScope 2.0 HarnessAgent (ReAct + Plan Mode + SubagentsMiddleware) |
| LLM | DashScope (qwen-max) / 可替换 |
| 沙箱 | Docker (DockerFilesystemSpec, USER 隔离) |
| 持久化 | MySQL (默认) + JPA/Hibernate |
| 分布式 | Redis (AgentStateStore + 会话状态) |
| 前端 | React SPA (TypeScript + Vite) |
| 通信 | SSE 流式 + JWT 认证 + REST API |

---

## 二、快速开始（5 分钟跑通）

### 2.1 前置准备

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| JDK | 21+ | 项目用 Java 21 |
| Maven | 3.9+ | 构建工具 |
| MySQL | 8.0+ | 主业务库 + 分析演示库（项目已移除 H2 依赖） |
| Docker | 可选 | 仅在需要 Sandbox 隔离时安装 |

**准备 MySQL 数据库**：项目需要两个独立的 MySQL 数据库，通过 `createDatabaseIfNotExist=true` 自动创建，无需手动建库：

| 数据库 | 用途 | 自动建表 |
|---|---|---|
| `agentscope_dataagent` | 主业务库（用户/Agent/会话/贡献等 JPA 实体） | Hibernate `ddl-auto=update` 自动建表 |
| `dataagent_analytics` | 分析演示库（Agent 调用 SQL 工具时查询的电商数据） | 启动时执行 `data-analytics-mysql.sql`（幂等） |

### 2.2 启动

```bash
# 编译（默认不含前端构建，只需 JDK + Maven）
mvn compile

# 打包（默认不含前端）
mvn package -DskipTests

# 如需把 React 前端一并构建进 jar：激活 frontend profile
mvn -Pfrontend package -DskipTests

# 运行（默认 mysql profile，连接本地 MySQL root/root）
java -jar target\agentscope-dataagent-2.0.0-SNAPSHOT-exec.jar
# 打开 http://localhost:8080
```

> **Windows 环境 Maven 注意**：Git Bash 下 `mvn` 脚本可能有路径转换问题，使用 `mvn.cmd` + Windows 路径格式即可：
>
> ```bash
> JAVA_HOME="D:\\jdk21" "D:\\apache-maven-3.9.16\\bin\\mvn.cmd" compile
> ```

### 2.3 默认账号

| 用户名 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `admin` | `admin` | user, admin | 所有 profile 都会注入（首次启动空表时由 `JpaUserStore.seedDefaultAdmin()` 创建） |

> 如需测试用户，用 `admin` 登录后在 `/admin/users` 页面创建。

**第一次登录建议用 `admin/admin`**——既是 owner（能管理 Agent 分享）也是管理员（能审批贡献、管用户）。

### 2.4 默认数据源

开箱即用的电商测试数据库（无需任何配置，启动时自动建表 + 灌数据）：

| 属性 | 值 |
|---|---|
| 数据源 id | `analytics_db` |
| 标签 | 电商业务数据库 |
| 类型 | MySQL 8 |
| JDBC URL | `jdbc:mysql://localhost:3306/dataagent_analytics` |
| 表 | `products`(15 行) / `users`(20 行) / `orders`(120+ 行) / `daily_sales`(每日汇总) |
| 覆盖品类 | 电子产品 / 运动户外 / 食品饮料 / 家居办公 / 图书教育 |

种子脚本在 [data-analytics-mysql.sql](file:///e:/demo/agentscope-dataagent/src/main/resources/data-analytics-mysql.sql)，启动时由 [AnalyticsDataConfig](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/AnalyticsDataConfig.java) 自动执行（幂等：DROP + CREATE + INSERT）。

### 2.5 第一个对话（Web UI）

1. 浏览器打开 `http://localhost:8080`
2. 用 `admin/admin` 登录
3. 在聊天框输入：`列出所有可用的数据源`
4. Agent 会调用 `list_data_sources` 工具，返回 `analytics_db`
5. 继续问：`analytics_db 里有哪些表？各表结构是什么？`
6. Agent 调用 `describe_table` 返回表结构
7. 继续问：`查询最近 7 天的日销售额，画个折线图`
8. Agent 调用 `run_sql_preview` 执行 SQL，再调 `render_chart` 出图

---

## 三、完整业务闭环

```
┌──────────────────────────────────────────────────────────────────┐
│  1. 管理员启动平台，配置 GLOBAL agent (data-agent)                 │
│     • 内置 SQL / 图表分析技能                                      │
│     • 内置 data-explorer / code-reviewer / report-writer 子代理    │
│     • Docker Sandbox 就绪                                         │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  2. 用户登录 (JWT) → 获得自己的 data agent 实例                    │
│     • 私有 workspace: skills/ memory/ subagents/ sessions/        │
│     • shared/ 只读层提供团队已审批的公共技能                        │
│     • Docker Sandbox 按 (userId, agentId) 隔离                    │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  3. 用户发起数据分析对话 (SSE 流式)                                │
│     • Agent 调用 list_data_sources → describe_table               │
│     • Plan Mode 先规划分析步骤 → 用户确认                          │
│     • Agent 调用 agent_spawn 分发子任务给子代理                    │
│       (code-reviewer 审查 SQL, report-writer 生成报告)             │
│     • Agent 调用 render_chart 生成 Vega-Lite 图表                  │
│     • Agent 调用 run_sql_preview 执行查询 (权限确认)               │
│     • Compaction 自动管理上下文窗口                                │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  4. 用户积累经验 → 技能进化                                        │
│     • memory/ 自动记录每日经验 (memory_search 可检索)              │
│     • Agent 可自学习新技能 (skill_manage)                          │
│     • 用户可手动编辑 workspace/skills/ 下的 SKILL.md              │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  5. 用户/Agent 提名贡献 → 管理员审批 → 共享库生长                  │
│     用户: POST /api/me/contributions                              │
│     管理员: 在 /admin/approvals 页审批                             │
│     通过: 写入 shared/skills/ → 所有用户可见                       │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  6. Agent 分享                                             │
│     owner: POST /api/agents/{id}/shares                           │
│     被授权者: 立刻在 /api/agents 列表里看到这个 Agent              │
│     权限级别: CLONE / RUN / EDIT                                   │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  7. 新用户加入 → 直接获得共享库积累的全部能力 + 一个全局的最初的Agent      │
│     循环回到步骤 2                                                │
└──────────────────────────────────────────────────────────────────┘
```

### 3.1 用户视角的一天

1. 登录 http://localhost:8080 → 看到自己的 agent 列表（含别人分享给你的）
2. 问："分析上月销售趋势，对比去年同期"
3. Agent 自动执行：`list_data_sources` → `describe_table` → Plan Mode 规划 → `agent_spawn code-reviewer` 审查 SQL → `agent_spawn report-writer` 生成报告 → `render_chart` 出图
4. 用户校对后，可以通过 `/contributions` 把这次的 SQL 模板提名到共享库
5. 次日，团队其他人就能用这个模板了

### 3.2 管理员视角的一天

1. 配置数据源（默认 `analytics_db` 开箱即用）
2. 管理用户和 Agent 实例（`/admin/users`、`/admin/agents`）
3. 审批 marketplace 贡献（`/admin/approvals`）
4. 查看平台用量统计（`/admin/usage`）
5. **调整 sandbox 策略 / 通道绑定**（`/admin/instances`、`/admin/channels`）

### 3.3 如何跑通本闭环（本地验证清单）

> 本章开头 §3 的 7 步流程，所需能力**在代码里已全部接好**，无需额外开发即可端到端验证：
> - 4 个数据分析工具：`DataAgentToolkit`（`list_data_sources` / `describe_table` / `run_sql_preview` / `render_chart`）
> - 子代理：`AgentRuntimeConfigurer` 已声明 `code-reviewer`（ISOLATED，不暴露给用户）与 `report-writer`（ISOLATED，暴露给用户）
> - Plan Mode：`b.enablePlanMode()` 已开启
> - Compaction：`triggerMessages(30)` + `toolResultEviction` 已配置
> - 权限：`PermissionMode.BYPASS` + `run_sql_preview` 单条 ASK 规则（执行 SQL 前需用户在 UI 确认）
> - 系统提示词 `prompts/system.md` 已引导 Agent 走"列数据源 → 看表 → Planner 规划 → 派子代理 → 画图 → 执行 SQL"的链路

**前置**：后端以 `mysql,redis` 启动、Docker daemon 可用、模型 API Key 已配（见 §17.1）。用 `admin/admin` 登录 Web UI 或走 SSE curl。

**依次发这 7 句话即可跑通**（每一步对应 §3 的一环）：

| # | 你说的话（示例） | 触发的能力 | 预期现象 |
|---|---|---|---|
| 1 | "列出所有可用的数据源" | `list_data_sources` | 返回 `analytics_db` |
| 2 | "看看 analytics_db 里 orders 表的结构和样例" | `describe_table` | 返回表结构 + 采样数据 |
| 3 | "帮我规划一下：分析最近 7 天日销售额并与去年同期对比" | **Plan Mode** | Agent 进入 plan，列出分析步骤，**等你点确认** |
| 4 | （确认后）"按这个计划执行，并用 code-reviewer 审查你的 SQL、用 report-writer 生成报告" | `agent_spawn` → `code-reviewer` / `report-writer` | 后台派两个子代理并行（report-writer 结果对用户可见） |
| 5 | "把日销售额趋势画成折线图" | `render_chart` | 返回 Vega-Lite spec（前端渲染图表） |
| 6 | "查询最近 7 天的日销售额" | `run_sql_preview` | **弹出权限确认**（HITL），批准后执行返回表格 |
| 7 | 继续多轮对话 | **Compaction** | 超过 30 轮后自动压缩上下文，早轮 tool_result 被裁剪，不爆窗口 |

**常见卡点排查**：
- 看不到确认弹窗 / 收到框架英文提示 → 见聊天链路文档的 HITL confirm 处理；`/reset` 可清掉残留确认状态。
- 日志出现 `SandboxConfigurationException: No active sandbox` → 属框架事后清理噪音，已在 `ChatController` 静默，对话不受影响（§14.7.5）。
- 工具报 `not implemented` → 检查 `dataagent.analytics.enabled=true` 且 `JdbcTemplate` 已注入（§6 工具表注释）。

---

## 四、技术架构

```
┌──────────────────────────────────────────────────────────┐
│  前端: React SPA (localhost:8080)                        │
│  SSE 流式事件 / REST API                                  │
└─────────────────────┬────────────────────────────────────┘
                      ▼
┌──────────────────────────────────────────────────────────────────┐
│  Spring Boot MVC (Tomcat)                                        │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │ config/  ── @ConfigurationProperties + 引导 + 安全配置     │  │  │
│  │  │   BootstrapConfig / DataAgentWorkspaceConfig         │  │  │
│  │  │   SecurityConfig (JWT + Spring Security) / WebConfig │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │ agent/  ── Agent 域 (api/application/domain/infrastruc│  │  │
│  │  │   api/          ── Agent*Controller (目录/技能/工具/绑定/克隆) │  │  │
│  │  │   application/  ── Catalog/Lifecycle/Mutation/ACL 编排 │  │  │
│  │  │   domain/       ── AgentDefinition / ACL / ShareGrant│  │  │
│  │  │   infrastructure/ ── JPA 实体 + 仓库                     │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │ conversation/ ── 会话域 (api/application/domain/infrastr│  │  │
│  │  │   api/          ── ChatController / SessionController│  │  │
│  │  │   application/  ── ConversationService + 定时任务        │  │  │
│  │  │   infrastructure/ ── SessionEntity (JPA)             │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │ workspace/ ── 沙箱 + 文件系统 (api/application/domain/infra│  │  │
│  │  │   api/          ── SandboxHeartbeatController        │  │  │
│  │  │   infrastructure/ ── UserSandboxPool + 文件系统适配        │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
│  │  ┌──────────────────────────────────────────────────────┐  │  │
│  │  │ capability / integration / runtime / tools           │  │  │
│  │  │ capability/  ── marketplace / contribution / template│  │  │
│  │  │ integration/ ── webhook / outbound (外部系统适配)          │  │  │
│  │  │ runtime/     ── AgentScope/Harness 适配 (Middleware)   │  │  │
│  │  │ tools/       ── 工具能力                                 │  │  │
│  │  └──────────────────────────────────────────────────────┘  │  │
└─────────────────────┬────────────────────────────────────────────┘
                      ▼
┌──────────────────────────────────────────────────────────┐
│  AgentScope 2.0 Harness                                  │
│  ┌────────────────────────────────────────────────────┐  │
│  │ HarnessAgent                                        │  │
│  │   • Plan Mode ── plan_enter/write/exit             │  │
│  │   • SubagentsMiddleware ── agent_spawn/send/list   │  │
│  │   • CompactionMiddleware ── 自动上下文压缩           │  │
│  │   • Memory Pipeline ── MEMORY.md + memory/         │  │
│  │   • PermissionEngine ── ALLOW/ASK/DENY             │  │
│  │   • DockerFilesystemSpec ── USER 隔离沙箱          │  │
│  └────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────┐  │
│  │ HarnessGateway ── 多 Agent 路由                      │  │
│  │ ChannelManager ── Channel 注册 / 消息投递           │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────┬────────────────────────────────────┘
                      ▼
┌──────────────────────────────────────────────────────────┐
│  runtime/  ── 核心运行时                                   │
│  ┌────────────────────────────────────────────────────┐  │
│  │ DataAgentBootstrap ── 编排 Agent + Channel 组装     │  │
│  │ AgentRuntimeConfigurer ── 统一运行时能力配置          │  │
│  │ conversation/ ── 会话域 (JPA: SessionEntity +        │  │
│  │   ConversationService + SessionController)           │  │
│  │ outbound/ ── 向 IM 通道主动推送消息                   │  │
│  │ channel/webhook/ ── HTTP Webhook 通道               │  │
│  │ middleware/ ── 自定义 Middleware                     │  │
│  │ tools/data/ ── 数据分析工具 (DSL 骨架)               │  │
│  │ marketplace/ ── 市场能力适配器                        │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

---

## 五、REST API 完整清单与使用指南

> 所有需要认证的接口都要在请求头带 `Authorization: Bearer <token>`。token 通过 `POST /api/auth/login` 获取。

### 5.1 认证：AuthController (`/api/auth`)

#### `POST /api/auth/login` — 登录

```bash
# 请求
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# 响应
{
  "token": "eyJhbGciOi...",
  "userId": "admin",
  "username": "admin",
  "roles": ["user", "admin"]
}
```

**预期效果**：返回 JWT token，后续所有请求都要带上。保存到环境变量方便测试：
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)
```

#### `GET /api/auth/me` — 当前用户信息

```bash
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.2 对话：ChatController (`/api/agents/{agentId}/chat`)

> `agentId` 默认是 `data-agent`。

#### `POST /stream` — SSE 流式对话（核心）

```bash
curl -N -X POST http://localhost:8080/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"列出所有可用的数据源"}'
```

**`-N` 必须**：禁用 curl 缓冲，否则看不到流式输出。

**预期效果**：依次输出 SSE 事件——
```
event:token
data:{"text":"我"}

event:token
data:{"text":"来"}

...

event:tool_call
data:{"tool":"list_data_sources","args":{}}

event:tool_result
data:{"tool":"list_data_sources","result":"..."}

event:done
data:{"sessionKey":"main-xxx"}
```

#### `POST /send` — 同步对话

```bash
curl -X POST http://localhost:8080/api/agents/data-agent/chat/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

**预期效果**：等 Agent 完整回复后一次性返回 JSON。

#### `GET /session?sessionKey=xxx` — 查询会话是否存在

```bash
curl "http://localhost:8080/api/agents/data-agent/chat/session?sessionKey=main-xxx" \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回 `{exists: true/false, sessionKey: ...}`。前端用它判断"有没有进行中的对话"。

---

### 5.3 Agent 目录与分享：AgentCatalogController (`/api/agents`)

#### `GET /api/agents` — 列出我能看到的 Agent

```bash
curl http://localhost:8080/api/agents \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回 `[AgentDefinition, ...]`，包含：
- 全局 Agent（scope=global，所有人可见）
- 自己创建的 Agent（scope=user, ownerId=自己）
- 别人分享给我的 Agent（带 shares 字段，我的权限在 tierForCurrentUser）

#### `GET /api/agents/{id}` — 查看单个 Agent

```bash
curl http://localhost:8080/api/agents/data-agent \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回 AgentDefinition，含 `shares`（分享列表）和 `tierForCurrentUser`（我当前权限）。

#### `POST /api/agents` — 创建自定义 Agent

```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "my-finance-agent",
    "name": "财务分析师",
    "description": "专门处理财务数据的 Agent",
    "sysPrompt": "你是一个财务分析专家...",
    "model": "dashscope:qwen-max"
  }'
```

**预期效果**：创建一个 scope=user、ownerId=当前用户的 Agent。返回完整的 AgentDefinition。

#### `PUT /api/agents/{id}` — 更新 Agent 配置

```bash
curl -X PUT http://localhost:8080/api/agents/my-finance-agent \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"高级财务分析师","description":"升级版"}'
```

**预期效果**：只有 owner 或 EDIT 权限能改。返回更新后的 AgentDefinition。

#### `DELETE /api/agents/{id}` — 删除 Agent

```bash
curl -X DELETE http://localhost:8080/api/agents/my-finance-agent \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：只有 owner 能删。

#### `POST /api/agents/{id}/shares` — 分享 Agent 给他人（新增）

```bash
# 给 alice 授予 RUN 权限
curl -X POST http://localhost:8080/api/agents/my-finance-agent/shares \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"granteeType":"USER","granteeId":"alice","tier":"RUN"}'

# 给所有登录用户授予 CLONE 权限
curl -X POST http://localhost:8080/api/agents/my-finance-agent/shares \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"granteeType":"WORKSPACE","granteeId":"*","tier":"CLONE"}'
```

**预期效果**：
- 只有 **owner** 能调用（被授权 EDIT 的人也不能分享，防止权限扩散）
- upsert 语义：同 (granteeType, granteeId) 已存在就更新 tier，否则新增
- 返回更新后的 AgentDefinition（含完整 shares 列表）
- alice 下次调 `GET /api/agents` 会看到这个 Agent，`tierForCurrentUser=RUN`

**权限级别说明**：

| Tier | 能做什么 |
|---|---|
| `CLONE` | 克隆一份变成自己的（最低） |
| `RUN` | 跟它聊天、调用工具 |
| `EDIT` | 修改配置（但不能再分享、不能删除） |

#### `DELETE /api/agents/{id}/shares?granteeType=USER&granteeId=alice` — 撤销分享（新增）

```bash
curl -X DELETE "http://localhost:8080/api/agents/my-finance-agent/shares?granteeType=USER&granteeId=alice" \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：精确匹配 (granteeType, granteeId) 移除一条记录。返回更新后的 AgentDefinition。alice 下次调 `GET /api/agents` 就看不到这个 Agent 了。

---

### 5.4 Agent 克隆：AgentCloneController (`/api/agents/{id}/clone`)

#### `POST /api/agents/{id}/clone` — 克隆 Agent

```bash
curl -X POST http://localhost:8080/api/agents/data-agent/clone \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"newAgentId":"my-clone-agent","name":"我的克隆 Agent"}'
```

**预期效果**：把源 Agent 的配置复制一份，ownerId 变成自己，shares 清空。需要源 Agent 的 CLONE 权限。

---

### 5.5 会话管理：SessionController (`/api/agents/{agentId}/sessions`)

#### `GET /inbox?limit=20&unreadOnly=false` — 会话收件箱

```bash
curl "http://localhost:8080/api/agents/data-agent/sessions/inbox?limit=20" \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回当前用户跟该 Agent 的所有会话列表，按最近活跃排序。类似微信的聊天列表。

#### `GET /{key}` — 查看对话详情

```bash
curl http://localhost:8080/api/agents/data-agent/sessions/main-xxx \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回该会话的结构化对话轮次（USER/ASSISTANT/TOOL）。

#### `POST /{key}/reset` — 重置会话

```bash
curl -X POST http://localhost:8080/api/agents/data-agent/sessions/main-xxx/reset \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：换一个新的 sessionId 和日志文件路径，Agent 记忆清空，但会话入口还在。类似"换个新本子"。

#### `PATCH /{key}/read` — 标记已读

```bash
curl -X PATCH http://localhost:8080/api/agents/data-agent/sessions/main-xxx/read \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：清除未读红点。

#### `DELETE /{key}` — 删除会话

```bash
curl -X DELETE http://localhost:8080/api/agents/data-agent/sessions/main-xxx \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：从列表移除，磁盘日志文件保留（可追溯）。

---

### 5.6 工作区文件：AgentWorkspaceController (`/api/agents/{agentId}/workspace`)

#### `GET /files?recursive=true` — 文件树

```bash
curl "http://localhost:8080/api/agents/data-agent/workspace/files?recursive=true" \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回当前用户沙箱容器里的文件树。

#### `GET /file?path=AGENTS.md` — 读文件

```bash
curl "http://localhost:8080/api/agents/data-agent/workspace/file?path=AGENTS.md" \
  -H "Authorization: Bearer $TOKEN"
```

#### `PUT /file?path=AGENTS.md` — 写文件

```bash
curl -X PUT "http://localhost:8080/api/agents/data-agent/workspace/file?path=AGENTS.md" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"# 新的 AGENTS.md 内容"}'
```

#### `POST /upload?path=/data/test.csv` — 上传文件（multipart）

```bash
curl -X POST "http://localhost:8080/api/agents/data-agent/workspace/upload?path=/data/test.csv" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/local/test.csv"
```

**预期效果**：文件上传到沙箱容器，Agent 立刻能读到。

#### `GET /memory` — 读取 Agent 记忆

```bash
curl http://localhost:8080/api/agents/data-agent/workspace/memory \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回 `MEMORY.md` 内容（Agent 的长期记忆）。

#### `POST /workspace` + `POST /workspace/scaffold` — 初始化/脚手架工作区

```bash
# 初始化工作区（确保目录与共享内容投影就位）
curl -X POST http://localhost:8080/api/agents/data-agent/workspace \
  -H "Authorization: Bearer $TOKEN"

# 按模板脚手架（生成标准 AGENTS.md / skills / subagents 骨架）
curl -X POST http://localhost:8080/api/agents/data-agent/workspace/scaffold \
  -H "Authorization: Bearer $TOKEN"
```

#### 文件管理（完整端点）

```bash
# 创建文件（与 PUT 类似，语义为"新建"）
curl -X POST "http://localhost:8080/api/agents/data-agent/workspace/file?path=/data/new.csv" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"a,b,c\n1,2,3"}'

# 移动/重命名文件
curl -X POST "http://localhost:8080/api/agents/data-agent/workspace/file/move" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"from":"/data/old.csv","to":"/data/new.csv"}'
```

> 其余文件端点见前文：`GET /files`（文件树）、`GET/PUT /file`（读/写）、`POST /upload`（上传）、`DELETE /file`（删除）。

#### 子代理管理

```bash
# 列出子代理
curl http://localhost:8080/api/agents/data-agent/workspace/subagents \
  -H "Authorization: Bearer $TOKEN"

# 新建/更新子代理
curl -X PUT http://localhost:8080/api/agents/data-agent/workspace/subagents/my-helper \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"# My Helper\n你是一个助手..."}'

# 从另一个 Agent 克隆子代理
curl -X POST http://localhost:8080/api/agents/data-agent/workspace/subagents/from-agent \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sourceAgentId":"data-agent","subagentName":"report-writer"}'

# 删除子代理
curl -X DELETE http://localhost:8080/api/agents/data-agent/workspace/subagents/my-helper \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.7 技能管理：AgentSkillsController (`/api/agents/{agentId}/skills`)

#### `GET /workspace` — 列出 workspace 中的技能

```bash
curl http://localhost:8080/api/agents/data-agent/skills/workspace \
  -H "Authorization: Bearer $TOKEN"
```

#### `PUT /workspace/{name}` — 创建/更新技能

```bash
curl -X PUT http://localhost:8080/api/agents/data-agent/skills/workspace/sql-template \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"# SQL Template\n## 用途\n月报 SQL 模板..."}'
```

#### `GET /repositories` — 列出技能仓库

```bash
curl http://localhost:8080/api/agents/data-agent/skills/repositories \
  -H "Authorization: Bearer $TOKEN"
```

#### `GET /repositories/{index}/skills` — 列出某仓库的技能

```bash
curl http://localhost:8080/api/agents/data-agent/skills/repositories/0/skills \
  -H "Authorization: Bearer $TOKEN"
# 单个技能详情：
curl http://localhost:8080/api/agents/data-agent/skills/repositories/0/skills/chart-rendering \
  -H "Authorization: Bearer $TOKEN"
```

#### `POST /workspace/install` — 从仓库安装技能

```bash
curl -X POST http://localhost:8080/api/agents/data-agent/skills/workspace/install \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"repositoryIndex":0,"skillName":"chart-rendering"}'
```

#### `POST /workspace/marketplace-install` — 从能力市场安装技能

```bash
curl -X POST http://localhost:8080/api/agents/data-agent/skills/workspace/marketplace-install \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"marketplaceId":"team-git","skillName":"chart-rendering"}'
```

---

### 5.8 工具配置：AgentToolsController (`/api/agents/{agentId}/tools`)

#### `GET /active` — 当前激活的工具

```bash
curl http://localhost:8080/api/agents/data-agent/tools/active \
  -H "Authorization: Bearer $TOKEN"
```

#### `GET /config` — 工具配置（白名单/黑名单）

```bash
curl http://localhost:8080/api/agents/data-agent/tools/config \
  -H "Authorization: Bearer $TOKEN"
```

#### `PUT /config` — 更新工具配置

```bash
curl -X PUT http://localhost:8080/api/agents/data-agent/tools/config \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"toolsAllow":["list_data_sources","describe_table","run_sql_preview","render_chart"]}'
```

#### `GET /catalog/builtins` — 内置工具目录

```bash
curl http://localhost:8080/api/agents/data-agent/tools/catalog/builtins \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回框架内置工具（read_file / write_file / edit_file / list_files / grep_files / glob_files / memory_search / memory_get / session_search / execute …）。

#### `GET /catalog/mcp-servers` — MCP 服务目录

```bash
curl http://localhost:8080/api/agents/data-agent/tools/catalog/mcp-servers \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回 `catalog/mcp-servers.json` 里登记的 MCP 服务清单。

---

### 5.9 贡献与审批：MarketContributionController + ContributionApprovalController

#### 用户提交贡献

```bash
# POST /api/me/contributions — 直接提交内容
curl -X POST http://localhost:8080/api/me/contributions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "targetType": "skill",
    "targetPath": "sql-template/SKILL.md",
    "rationale": "通用月报 SQL 模板",
    "payload": "# SQL Template\n..."
  }'

# POST /api/me/contributions/from-workspace — 从 workspace 文件提交
curl -X POST http://localhost:8080/api/me/contributions/from-workspace \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"targetType":"skill","sourcePath":"skills/my-skill/SKILL.md","rationale":"..."}'

# GET /api/me/contributions — 查看自己提交的
curl http://localhost:8080/api/me/contributions \
  -H "Authorization: Bearer $TOKEN"
```

#### 管理员审批

```bash
# 列出待审批（需 admin token）
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

curl "http://localhost:8080/api/admin/contributions?status=PENDING" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 审批通过
curl -X POST http://localhost:8080/api/admin/contributions/1/approve \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 驳回
curl -X POST http://localhost:8080/api/admin/contributions/1/reject \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"内容不够通用"}'
```

**预期效果**：审批通过后，内容写入 `shared/skills/`，所有用户的 Agent 下次启动时自动加载。

---

### 5.10 用户管理：AdminUserController (`/api/admin/users`，需 ADMIN)

```bash
# 列出所有用户
curl http://localhost:8080/api/admin/users \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 创建用户
curl -X POST http://localhost:8080/api/admin/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username":"charlie","initialPassword":"charlie123","roles":["user"]}'

# 重置密码
curl -X PATCH http://localhost:8080/api/admin/users/charlie/password \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"newPassword":"newpass"}'

# 修改角色
curl -X PATCH http://localhost:8080/api/admin/users/charlie/roles \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roles":["user","admin"]}'

# 删除用户
curl -X DELETE http://localhost:8080/api/admin/users/charlie \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

### 5.11 出站消息：OutboundController (`/api/outbound`)

```bash
curl -X POST http://localhost:8080/api/outbound/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "channelId":"dingtalk-prod",
    "peerKind":"USER",
    "peerId":"user123",
    "agentId":"data-agent",
    "markdown":"分析完成，请查收报告"
  }'
```

**预期效果**：通过指定 Channel 向 IM 通道推送消息。Agent 内部也可通过 `outbound_send` 工具调用。

---

### 5.12 Webhook 通道：WebhookCallbackController (`/api/webhook`)

#### 入站 Webhook

```bash
# 计算 HMAC 签名
SIG=$(echo -n '{"externalUserId":"alice","message":"how many users yesterday?"}' | \
  openssl dgst -sha256 -hmac "$SHARED_SECRET" | cut -d' ' -f2)

# 发送 webhook
curl -X POST http://localhost:8080/api/webhook/ops-webhook/inbound \
  -H "X-DataAgent-Sig: $SIG" \
  -H "Content-Type: application/json" \
  -d '{"externalUserId":"alice","message":"how many users yesterday?"}'
```

**预期效果**：外部系统通过 HTTP Webhook 与 data agent 交互，签名验证靠 HMAC-SHA256。

#### 出站长轮询

```bash
curl http://localhost:8080/api/webhook/ops-webhook/outbound/inbound-id-xxx \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.13 AI 草稿：AgentDraftController (`/api/agents/draft`)

```bash
curl -X POST http://localhost:8080/api/agents/draft \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":"一个专门处理财务数据、生成财务报表的 agent"}'
```

**预期效果**：AI 自动生成 Agent 配置草稿（name / sysPrompt / suggestedTools / suggestedSkills / suggestedSubagents），用户可基于草稿创建 Agent。

---

### 5.14 模板：TemplateController (`/api/templates`)

```bash
# 列出模板
curl http://localhost:8080/api/templates \
  -H "Authorization: Bearer $TOKEN"

# 查看单个模板
curl http://localhost:8080/api/templates/data-analyst \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.15 通道目录：ChannelDirectoryController

```bash
# 列出所有通道
curl http://localhost:8080/api/channels \
  -H "Authorization: Bearer $TOKEN"

# 列出通道类型
curl http://localhost:8080/api/channels/types \
  -H "Authorization: Bearer $TOKEN"

# 设置默认通道
curl -X POST http://localhost:8080/api/agents/data-agent/channels/dingtalk-prod/default \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.16 Marketplace 订阅：MarketplacesController (`/api/me/marketplaces`)

```bash
# 列出订阅
curl http://localhost:8080/api/me/marketplaces \
  -H "Authorization: Bearer $TOKEN"

# 订阅 Git marketplace
curl -X POST http://localhost:8080/api/me/marketplaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"team-git","type":"git","properties":{"url":"https://github.com/team/skills.git"}}'

# 列出 marketplace 中的技能
curl http://localhost:8080/api/me/marketplaces/team-git/skills \
  -H "Authorization: Bearer $TOKEN"

# 测试连接
curl -X POST http://localhost:8080/api/me/marketplaces/team-git/test \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.17 通道绑定：AgentBindingController (`/api/agents/{agentId}/bindings`)

```bash
# 列出绑定
curl http://localhost:8080/api/agents/data-agent/bindings \
  -H "Authorization: Bearer $TOKEN"

# 添加绑定
curl -X POST http://localhost:8080/api/agents/data-agent/bindings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"channelId":"dingtalk-prod","tier":"USER","peer":"user123"}'

# 删除绑定
curl -X DELETE http://localhost:8080/api/agents/data-agent/bindings/0?channelId=dingtalk-prod \
  -H "Authorization: Bearer $TOKEN"
```

---

### 5.18 活动日志：AgentActivityController (`/api/agents/{id}/activity`)

```bash
curl "http://localhost:8080/api/agents/data-agent/activity?limit=50" \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：返回该 Agent 的活动日志（创建、配置变更、分享等事件）。

---

### 5.19 Sandbox 心跳：SandboxHeartbeatController (`/api/internal/sandbox`)

```bash
curl -X POST "http://localhost:8080/api/internal/sandbox/heartbeat?containerId=xxx" \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：内部端点，沙箱容器内 sidecar 定期上报心跳。

---

## 六、Agent 可调用的工具

Agent 在对话中可调用以下工具。其中 4 个数据分析工具由 [DataAgentToolkit.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) 暴露，另 2 个由 `OutboundTool` / `ContributeWorkspaceTool` 暴露：

| 工具名 | 参数 | 作用 | 测试话术 |
|---|---|---|---|
| `list_data_sources` | 无 | 列出所有已配置数据源 | "列出所有可用的数据源" |
| `describe_table` | `source_id`, `table` | 返回表结构 + 5 行采样 | "看看 analytics_db 里 orders 表的结构" |
| `run_sql_preview` | `source_id`, `sql`, `row_limit`(可选,默认100,上限500) | 执行只读 SELECT/WITH 查询 | "查询最近 7 天的日销售额" |
| `render_chart` | `chart_type`, `vega_lite_spec` | 渲染 Vega-Lite 图表 | "画个折线图展示日销售额趋势" |
| `outbound_send` | `channel_id`, `peer_kind`, `peer_id`, `text`/`markdown` | 主动向已注册 IM 通道推送消息（见 §5.11） | "把报告发到钉钉群" |
| `contribute_to_workspace` | `source_paths`, `target_type`, `target_path`, `rationale` | 提名 workspace 文件到共享库，待管理员审批（见 §5.9） | "把这次的 SQL 模板提交到共享库" |

> 框架内置工具（read_file / write_file / edit_file / list_files / grep_files / glob_files / memory_search / memory_get / session_search / execute）由 AgentScope harness 自身注册，不在上表内。

**测试链路**：依次问"列出数据源 → 描述表 → 查询数据 → 画图"，能完整走完 4 个数据分析工具；`outbound_send` 与 `contribute_to_workspace` 需先配置通道 / 共享库后调用。

---

## 七、前端页面与路由

| 路径 | 页面 | 权限要求 | 说明 |
|---|---|---|---|
| `/login` | 登录页 | 公开 | JWT 登录 |
| `/chat` | 聊天页 | 登录 | 核心对话界面 |
| `/workspace` | 工作区浏览 | 登录（RUN 只读） | 浏览沙箱文件 |
| `/configure/skills` | 技能管理 | EDIT | 增删改 workspace 技能 |
| `/configure/subagents` | 子代理管理 | EDIT | 增删改子代理 |
| `/configure/channels` | 通道管理 | EDIT | 配置 IM 通道 |
| `/configure/tools` | 工具配置 | EDIT | 工具白名单/黑名单 |
| `/configure/shares` | 分享管理（新增） | EDIT | 管理 Agent 分享授权 |
| `/configure/settings` | Agent 设置 | EDIT | 系统提示词、模型等 |
| `/profile` | 个人资料 | 登录 | 修改密码等 |
| `/appearance` | 外观设置 | 登录 | 主题切换 |
| `/contributions` | 我的贡献 | 登录 | 提交/查看贡献 |
| `/bindings` | 通道绑定 | 登录 | 个人 IM 绑定 |
| `/usage` | 用量统计 | 登录 | 个人用量 |
| `/admin/overview` | 管理总览 | ADMIN | 平台概览 |
| `/admin/instances` | 实例管理 | ADMIN | Agent 实例列表 |
| `/admin/sessions` | 会话管理 | ADMIN | 所有用户会话 |
| `/admin/channels` | 通道管理 | ADMIN | 全局通道配置 |
| `/admin/agents` | Agent 管理 | ADMIN | 所有 Agent 列表 |
| `/admin/approvals` | 审批中心 | ADMIN | 贡献审批 |
| `/admin/users` | 用户管理 | ADMIN | 用户 CRUD |
| `/admin/usage` | 用量统计 | ADMIN | 平台用量 |
| `/admin/config` | 系统配置 | ADMIN | 运行时配置 |
| `/admin/debug` | 调试面板 | ADMIN | 诊断工具 |

**EditTierGate 守卫**：6 个 `/configure/*` 页面都走 [EditTierGate](file:///e:/demo/agentscope-dataagent/frontend/src/components/EditTierGate.tsx)，要求 `tierForCurrentUser === 'EDIT'`。非 EDIT 用户访问会被重定向回 `/chat`。

---

## 八、包结构与文件清单

> 共 145 个 Java 源文件，按业务域 + 四层（api / application / domain / infrastructure）组织。
> 依赖方向严格单向：`api → application → domain ← infrastructure`，`runtime`/`integration` 仅做框架适配。任何层都不得反向依赖（`domain` 不依赖 `api`/`application`/`infrastructure`）。

---

## 九、目录结构

```
agentscope-dataagent/
├── src/main/java/io/agentscope/dataagent/
│   ├── agent/                        # 0 个文件
│   │   ├── api/                        # 10 个文件
│   │   │   ├── AgentActivityController.java
│   │   │   ├── AgentBindingController.java
│   │   │   ├── AgentCatalogController.java
│   │   │   ├── AgentCloneController.java
│   │   │   ├── AgentDraftController.java
│   │   │   ├── AgentSkillsController.java
│   │   │   ├── AgentToolsController.java
│   │   │   ├── AgentToolsSupport.java
│   │   │   ├── AgentWorkspaceController.java
│   │   │   ├── ChannelDirectoryController.java
│   │   ├── application/                        # 15 个文件
│   │   │   ├── command/                        # 3 个文件
│   │   │   │   ├── AgentCreateRequest.java
│   │   │   │   ├── AgentDraft.java
│   │   │   │   ├── NamedFile.java
│   │   │   ├── AgentAccessGuard.java
│   │   │   ├── AgentAclService.java
│   │   │   ├── AgentActivityStore.java
│   │   │   ├── AgentCatalogService.java
│   │   │   ├── AgentDraftService.java
│   │   │   ├── AgentLifecycleService.java
│   │   │   ├── AgentMutationService.java
│   │   │   ├── AgentMutationSupport.java
│   │   │   ├── SkillFileService.java
│   │   │   ├── SkillInstallService.java
│   │   │   ├── SubagentService.java
│   │   │   ├── WorkspaceFileService.java
│   │   │   ├── WorkspaceFileSupport.java
│   │   │   ├── WorkspaceResolutionService.java
│   │   │   ├── WorkspaceSummaryService.java
│   │   ├── domain/                        # 4 个文件
│   │   │   ├── ActivityEvent.java
│   │   │   ├── AgentDefinition.java
│   │   │   ├── AgentShareGrant.java
│   │   │   ├── UserAgentDefinitionStore.java
│   │   ├── infrastructure/                        # 5 个文件
│   │   │   ├── AgentEntity.java
│   │   │   ├── AgentEntityRepository.java
│   │   │   ├── AgentShareEntity.java
│   │   │   ├── BindingPersistence.java
│   │   │   ├── JpaUserAgentDefinitionStore.java
│   ├── capability/                        # 0 个文件
│   │   ├── contribution/                        # 0 个文件
│   │   │   ├── api/                        # 2 个文件
│   │   │   │   ├── ContributionApprovalController.java
│   │   │   │   ├── MarketContributionController.java
│   │   │   ├── application/                        # 3 个文件
│   │   │   │   ├── ContributeWorkspaceTool.java
│   │   │   │   ├── ContributionToolRegistrar.java
│   │   │   │   ├── MarketContributionService.java
│   │   │   ├── domain/                        # 1 个文件
│   │   │   │   ├── FileEntry.java
│   │   │   ├── infrastructure/                        # 2 个文件
│   │   │   │   ├── ContributionEntity.java
│   │   │   │   ├── ContributionRepository.java
│   │   ├── marketplace/                        # 0 个文件
│   │   │   ├── api/                        # 1 个文件
│   │   │   │   ├── MarketplacesController.java
│   │   │   ├── application/                        # 3 个文件
│   │   │   │   ├── MarketplaceConfig.java
│   │   │   │   ├── UserMarketplacePersistence.java
│   │   │   │   ├── UserMarketplaceRegistry.java
│   │   │   ├── domain/                        # 4 个文件
│   │   │   │   ├── DataAgentMarketplace.java
│   │   │   │   ├── MarketSkillContent.java
│   │   │   │   ├── MarketSkillSummary.java
│   │   │   │   ├── MarketplaceConfigEntry.java
│   │   │   ├── infrastructure/                        # 5 个文件
│   │   │   │   ├── GitDataAgentMarketplace.java
│   │   │   │   ├── LocalApprovalMarketplace.java
│   │   │   │   ├── NacosDataAgentMarketplace.java
│   │   │   │   ├── UserMarketplaceEntity.java
│   │   │   │   ├── UserMarketplaceRepository.java
│   │   ├── template/                        # 0 个文件
│   │   │   ├── api/                        # 1 个文件
│   │   │   │   ├── TemplateController.java
│   │   │   ├── application/                        # 1 个文件
│   │   │   │   ├── TemplateRegistry.java
│   ├── common/                        # 1 个文件
│   │   ├── WorkspaceCopier.java
│   ├── config/                        # 6 个文件
│   │   ├── properties/                        # 4 个文件
│   │   │   ├── AgentProperties.java
│   │   │   ├── OllamaProperties.java
│   │   │   ├── SessionRedisProperties.java
│   │   │   ├── WorkspaceProperties.java
│   │   ├── BootstrapConfig.java
│   │   ├── DataAgentConfig.java
│   │   ├── JpaPersistenceConfig.java
│   │   ├── ModelConfig.java
│   │   ├── StateStoreConfig.java
│   │   ├── WebConfig.java
│   ├── conversation/                        # 0 个文件
│   │   ├── api/                        # 3 个文件
│   │   │   ├── ChatController.java
│   │   │   ├── ChatSupport.java
│   │   │   ├── SessionController.java
│   │   ├── application/                        # 6 个文件
│   │   │   ├── ConversationMigrationService.java
│   │   │   ├── ConversationService.java
│   │   │   ├── ConversationSupport.java
│   │   │   ├── SessionLifecycleScheduler.java
│   │   │   ├── SessionTurnParser.java
│   │   │   ├── UsageStore.java
│   │   ├── domain/                        # 5 个文件
│   │   │   ├── AgentManagerConfig.java
│   │   │   ├── HistoryResult.java
│   │   │   ├── SessionEntry.java
│   │   │   ├── SessionKind.java
│   │   │   ├── SessionMaintenanceConfig.java
│   │   ├── infrastructure/                        # 4 个文件
│   │   │   ├── SessionEntity.java
│   │   │   ├── SessionEntityRepository.java
│   │   │   ├── SessionReadStateEntity.java
│   │   │   ├── SessionReadStateRepository.java
│   ├── integration/                        # 0 个文件
│   │   ├── outbound/                        # 0 个文件
│   │   │   ├── api/                        # 1 个文件
│   │   │   │   ├── OutboundController.java
│   │   │   ├── application/                        # 1 个文件
│   │   │   │   ├── OutboundService.java
│   │   │   ├── domain/                        # 2 个文件
│   │   │   │   ├── OutboundRequest.java
│   │   │   │   ├── OutboundTool.java
│   │   ├── webhook/                        # 0 个文件
│   │   │   ├── api/                        # 1 个文件
│   │   │   │   ├── WebhookCallbackController.java
│   │   │   ├── application/                        # 1 个文件
│   │   │   │   ├── WebhookChannel.java
│   │   │   ├── domain/                        # 2 个文件
│   │   │   │   ├── WebhookChannelProperties.java
│   │   │   │   ├── WebhookInboundRequest.java
│   │   │   ├── infrastructure/                        # 3 个文件
│   │   │   │   ├── WebhookInboundMapper.java
│   │   │   │   ├── WebhookOutboundClient.java
│   │   │   │   ├── WebhookSignature.java
│   ├── runtime/                        # 2 个文件
│   │   ├── config/                        # 8 个文件
│   │   │   ├── AgentConfigEntry.java
│   │   │   ├── AgentscopeConfig.java
│   │   │   ├── BindingConfigEntry.java
│   │   │   ├── ChannelConfigEntry.java
│   │   │   ├── ChannelTypeRegistry.java
│   │   │   ├── SessionLifecycleConfig.java
│   │   │   ├── SkillRepositoryConfigEntry.java
│   │   │   ├── SkillRepositorySupport.java
│   │   ├── middleware/                        # 1 个文件
│   │   │   ├── UserSandboxContextMiddleware.java
│   │   ├── AgentRuntimeConfigurer.java
│   │   ├── DataAgentBootstrap.java
│   ├── security/                        # 1 个文件
│   │   ├── api/                        # 3 个文件
│   │   │   ├── AdminUserController.java
│   │   │   ├── AuthController.java
│   │   │   ├── UserController.java
│   │   ├── application/                        # 1 个文件
│   │   │   ├── JwtService.java
│   │   ├── domain/                        # 1 个文件
│   │   │   ├── UserStore.java
│   │   ├── infrastructure/                        # 4 个文件
│   │   │   ├── IdentityLinkStore.java
│   │   │   ├── JpaUserStore.java
│   │   │   ├── UserEntity.java
│   │   │   ├── UserEntityRepository.java
│   │   ├── SecurityConfig.java
│   ├── tools/                        # 0 个文件
│   │   ├── data/                        # 10 个文件
│   │   │   ├── AnalyticsDataConfig.java
│   │   │   ├── ChartRenderer.java
│   │   │   ├── DataAgentToolkit.java
│   │   │   ├── DataSource.java
│   │   │   ├── DataSourceRegistry.java
│   │   │   ├── DataToolkitConfig.java
│   │   │   ├── DataToolkitRegistrar.java
│   │   │   ├── InMemoryDataSourceRegistry.java
│   │   │   ├── MarkdownTables.java
│   │   │   ├── StubChartRenderer.java
│   ├── workspace/                        # 0 个文件
│   │   ├── api/                        # 1 个文件
│   │   │   ├── SandboxHeartbeatController.java
│   │   ├── application/                        # 3 个文件
│   │   │   ├── SandboxReaperService.java
│   │   │   ├── SharedWorkspaceSeeder.java
│   │   │   ├── WorkspaceScaffolder.java
│   │   ├── domain/                        # 2 个文件
│   │   │   ├── SandboxPool.java
│   │   │   ├── SharedWorkspaceProjection.java
│   │   ├── infrastructure/                        # 7 个文件
│   │   │   ├── DataAgentWorkspaceConfig.java
│   │   │   ├── SandboxLifecycleObserver.java
│   │   │   ├── SandboxLifecycleRecord.java
│   │   │   ├── SandboxLifecycleRepository.java
│   │   │   ├── SharedSandboxFilesystem.java
│   │   │   ├── UserSandboxPool.java
│   │   │   ├── WorkspaceManagerFactory.java
│   ├── DataAgentApp.java
├── src/main/resources/
│   ├── application.yml / application-mysql.yml / application-prod.yml / application-redis.yml
│   ├── catalog/mcp-servers.json          # MCP 服务目录
│   ├── data-analytics-mysql.sql         # 电商测试库种子
│   ├── logback-spring.xml               # 日志配置
│   ├── prompts/agent-draft.md           # AI Draft 提示词
│   ├── shared/agents/data-agent/        # 共享技能/子代理（投影进每个新容器）
│   └── workspace-template/              # 工作区模板
├── docs/
│   ├── project-overview.md               # 本文档
│   ├── 使用测试.md                        # 用户测试指南
│   └── 聊天链路协作详解.md                # 聊天链路文档
├── frontend/                             # React 前端源码
└── pom.xml                               # Maven 定义（默认不构建前端；`mvn -Pfrontend package` 才打包 React SPA）
```

## 十、配置参考

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `dataagent.dashscope.api-key` | — | DashScope API 密钥 |
| `dataagent.dashscope.model-name` | `qwen-max` | 模型名称 |
| `dataagent.workspace` | `$CWD` | 工作目录 (生产必填) |
| `dataagent.jwt.secret` | 开发占位 | JWT 签名密钥 (≥32 字符) |
| `dataagent.session.redis.enabled` | `false` | 启用 Redis 分布式状态兜底 |
| `dataagent.session.redis.host` | `localhost` | Redis 地址 |
| `dataagent.marketplace.enabled` | `true` | 启用能力市场 |
| `dataagent.marketplace.max-contribution-bytes` | `1048576` | 最大贡献大小 |
| `dataagent.agent.name` | `data-agent` | Agent 显示名 |
| `dataagent.agent.sys-prompt` | 内置 | 系统提示词 |
| `dataagent.analytics.enabled` | `true` | 启用电商分析演示数据库 |
| `dataagent.analytics.jdbc-url` | `jdbc:mysql://localhost:3306/dataagent_analytics...` | 分析库 JDBC URL（独立于主业务库） |
| `dataagent.analytics.username` | `${spring.datasource.username}` | 分析库用户名（默认继承主库） |
| `dataagent.analytics.password` | `${spring.datasource.password}` | 分析库密码（默认继承主库） |
| `dataagent.analytics.init-script` | `data-analytics-mysql.sql` | 启动时执行的种子脚本（幂等） |
| `server.port` | `8080` | HTTP 端口 |
| `dataagent.sandbox.idle-ttl-min` | `15` | 沙箱空闲多久（分钟）后被回收（`close()` + docker rm） |
| `dataagent.sandbox.eviction-poll-sec` | `60` | 后台空闲回收扫描间隔（秒） |
| `dataagent.session.redis.enabled` | `false`（默认 profile 含 `redis`→`true`） | 同时切换**状态后端**（`RedisAgentStateStore`）与**快照后端**（`RedisSnapshotSpec`）为 Redis，构成双分布式、消除重启丢沙箱 |
| `dataagent.sandbox.snapshot.jdbc.enabled` | `false` | 预留的 JDBC 快照开关；启用需先引入 `agentscope-extensions-mysql` 依赖并补一个 `JdbcSnapshotSpec(dataSource)` Bean（详见 §14.7.2） |

---

## 十一、构建与运行

```bash
# 编译（默认不含前端构建）
mvn compile

# 打包（默认不含前端）
mvn package -DskipTests

# 运行
java -jar target/agentscope-dataagent-*-exec.jar
# 打开 http://localhost:8080, 默认账号: admin/admin
```

> **Windows 环境 Maven 注意**：Git Bash 下 `mvn` 脚本可能有路径转换问题，使用 `mvn.cmd` + Windows 路径格式即可：
> ```bash
> JAVA_HOME="D:\\jdk21" "D:\\apache-maven-3.9.16\\bin\\mvn.cmd" compile
> ```

---

## 十二、相关资源

- [AgentScope Java v2 官方文档](https://java.agentscope.io/v2/en/docs/index.html)
- [AgentScope Java GitHub](https://github.com/agentscope-ai/agentscope-java)
- [官方示例代码](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/documentation)

---

## 十三、代码阅读指南

> 四步走，按数据流方向从"配置"到"对话"再到"工具"。

### Step 1 — 看清配置（5 分钟）

| 文件 | 看什么 |
|------|--------|
| [application.yml](file:///e:/demo/agentscope-dataagent/src/main/resources/application.yml) | 默认 MySQL 启动。同文件内含 `redis` profile（`---` 分隔），另有 `application-mysql.yml`/`application-prod.yml`/`application-redis.yml` 独立 profile 文件 |
| [BootstrapConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/config/BootstrapConfig.java) | **启动装配入口**——创建 `AgentRuntimeConfigurer` 和 `DataAgentBootstrap` 两个 Spring Bean。全局 Agent（如 `data-agent`）在 `DataAgentBootstrap.build()` 时从 `agentscope.json` 构建、注册进 `HarnessGateway`，并应用 `AgentRuntimeConfigurer`（Plan Mode/Compaction/Memory/Permission/Subagents/Sandbox）。用户自定义 Agent 在运行时由 `AgentLifecycleService` **注入这两个 Bean 后**，调用框架原生 `HarnessAgent.builder()` 创建、复用同一个 configurer 和 gateway 注册——BootstrapConfig 本身不直接装配 UCA |

### Step 2 — 启动管线（10 分钟）

| 文件 | 看什么 |
|------|--------|
| [DataAgentBootstrap.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/DataAgentBootstrap.java) | 从 `agentscope.json` 构建 HarnessAgent → 创建 Gateway → 绑定 Channel |
| [ConversationService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/conversation/ConversationService.java) | 会话查询/管理服务。基于 JPA `SessionEntity`，提供查询、重置、删除（取代已删除的 SessionAgentManager） |
| [UserSandboxPool.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/workspace/infrastructure/UserSandboxPool.java) | Docker 沙箱容器池。按 `(userId, agentId)` 懒创建/复用/回收 |
| [SecurityConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/config/SecurityConfig.java) | JWT 过滤器链、`/api/` 路径权限 |

### Step 3 — 对话是怎么走的（15 分钟）

| 文件 | 看什么 |
|------|--------|
| [ChatController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/conversation/api/ChatController.java) | **入口**。`POST /stream` 是核心——把一个用户消息变成 SSE 事件流：token → tool_call → tool_result → done |
| [DataAgentToolkit.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) | Agent 实际调用的四个工具：`list_data_sources` / `describe_table` / `run_sql_preview` / `render_chart` |
| [AnalyticsDataConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/AnalyticsDataConfig.java) | 独立的 MySQL 分析数据库 + DataSourceRegistry |
| [OutboundTool.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/integration/outbound/domain/OutboundTool.java) | Agent 向 IM 通道推送消息的 `outbound_send` 工具 |

### Step 4 — 权限与分享（10 分钟）

| 文件 | 看什么 |
|------|--------|
| [AgentAclService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/agent/application/AgentAclService.java) | Tier 三级(CLONE<RUN<EDIT) + Scope 三种(global/user/share)。`tierFor()` 算用户最高权限 |
| [AgentAccessGuard.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/agent/application/AgentAccessGuard.java) | `guard.require(userId, agentId, tier)` 两道门：可见性(404) + 权限级别(403) |
| [AgentCatalogService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/agent/application/AgentCatalogService.java) | `grantShare()`/`revokeShare()` 实现，upsert 语义。另见 [AgentLifecycleService](file:///e:/demo/agentscope/dataagent/src/main/java/io/agentscope/dataagent/agent/application/AgentLifecycleService.java)（运行时生命周期） |

### Step 5 — 高级功能（选读）

| 文件 | 看什么 |
|------|--------|
| `capability/marketplace/` | 技能贡献 → 审批 → 共享库流程 |
| `capability/marketplace/` | Git/Nacos 市场的适配器 |
| `integration/webhook/` | HTTP Webhook 入站通道（签名验证） |
| [UserSandboxPool.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/workspace/infrastructure/UserSandboxPool.java) | Docker 沙箱按 (userId, agentId) 生命周期管理 |

---

## 十四、沙箱隔离体系详解

> UserSandboxPool 是多租户隔离的核心——它为每个 `(userId, agentId)` 组合懒创建、缓存复用、空闲回收 Docker 沙箱容器，确保用户之间的文件系统完全物理隔离。

### 14.1 为什么需要沙箱隔离？

这是一个**多租户系统**——多个用户同时使用同一个 DataAgent 服务。如果没有隔离：

```
❌ 没有沙箱的世界：
┌─────────────────────────────────────┐
│           共享的 LocalFilesystem      │
│  用户 A 的文件: /workspace/a/data.csv │
│  用户 B 的文件: /workspace/b/data.csv │
│  ⚠️ Agent 执行 SQL 时可能读到别人的文件 │
└─────────────────────────────────────┘

✅ 有沙箱的世界：
┌──────────────┐  ┌──────────────┐
│ 用户 A 的容器  │  │ 用户 B 的容器  │
│ /workspace/   │  │ /workspace/   │
│  data.csv     │  │  data.csv     │
│  skills/      │  │  skills/      │
└──────────────┘  └──────────────┘
  物理隔离，互不可见
```

### 14.2 核心数据结构

```java
// 复合键：一个用户 + 一个 Agent = 一个沙箱
public record Key(String userId, String agentId) {}

// 缓存条目：沙箱实例 + 最后访问时间
private static final class Entry {
    final Sandbox sandbox;           // Docker 容器的抽象
    volatile long lastAccessMs;      // 最后访问时间（用于空闲回收）
}

// 核心：线程安全的缓存表
private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
```

**关键设计**：`ConcurrentHashMap` + `compute()` 保证同一个 key 的 `borrow()` 串行化，避免同一个用户并发创建两个容器。

### 14.3 五个核心方法

| 方法 | 行为 | 调用者 |
|------|------|--------|
| `borrow(userId, agentId)` | 已有缓存→返回+刷新时间；没有→创建新容器 | WorkspaceManagerFactory（浏览器端）、UserSandboxContextMiddleware（Agent 运行时） |
| `peek(userId, agentId)` | 只看不创建，返回 `Optional<Sandbox>` | 前端刚加载页面时，避免触发冷启动 |
| `invalidate(userId, agentId)` | **惰性标记 stale**（不立即销毁活跃容器）：标记后下次该用户 `borrow()` 时自动 `close()`（快照）+ 清状态 + 重建（加载新共享层 + 恢复用户文件）；userId=null 时标记该 Agent 所有用户 | MarketContributionService.approve（审批通过后让共享层变化生效）；管理台 `POST /api/agents/{id}/rebuild-workspace` |
| `evictIdle()` | 后台定时扫描，超过 idleTtl（默认 15 分钟）的容器自动关闭 | 内部定时线程 |
| `shutdownAll()` | 关闭所有沙箱，清空缓存 | Spring `@PreDestroy`（应用停机时） |

### 14.4 容器创建 / 恢复过程

> 注意：当前实现**不再直接调用 `client.create`**，而是委托框架 `SandboxManager.acquire()`，由它根据持久化状态决定「恢复同一容器」还是「新建」。快照后端（`SandboxSnapshotSpec`）也是注入的，不再是写死的 `NoopSnapshotSpec`。

```
borrow(userId, agentId) 首次调用
    │
    ▼
acquireAndStart(key)
    ├── 1. buildWorkspaceSpec(key)
    │       构建 WorkspaceSpec：把 {cwd}/shared/agents/{agentId}/ 下的
    │       AGENTS.md / skills/ / subagents/ / knowledge/ 投影（挂载）到容器
    │
    ├── 2. SandboxManager.acquire(ctx, rtc)   ← 框架决定 恢复 / 新建
    │       • Priority 3: AgentStateStore 中有该 (userId,agentId) 的 SandboxState
    │         → resume 同一容器；若 RedisSnapshotSpec 有工作区 tar → 自动恢复文件
    │       • Priority 4: 无状态 / 状态过期 → 全新创建 Docker 容器
    │       （ctx 携带注入的 snapshotSpec，由 SandboxSnapshotConfig 决定 Redis/Noop）
    │
    ├── 3. sandbox.start()
    │       启动容器
    │
    ├── 4. SandboxManager.persistState(...)   ← 把运行中状态写回 AgentStateStore
    │
    └── 5. recordLifecycle(key, sandbox)
            写 DB 记录（SandboxLifecycleRecord），跟踪容器 ID/状态/心跳
```

**共享内容投影**：每个新容器启动时，自动获得该 Agent 的共享内容（技能、子代理、知识库、AGENTS.md），但用户自己的文件是空的。若快照后端命中（Redis 中有该用户的 tar），则连上次跑出的 `MEMORY.md`、上传文件等一并恢复。

```
宿主机                              容器内部
{cwd}/shared/agents/data-agent/     /workspace/
├── AGENTS.md          ──投影──→    ├── AGENTS.md        (只读)
├── skills/            ──投影──→    ├── skills/          (只读)
├── subagents/         ──投影──→    ├── subagents/       (只读)
└── knowledge/         ──投影──→    └── knowledge/       (只读)
                                    └── (用户自己的文件)   (可读写)
```

### 14.5 重要的架构约束

| 约束 | 说明 |
|------|------|
| **常驻缓存表在内存** | `entries`（`ConcurrentHashMap<Key,Entry>`）是进程内路由表，重启即清空——它只决定"本 pod 是否已有该用户的容器引用"，**不存工作区数据**。真正的工作区文件由 `SandboxSnapshotSpec`（Redis 默认）持久化，容器引用由 `AgentStateStore` 持久化，二者配合实现跨重启恢复（见 §14.7） |
| **单副本路由** | 多副本部署必须用 sticky session（按 userId 粘性路由），否则不同 pod 会为同一用户重复 `borrow()` 创建多个容器。注意：**默认 `redis` profile 下工作区已能跨重启/跨副本恢复**，sticky 只是避免"重复建容器"的浪费与竞态，并非数据丢失的救命绳 |
| **懒创建** | 容器不会预创建，首次 `borrow()`（前端打开工作区或 Agent 运行时借沙箱）才启动（冷启动延迟） |
| **空闲回收** | 默认 15 分钟（`dataagent.sandbox.idle-ttl-min`，可通过 `dataagent.sandbox.eviction-poll-sec` 调扫描间隔）不活跃就 `close()`（docker rm），下次 `borrow()` 凭快照后端重建并恢复工作区 |
| **保留式关闭** | 应用 `@PreDestroy`（`shutdownAll()`）只 `stop()` 不 `docker rm`，保留容器于宿主机；重启后 `acquire` 的 Priority-3 恢复路径重新挂载同一容器（见 §14.7.3） |
| **共享内容投影** | 新容器自动挂载 `{cwd}/shared/agents/{agentId}/` 下的只读内容 |

### 14.6 用一个比喻总结

> **UserSandboxPool 就像酒店的"房间管理台"**：
>
> - `borrow()` = 客人来了，有房间就给钥匙，没房间就开一间新的
> - `peek()` = 只是看看客人有没有房间，不开新房
> - `invalidate()` = 客房服务更新了，让客人换一间新房间
> - `evictIdle()` = 客人走了很久，把房间收回
> - `shutdownAll()` = 酒店打烊，所有房间都关了
> - 每个房间（Docker 容器）里都有标准配置（共享内容投影），但客人的私人物品互不可见

---

### 14.7 Sandbox 生命周期由应用方掌握——当前方案详解

> 设计原则（见 §1.2 第 3 条）：**Sandbox 生命周期由应用方掌握**。框架（AgentScope Harness）只提供 Docker 原语与状态/快照存储机制，**何时创建/复用/回收/跨重启恢复**由本项目的 `UserSandboxPool` 决定，并通过注入两个"持久化后端" Bean 选择耐久策略。下面拆解当前落地方案。

#### 14.7.1 职责划分：应用方 vs 框架

| 层 | 负责什么 | 本项目具体落点 |
|---|---|---|
| **应用方（本项目）** | 生命周期决策：复合键、懒创建、常驻持有、空闲回收、关闭策略；并**注入持久化后端选型** | `UserSandboxPool`（实现 `SandboxPool`） |
| **框架 Harness** | Docker 容器原语（create/resume/stop/close/destroy）、`SandboxState` 序列化、`SandboxSnapshotSpec` 的 tar 打包/恢复 | `SandboxManager` / `DockerSandbox` / `RedisSnapshotSpec` |
| **注入的存储后端** | 持久化"容器引用"与"工作区 tar"，决定能否跨重启/跨副本恢复 | `AgentStateStore`（Redis/InMemory）+ `SandboxSnapshotSpec`（Redis/Noop） |

**关键认知**：应用方**不亲自调 `docker`**。它把"容器怎么活、状态存哪"取决于注入的两个 Bean——这正是"生命周期由应用方掌握"的真实含义：应用通过装配选择后端，框架照做。运维想换策略（如改用 JDBC 存快照），只需在 `SandboxSnapshotConfig` 加一个 Bean，无需碰 `UserSandboxPool`。

#### 14.7.2 四个可插拔后端（持久化 + 并发）

`UserSandboxPool` 现在构造时接收两类持久化后端，`UserSandboxContextMiddleware` 额外持有一个回合锁；全部由 `SandboxSnapshotConfig`（Redis / Noop 兜底）统一装配：

1. **隔离状态后端 `AgentStateStore`**（`StateStoreConfig` 装配）
   - `dataagent.session.redis.enabled=true`（默认 profile 含 `redis`）→ `RedisAgentStateStore`，持久化容器引用（`DockerSandboxState`）。
   - 否则 → `InMemoryAgentStateStore`（仅本 pod）。

2. **工作区快照后端 `SandboxSnapshotSpec`**（`SandboxSnapshotConfig` 装配）
   - `redis` 开启 → `RedisSnapshotSpec`：容器 `stop()`/`close()` 时框架把 `/workspace` 打成 tar 存进 Redis；下次 `acquire` 重建容器时**自动恢复**工作区文件（含 `MEMORY.md`、上传文件等）。
   - 未开启 → `NoopSnapshotSpec`：不单独存快照，依赖"保留容器"策略。
   - *JDBC 备选*（`JdbcSnapshotSpec`，复用应用 `DataSource`）因离线构建环境缺 `agentscope-extensions-mysql` 暂未接入，联网构建一次 + 加一个 `@ConditionalOnProperty` Bean 即启用。

3. **并发守卫 `SandboxExecutionGuard`**（`SandboxSnapshotConfig` 装配，注入到 agent 的 `DockerFilesystemSpec`）
   - `redis` 开启 → `RedisSandboxExecutionGuard`：对**框架托管**的沙箱（Priority 3/4、子代理 ISOLATED）用 Redis 租约串行化，避免多副本对同一隔离槽位竞态。
   - 否则 → `SandboxExecutionGuard.noop()`。
   - **关键限制**：主 Agent 走 `externalSandbox`（Priority 1），框架守卫在此路径被**绕过**，故它只保护子代理路径（见下文 `SandboxLock`）。

4. **回合级串行锁 `SandboxLock`**（`SandboxSnapshotConfig` 装配）
   - `redis` 开启 → `RedisSandboxLock`：跨进程串行化同一 `(userId, agentId)` 的 Agent 回合，覆盖 externalSandbox 路径（守卫够不到处）。
   - 否则 → `InMemorySandboxLock`（仅本 JVM 内有效）。
   - 由 `UserSandboxContextMiddleware` 在回合开始时 `lock()`、整条 Flux 终止时（`doFinally`）`unlock()`，覆盖 `acquire → run → release` 整段窗口。这是多副本下**真正的并发闸门**。

**为什么需要这四样**：①`AgentStateStore`（容器引用）只能恢复"同一个容器对象"，但容器被 recreate 后工作区文件是空的；②`SandboxSnapshotSpec` 补上"文件持久化"，二者配合才真正**彻底消除重启丢沙箱**；③④则是多副本并发的两道闸门——③保护子代理，④（external 路径）保护主 Agent 回合。

#### 14.7.3 生命周期状态机

```
          borrow(userId, agentId)
                 │
   ┌─────────────┴──────────────┐
   │ entries 命中？              │
   ├─ 是 → 刷新 lastAccessMs     │  （复用同一容器，跨会话续上工作区）
   └─ 否 → SandboxManager.acquire(ctx, rtc)
                 │
       ┌─────────┴──────────┐
       │ P3: 从持久化 state    │  ← RedisAgentStateStore 里有该 (userId,agentId) 的 SandboxState
       │     resume 同容器     │     且 RedisSnapshotSpec 里有工作区 tar → 恢复文件
       ├─────────┬───────────┤
       │ P4: 新建容器          │  ← 首跑 / 状态过期 / 无快照
       └─────────┴───────────┘
                 │ sandbox.start()
                 │ SandboxManager.persistState(...)  ← 把运行中状态写回 AgentStateStore
                 ▼
           返回并常驻持有（不释放，直到 evict / invalidate / shutdown）

   evictIdle() [后台, 每 evictionPollSec]
       lastAccessMs 超过 idleTtl(默认15m) → close() = stop()+docker rm + clearState

   shutdownAll() [@PreDestroy]
       detachQuietly() → sandbox.stop() 仅停不删，保留容器于宿主机
       → 重启后 acquire 的 P3 恢复路径凭持久化 SandboxState 重新挂载同一容器
```

#### 14.7.4 当前默认行为（开箱即用）

`application.yml` 默认 profile = `mysql,redis` → `dataagent.session.redis.enabled=true`：

- **状态后端 = Redis**，**快照后端 = RedisSnapshotSpec**（同一 Redis 实例，Jedis 客户端；与 `StateStoreConfig` 的 Lettuce 客户端共存无碍）。
- 因此**默认即"双分布式"**：应用关闭/容器被回收后，工作区 tar 在 Redis，下次重建自动恢复，**无需任何额外配置即消除重启丢沙箱**。
- 启动日志会打印：`沙箱快照后端 = RedisSnapshotSpec: redis=...`、`沙箱并发守卫(guard) = RedisSandboxExecutionGuard: ...`、`沙箱回合锁(lock) = RedisSandboxLock: ...`。
- **并发默认即防护**：`DataAgentBootstrap` 为每个 Agent 注册 `UserSandboxContextMiddleware`（持有 `RedisSandboxLock`），同一 `(userId, agentId)` 的回合被跨进程串行化；子代理（ISOLATED）路径额外由 `RedisSandboxExecutionGuard` 兜底。单副本（仅 `mysql`）则退化为 `InMemorySandboxLock` / `noop` 守卫，但 externalSandbox 路径仍由内存锁串行化。
- 若仅 `mysql`（无 `redis` profile）：状态后端=内存、快照后端=Noop，回到"单副本保留容器"模式——宿主机不被清、容器不 prune 时仍可用，但跨主机/被 prune 即丢。

#### 14.7.5 与早前"重启丢沙箱 / SandboxConfigurationException"的关系

- **"重启丢沙箱"**：上一轮（2026-07-08 之前）快照后端是写死的 `NoopSnapshotSpec`，容器重建即空工作区。本轮（2026-07-09）已改为注入式 `SandboxSnapshotSpec`，默认 Redis，**该问题彻底解决**。
- **`SandboxConfigurationException: No active sandbox` 日志**：属于**框架清理阶段的时序噪音**（call context 结束后中间件访问已回收沙箱文件系统），与持久化策略无关；已在 `ChatController.onErrorResume` 中以 `isCleanupNoise()` 静默吞掉，对话前端不再出现该报错（详见聊天链路文档与 §15）。
- **`invalidate` 不再打断活跃会话（P1 爆炸半径）**：原先 `MarketContributionService.approve` 通过 `invalidate(null, targetAgentId)` **立即 `close()`（docker rm）该 Agent 下所有用户容器**——审批通过即销毁数十个活跃会话、峰值同时重建。现已改为**惰性 stale 标记**：只标记、不立即销毁；标记后用户**下一次 `borrow()`** 才 `close()`（快照私有文件）+ 清状态 + 重建（挂载新共享层 + 从快照恢复私有文件）。进行中的会话不受影响，重建分散到各用户下次访问时；已被空闲回收的用户无需处理（下次冷启动本就加载新层）。管理台另提供 `POST /api/agents/{id}/rebuild-workspace` 显式触发同一惰性重建，覆盖"绕过贡献流程手动改 `shared/` 层"的场景。

---

## 十五、会话管理体系详解

> Session* 文件构建的是一个"聊天管理后台"，而不是"打电话的能力"。ChatController 的 stream/send 是"打电话"（harness 提供），Session* 文件是"聊天记录管理"（本项目实现）。

### 15.1 核心架构：两个接口各司其职

| 接口 | 方法 | 职责 | 是否调用 ConversationService |
|------|------|------|---------------------------|
| `GET /session` | `currentSession()` | 前端探测"有没有进行中的对话" | ✅ 调用 `findByGateKey()` |
| `POST /stream` | `stream()` | 发送消息，触发 Agent 处理 | ❌ 不调用，会话创建由 harness 框架内部处理 |

**`stream()` 和 `currentSession()` 不是串行关系，而是并行关系**：
- `currentSession()` → 算 key → 查会话 → 返回"有/无"（纯查询）
- `stream()` → 直接发消息 → Gateway 自动处理会话（黑盒）

### 15.2 ConversationService 的真实定位

**ConversationService 是会话的"消费者和管理者"，不是"生产者"。** 它没有 `create()`/`register()`/`add()` 方法——会话创建由 harness 框架在 `dispatchStream` 内部完成。

| 角色 | 组件 | 职责 |
|------|------|------|
| **会话生产者** | harness 框架 | 创建会话、写聊天日志（JSONL） |
| **会话消费者/管理者** | ConversationService | 查询、重置、删除（基于 JPA `SessionEntity`，数据在 MySQL） |

**数据流**：
```
harness 框架（运行时）──写 JSONL 日志──→ SessionTurnParser 解析
                                          ↓
MySQL (SessionEntity) ←── ConversationService 读写 ──→ SessionController
```

### 15.3 用微信类比理解

| 组件 | 对应微信的功能 | 在项目中 |
|------|--------------|---------|
| `ChatController` (stream/send) | 发消息/接电话 | **聊天能力**（harness 提供） |
| `SessionController` | 聊天列表 + 聊天记录 + 删除聊天 | **会话管理 UI** |
| `ConversationService` | 后台的聊天记录数据库 | **会话数据层**（JPA + MySQL） |
| `SessionEntity` (JPA) | 聊天记录存到云端 | **持久化层**（MySQL 表） |
| `SessionTurnParser` | 把聊天记录格式化展示 | **日志解析器** |
| `SessionLifecycleScheduler` | 自动清理过期聊天 | **定时任务** |

**如果你只关心"怎么发消息、怎么收到回复"，那确实不需要任何 Session* 文件。但如果你想做一个完整的聊天产品（有历史记录、有聊天列表、能删除、能重置），那就必须要有这套基础设施。**

---

## 十六、部署模式与多副本现状

> **重要前提**：本项目当前是**单机设计**。多副本部署需要改造 6+ 个核心组件，或配合 sticky session 规避。请先读完本章节再决定部署策略。

### 16.1 当前架构的多副本能力矩阵

| 组件 | 多副本能力 | 问题与风险 |
|---|---|---|
| 主业务库（MySQL） | ✅ 已支持 | JPA 实体在共享 MySQL，多副本天然共享 |
| AgentStateStore | ✅ 可选 Redis | `--spring.profiles.active=mysql,redis` 启用，跨副本共享会话状态 |
| SandboxLifecycleRecord | ✅ 已支持 | 容器生命周期记录在 MySQL，跨副本可见 |
| SandboxHeartbeatController | ✅ 已支持 | 心跳上报到共享 DB |
| **UserSandboxPool** | ✅ 默认四后端双分布式（`redis` profile） | 四个后端均由 `SandboxSnapshotConfig` 装配：`AgentStateStore`（Redis/InMemory）+ `SandboxSnapshotSpec`（Redis/Noop）+ `SandboxExecutionGuard`（Redis/Noop）+ `SandboxLock`（Redis/InMemory）。**默认 `mysql,redis` profile 下**：状态/快照进 Redis、`Guard`/`Lock` 进 Redis——容器引用、工作区 tar、并发闸门都跨副本共享（详见 §14.7）。`invalidate` 已改**惰性 stale**（不立即 docker rm 活跃容器，下次 borrow 再重建），消除审批即打断会话的爆炸半径。`SandboxLock` 是 externalSandbox 路径（框架守卫够不到）下**真正的并发闸门**；**仍需 sticky session** 的原因只剩"避免不同 pod 重复 `borrow()` 建多个容器 / 冷启动浪费"，正确性由粘性路由保证，`SandboxLock` 仅作防错安全网 |
| **ConversationService** | ✅ 已支持 | 会话数据在 MySQL（`SessionEntity` JPA），跨副本天然共享 |
| **SandboxReaperService** | ❌ 无分布式锁 | 每副本都跑 `@Scheduled` 清理，会重复 `docker rm` + 误杀其他副本活跃容器 |
| **SharedSandboxFilesystem** | ❌ 绑定单容器 | 持有单个 `Sandbox` 实例，跨副本无法访问容器文件 |
| **sticky session** | ⚠️ 仅文档约束 | 代码零实现，依赖外部 LB 配置 userId 亲和 |

### 16.2 推荐部署策略：sticky session + 单机增强

**最务实的方案**：用 LB 的 sticky session 把同一用户路由到同一副本，规避大部分多副本问题。

```
                    ┌─────────────────────────┐
                    │  Load Balancer (Nginx)   │
                    │  按 userId 亲和路由       │
                    │  upstream hash $arg_uid  │
                    └─────────────┬───────────┘
                                  │
              ┌───────────────────┼───────────────────┐
              ▼                   ▼                   ▼
      ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
      │   副本 A      │    │   副本 B      │    │   副本 C      │
      │  本地内存状态  │    │  本地内存状态  │    │  本地内存状态  │
      │  本地容器池    │    │  本地容器池    │    │  本地容器池    │
      └──────┬───────┘    └──────┬───────┘    └──────┬───────┘
             │                   │                   │
             └───────────────────┼───────────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │  共享存储层               │
                    │  • MySQL (业务+生命周期)  │
                    │  • Redis (AgentState)    │
                    │  • NFS (workspace 目录)  │
                    └─────────────────────────┘
```

**Nginx sticky session 配置示例**：

```nginx
upstream dataagent_backend {
    # 按 userId 哈希路由（从 JWT 解析或 query 参数获取）
    hash $arg_uid consistent;
    server replica-a:8080;
    server replica-b:8080;
    server replica-c:8080;
}

server {
    listen 80;
    location / {
        proxy_pass http://dataagent_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        # SSE 必须关掉 buffer
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}
```

**Kubernetes Ingress 配置示例**（用 nginx-ingress 的 affinity）：

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: dataagent-ingress
  annotations:
    nginx.ingress.kubernetes.io/affinity: cookie
    nginx.ingress.kubernetes.io/affinity-mode: persistent
    nginx.ingress.kubernetes.io/session-cookie-name: dataagent_route
    nginx.ingress.kubernetes.io/session-cookie-hash: sha1
spec:
  rules:
    - host: dataagent.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: dataagent
                port:
                  number: 8080
```

### 16.3 sticky session 仍存在的风险

即便用 sticky session，仍有 2 个风险需要警惕：

| 风险 | 触发场景 | 缓解措施 |
|---|---|---|
| **副本下线后用户重路由** | 副本 A 挂了，LB 把 alice 路由到副本 B。B 没有 alice 的内存状态，会重新创建容器+会话 | 副本重启后从 MySQL+Redis 恢复（ConversationService 查 MySQL SessionEntity、SandboxLifecycleRecord 查 DB）；会话数据不丢失 |
| **SandboxReaperService 误杀** | 副本 A 重启时 `cleanupOnStartup()` 扫 DB 把心跳>1分钟的 ACTIVE 容器全 `docker rm -f`，可能杀掉副本 B 正在用的容器 | **临时缓解**：注释掉 `cleanupOnStartup()` 或加 `instance_id` 字段过滤；**根本解决**：加 ShedLock 分布式锁 |

### 16.4 要真正支持多副本需做的改造（未来路线图）

如果未来要彻底摆脱 sticky session，需要改造以下组件：

| 优先级 | 组件 | 改造方案 | 工作量 |
|---|---|---|---|
| P0 | SandboxReaperService | 加 ShedLock 分布式锁 + `SandboxLifecycleRecord` 加 `owner_pod` 字段 | 小 |
| ~~P0~~ | ~~SessionStore~~ | ~~从 JSON 文件改为 JPA 表~~ ✅ **已完成**（会话域提取重构：`SessionEntity` + JPA + MySQL） | — |
| P1 | UserSandboxPool | entries 从内存 ConcurrentHashMap 改为 Redis Hash | 大 |
| ~~P1~~ | ~~SessionAgentManager~~ | ~~4 个内存索引改为 Redis~~ ✅ **已完成**（删除 `SessionAgentManager`，`ConversationService` 基于 MySQL 天然跨副本共享） | — |
| P3 | SharedSandboxFilesystem | 跨副本容器寻址（或保证 sticky） | 大 |

---

## 十七、生产环境检查清单

> 部署到生产前逐项检查。**未满足的项会导致生产事故**。

### 17.1 必填项（不满足不能上生产）

| # | 检查项 | 配置方式 | 验证方法 |
|---|---|---|---|
| 1 | **MySQL 8.0+ 已部署** | `application-mysql.yml` 的 `spring.datasource.url` | `mysql -u root -p -e "SELECT VERSION()"` |
| 2 | **两个数据库已创建** | `createDatabaseIfNotExist=true` 自动创建 | `SHOW DATABASES LIKE 'agentscope_dataagent'; SHOW DATABASES LIKE 'dataagent_analytics';` |
| 3 | **MySQL 密码已改** | 环境变量 `MYSQL_PASSWORD=<强密码>` | 不再是默认的 `root` |
| 4 | **JWT 密钥已设** | 环境变量 `DATAAGENT_JWT_SECRET=<≥32字符随机串>` | `echo $DATAAGENT_JWT_SECRET \| wc -c` ≥ 33 |
| 5 | **DashScope API Key 已设** | 环境变量 `DASHSCOPE_API_KEY=<sk-xxx>` | 启动日志无 "api-key is empty" 警告 |
| 6 | **workspace 目录可写** | 环境变量 `DATAAGENT_WORKSPACE=<绝对路径>` | `touch <path>/test && rm <path>/test` |
| 7 | **ddl-auto 改为 validate** | `--spring.profiles.active=prod,mysql,redis` | 启动日志无 "SchemaDdl" 变更 |
| 8 | **Docker daemon 可访问**（如需沙箱） | `docker info` 成功 | `docker ps` 不报错 |

### 17.2 强烈建议项

| # | 检查项 | 原因 |
|---|---|---|
| 9 | Redis 已部署（多副本必需） | `--spring.profiles.active=mysql,redis`，否则 AgentState 用内存兜底，副本重启丢状态 |
| 10 | Redis 密码已设 | `REDIS_PASSWORD=<强密码>` |
| 11 | HTTPS 反向代理 | Nginx/Caddy 终结 TLS，JWT 不裸传 |
| 12 | 日志持久化 | 挂载 `logs/` 目录到持久卷 |
| 13 | 数据库定期备份 | `mysqldump agentscope_dataagent > backup.sql` |
| 14 | Docker 容器资源限制 | `--memory=512m --cpus=0.5` 防止沙箱吃满主机 |
| 15 | 监控告警 | Actuator `/actuator/health` + Prometheus + Grafana |
| 16 | **关闭 SandboxReaperService 的 cleanupOnStartup**（多副本） | 避免副本重启误杀其他副本容器 |

### 17.3 启动命令模板

```bash
# 单机生产（最简）
java -jar dataagent.jar \
  --spring.profiles.active=prod,mysql \
  --DATAAGENT_JWT_SECRET=<32字符> \
  --DASHSCOPE_API_KEY=<sk-xxx> \
  --MYSQL_PASSWORD=<强密码>

# 多副本生产（sticky session + Redis）
java -jar dataagent.jar \
  --spring.profiles.active=prod,mysql,redis \
  --DATAAGENT_JWT_SECRET=<32字符> \
  --DASHSCOPE_API_KEY=<sk-xxx> \
  --MYSQL_PASSWORD=<强密码> \
  --REDIS_HOST=<redis-host> \
  --REDIS_PASSWORD=<redis-password>

# Docker 容器启动
docker run -d --name dataagent \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod,mysql,redis \
  -e DATAAGENT_JWT_SECRET=<32字符> \
  -e DASHSCOPE_API_KEY=<sk-xxx> \
  -e MYSQL_PASSWORD=<强密码> \
  -e REDIS_HOST=<redis-host> \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /opt/dataagent/workspace:/workspace \
  dataagent:latest
```

### 17.4 环境变量速查表

| 变量名 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `DATAAGENT_JWT_SECRET` | ✅ | dev 占位 | JWT 签名密钥（≥32 字符） |
| `DASHSCOPE_API_KEY` | ✅ | 空 | 阿里云 DashScope API Key |
| `MYSQL_PASSWORD` | ✅ | root | MySQL 密码 |
| `REDIS_HOST` | 多副本必填 | localhost | Redis 地址 |
| `REDIS_PASSWORD` | 多副本必填 | 空 | Redis 密码 |
| `DATAAGENT_WORKSPACE` | 建议 | $CWD | workspace 根目录 |
| `SERVER_PORT` | 否 | 8080 | HTTP 端口 |
| `SPRING_PROFILES_ACTIVE` | ✅ | mysql | profile 组合 |

---

## 十八、测试方案

### 18.1 单机功能测试（冒烟测试）

**目标**：验证单机部署后核心链路可用。

```bash
# 1. 启动服务
java -jar dataagent.jar --spring.profiles.active=mysql

# 2. 登录获取 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)
echo "Token: $TOKEN"

# 3. 验证 Agent 列表
curl -s http://localhost:8080/api/agents \
  -H "Authorization: Bearer $TOKEN" | jq .

# 4. 验证 SSE 流式对话（核心链路）
curl -N -X POST http://localhost:8080/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"列出所有可用的数据源"}'
# 预期：SSE 事件流（token → tool_call → tool_result → done）

# 5. 验证 SQL 工具
curl -N -X POST http://localhost:8080/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"查询 products 表前 5 条记录"}'
# 预期：Agent 调用 run_sql_preview 工具，返回 5 条产品数据

# 6. 验证分享 API
curl -s -X POST http://localhost:8080/api/agents/data-agent/shares \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"granteeType":"WORKSPACE","granteeId":"*","tier":"RUN"}' | jq .
# 预期：返回 AgentDefinition，shares 字段含新授权

# 7. 验证工作区文件
curl -s "http://localhost:8080/api/agents/data-agent/workspace/files?recursive=true" \
  -H "Authorization: Bearer $TOKEN" | jq .
# 预期：返回沙箱容器内文件树

# 8. 验证数据库种子数据
mysql -u root -p -e "USE dataagent_analytics; SELECT COUNT(*) FROM orders;"
# 预期：120
```

### 18.2 多副本测试方案（单机模拟）

> 目标：在单机上模拟多副本，验证 sticky session 必要性和已知风险。

**步骤 1：启动两个副本**

```bash
# 副本 A（端口 8081）
java -jar dataagent.jar \
  --spring.profiles.active=mysql,redis \
  --server.port=8081 \
  --DATAAGENT_WORKSPACE=/tmp/replica-a

# 副本 B（端口 8082，共享同一 MySQL + Redis）
java -jar dataagent.jar \
  --spring.profiles.active=mysql,redis \
  --server.port=8082 \
  --DATAAGENT_WORKSPACE=/tmp/replica-b
```

**步骤 2：验证 Redis 共享 AgentState**

```bash
# 在副本 A 发起对话
TOKEN_A=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

curl -N -X POST http://localhost:8081/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'

# 从 SSE 响应里拿到 sessionKey
SESSION_KEY=main-xxx

# 在副本 B 查询同一会话（验证 Redis 共享）
TOKEN_B=$(curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

curl "http://localhost:8082/api/agents/data-agent/chat/session?sessionKey=$SESSION_KEY" \
  -H "Authorization: Bearer $TOKEN_B"
# 预期：存在（AgentState 在 Redis 共享）
# 会话数据在 MySQL（ConversationService 查 SessionEntity）→ 跨副本一致，不会丢失
```

**步骤 3：验证已知风险——容器重复创建**

```bash
# 在副本 A 借用沙箱（通过聊天触发容器创建）
curl -N -X POST http://localhost:8081/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"message":"执行 ls 命令"'

# 查看副本 A 创建的容器
docker ps --filter "label=dataagent"

# 在副本 B 给同一用户发起对话（无 sticky session → 副本 B 会重新创建容器）
curl -N -X POST http://localhost:8082/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN_B" \
  -H "Content-Type: application/json" \
  -d '{"message":"执行 ls 命令"'

# 再次查看容器
docker ps --filter "label=dataagent"
# 预期：有 2 个容器（每个副本各创建一个）→ 验证了 UserSandboxPool 不共享的问题
```

**步骤 4：验证已知风险——SandboxReaperService 重复清理**

```bash
# 两副本都跑 @Scheduled(fixedRate=60000) 清理任务
# 等待容器空闲超时（默认 15 分钟）或手动触发
# 观察日志：两副本都会对同一超时容器执行 docker stop
tail -f /tmp/replica-a/logs/data-agent.log | grep -i reap
tail -f /tmp/replica-b/logs/data-agent.log | grep -i reap
# 预期：两副本都尝试清理同一容器（一个成功，一个 warn "container not found"）
```

**步骤 5：验证 sticky session 效果**

```bash
# 用 Nginx 做反向代理 + sticky session
cat > /tmp/nginx.conf <<'EOF'
upstream dataagent {
    hash $arg_uid consistent;
    server 127.0.0.1:8081;
    server 127.0.0.1:8082;
}
server {
    listen 8080;
    location / {
        proxy_pass http://dataagent;
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}
EOF
nginx -c /tmp/nginx.conf

# 同一用户的请求总是路由到同一副本 → 不会重复创建容器
curl "http://localhost:8080/api/agents?uid=admin" -H "Authorization: Bearer $TOKEN_A"
```

### 18.3 测试用例速查表

| 测试场景 | 验证点 | 预期结果 | 命令 |
|---|---|---|---|
| 登录 | JWT 签发 | 返回 token | `POST /api/auth/login` |
| 流式对话 | SSE 事件流 | token→tool_call→tool_result→done | `POST /api/agents/{id}/chat/stream` |
| SQL 查询 | `run_sql_preview` 工具 | 返回查询结果 | 问 "查询 products 前 5 条" |
| 图表生成 | `render_chart` 工具 | 返回 Vega-Lite spec | 问 "画日销售额折线图" |
| Agent 分享 | `POST /shares` | shares 列表新增 | `POST /api/agents/{id}/shares` |
| Agent 克隆 | `POST /clone` | 新 Agent 创建 | `POST /api/agents/{id}/clone` |
| 工作区文件 | 文件树 | 返回 JSON 树 | `GET /workspace/files` |
| 会话列表 | inbox | 返回会话数组 | `GET /sessions/inbox` |
| 贡献审批 | 提交→审批→共享 | shared/ 目录出现新文件 | `POST /api/me/contributions` |
| 多副本 Redis 共享 | AgentState 跨副本 | 副本 B 能读到副本 A 的状态 | 步骤 2 |
| 多副本容器隔离 | UserSandboxPool | 每副本独立容器池 | 步骤 3 |
| sticky session | LB 亲和路由 | 同一用户固定副本 | 步骤 5 |

### 18.4 压力测试建议

```bash
# 用 wrk 或 hey 压测 SSE 流式接口
# 注意：SSE 是长连接，并发数 = 同时在线用户数
hey -z 60s -c 50 -m POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}' \
  http://localhost:8080/api/agents/data-agent/chat/send

# 关注指标：
# - QPS（同步 send 接口）
# - P99 延迟（LLM 响应时间，通常 2-10s）
# - MySQL 连接池水位（HikariCP maximum-pool-size=20）
# - Docker 容器数量（UserSandboxPool entries size）
# - JVM 堆内存（Agent 上下文 + 工具结果缓存）
```


