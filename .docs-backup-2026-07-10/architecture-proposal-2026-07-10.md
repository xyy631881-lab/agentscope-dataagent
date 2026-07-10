# 历史架构设计稿（2026-07-10）

> 本文件于 2026-07-10 从仓库根目录移入 `docs/`，保留其中的设计材料，同时避免被误认为
> 当前实现。文中包含应用侧沙箱注册表、租约、清理器、心跳/审计 API 和锁等提案；这些组件
> 当前均不存在于源码中。
>
> 沙箱生命周期的当前结论和交付状态见
> [current-delivery-status-2026-07-10.md](current-delivery-status-2026-07-10.md)；历史项目总览见
> [project-overview.md](project-overview.md)。

# agentscope-dataagent 项目完整文档（历史设计稿）

> 版本：2.0.0-SNAPSHOT | 更新时间：2026-07-09
>
> - **2026-07-10**：修复 `WorkspaceStartException: Failed to start workspace at \workspace`——根因是 Windows 上 `Path.of("/workspace")` 被转为 `\workspace`，Linux 容器内 sh 把 `\w` 当作转义序列，实际创建的是 `$PWD/workspace` 而非 `/workspace`。新增 `RetryStartDockerSandbox`：在 `super.start()` 前用硬编码正斜杠字符串预创建 `/workspace`（绕过 Java Path API 的 Windows 路径转换），同时保留重试 + 容器状态探测。详见 §14.15。

---

## 一、项目概览

### 1.1 一句话总结

dataagent 是一个**多租户、自进化的企业数据分析 Agent 平台**。每位数据分析师拥有一个私有 data agent（随个人习惯进化），团队把优秀的 SQL 技能、子智能体、图表模板通过审批流程沉淀到共享库，所有人都受益。

### 1.2 核心设计理念

- **多人并行进化、互不干扰。** 每个用户的 workspace 完全隔离（skills / memory / subagents / sessions），同一份初始 agent 在不同人手里会长成不同模样。
- **能力市场，不是大杂烩。** 磨出来的好内容（SQL 技能、子智能体、memory 备忘）可以提名 → 管理员审批 → 进入 `shared/` 共享库 → 下次所有人的 agent 自动看到。知识自下而上流动，但中间有道闸也就是需要管理员审核。
- **Sandbox 生命周期由应用方掌握。** 应用方负责沙箱策略、租约、文件挂载、会话绑定、空闲回收、失效重建、审计与配额；AgentScope 负责按应用方下发的 SandboxSpec 执行底层 Docker 创建、启动、停止与工具运行。
- **Agent 可分享。** 用户自建的 Agent 可通过 share API 授权给指定用户或全员（CLONE/RUN/EDIT 三级权限），不靠管理员中转。
- **容器不是数据源。** Docker 容器是可随时销毁的执行环境，用户文件、记忆、技能必须落在持久化层（Redis 快照 / 宿主机目录 / 共享存储），而非容器 writable layer。当前实现已通过 Redis 快照 + `shutdownAll()` 保留式关闭实现跨重启恢复，但快照时机与会话状态的同步仍有缺口（详见 §15.7）。
- **多副本需配套治理。** 单机可稳定运行；多副本部署已在代码层补齐 `SandboxLock`（回合边界串行化 + 多副本 Redis 锁）与 `SandboxExecutionGuard`（子代理路径并发保护），但 Docker 容器不跨主机可达——因而仍**必须在网关层配置粘性路由**（按 userId 亲和），`RedisSandboxLock` 仅作防错安全网（详见 §14.14）。

### 1.3 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3 + Spring MVC (Tomcat/Servlet) |
| AI 引擎 | AgentScope 2.0 HarnessAgent (ReAct + Plan Mode + SubagentsMiddleware) |
| LLM | DashScope (qwen-max) / 可替换 |
| 沙箱 | Docker + AgentScope SandboxManager + 应用侧 SandboxRegistry/SandboxLeaseService（USER 隔离） |
| 持久化 | MySQL (默认) + JPA/Hibernate |
| 分布式 | Redis（AgentStateStore、运行态租约、分布式锁、会话状态兜底） |
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
│     • Docker Sandbox 按 (tenantId,userId,agentId) 隔离             │
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
│  │  │   application/  ── WorkspaceService/SandboxLease/Binding    │  │  │
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

#### `POST /api/agents/{id}/rebuild-workspace` — 触发工作区惰性重建（新增）

```bash
curl -X POST http://localhost:8080/api/agents/data-agent/rebuild-workspace \
  -H "Authorization: Bearer $TOKEN"
```

**预期效果**：将该 Agent 下**所有用户**的沙箱标记为失效（惰性 stale），各用户**下一次聊天**时自动快照+重建容器（加载最新共享层 + 恢复私有文件）。不打断进行中的会话。

**使用场景**：
- 管理员**绕过贡献审批流程**手动编辑了 `shared/agents/{agentId}/` 下的共享文件 → 调用此端点让所有用户容器下次重建时加载新内容
- 贡献审批通过后 `MarketContributionService.approve` 会自动调用同一机制——此端点供手动覆盖使用

**权限**：需要对该 Agent 的 EDIT 权限（全局 Agent 由管理员持有；用户 Agent 仅 owner）。

**返回**：`202 Accepted`（异步标记，实际重建在用户下次发送消息时触发）。

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


### 5.20 沙箱管理：AdminSandboxController（建议新增，`/api/admin/sandboxes`，需 ADMIN）

> 用于落实“Sandbox 生命周期由应用方掌握”。该接口属于应用控制面，不直接暴露 Docker 原生命令，而是围绕沙箱状态、租约、重启、失效、回收和审计进行管理。

```bash
# 沙箱列表
curl "http://localhost:8080/api/admin/sandboxes?status=ACTIVE&userId=alice" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 查看单个沙箱详情
curl http://localhost:8080/api/admin/sandboxes/{sandboxId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 标记失效：当前活跃执行不强杀，禁止新请求复用，待 lease 释放后重建
curl -X POST http://localhost:8080/api/admin/sandboxes/{sandboxId}/invalidate \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"shared skills updated"}'

# 重启沙箱：停止当前容器，下次 borrow 自动创建新容器
curl -X POST http://localhost:8080/api/admin/sandboxes/{sandboxId}/restart \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 停止空闲沙箱
curl -X POST http://localhost:8080/api/admin/sandboxes/{sandboxId}/stop \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 查看生命周期审计
curl http://localhost:8080/api/admin/sandboxes/{sandboxId}/events \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**返回字段建议**：

| 字段 | 说明 |
|---|---|
| `sandboxId` | 应用侧沙箱 ID |
| `tenantId/userId/agentId` | 租户、用户、Agent 维度 |
| `workspaceId` | 绑定的长期工作区 |
| `containerId` | Docker 容器 ID |
| `status` | NEW/STARTING/READY/ACTIVE/IDLE/UNHEALTHY/INVALIDATING/STOPPED/FAILED |
| `activeLeaseCount` | 正在使用该沙箱的运行租约数 |
| `ownerInstanceId` | 当前持有该沙箱的应用实例 ID |
| `policyVersion` | 沙箱资源策略版本 |
| `sharedVersion` | shared 能力库版本 |
| `lastAccessAt` | 最近访问时间 |
| `heartbeatAt` | 最近心跳时间 |
| `stopReason` | 停止原因 |
| `resourceUsage` | CPU/内存/磁盘等运行指标 |

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
| `/admin/sandboxes` | 沙箱管理 | ADMIN | 查看、重启、失效、停止沙箱与生命周期审计 |

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
│   │   ├── SandboxSnapshotConfig.java
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
│   ├── runtime/                        # 5 个文件
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
│   │   ├── InMemorySandboxLock.java
│   │   ├── RedisSandboxLock.java
│   │   ├── RetryStartDockerSandbox.java
│   │   ├── SandboxLock.java
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
| `dataagent.sandbox.enabled` | `true` | 是否启用 Docker Sandbox |
| `dataagent.sandbox.idle-ttl` | `15m` | 空闲回收时间 |
| `dataagent.sandbox.max-active-per-user` | `3` | 单用户最大活跃沙箱数 |
| `dataagent.sandbox.memory-limit` | `512m` | 单个容器内存限制 |
| `dataagent.sandbox.cpu-limit` | `0.5` | 单个容器 CPU 限制 |
| `dataagent.sandbox.workspace-root` | `${dataagent.workspace}` | 用户 workspace、shared、session scratch 的宿主机根目录 |
| `dataagent.sandbox.reaper.lock-enabled` | `true` | 多副本下启用分布式清理锁 |
| `dataagent.sandbox.owner-instance-id` | 自动生成 | 当前应用实例 ID，用于防止误清理其他实例容器 |
| `dataagent.sandbox.eviction-poll-sec` | `300` | 空闲回收扫描间隔（秒） |
| `dataagent.sandbox.snapshot.jdbc.enabled` | `false` | JDBC 快照开关（需先联网拉取 mysql 扩展依赖） |
| `dataagent.model.active` | `dashscope` | 模型提供方：dashscope / ollama |
| `dataagent.model.default-model-name` | `qwen-max` | 默认模型名 |
| `server.port` | `8080` | HTTP 端口 |

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

## 十四、Sandbox 生命周期、文件系统与会话管理完整方案

> 本章是对“Sandbox 生命周期由应用方掌握”的正式落地方案。核心目标是把 **容器生命周期**、**文件生命周期**、**会话生命周期** 解耦：容器可以被回收或重建，但用户 workspace、Agent 记忆、技能、子代理、会话产物和审计日志不能丢失。

### 14.1 设计结论

本项目采用 **应用控制面 + AgentScope 执行面** 的双层模型：

| 层 | 负责内容 | 不负责内容 |
|---|---|---|
| 应用控制面 | 租户隔离、权限校验、沙箱策略、租约、状态机、会话绑定、文件挂载、空闲回收、失效重建、审计、配额、多副本协调 | 不直接把业务逻辑写死到 Docker 原生命令里 |
| AgentScope 执行面 | 根据应用方下发的 `SandboxSpec` 创建、启动、停止 Docker 容器；在容器内执行 Agent 工具、代码和文件读写 | 不决定租户策略、文件持久化策略、会话归属和业务权限 |
| 存储层 | MySQL 存业务元数据，Redis 存运行态锁和租约，Volume/NFS/Object Storage 存 workspace 文件 | 不承担 Agent 推理和工具执行 |

一句话：**应用方掌握“该不该创建、给谁用、挂什么目录、何时回收、何时重建、如何审计”；AgentScope 负责“按规格把容器跑起来并执行”。**

### 14.2 总体架构

```text
┌──────────────────────────────────────────────────────────────────┐
│                        应用控制面                                  │
│  AgentAccessGuard / WorkspaceService / SandboxPolicyService       │
│  SandboxRegistry / SandboxLeaseService / SessionRuntimeBinding    │
│  SandboxReaper / SandboxHeartbeat / SandboxAudit                  │
└───────────────────────────────┬──────────────────────────────────┘
                                │ SandboxSpec / Lease / Context
┌───────────────────────────────▼──────────────────────────────────┐
│                    AgentScope Runtime 执行面                       │
│  HarnessAgent / HarnessGateway / Middleware / SandboxManager       │
│  DockerFilesystemSpec / PermissionEngine / Tool Execution          │
└───────────────────────────────┬──────────────────────────────────┘
                                │ mount / execute / log
┌───────────────────────────────▼──────────────────────────────────┐
│                         存储与运行态                                │
│  MySQL: users / agents / sessions / sandbox records / audit         │
│  Redis: lease / lock / runtime state / distributed reaper           │
│  Workspace Root: shared / users / sessions / artifacts              │
└──────────────────────────────────────────────────────────────────┘
```

### 14.3 三个生命周期的边界

| 生命周期 | 主键粒度 | 生命周期长短 | 是否跟容器绑定 | 说明 |
|---|---|---|---|---|
| Sandbox | `(tenantId,userId,agentId,workspaceId)` | 中短期，可回收 | 是 | Docker 执行环境，可冷启动、空闲回收、异常重建 |
| Workspace | `(tenantId,userId,agentId)` | 长期 | 否 | 用户私有长期文件、memory、skills、subagents、outputs |
| Session Scratch | `(tenantId,userId,agentId,sessionKey)` | 会话级 | 否 | 单次会话临时文件、工具日志、中间结果、会话产物 |

设计原则：

1. **容器不是数据源**：容器可随时删除，不能把用户文件只放在 Docker writable layer。
2. **Workspace 是长期事实源**：用户上传文件、Agent 生成报告、memory、私有技能都必须落到应用管理的持久化目录或对象存储。
3. **Session Scratch 独立隔离**：同一个用户同一个 Agent 下的不同会话，临时文件不能互相污染。
4. **Shared 只读投影**：团队共享技能、子代理、知识库通过只读方式挂载进容器，用户不能直接写 shared。
5. **运行时靠 Lease 保护**：容器回收、重启、失效必须尊重 active lease，不能强杀正在执行的任务。

### 14.4 Sandbox 状态机

```text
NEW
  ↓
STARTING
  ↓
READY
  ↓
ACTIVE
  ↓
IDLE
  ↓
EVICTING
  ↓
STOPPED

异常/变更分支：
STARTING → FAILED
READY / ACTIVE / IDLE → UNHEALTHY
READY / IDLE → INVALIDATING → STOPPED
ACTIVE → DRAINING → INVALIDATING → STOPPED
STOPPED → STARTING
```

| 状态 | 含义 | 可接受的新请求 | 后台动作 |
|---|---|---|---|
| `NEW` | 已有元数据，尚未创建容器 | 否 | 等待 borrow |
| `STARTING` | 正在创建并启动容器 | 否 | 等待启动完成 |
| `READY` | 容器可用但暂未被占用 | 是 | 可转 ACTIVE |
| `ACTIVE` | 有会话或工具正在使用 | 是，按策略决定是否并发复用 | 维护 heartbeat 与 lease |
| `IDLE` | 无活跃 lease，等待复用 | 是 | 超过 idleTtl 后回收 |
| `DRAINING` | 已标记失效，但仍有运行任务 | 否 | 等 lease 归零 |
| `INVALIDATING` | 准备停止并清理旧容器 | 否 | stop/remove |
| `EVICTING` | 空闲回收中 | 否 | stop/remove |
| `UNHEALTHY` | 心跳或容器检查失败 | 否 | 下次 borrow 重建 |
| `STOPPED` | 容器已停止 | 否 | 保留元数据和持久文件 |
| `FAILED` | 创建或启动失败 | 否 | 记录错误，按重试策略处理 |

### 14.5 推荐核心组件

| 组件 | 所属层 | 职责 |
|---|---|---|
| `SandboxPolicyService` | application | 计算资源规格、镜像、网络、超时、配额、是否允许 execute |
| `WorkspaceService` | application | 解析 shared/user/session 三层目录，负责初始化、权限、配额和路径安全 |
| `SessionRuntimeBindingService` | application | 将 `sessionKey`、`workspaceId`、`sandboxId`、`leaseId` 绑定起来 |
| `SandboxRegistry` | application/infrastructure | 维护 sandbox 元数据、状态机和审计记录 |
| `SandboxLeaseService` | application | borrow/release 租约，保证执行中不被回收 |
| `UserSandboxPool` | infrastructure | 对接 AgentScope SandboxManager，完成容器懒创建、复用、失效、回收 |
| `SandboxReaperService` | application | 定时回收 IDLE/UNHEALTHY/过期 sandbox，多副本下必须带分布式锁 |
| `SandboxHeartbeatController` | api | 接收容器 sidecar 或 runtime 心跳 |
| `UserSandboxContextMiddleware` | runtime | 在 Agent 执行前注入当前用户、Agent、workspace、session scratch 上下文 |
| `SharedSandboxFilesystem` | infrastructure | 负责 shared 内容只读投影和版本识别 |

### 14.6 Sandbox 元数据表

建议将 `SandboxLifecycleRecord` 强化为可支撑生产治理的状态表：

```sql
sandbox_lifecycle_record
- id BIGINT PK
- tenant_id VARCHAR(64)
- user_id VARCHAR(128)
- agent_id VARCHAR(128)
- workspace_id VARCHAR(128)
- sandbox_key VARCHAR(512) UNIQUE
- container_id VARCHAR(128)
- image VARCHAR(256)
- status VARCHAR(32)
- policy_version BIGINT
- shared_version BIGINT
- owner_instance_id VARCHAR(128)
- active_lease_count INT
- needs_restart BOOLEAN
- heartbeat_at DATETIME
- last_access_at DATETIME
- created_at DATETIME
- updated_at DATETIME
- stopped_at DATETIME
- stop_reason VARCHAR(64)
- error_message TEXT
```

关键字段：

| 字段 | 作用 |
|---|---|
| `sandbox_key` | 推荐格式：`tenantId:userId:agentId:workspaceId` |
| `policy_version` | 沙箱资源策略版本，策略变化后旧 sandbox 失效 |
| `shared_version` | shared 能力库版本，审批通过后触发新容器加载新内容 |
| `owner_instance_id` | 多副本部署下，标识哪个应用实例拥有该容器 |
| `active_lease_count` | 防止执行中被 reaper 或管理员操作误杀 |
| `needs_restart` | ACTIVE 状态下收到 invalidate 时先标记，等任务结束再重启 |
| `heartbeat_at` | 判断容器健康状态 |
| `stop_reason` | `IDLE_TIMEOUT`、`INVALIDATED`、`USER_DELETE`、`APP_SHUTDOWN`、`FAILED` 等 |

### 14.7 Sandbox Lease 表

```sql
sandbox_lease
- id BIGINT PK
- lease_id VARCHAR(128) UNIQUE
- sandbox_id BIGINT
- tenant_id VARCHAR(64)
- user_id VARCHAR(128)
- agent_id VARCHAR(128)
- session_key VARCHAR(128)
- request_id VARCHAR(128)
- status VARCHAR(32) -- ACTIVE / RELEASED / EXPIRED / FAILED
- acquired_at DATETIME
- released_at DATETIME
- expire_at DATETIME
```

Lease 的作用：

- 标记某次对话、文件操作或工具执行正在使用哪个 sandbox。
- reaper 回收前必须检查 `active_lease_count == 0`。
- SSE 异常中断时，可通过 lease 超时兜底释放。
- 管理员重启 sandbox 时，对 ACTIVE sandbox 先进入 `DRAINING`，不再接收新 lease。

### 14.8 borrow 流程

```text
ChatController / WorkspaceController
  ↓
1. AgentAccessGuard 校验用户对 Agent 的 RUN/EDIT 权限
  ↓
2. SessionRuntimeBindingService 确认或创建 sessionKey
  ↓
3. WorkspaceService 初始化 user workspace 与 session scratch
  ↓
4. SandboxLeaseService 获取分布式锁 lock:sandbox:{sandboxKey}
  ↓
5. SandboxRegistry 查找可复用 sandbox
  ↓
6. 判断是否可复用：
   - status in READY/IDLE/ACTIVE
   - heartbeat 正常
   - policy_version 一致
   - shared_version 一致
   - needs_restart = false
   - owner_instance_id 为当前实例，或当前实例可安全接管
  ↓
7. 可复用：active_lease_count + 1，状态转 ACTIVE
  ↓
8. 不可复用：生成 SandboxSpec，调用 AgentScope SandboxManager 创建新容器
  ↓
9. 建立 SessionRuntimeBinding(sessionKey → workspaceId → sandboxId → leaseId)
  ↓
10. UserSandboxContextMiddleware 注入上下文
  ↓
11. HarnessGateway.dispatchStream / 工具执行
```

### 14.9 release 流程

```text
请求完成 / SSE done / 工具执行结束 / 异常中断
  ↓
1. 标记 lease RELEASED 或 FAILED
  ↓
2. active_lease_count - 1
  ↓
3. 若 active_lease_count > 0，保持 ACTIVE
  ↓
4. 若 active_lease_count == 0 且 needs_restart = false，转 IDLE
  ↓
5. 若 active_lease_count == 0 且 needs_restart = true，转 INVALIDATING 并停止旧容器
```

释放必须放在 `finally` 中执行；SSE 断开、浏览器关闭、LLM 异常、工具异常都不能泄露 lease。

### 14.10 invalidate 流程

触发场景：

| 触发事件 | 处理策略 |
|---|---|
| shared skill / subagent 审批通过 | `shared_version + 1`，相关 sandbox 失效 |
| Agent 系统提示词变更 | 当前 Agent 相关 sandbox 失效 |
| 工具白名单/黑名单变更 | 当前 Agent 相关 sandbox 失效 |
| 沙箱镜像或资源策略变更 | `policy_version + 1`，旧 sandbox 失效 |
| 管理员手动重启 | 指定 sandbox 失效 |
| 心跳失败 | 标记 `UNHEALTHY`，下次 borrow 重建 |

ACTIVE sandbox 不建议直接强杀：

```text
如果 status 是 IDLE/READY：
  立即 INVALIDATING → STOPPED

如果 status 是 ACTIVE：
  设置 needs_restart = true
  状态转 DRAINING
  禁止新 lease
  等 active_lease_count 归零后停止
```

#### 落地状态

上述 DRAINING + `needs_restart` 机制是目标设计。当前代码已实现**惰性 stale 标记**作为过渡方案：

- `MarketContributionService.approve` 调用 `invalidate(null, targetAgentId)` **不再立即 docker rm 所有用户容器**（原爆炸半径：审批通过即销毁数十个活跃会话、峰值同时重建）。
- 改为**惰性 stale 标记**：只标记、不立即销毁；标记后用户**下一次 `borrow()`** 才 `close()`（快照私有文件）+ 清状态 + 重建（挂载新共享层 + 从快照恢复私有文件）。进行中的会话不受影响，重建分散到各用户下次访问时；已被空闲回收的用户无需处理（下次冷启动本就加载新层）。
- 管理台另提供 `POST /api/agents/{id}/rebuild-workspace` 显式触发同一惰性重建，覆盖"绕过贡献流程手动改 `shared/` 层"的场景。
- DRAINING + `needs_restart` 可作为后续增强：当容器 ACTIVE 且正执行时，先标记 `needs_restart`，等 active lease 归零后再停止并重建——实现零打断平滑切换。

### 14.11 reaper 回收策略

回收条件：

| 条件 | 动作 |
|---|---|
| `status = IDLE` 且 `now - last_access_at > idleTtl` | stop/remove 容器，状态转 STOPPED |
| `status = UNHEALTHY` 且无 active lease | stop/remove 容器 |
| `status = DRAINING` 且 active lease 归零 | stop/remove 容器 |
| `lease.status = ACTIVE` 且超过 `expire_at` | 标记 EXPIRED，修正 active lease 计数 |

多副本要求：

- `SandboxReaperService` 必须使用分布式锁，例如 `lock:sandbox:reaper`。
- 只能清理 `owner_instance_id = 当前实例` 的容器；除非容器 owner 已过期且经过接管流程。
- 启动时不能无条件 `docker rm -f` 数据库里的 ACTIVE 容器，避免误杀其他副本正在使用的容器。

### 14.12 修复：双 DockerFilesystemSpec 配置不一致（P0）

> **状态：✅ 已修复（2026-07-09）**

上述 §14.1–§14.11 是目标设计方案。代码审查曾发现两处 `DockerFilesystemSpec` 实例配置不一致：

| 位置 | snapshotSpec | isolationScope | 用途 |
|---|---|---|---|
| `AgentRuntimeConfigurer` 第 73–76 行（修前） | **缺失** | `IsolationScope.USER` | 传给 `HarnessAgent.Builder.filesystem()` |
| `UserSandboxPool.contextFor` | **有**（Redis / Noop） | `IsolationScope.USER` | 传给 `SandboxManager.acquire()` |

正常运行时，`UserSandboxContextMiddleware` 注入 `externalSandbox`，框架走 `UserSandboxPool` 路径（有快照），主 Agent 不受影响。但 `builder.filesystem()` 那个无快照的 spec 仍然挂在 Agent 上——一旦框架在**兜底路径**（borrow 失败，框架回退到 agent 默认 `SandboxContext`）或**子代理 ISOLATED 路径**（新建独立沙箱时用 agent 的 filesystem spec）回退到它，容器被回收后用户工作区文件会直接丢失。

**现已修复**：`AgentRuntimeConfigurer` 的 `DockerFilesystemSpec` 现在注入 `SandboxSnapshotSpec` + `SandboxExecutionGuard`（均由 `SandboxSnapshotConfig` 统一装配，Redis 或 Noop 兜底），与 `UserSandboxPool` 共用同一后端。子代理产出文件（如 `report-writer` 的报告）现受 Redis 快照保护。

### 14.13 修复：子 Agent 沙箱快照保护（P0）

> **状态：✅ 已修复（2026-07-09）**

`AgentRuntimeConfigurer` 注册了两个子 Agent（`code-reviewer`、`report-writer`），均为 `WorkspaceMode.ISOLATED`（独立工作区）。曾存在的隐患：

1. 子 Agent 被 `agent_spawn` 调用时，若走框架自动管理路径（不继承父 `SandboxContext`），它使用的就是 `builder.filesystem()` 的 spec——修复前该 spec 无 `snapshotSpec`。
2. 子 Agent 产出文件 → 容器回收 → 无快照 → 文件丢失。

**现已修复**：`AgentRuntimeConfigurer` 的 `DockerFilesystemSpec` 已注入 `SandboxSnapshotSpec` + `SandboxExecutionGuard`（由 `SandboxSnapshotConfig` 统一装配），子代理创建独立沙箱时同样携带 Redis 快照保护。框架守卫（`RedisSandboxExecutionGuard`）对子代理等 Priority 3/4 路径同样生效，多副本下子代理路径也具备分布式串行能力。

### 14.14 多副本并发模型（SandboxLock + SandboxExecutionGuard）

#### 为什么需要两把锁

框架 `SandboxManager.acquire` 的优先级：**P1 外部沙箱**（`externalSandbox`）> P2 外部状态 > P3 持久化状态 > P4 新建。**P1/P2 显式绕过 `SandboxExecutionGuard`**——因为沙箱由调用方自行管理，框架假定你已在外部串行化。

本项目主 Agent 走 P1（`UserSandboxContextMiddleware` 注入 `externalSandbox`）。因此：

| 路径 | 并发保护 | 后端 |
|---|---|---|
| **主 Agent（externalSandbox，P1）** | `SandboxLock`（回合边界锁） | `RedisSandboxLock`（多副本）/ `InMemorySandboxLock`（单副本） |
| **子代理 / fallback（P3/P4）** | `SandboxExecutionGuard` + `SandboxLock` | `RedisSandboxExecutionGuard` + `RedisSandboxLock` |

`SandboxLock` 由 `UserSandboxContextMiddleware` 在回合开始 `lock(userId, agentId)`、整条 Flux 终止时 `doFinally` → `unlock()`，覆盖 `acquire → run → release` 整段窗口。

`RedisSandboxLock` 实现：`SET key token NX PX <ttl>` 抢占，失败则轮询重试至超时；`unlock` 用 Lua CAS（仅 token 匹配时才删除，防止误删他人锁）。

#### 多副本仍需粘性路由

`SandboxLock`（Redis）只是**防错安全网**——即使粘性失效，也不会两个副本同时写同一沙箱。但 Docker 容器本身**不跨主机可达**：副本 B 抢到锁、等副本 A 回合结束后，B 上仍无该用户的容器，只能冷启动再建一个。

因此：
- 正确性由**粘性路由**保证（同一 userId 始终落到持有容器的副本）；
- `RedisSandboxLock` 是兜底，防粘性失效时的数据竞争；
- 代码零实现粘性，需在 LB/网关层配置按 `userId` 亲和。

#### 四大可插拔后端的装配来源

全部由 `SandboxSnapshotConfig`（redis 开 → Redis，否则 Noop/InMemory 兜底）统一装配：

| # | 后端接口 | 用途 | Redis 实现 |
|---|---|---|---|
| 1 | `AgentStateStore` | 容器引用持久化 | `RedisAgentStateStore`（Lettuce） |
| 2 | `SandboxSnapshotSpec` | 工作区 tar 快照 | `RedisSnapshotSpec`（Jedis） |
| 3 | `SandboxExecutionGuard` | 框架托管沙箱（P3/P4）串行化 | `RedisSandboxExecutionGuard` |
| 4 | `SandboxLock` | 回合边界串行化（external P1 路径） | `RedisSandboxLock`（SET NX + Lua） |

#### JDBC 快照后端（就绪，待联网构建）

`SandboxSnapshotConfig` 中已预留 `JdbcSnapshotSpec(dataSource)` Bean（注释状态），配合 `dataagent.sandbox.snapshot.jdbc.enabled=true`。取消注释 pom.xml 中 `agentscope-extensions-mysql` 依赖 + 联网 `mvn -U compile` 拉包后即可启用——快照 tar 存入应用自身的 MySQL（复用 JPA DataSource）。

### 14.15 修复：WorkspaceStartException（Windows 路径分隔符转义）

> **状态：✅ 已修复（2026-07-10）**
>
> 现象：Docker Desktop (WSL2) 上容器被回收后重建，新容器 `start()` 时抛出 `WorkspaceStartException: Failed to start workspace at \workspace`。日志显示 `docker tar command failed (exit=2): tar: /workspace: Cannot open: No such file or directory`。

#### 根因

**不是容器初始化慢，而是 Windows 路径分隔符转义。**

框架 `DockerSandbox.doSetupWorkspace()` 内部用 Java `Path` API 解析 workspace 路径：

```java
// 框架代码（DockerSandbox.java）
String workspaceRoot = dockerState.getWorkspaceRoot();  // "/workspace"
runDockerCliBlocking(30, "docker", "exec", containerId, "mkdir", "-p", workspaceRoot);
```

在 Windows 上，`Path.of("/workspace")` 会被转换为 `\workspace`（反斜杠）。当这个路径传给 `docker exec <cid> mkdir -p \workspace` 时：

- Linux 容器内的 `sh` 把 `\w` 解释为转义序列 → 实际创建的是 `$PWD/workspace`，不是 `/workspace`
- 后续 `docker tar /workspace` 找不到根级 `/workspace` → 报错
- 重试 5 次都是同样的路径问题，所以单纯重试无效

#### 修复

`RetryStartDockerSandbox` 在 `super.start()` 之前，用 `ProcessBuilder` 直接执行 `docker exec <cid> mkdir -p /workspace`，其中路径是**硬编码的正斜杠字符串**，完全绕过 Java `Path` API 的 Windows 路径转换：

```java
private static final String CONTAINER_WORKSPACE = "/workspace";  // 硬编码正斜杠

private void ensureWorkspaceDirExists() {
    // ProcessBuilder 直接传字符串，不经过 Path API
    List<String> cmd = List.of("docker", "exec", containerId, "mkdir", "-p", CONTAINER_WORKSPACE);
    ...
}
```

这样当 `super.start() → doSetupWorkspace()` 执行时，`/workspace` 目录已经存在，`mkdir -p /workspace` 是幂等操作，不会报错。

| 机制 | 值 |
|---|---|
| 预创建路径 | `CONTAINER_WORKSPACE = "/workspace"`（硬编码正斜杠字符串） |
| 最大重试次数 | 8 |
| 重试间隔 | 4000ms |
| 连续退出阈值 | 3 次 |
| 触发条件 | `WorkspaceStartException` |

`DataAgentWorkspaceConfig.sandboxClient` 返回自定义 `SandboxClient`，将 `delegate.create()` / `delegate.resume()` 返回的 `DockerSandbox` 包装为 `RetryStartDockerSandbox`。零侵入框架源码，仅通过 Bean 装配层替换。

同时 `ChatController.onErrorResume` 对 `WorkspaceStartException` 打印完整 `cause` 链（`log.error(..., wse.getCause())`），方便后续定位非路径类根因。

#### 验证

- `mvn -o compile` 通过。
- 重启后首次对话触发容器重建，`RetryStartDockerSandbox` 会先预创建 `/workspace`，然后 `super.start()` 正常完成。日志会打印 `[sandbox-retry] Pre-created /workspace in container <id>`。

---

## 十五、文件系统设计

### 15.1 总体目录结构

建议容器内统一挂载为 `/workspace`，分三层：

```text
/workspace
├── shared/                 # 只读：团队共享能力
│   ├── AGENTS.md
│   ├── skills/
│   ├── subagents/
│   └── knowledge/
│
├── user/                   # 可读写：用户长期私有工作区
│   ├── data/
│   ├── outputs/
│   ├── memory/
│   ├── skills/
│   └── subagents/
│
└── sessions/               # 可读写：会话级临时空间
    └── {sessionKey}/
        ├── tmp/
        ├── artifacts/
        ├── execution.log
        └── metadata.json
```

宿主机或共享存储上的推荐映射：

```text
{DATAAGENT_WORKSPACE}/
├── shared/
│   └── agents/{agentId}/...
├── users/
│   └── {userId}/agents/{agentId}/...
└── sessions/
    └── {userId}/{agentId}/{sessionKey}/...
```

### 15.2 shared 层

| 项 | 设计 |
|---|---|
| 来源 | `{DATAAGENT_WORKSPACE}/shared/agents/{agentId}/` |
| 容器路径 | `/workspace/shared/` |
| 权限 | 只读 |
| 内容 | `AGENTS.md`、团队技能、子代理、知识库、公共模板 |
| 更新入口 | marketplace 贡献审批、管理员发布、Git/Nacos marketplace 同步 |
| 生效方式 | `shared_version + 1`，旧 sandbox invalidate，下次 borrow 创建新容器 |

规则：

- 用户不能通过 workspace 文件接口直接写 `/workspace/shared`。
- 贡献必须走审批流程，通过后由服务端写 shared。
- shared 内容更新不应破坏正在运行的 ACTIVE sandbox，采用 DRAINING 机制平滑切换。

### 15.3 user 层

| 目录 | 用途 | 生命周期 |
|---|---|---|
| `/workspace/user/data` | 用户上传的数据文件 | 长期 |
| `/workspace/user/outputs` | 报告、图表、导出 CSV | 长期，可按配额清理 |
| `/workspace/user/memory` | Agent 长期记忆 | 长期 |
| `/workspace/user/skills` | 用户私有技能 | 长期 |
| `/workspace/user/subagents` | 用户私有子代理 | 长期 |

规则：

- 容器回收不能删除 user 层文件。
- Agent 分享时，只分享 Agent 定义和权限，不共享 owner 的 user workspace。
- 被授权 RUN 的用户运行共享 Agent 时，仍使用自己的 user workspace。
- CLONE 后新 Agent 拥有新的 workspace，可以选择复制模板内容，但不复制源用户私有数据。

### 15.4 session scratch 层

| 目录 | 用途 | 生命周期 |
|---|---|---|
| `tmp/` | 中间文件、临时脚本、缓存 | 会话结束后可清 |
| `artifacts/` | 本次会话生成的可下载产物 | 按会话保留策略保存 |
| `execution.log` | 工具调用、代码执行日志 | 用于审计和故障排查 |
| `metadata.json` | 会话运行上下文 | 跟 SessionEntity 对齐 |

规则：

- 每个 sessionKey 一个独立 scratch 目录。
- reset 会话时新建 scratch，不直接覆盖旧 scratch。
- delete 会话默认软删除，按保留策略异步清理 scratch。
- 用户明确“清空本次会话文件”时，再删除 scratch。

### 15.5 文件 API 安全规则

| 风险 | 约束 |
|---|---|
| 路径穿越 | 禁止 `../`，服务端 normalize 后必须仍在 workspace root 下 |
| 软链接逃逸 | 禁止跟随指向 root 外部的 symlink |
| 大文件攻击 | 限制单文件大小、单用户总量、单 Agent workspace 总量 |
| 覆盖关键文件 | shared 层只读；系统生成文件需白名单路径 |
| 并发写冲突 | 写文件采用临时文件 + atomic rename |
| 文件版本丢失 | `AGENTS.md`、`SKILL.md`、子代理配置建议保留版本历史 |
| 恶意脚本执行 | 上传不等于可执行，execute 仍需 PermissionEngine 判断 |
| 敏感文件泄露 | 文件下载、预览、读取都必须经过 AgentAccessGuard |

### 15.6 文件操作语义

| 操作 | 是否需要 sandbox 运行 | 说明 |
|---|---|---|
| 上传文件 | 否 | 直接写入 user workspace，下次 sandbox 启动自动可见 |
| 浏览文件树 | 否，优先读持久层 | 不应为了看文件树触发冷启动 |
| Agent 执行读写 | 是 | 在 sandbox 内通过挂载路径访问 |
| 删除文件 | 否 | 删除持久层文件；如 sandbox 正在运行，挂载视图同步变化 |
| 生成报告 | 是 | 先写 session artifacts，再按用户确认移动到 user outputs |

### 15.7 当前实现风险：快照时机与会话状态不同步

上述 §15.1–§15.6 的三层目录设计是目标方案。当前实现中，会话状态（对话历史）实时写入 Redis（`AgentStateStore`），但沙箱文件只在容器 `close()` 时才打包成快照存储。两者不是同步的：

| 数据类型 | 存储位置 | 写入时机 | 恢复时机 |
|---|---|---|---|
| 会话状态（对话历史、AgentState） | Redis AgentStateStore | 每次 `call()` 结束实时写 | 下次 `call()` 开始时加载 |
| 沙箱文件（用户产出、临时文件） | Redis 快照 / 容器 writable layer | 仅 `close()` 时打包 | 容器不存在时从快照恢复 |

风险场景：用户在会话中生成了文件（容器还在运行，文件在容器里，未存快照）→ 此时机器故障 / Docker daemon 重启 / 容器被外部 `docker prune` → 容器没了，但没 `close()` 所以没存快照 → 用户继续会话 → 会话历史还在 Redis，但 Agent 引用的文件已不存在 → 上下文与文件环境不一致。

AgentScope 官方文档的快照语义是 `call()` 结束时打包：容器还在直接接着用（最快）；容器没了拿快照重建；没快照走冷启动。但 `close()` 和 `call()` 结束是两个时机——容器在运行期间不 `close()`，这段时间内的文件变动没有快照保护。

#### 当前状态与已落地改善

1. **已实现**：`shutdownAll()` 只做 `stop()`（保留容器于宿主机），不做 `docker rm`——进程重启后框架可恢复同一容器，工作区文件完好。
2. **已实现**：Redis 快照 (`RedisSnapshotSpec`) 在容器 `close()` 时持久化工作区 tar，下次重建自动恢复。
3. **已文档化**：会话历史实时写 Redis/MySQL（不丢）；沙箱文件仅 `close()` 时快照（最终一致）——这是框架 `RedisSnapshotSpec` 的固有语义，非本项目 bug。运行中容器意外死亡（宿主机宕机 / 外部 `docker prune`）时文件未落快照即丢失——缓解：①重要产物同时落会话/记忆存储；②依赖 `close()` 正常关闭逻辑；③关键负载建议配 `docker prune` 白名单。
4. **中期方向**：将用户长期文件从容器 writable layer 剥离到宿主机持久化目录（`user/` 层设计），通过 bind-mount 挂载进容器，容器销毁不影响文件。

### 15.8 当前实现风险：shared 投影仅在容器创建时生效

共享层通过 bind-mount 在容器创建时投影进容器（由 `SharedWorkspaceProjection.buildSpec` 构建 `WorkspaceSpec`）。如果有人绕过贡献审批流程直接修改 `shared/` 目录（例如管理员手动编辑文件），已有容器不会感知变化，只有新建容器才有。贡献审批流程通过 `invalidate` 保证了重建，但绕过流程的修改没有这个保障。

AgentScope 官方文档指出：`AGENTS.md` / `skills/` / `subagents/` / `knowledge/` 等宿主侧的工作区文件在每次沙箱启动时同步进沙箱（按内容哈希增量）。当前实现依赖 `WorkspaceSpec` 的 bind-mount，是只读挂载而非每次 `call()` 同步。这意味着共享内容更新后，必须重建容器才能让新内容生效。

#### 已落地改善

1. **已实现（惰性 invalidate）**：贡献审批通过 → `invalidate`（惰性 stale）→ 下次 borrow 重建（加载新共享层）。不立即销毁活跃容器，不在审批峰值同时重建。
2. **已实现（管理台重建端点）**：`POST /api/agents/{id}/rebuild-workspace`（需 EDIT 权限）→ 显式触发同一惰性重建，覆盖绕过审批流程手动修改 `shared/` 的场景。
3. **已实现（空闲回收）**：`evictIdle` 回收后下次 `borrow` 冷启动，自动加载最新共享层。
4. **后续增强**：`SharedWorkspaceProjection` 增加 `shared_version` 检查——每次 `borrow` 时比对版本，不一致即触发重建。或改用框架原生 projection 机制（每次 `call()` 按内容哈希增量同步），替代静态 bind-mount。

---

## 十六、会话管理体系详解

> 会话管理不只是“聊天记录展示”。在当前设计中，会话还承担 **上下文归属、文件 scratch 归属、sandbox lease 绑定、审计追踪** 四个职责。

### 16.1 ChatController 与 SessionController 的职责边界

| 接口/组件 | 职责 | 是否负责运行时执行 |
|---|---|---|
| `ChatController` | 接收用户消息，建立运行绑定，触发 Agent 执行，返回 SSE | 是 |
| `SessionController` | 会话列表、详情、已读、重置、删除 | 否 |
| `ConversationService` | 维护会话元数据，查询/重置/删除会话 | 否 |
| `SessionTurnParser` | 解析 JSONL 日志，转成结构化轮次 | 否 |
| `SessionRuntimeBindingService` | 将会话与 workspace、sandbox、lease 绑定 | 是，属于执行前置编排 |

原有“会话创建完全由 harness 内部完成”的表述需要调整为：

> Harness 可以负责底层 runtime session 和 JSONL 日志写入，但应用方必须在 dispatch 前创建或确认业务会话元数据，以便绑定 workspace、session scratch、sandbox lease 和审计链路。

### 16.2 SessionEntity 建议字段

```sql
session_entity
- id BIGINT PK
- tenant_id VARCHAR(64)
- user_id VARCHAR(128)
- agent_id VARCHAR(128)
- session_key VARCHAR(128)
- runtime_session_id VARCHAR(128)
- workspace_id VARCHAR(128)
- current_sandbox_id BIGINT
- title VARCHAR(256)
- status VARCHAR(32) -- ACTIVE / RESET / DELETED / ARCHIVED
- log_path VARCHAR(1024)
- scratch_path VARCHAR(1024)
- unread_count INT
- last_message_at DATETIME
- created_at DATETIME
- updated_at DATETIME
- deleted_at DATETIME
```

### 16.3 SessionRuntimeBinding 表

```sql
session_runtime_binding
- id BIGINT PK
- session_key VARCHAR(128)
- runtime_session_id VARCHAR(128)
- workspace_id VARCHAR(128)
- sandbox_id BIGINT
- lease_id VARCHAR(128)
- bind_status VARCHAR(32) -- ACTIVE / RELEASED / FAILED
- created_at DATETIME
- released_at DATETIME
```

绑定关系：

```text
sessionKey → workspaceId → sandboxId → containerId
                         ↘ leaseId
```

这样可以追踪：

- 当前会话使用了哪个 sandbox。
- 容器异常会影响哪些会话。
- reset 后是否创建了新的 scratch。
- delete 后哪些 artifacts 可保留、哪些 tmp 可清理。
- 管理员 stop/restart sandbox 时是否会影响活跃会话。

### 16.4 发起对话流程

```text
前端 POST /api/agents/{agentId}/chat/stream
  ↓
ChatController
  ↓
1. 解析 JWT，获得 userId/tenantId
  ↓
2. AgentAccessGuard 校验 RUN 权限
  ↓
3. ConversationService 确认 sessionKey；没有则创建 SessionEntity
  ↓
4. WorkspaceService 确认 user workspace 与 session scratch
  ↓
5. SandboxLeaseService.borrow() 获取可用 sandbox 与 lease
  ↓
6. SessionRuntimeBindingService 记录绑定关系
  ↓
7. UserSandboxContextMiddleware 注入上下文
  ↓
8. HarnessGateway.dispatchStream()
  ↓
9. SSE 输出 token/tool_call/tool_result/done
  ↓
10. finally: release lease，更新 SessionEntity.lastMessageAt
```

### 16.5 会话 reset / delete / restart 的语义区分

| 操作 | 清聊天上下文 | 清 session scratch | 清 user workspace | 重启 sandbox | 适用场景 |
|---|---|---|---|---|---|
| 重置会话 reset | 是 | 新建 scratch，旧 scratch 归档 | 否 | 默认否 | 用户想换一个新上下文继续分析 |
| 删除会话 delete | 软删除 | 可延迟清理 | 否 | 否 | 用户从列表移除聊天 |
| 重启沙箱 restart sandbox | 否 | 否 | 否 | 是 | 执行环境异常、依赖损坏 |
| 清空工作区 clear workspace | 可选 | 可选 | 是 | 是 | 用户明确要求清理所有私有文件 |

建议前端不要把这几个动作混成一个按钮，否则用户容易误删文件或误以为重置会话会重启执行环境。

### 16.6 会话日志与展示

推荐日志分两层：

| 日志 | 位置 | 用途 |
|---|---|---|
| Harness JSONL | session scratch 或 runtime log 目录 | 还原 USER/ASSISTANT/TOOL 轮次 |
| Sandbox execution log | `sessions/{sessionKey}/execution.log` | 记录工具执行、代码执行、错误栈、权限判断 |

`SessionTurnParser` 应只做解析和展示，不承担生命周期决策。生命周期决策由 `ConversationService`、`SandboxLeaseService`、`SessionRuntimeBindingService` 负责。

---

## 十七、关键交互流程

### 17.1 第一次进入聊天页

```text
前端加载 /chat
  ↓
GET /api/agents
  ↓
GET /api/agents/{agentId}/chat/session?sessionKey=main-xxx
  ↓
GET /api/agents/{agentId}/sessions/inbox
```

规则：

- 页面初始化只查询会话和文件元数据，不应触发 sandbox 冷启动。
- 只有用户发送消息、Agent 执行工具、或需要容器内运行环境时，才 borrow sandbox。

### 17.2 上传数据文件

```text
POST /api/agents/{agentId}/workspace/upload?path=/data/orders.csv
  ↓
AgentAccessGuard 校验权限
  ↓
WorkspaceFileService 校验路径、大小、配额、文件名
  ↓
写入 {DATAAGENT_WORKSPACE}/users/{userId}/agents/{agentId}/data/orders.csv
  ↓
记录文件 metadata/audit
  ↓
返回成功
```

规则：

- 上传不依赖容器运行。
- 如果 sandbox 正在运行，因为 user 目录是挂载卷，容器内立即可见。
- 如果 sandbox 未运行，下次启动时自动挂载可见。

### 17.3 Agent 执行代码或文件工具

```text
Agent 调用 execute/read_file/write_file
  ↓
PermissionEngine 判断 ALLOW/ASK/DENY
  ↓
工具在 sandbox 内执行
  ↓
可访问：/workspace/shared 只读、/workspace/user 可读写、当前 /workspace/sessions/{sessionKey} 可读写
  ↓
写入 execution.log 和 tool_result
  ↓
SSE 返回 tool_call/tool_result
```

### 17.4 shared 能力审批通过

```text
用户提交 contribution
  ↓
管理员 approve
  ↓
MarketContributionService 写入 shared/agents/{agentId}/...
  ↓
shared_version + 1
  ↓
SandboxRegistry 标记相关 sandbox needs_restart
  ↓
IDLE sandbox 立即停止
ACTIVE sandbox 进入 DRAINING
  ↓
下一次 borrow 创建新 sandbox，挂载新 shared 内容
```

### 17.5 Agent 分享与 workspace 隔离

```text
owner 分享 Agent 给 alice，tier=RUN
  ↓
alice 在 /api/agents 列表看到该 Agent
  ↓
alice 发起对话
  ↓
系统使用 alice 自己的 workspace：users/alice/agents/{agentId}/...
  ↓
不会读取 owner 的 user workspace
```

规则：

- 分享 Agent 分享的是定义、提示词、工具配置、可运行权限，不分享 owner 私有文件。
- 共享能力应该沉淀到 shared，不能依赖 owner workspace。

---

## 十八、部署模式与多副本现状

> 当前项目可以单机稳定运行。多副本部署的核心风险不在 MySQL，而在 **运行态 sandbox 池、Docker 容器归属、文件系统一致性、reaper 清理归属**。

### 18.1 当前能力矩阵

| 组件 | 当前能力 | 风险 | 建议 |
|---|---|---|---|
| MySQL/JPA | 支持多副本共享 | 无明显问题 | 生产使用 migration 管理 schema |
| Redis AgentStateStore | 可选 | 未启用时副本重启丢运行态 | 多副本必须启用 |
| ConversationService | 基于 MySQL，适合多副本 | dispatch 前元数据绑定需补强 | 增加 SessionRuntimeBinding |
| UserSandboxPool | 内存 `ConcurrentHashMap` | 多副本会为同一用户创建多个容器 | sticky session 或 Redis Registry |
| SandboxReaperService | 本地定时任务 | 可能误清其他副本容器 | 加 owner_instance_id + 分布式锁 |
| 并发控制（SandboxExecutionGuard） | **缺失** | 两副本同时处理同一用户请求时状态竞争 | 引入 Redis 分布式锁（见 §18.1.1） |
| SharedSandboxFilesystem | 依赖本地 workspace | 多副本 shared 不一致 | 使用共享卷/NFS/对象存储或发布同步 |
| 文件上传/浏览 | 应用层可处理 | 若依赖容器则会冷启动 | 文件 API 优先读持久层 |
| SSE 流式 | 单连接长请求 | LB 超时、缓冲影响体验 | 关闭 proxy buffering，调大超时 |

#### 18.1.1 并发控制缺失：SandboxExecutionGuard

AgentScope 2.0 官方文档明确指出：`IsolationScope.USER` 在多副本部署下，两个副本同时处理同一用户的请求会都把状态写到同一个 slot，最后写入的为准。如果不加分布式锁，会出现状态竞争。

当前项目 grep 搜索 `executionGuard` / `SandboxExecutionGuard` / `ExecutionGuard` 均无任何匹配——项目完全没有并发控制。当 `backingStore` 是 InMemory 时各自创建容器（数据分裂）；是 Redis 时可能两个 `acquire` 同时写隔离状态（状态竞争）。

官方推荐使用 `RedisSandboxExecutionGuard`：

```java
SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
    .leaseTtl(Duration.ofMinutes(30))
    .retryInterval(Duration.ofMillis(500))
    .build();
```

锁的 key 按 scope 自动分桶（USER → 按 userId）。在 `DockerFilesystemSpec` 上 `.executionGuard(guard)` 即可启用。项目应在多副本部署时通过 `SandboxSnapshotConfig` 或等效配置类注入此 Bean。

### 18.2 推荐部署策略

#### 单机生产

适合：内网试点、PoC、小团队。

要求：

- MySQL 独立部署。
- Docker daemon 可访问。
- `DATAAGENT_WORKSPACE` 使用绝对路径并持久化。
- sandbox idle 回收开启。
- 定期备份 MySQL 和 workspace。

#### 多副本过渡方案：sticky session

适合：需要高可用入口，但暂不改造 sandbox registry。

要求：

- 按 userId 或稳定 cookie 做粘性路由。
- Redis 开启。
- workspace 使用共享卷，或保证用户总是路由到同一副本且副本本地卷可保留。
- reaper 必须只清理当前 `owner_instance_id` 的容器。

Nginx 示例：

```nginx
upstream dataagent_backend {
    hash $cookie_dataagent_route consistent;
    server 10.0.0.11:8080;
    server 10.0.0.12:8080;
}

server {
    listen 443 ssl;
    server_name dataagent.example.com;

    location / {
        proxy_pass http://dataagent_backend;
        proxy_buffering off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

#### 多副本目标方案：分布式 sandbox control plane

适合：正式 SaaS、多租户企业版。

需要改造：

| 优先级 | 改造项 | 说明 |
|---|---|---|
| P0 | `SandboxLifecycleRecord.owner_instance_id` | 防止误清其他实例容器 |
| P0 | `SandboxLeaseService` Redis 化 | lease、active count、锁跨副本一致 |
| P0 | `SandboxReaperService` 分布式锁 | 同一时间只允许一个 reaper 执行全局扫描 |
| P0 | 共享 workspace 存储 | NFS/对象存储/分布式文件系统，避免本地文件不一致 |
| P1 | `UserSandboxPool.entries` Redis Registry 化 | 从本地池演进为分布式 registry |
| P1 | 容器接管流程 | owner 副本失联时，其他副本可安全接管或重建 |
| P1 | 管理后台沙箱页面 | 可观测、可重启、可审计 |

### 18.3 多副本下不能做的事

- 不能启动时无条件清理数据库里所有 ACTIVE 容器。
- 不能假设本地 `ConcurrentHashMap` 就代表全局 sandbox 状态。
- 不能把用户文件只存在副本本地临时目录。
- 不能让两个副本同时 reaper 同一个容器。
- 不能在 ACTIVE sandbox 上直接强制 remove，除非管理员选择“强制终止”。

---

## 十九、生产环境检查清单

### 19.1 必填项

| # | 检查项 | 配置方式 | 验证方法 |
|---|---|---|---|
| 1 | MySQL 8.0+ 已部署 | `spring.datasource.url` | `SELECT VERSION()` |
| 2 | 主业务库和分析库已创建 | `createDatabaseIfNotExist=true` 或手动创建 | `SHOW DATABASES` |
| 3 | MySQL 默认密码已修改 | 环境变量或密钥系统 | 不允许 root/root |
| 4 | JWT 密钥已设置 | `DATAAGENT_JWT_SECRET` | 长度 ≥ 32 字符 |
| 5 | DashScope API Key 已设置 | `DASHSCOPE_API_KEY` | 启动日志无缺失警告 |
| 6 | workspace 根目录持久化 | `DATAAGENT_WORKSPACE` | 重启后文件仍存在 |
| 7 | Docker daemon 可访问 | `/var/run/docker.sock` 或远程 Docker | `docker info` 成功 |
| 8 | 容器资源限制已配置 | sandbox policy | 内存、CPU、进程数限制生效 |
| 9 | 文件路径安全校验 | WorkspaceFileService | `../`、symlink escape 被拒绝 |
| 10 | ddl-auto 生产禁用 update | prod profile | 使用 validate/migration |

### 19.2 多副本必填项

| # | 检查项 | 原因 |
|---|---|---|
| 1 | Redis 开启 | 运行态状态、lease、锁需要跨副本共享 |
| 2 | sticky session 或分布式 sandbox registry | 避免重复创建容器 |
| 3 | `owner_instance_id` 生效 | 防止误清其他副本容器 |
| 4 | reaper 分布式锁 | 防止重复清理 |
| 5 | 共享 workspace 存储 | 防止文件不一致 |
| 6 | SSE 反向代理关闭缓冲 | 保证流式输出 |
| 7 | LB 超时时间 ≥ Agent 最大执行时间 | 防止长任务中断 |

### 19.3 安全基线

| 类别 | 要求 |
|---|---|
| 认证 | 所有 `/api/**` 默认需要 JWT，公开接口显式白名单 |
| 授权 | AgentAccessGuard 统一控制 CLONE/RUN/EDIT |
| 沙箱 | 禁止 privileged，限制 network、CPU、memory、pids、mount |
| 文件 | 禁止路径穿越，shared 只读，上传大小限制 |
| SQL | `run_sql_preview` 只允许 SELECT/WITH，限制 row limit |
| 审计 | 登录、Agent 分享、贡献审批、sandbox 创建/停止、工具执行均记录 |
| 密钥 | JWT、数据库、DashScope、Webhook secret 使用环境变量或密钥系统 |

### 19.4 启动命令模板

```bash
# 单机生产
java -jar dataagent.jar \
  --spring.profiles.active=prod,mysql \
  --DATAAGENT_JWT_SECRET=<32字符以上随机串> \
  --DASHSCOPE_API_KEY=<sk-xxx> \
  --MYSQL_PASSWORD=<强密码> \
  --DATAAGENT_WORKSPACE=/opt/dataagent/workspace

# 多副本生产：sticky session + Redis + 共享 workspace
java -jar dataagent.jar \
  --spring.profiles.active=prod,mysql,redis \
  --DATAAGENT_JWT_SECRET=<32字符以上随机串> \
  --DASHSCOPE_API_KEY=<sk-xxx> \
  --MYSQL_PASSWORD=<强密码> \
  --REDIS_HOST=<redis-host> \
  --REDIS_PASSWORD=<redis-password> \
  --DATAAGENT_WORKSPACE=/mnt/dataagent-workspace \
  --DATAAGENT_INSTANCE_ID=${HOSTNAME}
```

### 19.5 环境变量速查表

| 变量名 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `DATAAGENT_JWT_SECRET` | 是 | dev 占位 | JWT 签名密钥 |
| `DASHSCOPE_API_KEY` | 是 | 空 | LLM API Key |
| `MYSQL_HOST` | 是 | localhost | MySQL 地址 |
| `MYSQL_PASSWORD` | 是 | root | MySQL 密码，生产必须修改 |
| `REDIS_HOST` | 多副本是 | localhost | Redis 地址 |
| `REDIS_PASSWORD` | 多副本是 | 空 | Redis 密码 |
| `DATAAGENT_WORKSPACE` | 是 | `$CWD` | workspace 根目录 |
| `DATAAGENT_INSTANCE_ID` | 多副本是 | 自动生成 | 应用实例 ID |
| `DATAAGENT_SANDBOX_IDLE_TTL` | 否 | 15m | 空闲回收时间 |
| `DATAAGENT_SANDBOX_MEMORY_LIMIT` | 否 | 512m | 容器内存限制 |
| `DATAAGENT_SANDBOX_CPU_LIMIT` | 否 | 0.5 | 容器 CPU 限制 |
| `SPRING_PROFILES_ACTIVE` | 是 | mysql | profile 组合 |

---

## 二十、测试与验收方案

### 20.1 单机冒烟测试

```bash
# 1. 启动服务
java -jar dataagent.jar --spring.profiles.active=mysql

# 2. 登录获取 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

# 3. 查询 Agent 列表
curl -s http://localhost:8080/api/agents \
  -H "Authorization: Bearer $TOKEN" | jq .

# 4. SSE 流式对话
curl -N -X POST http://localhost:8080/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"列出所有可用的数据源"}'

# 5. SQL 工具
curl -N -X POST http://localhost:8080/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"查询 products 表前 5 条记录"}'
```

验收标准：

| 场景 | 预期 |
|---|---|
| 登录 | 返回 JWT |
| Agent 列表 | 返回 global/user/share 可见 Agent |
| SSE | token/tool_call/tool_result/done 顺序正常 |
| SQL | 只读查询执行成功，row limit 生效 |
| 图表 | `render_chart` 返回图表 spec 或可视化产物 |
| 会话 | inbox 可看到刚才的会话 |
| 文件 | 上传文件后 Agent 可读取 |

### 20.2 Sandbox 生命周期测试

| 测试 | 步骤 | 预期 |
|---|---|---|
| 懒创建 | 只打开聊天页，不发消息 | 不创建容器 |
| 首次 borrow | 发送需要工具执行的消息 | 创建 sandbox，状态 READY/ACTIVE |
| 复用 | 连续追问 | 复用同一 sandbox |
| release | SSE done 后 | lease 释放，active count 归零 |
| idle 回收 | 等待超过 idleTtl | 容器停止，状态 STOPPED |
| 文件持久 | 上传文件→回收容器→再次发问 | 文件仍存在 |
| invalidate | 更新 shared skill | 旧 sandbox 标记 needs_restart，新请求用新容器 |
| active 保护 | 长任务执行中触发 invalidate | 不强杀，进入 DRAINING |
| 心跳异常 | 模拟容器失联 | 标记 UNHEALTHY，下次 borrow 重建 |

### 20.3 文件系统测试

```bash
# 上传文件
curl -X POST "http://localhost:8080/api/agents/data-agent/workspace/upload?path=/data/test.csv" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./test.csv"

# 文件树
curl "http://localhost:8080/api/agents/data-agent/workspace/files?recursive=true" \
  -H "Authorization: Bearer $TOKEN"

# 路径穿越测试，预期被拒绝
curl "http://localhost:8080/api/agents/data-agent/workspace/file?path=../../etc/passwd" \
  -H "Authorization: Bearer $TOKEN"
```

验收标准：

- 上传不触发 sandbox 冷启动。
- `../` 路径被拒绝。
- shared 层写入被拒绝。
- 大文件超过限制被拒绝。
- 容器重建后 user 文件仍可见。

### 20.4 会话管理测试

| 测试 | 预期 |
|---|---|
| 新建会话 | `SessionEntity` 创建，scratch 目录创建 |
| 查看 inbox | 会话按最近活跃排序 |
| 查看详情 | USER/ASSISTANT/TOOL 轮次结构化展示 |
| 标记已读 | unread 清零 |
| reset | 新 runtime session，新 scratch，user workspace 保留 |
| delete | 会话软删除，日志按策略保留 |
| sandbox restart | 会话不删除，执行环境重建 |

### 20.5 多副本测试

```bash
# 副本 A
java -jar dataagent.jar \
  --spring.profiles.active=mysql,redis \
  --server.port=8081 \
  --DATAAGENT_INSTANCE_ID=replica-a \
  --DATAAGENT_WORKSPACE=/mnt/dataagent-workspace

# 副本 B
java -jar dataagent.jar \
  --spring.profiles.active=mysql,redis \
  --server.port=8082 \
  --DATAAGENT_INSTANCE_ID=replica-b \
  --DATAAGENT_WORKSPACE=/mnt/dataagent-workspace
```

验收点：

| 场景 | 预期 |
|---|---|
| sticky session | 同一用户请求稳定进入同一副本 |
| Redis 状态 | 副本 B 可查询副本 A 创建的会话元数据 |
| reaper | 只清理当前 owner 的容器，不误杀其他副本 |
| owner 失联 | 超时后允许接管或重建 |
| shared 文件 | 两副本看到一致的 shared_version |
| SSE | 反向代理不缓冲，长连接不中断 |

### 20.6 压测建议

```bash
# 同步 send 接口压测
hey -z 60s -c 50 -m POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}' \
  http://localhost:8080/api/agents/data-agent/chat/send
```

关注指标：

| 指标 | 说明 |
|---|---|
| P95/P99 延迟 | LLM 响应和工具执行耗时 |
| SSE 连接数 | 同时在线用户数 |
| sandbox 数量 | 容器池规模是否符合预期 |
| active lease 数 | 是否存在泄露 |
| MySQL 连接池 | HikariCP 是否耗尽 |
| Redis 延迟 | lease 和锁是否稳定 |
| workspace 磁盘 | 用户文件和 artifacts 是否超配额 |

---

## 二十一、文档审查后已修正/建议优化点

### 21.1 已在本文档中修正的关键问题

| 问题 | 原风险 | 本次修改 |
|---|---|---|
| “应用方掌握生命周期”表述不够精确 | 容易理解成应用方直接操作 Docker，或与 AgentScope 职责冲突 | 改为“应用控制面 + AgentScope 执行面” |
| sandbox 只按 `(userId,agentId)` 描述 | 多租户和 session 临时文件隔离不足 | 补充 `(tenantId,userId,agentId,workspaceId)` 与 session scratch |
| 容器与文件持久化边界不清 | 容器回收可能导致文件丢失 | 明确容器不是数据源，workspace 是持久事实源 |
| 会话创建过度依赖 harness 黑盒 | 难以绑定 sandbox、scratch、审计 | 新增 SessionRuntimeBindingService 设计 |
| reset/delete/restart 语义混淆 | 用户误删数据或误以为重启环境 | 拆分四类操作语义 |
| 多副本风险描述不够可执行 | sticky session 之外缺少治理路径 | 补充 owner_instance_id、lease、reaper lock、共享存储 |
| 文件 API 安全约束不足 | 路径穿越、symlink、配额风险 | 增加路径安全和配额规则 |
| 管理后台缺少 sandbox 控制面 | 运维无法观察和干预容器 | 新增 `/api/admin/sandboxes` 建议接口 |

### 21.2 代码审查发现的当前实现风险

以下问题基于源码级审查（2026-07-09），是上述设计提案尚未覆盖的、当前代码中已存在的风险：

| 风险 | 级别 | 代码位置 | 影响 | 详见 |
|---|---|---|---|---|
| 双 DockerFilesystemSpec 配置不一致 | P0 | `AgentRuntimeConfigurer` vs `UserSandboxPool.contextFor` | 回退路径下容器回收丢文件，无快照可恢复 | §14.12 |
| 子 Agent 沙箱无快照保护 | P0 | `AgentRuntimeConfigurer` 第 100–125 行 | `code-reviewer` / `report-writer` 产出可能丢失 | §14.13 |
| invalidate 全局爆炸半径 | P1 | `UserSandboxPool.invalidate` 第 219–244 行 | 一次审批触发所有用户容器同时重建 | §14.10 |
| 多副本缺少并发锁 | P1 | 项目全局（无 `SandboxExecutionGuard`） | 两副本同时写同一用户隔离状态 | §18.1.1 |
| 快照时机与会话状态不同步 | P2 | `UserSandboxPool` 快照 vs `AgentStateStore` | 运行中容器意外销毁导致上下文与文件不一致 | §15.7 |
| shared 投影仅建容器时生效 | P2 | `SharedWorkspaceProjection.buildSpec` | 绕过审批流程的 shared 修改不会传播到已有容器 | §15.8 |

### 21.3 代码级风险修复优先级矩阵

| 优先级 | 风险 | 修复方案 | 工作量 | 依赖 |
|---|---|---|---|---|
| P0 | 双 DockerFilesystemSpec 不一致 | 方案 A：从 `AgentRuntimeConfigurer` 移除 `DockerFilesystemSpec`，完全依赖中间件注入；或方案 B：补齐 `snapshotSpec` | 0.5 天 | 无 |
| P0 | 子 Agent 沙箱无快照 | 确认框架子 Agent 沙箱继承路径（需阅读 harness JAR）；如不自动继承则在子 Agent builder 上注册沙箱注入 | 1–2 天 | 需框架源码确认 |
| P1 | invalidate 全局爆炸 | 引入 DRAINING 语义 + `needs_restart` 标记 | 3 天 | 依赖 §14.4 状态机 + §14.7 Lease 表 |
| P1 | 多副本并发锁缺失 | 注入 `RedisSandboxExecutionGuard` Bean | 1 天 | 需 redis profile |
| P2 | 快照时机不同步 | 将 user workspace 剥离到宿主机持久化目录，bind-mount 进容器 | 3–5 天 | 依赖 §15.1 三层目录设计 |
| P2 | shared 投影不传播 | `SharedWorkspaceProjection` 增加 `shared_version` 检查，`borrow` 时比对版本 | 2 天 | 依赖 §14.10 invalidate 的 `shared_version` 机制 |

### 21.4 后续产品化建议

| 优先级 | 建议 | 价值 |
|---|---|---|
| P0 | 完成 SandboxLeaseService 与 SessionRuntimeBindingService | 让沙箱、文件、会话真正可控 |
| P0 | 将 user workspace 从容器 writable layer 中彻底剥离 | 避免回收丢文件 |
| P0 | reaper 加 owner_instance_id 和分布式锁 | 多副本安全基础 |
| P0 | 文件路径安全统一封装 | 防止高危文件逃逸漏洞 |
| P1 | 管理后台增加 sandbox 页面 | 提升运维可观测性 |
| P1 | shared_version/policy_version 机制 | 支持共享能力灰度和可控重建 |
| P1 | workspace 配额和 artifacts 保留策略 | 控制磁盘成本 |
| P2 | sandbox 预热池 | 降低首次工具调用冷启动延迟 |
| P2 | workspace 版本管理 | 支持技能/提示词回滚 |

### 21.5 推荐落地顺序

```text
第零阶段：代码级紧急修复（不改架构，只消除隐患）
  1. 修复双 DockerFilesystemSpec 配置不一致（§14.12 方案 A 或 B）
  2. 确认子 Agent 沙箱继承路径，补齐快照保护（§14.13）

第一阶段：单机稳定
  1. 文件持久化三层目录（§15.1）
  2. sandbox 状态机 + lease（§14.4 / §14.7）
  3. borrow/release lease（§14.8 / §14.9）
  4. invalidate 引入 DRAINING 语义（§14.10）
  5. reset/delete/restart 语义拆分（§16.5）

第二阶段：可运维
  1. sandbox 管理后台（§5.20）
  2. lifecycle audit
  3. heartbeat + unhealthy 重建
  4. 配额与清理策略
  5. shared_version 版本检查机制（§15.8）

第三阶段：多副本
  1. 注入 SandboxExecutionGuard 分布式锁（§18.1.1）
  2. Redis lease/lock
  3. owner_instance_id
  4. reaper 分布式锁
  5. 共享 workspace 存储
  6. 容器接管或安全重建
```

最终推荐模型：**Sandbox 管执行环境，Workspace 管长期文件，Session 管对话上下文，Lease 把一次运行临时绑定到某个 Sandbox。**
