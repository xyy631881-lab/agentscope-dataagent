# agentscope-dataagent 项目完整文档

> 版本：2.0.0-SNAPSHOT | 更新时间：2026-07-02

---

## 一、项目概览

### 1.1 一句话总结

dataagent 是一个**多租户、自进化的企业数据分析 Agent 平台**。每位数据分析师拥有一个私有 data agent（随个人习惯进化），团队把优秀的 SQL 技能、子智能体、图表模板通过审批流程沉淀到共享库，所有人都受益。

### 1.2 核心设计理念

- **多人并行进化、互不干扰。** 每个用户的 workspace 完全隔离（skills / memory / subagents / sessions），同一份初始 agent 在不同人手里会长成不同模样。
- **能力市场，不是大杂烩。** 磨出来的好内容（SQL 技能、子智能体、memory 备忘）可以提名 → 管理员审批 → 进入 `shared/` 共享库 → 下次所有人的 agent 自动看到。知识自下而上流动，但中间有道闸。
- **Sandbox 生命周期由应用方掌握。** Agent 执行的脚本在隔离 Docker 沙箱中运行，容器规格、回收策略、驱动工具链全部可由运维团队定制。
- **Agent 可分享。** 用户自建的 Agent 可通过 share API 授权给指定用户或全员（CLONE/RUN/EDIT 三级权限），不靠管理员中转。

### 1.3 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3 + WebFlux (响应式) |
| AI 引擎 | AgentScope 2.0 HarnessAgent (ReAct + Plan Mode + SubagentsMiddleware) |
| LLM | DashScope (qwen-max) / 可替换 |
| 沙箱 | Docker (DockerFilesystemSpec, USER 隔离) |
| 持久化 | 嵌入式 H2 (默认) → MySQL/PostgreSQL (生产) |
| 分布式 | 可选 Redis (AgentState 兜底 + ToolEventBus + RemoteFilesystem) |
| 前端 | React SPA (TypeScript + Vite) |
| 通信 | SSE 流式 + JWT 认证 + REST API |

---

## 二、快速开始（5 分钟跑通）

### 2.1 启动

```bash
# 编译（跳过前端构建）
mvn compile -DskipFrontend

# 打包
mvn package -DskipFrontend -DskipTests

# 运行
java -jar target/agentscope-dataagent-*-exec.jar
# 打开 http://localhost:8080
```

> **Windows 环境 Maven 注意**：Git Bash 下 `mvn` 脚本可能有路径转换问题，使用 `mvn.cmd` + Windows 路径格式即可：
> ```bash
> JAVA_HOME="D:\\jdk21" "D:\\apache-maven-3.9.16\\bin\\mvn.cmd" compile -DskipFrontend
> ```

### 2.2 默认账号

| 用户名 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `admin` | `admin` | user, admin | 所有 profile 都会注入（首次启动空表时） |
| `bob` | `bob` | user | 仅 H2 dev profile 注入 |
| `alice` | `alice` | user | 仅 H2 dev profile 注入 |

**第一次登录建议用 `admin/admin`**——既是 owner（能管理 Agent 分享）也是管理员（能审批贡献、管用户）。

### 2.3 默认数据源

开箱即用的电商测试数据库（无需任何配置）：

| 属性 | 值 |
|---|---|
| 数据源 id | `analytics_db` |
| 标签 | 电商业务数据库 |
| 类型 | H2 内存数据库 |
| JDBC URL | `jdbc:h2:mem:analytics;MODE=MySQL` |
| 表 | `products`(15 行) / `users`(20 行) / `orders`(120+ 行) / `daily_sales`(每日汇总) |
| 覆盖品类 | 电子产品 / 运动户外 / 食品饮料 / 家居办公 / 图书教育 |

种子脚本在 [data-analytics.sql](file:///e:/demo/agentscope-dataagent/src/main/resources/data-analytics.sql)，启动时自动执行。

### 2.4 第一个对话（Web UI）

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
│  6. Agent 分享（新增）                                             │
│     owner: POST /api/agents/{id}/shares                           │
│     被授权者: 立刻在 /api/agents 列表里看到这个 Agent              │
│     权限级别: CLONE / RUN / EDIT                                   │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  7. 新用户加入 → 直接获得共享库积累的全部能力 + 被分享的 Agent      │
│     • OverlayFilesystem 自动融合 shared/ + per-用户 workspace     │
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
5. 调整 sandbox 策略 / 通道绑定（`/admin/instances`、`/admin/channels`）

---

## 四、技术架构

```
┌──────────────────────────────────────────────────────────┐
│  前端: React SPA (localhost:8080)                        │
│  SSE 流式事件 / REST API                                  │
└─────────────────────┬────────────────────────────────────┘
                      ▼
┌──────────────────────────────────────────────────────────┐
│  Spring Boot WebFlux                                      │
│  ┌────────────────────────────────────────────────────┐  │
│  │ web/config/                                         │  │
│  │   DataAgentConfig ── 组装 HarnessAgent + Channel    │  │
│  │   SecurityConfig ── JWT + Spring Security          │  │
│  │   WebConfig ── CORS                                 │  │
│  └────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────┐  │
│  │ web/api/  ── REST Controller                        │  │
│  │ web/auth/  ── 认证 + 用户管理                       │  │
│  │ web/catalog/ ── Agent 目录 + 定义持久化 + 分享授权   │  │
│  │ web/marketplace/ ── 贡献 + 审批流程                  │  │
│  │ web/session/ ── 会话生命周期调度                     │  │
│  │ web/workspace/ ── Sandbox + 文件系统管理              │  │
│  │ web/toolbus/ ── SSE 工具事件总线                     │  │
│  │ web/persistence/ ── JPA 实体 + Repository            │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────┬────────────────────────────────────┘
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
│  │ session/SessionAgentManager ── MAIN session 管理    │  │
│  │ session/SessionStore ── JSON 持久化                 │  │
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

#### `POST /workspace/install` — 从仓库安装技能

```bash
curl -X POST http://localhost:8080/api/agents/data-agent/skills/workspace/install \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"repositoryIndex":0,"skillName":"chart-rendering"}'
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

## 六、Agent 可调用的工具（DataAgentToolkit）

Agent 在对话中可调用以下工具（由 [DataAgentToolkit.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) 暴露）：

| 工具名 | 参数 | 作用 | 测试话术 |
|---|---|---|---|
| `list_data_sources` | 无 | 列出所有已配置数据源 | "列出所有可用的数据源" |
| `describe_table` | `source_id`, `table` | 返回表结构 + 5 行采样 | "看看 analytics_db 里 orders 表的结构" |
| `run_sql_preview` | `source_id`, `sql`, `row_limit`(可选,默认100,上限500) | 执行只读 SELECT/WITH 查询 | "查询最近 7 天的日销售额" |
| `render_chart` | `chart_type`, `vega_lite_spec` | 渲染 Vega-Lite 图表 | "画个折线图展示日销售额趋势" |

**测试链路**：依次问"列出数据源 → 描述表 → 查询数据 → 画图"，能完整走完 4 个工具。

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

> 共 115 个 Java 源文件，分布在 18 个包下。

### 8.1 `web/` — Web 层 (Spring Boot)

#### `web/config/` — 配置 (3 个)

| 文件 | 职责 |
|------|------|
| [DataAgentConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/config/DataAgentConfig.java) | **核心配置入口**。组装 DataAgentBootstrap，注册 Model Bean，通过 `configureAllAgents` 回调统一配置所有 Agent 的 2.0 能力：Plan Mode / Compaction (trigger=30, keep=10) / Memory (throttled flush) / SubagentDeclarations (code-reviewer + report-writer) / PermissionContextState (ALLOW/ASK 规则) / maxRetries=2 / DockerFilesystemSpec (USER 隔离)。可选 Redis 兜底（`@ConditionalOnExpression` 开关） |
| [SecurityConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/config/SecurityConfig.java) | Spring Security 配置：JWT 过滤器、CORS、路径权限 (公开 / 用户 / 管理员) |
| [WebConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/config/WebConfig.java) | CORS 跨域配置 |

#### `web/api/` — REST Controller (14 个)

| 文件 | 路径前缀 | 职责 |
|------|----------|------|
| [ChatController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/ChatController.java) | `/api/agents/{agentId}/chat` | **核心对话端点**。POST `/stream` (SSE 流式)、POST `/send` (同步)、GET `/session` (会话检查)。**注意**：`stream()` 不调用 SessionAgentManager，会话创建/复用由 harness 框架内部处理；`currentSession()` 才调用 `findByGateKey()` |
| [SessionController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/SessionController.java) | `/api/agents/{agentId}/sessions` | Session 列表、历史消息、reset/delete、标记已读 |
| [AdminUserController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/AdminUserController.java) | `/api/admin/users` | 管理员端：用户 CRUD、密码重置、角色管理、撤销用户的所有 share 授权 |
| [AgentCatalogController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/catalog/AgentCatalogController.java) | `/api/agents` | Agent CRUD + **分享管理**（POST/DELETE `/{id}/shares`） |
| [AgentCloneController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/AgentCloneController.java) | `/api/agents/{id}/clone` | 克隆 agent |
| [AgentSkillsController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/AgentSkillsController.java) | `/api/agents/{agentId}/skills` | 查看/编辑/删除 workspace 中的 skill |
| [AgentToolsController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/AgentToolsController.java) | `/api/agents/{agentId}/tools` | 查看/注册自定义工具 |
| [AgentWorkspaceController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/AgentWorkspaceController.java) | `/api/agents/{agentId}/workspace` | 浏览/读写 workspace 文件 |
| [AgentActivityController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/AgentActivityController.java) | `/api/agents/{id}/activity` | Agent 活动日志 |
| [AgentBindingController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/AgentBindingController.java) | `/api/agents/{agentId}/bindings` | 用户通道绑定偏好 |
| [ChannelDirectoryController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/ChannelDirectoryController.java) | `/api/channels` | 通道目录 |
| [MarketplacesController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/MarketplacesController.java) | `/api/me/marketplaces` | 用户 marketplace 订阅管理 |
| [SandboxHeartbeatController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/SandboxHeartbeatController.java) | `/api/internal/sandbox` | Sandbox 健康检查端点 |
| [SandboxReaperService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/SandboxReaperService.java) | — | Sandbox 僵尸容器回收服务 |

#### `web/auth/` — 认证 (4 个)

| 文件 | 职责 |
|------|------|
| [AuthController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/auth/AuthController.java) | 登录 / 令牌刷新 |
| [JwtService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/auth/JwtService.java) | JWT 签发与验证 |
| [UserController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/auth/UserController.java) | 用户信息查询 |
| [UserStore.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/auth/UserStore.java) | 用户存储接口 |

#### `web/catalog/` — Agent 目录 (4 个)

| 文件 | 职责 |
|------|------|
| [AgentCatalogController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/catalog/AgentCatalogController.java) | Agent CRUD + 分享管理端点 |
| [AgentCatalogService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/catalog/AgentCatalogService.java) | Agent 创建/克隆/配置/分享服务。`grantShare`/`revokeShare` 实现 upsert/精确撤销 |
| [AgentDefinition.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/catalog/AgentDefinition.java) | Agent 定义数据结构（含 scope=global/user、ownerId、shares） |
| [UserAgentDefinitionStore.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/catalog/UserAgentDefinitionStore.java) | 按用户持久化 Agent 定义 |

#### `web/share/` — 权限与分享 (3 个)

| 文件 | 职责 |
|------|------|
| [AgentAccessGuard.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/share/AgentAccessGuard.java) | Agent 访问权限守卫。`guard.require(userId, agentId, tier)` 两道门：可见性(404) + 权限级别(403) |
| [AgentAclService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/share/AgentAclService.java) | ACL 服务。Tier 三级(CLONE<RUN<EDIT) + Scope 三种(global/user/share)。`tierFor()` 算用户最高权限 |
| [AgentShareGrant.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/share/AgentShareGrant.java) | Agent 共享授权数据结构（granteeType=USER/WORKSPACE, granteeId, tier） |

#### `web/marketplace/` — 能力市场 (6 个)

| 文件 | 职责 |
|------|------|
| [MarketContributionController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/marketplace/MarketContributionController.java) | 用户提交贡献 (POST /api/me/contributions) |
| [MarketContributionService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/marketplace/MarketContributionService.java) | 贡献存储与检索。审批通过后调 `UserSandboxRegistry.invalidate()` 强制重建容器 |
| [ContributionApprovalController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/marketplace/ContributionApprovalController.java) | 管理员审批 (approve/reject) |
| [ContributeWorkspaceTool.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/marketplace/ContributeWorkspaceTool.java) | Agent 可调用工具：`contribute_to_workspace` |
| [ContributionToolRegistrar.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/marketplace/ContributionToolRegistrar.java) | 将 ContributeWorkspaceTool 注册到所有 GLOBAL agent |
| [FileEntry.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/marketplace/FileEntry.java) | 贡献文件实体 |

#### `web/persistence/jpa/` — JPA 持久化 (14 个)

| 文件 | 职责 |
|------|------|
| `UserEntity.java` / `UserEntityRepository.java` | 用户表 |
| `AgentEntity.java` / `AgentEntityRepository.java` | Agent 定义表（含 shares @OneToMany） |
| `AgentShareEntity.java` | Agent 共享记录（表 `dataagent_agent_share`） |
| `ContributionEntity.java` / `ContributionRepository.java` | Marketplace 贡献表 |
| `UserMarketplaceEntity.java` / `UserMarketplaceRepository.java` | 用户 marketplace 订阅 |
| `SandboxLifecycleRecord.java` / `SandboxLifecycleRepository.java` | Sandbox 生命周期日志 |
| `JpaPersistenceConfig.java` | JPA 自动配置 |
| `JpaUserAgentDefinitionStore.java` | JPA 实现的 UserAgentDefinitionStore |
| `JpaUserStore.java` | JPA 实现的 UserStore（含 `seedDefaultAdmin()` 注入 admin/admin） |

#### `web/session/` — 会话管理 (3 个)

| 文件 | 职责 |
|------|------|
| [SessionLifecycleScheduler.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/session/SessionLifecycleScheduler.java) | 定时任务：每分钟检查空闲超时会话（默认 30 分钟）自动重置、每日定时重置所有会话、每 5 分钟清理过期/超量会话 |
| [SessionReadStateStore.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/session/SessionReadStateStore.java) | 用户阅读状态追踪（已读/未读红点） |
| [SessionTurnParser.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/session/SessionTurnParser.java) | 把 harness 框架写入的 JSONL 日志翻译成结构化对话轮次 |

#### `web/workspace/` — Sandbox & 文件系统 (5 个)

| 文件 | 职责 |
|------|------|
| [UserSandboxRegistry.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/workspace/UserSandboxRegistry.java) | **用户专属 Docker 容器池**。按 `(userId, agentId)` 懒创建、缓存复用、空闲回收 |
| [SharedSandboxFilesystem.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/workspace/SharedSandboxFilesystem.java) | Sandbox 文件系统适配。用 Base64 + shell 单引号转义上传/下载文件 |
| [SharedWorkspaceSeeder.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/workspace/SharedWorkspaceSeeder.java) | 新用户 workspace 初始化 (种子数据) |
| [WorkspaceManagerFactory.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/workspace/WorkspaceManagerFactory.java) | 按用户创建隔离的 WorkspaceManager |
| [DataAgentWorkspaceConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/workspace/DataAgentWorkspaceConfig.java) | Workspace 路径/存储配置 |

#### `web/` 其他文件 (11 个)

| 文件 | 包 | 职责 |
|------|-----|------|
| [DataAgentApp.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/DataAgentApp.java) | `web/` | Spring Boot 入口，启用定时任务 |
| [WorkspaceScaffolder.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/scaffold/WorkspaceScaffolder.java) | `web/scaffold/` | 首次启动时自动创建 AGENTS.md / skills/ / subagents/ |
| [ToolEventBus.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/toolbus/ToolEventBus.java) | `web/toolbus/` | 工具事件 SSE 总线 (Sinks.Many) |
| [ToolNotificationMiddleware.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/toolbus/ToolNotificationMiddleware.java) | `web/toolbus/` | HarnessAgent Middleware：工具调用前发布事件到 ToolEventBus |
| [IdentityLinkStore.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/identity/IdentityLinkStore.java) | `web/identity/` | 用户身份链接 (dock 命令) |
| [TemplateController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/template/TemplateController.java) | `web/template/` | Agent 模板管理 |
| [TemplateRegistry.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/template/TemplateRegistry.java) | `web/template/` | 模板注册表 |
| [UsageStore.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/usage/UsageStore.java) | `web/usage/` | 用量统计存储 |

#### `web/ai/` — AI 辅助 (2 个)

| 文件 | 职责 |
|------|------|
| [AgentDraftController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/ai/AgentDraftController.java) | POST `/api/agents/draft` — AI 辅助生成 Agent 配置 |
| [AgentDraftService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/ai/AgentDraftService.java) | 调用 LLM 根据一句话描述生成 Agent 草稿 |

#### `web/audit/` — 审计 (2 个)

| 文件 | 职责 |
|------|------|
| [ActivityEvent.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/audit/ActivityEvent.java) | 活动事件数据结构 |
| [AgentActivityStore.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/audit/AgentActivityStore.java) | 活动日志存储 |

---

### 8.2 `runtime/` — 运行时核心层

#### `runtime/` 根目录

| 文件 | 职责 |
|------|------|
| [DataAgentBootstrap.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/DataAgentBootstrap.java) | **编排核心**。组装 Agent + Session + Channel + Gateway 全链路 |

#### `runtime/session/` — 会话管理 (7 个)

| 文件 | 职责 |
|------|------|
| [SessionAgentManager.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/session/SessionAgentManager.java) | **会话查询/管理中枢**（但不创建会话）。四个内存索引。提供查询、重置、删除、过期清理 |
| [SessionStore.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/session/SessionStore.java) | `sessions.json` 的读写引擎。临时文件+原子重命名保证写入安全 |
| `SessionEntry.java` | 一条会话档案的 Record |
| `SessionKind.java` | 会话类型枚举：MAIN / SUBAGENT |
| `HistoryResult.java` | 读取历史的结果包装 Record |
| `AgentManagerConfig.java` | 维护策略配置 |
| `SessionMaintenanceConfig.java` | 过期清理规则 |

#### `runtime/outbound/` — 出站消息 (4 个)

| 文件 | 职责 |
|------|------|
| [OutboundController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundController.java) | POST `/api/outbound/send` |
| [OutboundService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundService.java) | 出站消息投递逻辑 |
| [OutboundTool.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundTool.java) | Agent 工具：`outbound_send` |
| `OutboundRequest.java` | 出站请求数据结构 |

#### `runtime/channel/webhook/` — Webhook 通道 (7 个)

| 文件 | 职责 |
|------|------|
| [WebhookChannel.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookChannel.java) | Webhook 通道实现 |
| `WebhookChannelProperties.java` | Webhook 配置属性 |
| [WebhookCallbackController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookCallbackController.java) | 入站 Webhook 端点 + 出站回调 |
| `WebhookInboundMapper.java` | 入站请求 → InboundMessage 转换 |
| `WebhookInboundRequest.java` | 入站请求数据结构 |
| `WebhookOutboundClient.java` | 出站回调 HTTP 客户端 |
| [WebhookSignature.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookSignature.java) | HMAC-SHA256 签名验证 |

#### `runtime/config/` — 配置解析 (9 个)

| 文件 | 职责 |
|------|------|
| `AgentscopeConfig.java` | `agentscope.json` 完整数据结构 |
| `AgentConfigEntry.java` | 单个 Agent 的 JSON 配置 |
| `ChannelConfigEntry.java` | 单个 Channel 的 JSON 配置 |
| `BindingConfigEntry.java` | Channel 绑定配置 |
| `ChannelTypeRegistry.java` | Channel 类型注册表 |
| `MarketplaceConfigEntry.java` | Marketplace 配置 |
| `SkillRepositoryConfigEntry.java` | Skill 仓库配置 |
| `SkillRepositorySupport.java` | SkillRepository 创建工厂 |
| `SessionLifecycleConfig.java` | Session 生命周期配置 |

#### `runtime/marketplace/` — 市场适配器 (8 个)

| 文件 | 职责 |
|------|------|
| `DataAgentMarketplace.java` | Marketplace 接口 |
| `LocalApprovalMarketplace.java` | 本地审批市场 |
| `GitDataAgentMarketplace.java` | Git 仓库市场 |
| `NacosDataAgentMarketplace.java` | Nacos 配置中心市场 |
| `MarketSkillContent.java` | Skill 内容数据结构 |
| `MarketSkillSummary.java` | Skill 摘要数据结构 |
| `UserMarketplaceRegistry.java` | 按用户水化 marketplace 实例 |
| `UserMarketplacePersistence.java` | 用户 marketplace 订阅持久化 |

#### `runtime/middleware/` — 中间件 (1 个)

| 文件 | 职责 |
|------|------|
| [UserSandboxContextMiddleware.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/middleware/UserSandboxContextMiddleware.java) | 在每个 Agent 调用前注入 per-用户的 Docker Sandbox Context |

#### `tools/data/` — 数据分析工具 (8 个)

| 文件 | 职责 |
|------|------|
| [DataAgentToolkit.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) | **数据分析工具集**。4 个工具：`list_data_sources` / `describe_table` / `run_sql_preview` / `render_chart`。用 JdbcTemplate + ConnectionCallback + setMaxRows 实现 |
| `DataSource.java` | 数据源定义 |
| `DataSourceRegistry.java` | 数据源注册接口 (SPI) |
| `InMemoryDataSourceRegistry.java` | 内存实现 |
| `ChartRenderer.java` | 图表渲染接口 (SPI) |
| `StubChartRenderer.java` | Stub 实现 |
| [DataToolkitConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/DataToolkitConfig.java) | 工具配置。用 JdbcTemplate 包装 DataSource |
| `DataToolkitRegistrar.java` | 启动时将 DataAgentToolkit 注册到所有 GLOBAL agent |
| [MarkdownTables.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/MarkdownTables.java) | Markdown 表格渲染工具类 |

---

## 九、目录结构

```
agentscope-dataagent/
├── src/main/java/io/agentscope/dataagent/
│   ├── runtime/                          # 核心运行时
│   │   ├── DataAgentBootstrap.java       # 编排核心
│   │   ├── channel/webhook/              # Webhook 通道 (7 个文件)
│   │   ├── config/                       # 配置解析 (9 个文件)
│   │   ├── marketplace/                  # 市场适配器 (8 个文件)
│   │   ├── middleware/                   # 自定义中间件 (1 个)
│   │   ├── outbound/                     # 出站消息 (4 个)
│   │   ├── session/                      # 会话管理 (7 个)
│   ├── tools/data/                       # 数据分析工具 (9 个)
│   ├── web/                              # Web 层
│   │   ├── DataAgentApp.java             # Spring Boot 入口
│   │   ├── ai/                           # AI 辅助 (2 个)
│   │   ├── api/                          # REST Controller (14 个)
│   │   ├── audit/                        # 审计 (2 个)
│   │   ├── auth/                         # 认证 (4 个)
│   │   ├── catalog/                      # Agent 目录 (4 个)
│   │   ├── config/                       # Spring 配置 (3 个)
│   │   ├── identity/                     # 身份链接 (1 个)
│   │   ├── marketplace/                  # 贡献/审批 (6 个)
│   │   ├── persistence/jpa/              # JPA 持久化 (14 个)
│   │   ├── scaffold/                     # 首次启动脚手架 (1 个)
│   │   ├── session/                      # 会话生命周期 (3 个)
│   │   ├── share/                        # Agent 共享与权限 (3 个)
│   │   ├── template/                     # 模板管理 (2 个)
│   │   ├── toolbus/                      # 工具事件总线 (2 个)
│   │   ├── usage/                        # 用量统计 (1 个)
│   │   ├── util/                         # 工具类 (1 个)
│   │   └── workspace/                    # Sandbox + 文件系统 (5 个)
├── src/main/resources/
│   ├── application.yml                   # Spring Boot 主配置
│   ├── application-dev.yml               # H2 dev profile
│   ├── application-mysql.yml             # MySQL profile
│   ├── data-h2.sql                       # H2 用户种子数据
│   ├── data-analytics.sql                # 电商测试数据库种子
│   ├── prompts/agent-draft.md            # AI Draft 提示词
│   └── shared/agents/data-agent/         # 共享技能/子代理
│       ├── skills/
│       │   ├── chart-rendering/SKILL.md
│       │   └── sql-analysis/SKILL.md
│       └── subagents/
│           ├── data-explorer.md
│           └── report-writer.md
├── docs/
│   ├── 2.0-upgrade-guide.md              # 2.0 升级指南
│   ├── agent-definition.md               # Agent 定义方式
│   ├── cluster-deploy.md                 # 分布式部署指南
│   ├── 聊天链路协作详解.md                # 聊天链路文档
│   └── project-overview.md               # 本文档
├── frontend/                             # React 前端源码
└── pom.xml                               # Maven 项目定义
```

---

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
| `dataagent.analytics.h2.enabled` | `true` | 启用电商测试数据库 |
| `server.port` | `8080` | HTTP 端口 |

---

## 十一、构建与运行

```bash
# 编译（跳过前端构建）
mvn compile -DskipFrontend

# 打包
mvn package -DskipFrontend -DskipTests

# 运行
java -jar target/agentscope-dataagent-*-exec.jar
# 打开 http://localhost:8080, 默认账号: admin/admin (或 bob/bob)
```

> **Windows 环境 Maven 注意**：Git Bash 下 `mvn` 脚本可能有路径转换问题，使用 `mvn.cmd` + Windows 路径格式即可：
> ```bash
> JAVA_HOME="D:\\jdk21" "D:\\apache-maven-3.9.16\\bin\\mvn.cmd" compile -DskipFrontend
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
| [application.yml](file:///e:/demo/agentscope-dataagent/src/main/resources/application.yml) | 默认 H2 零依赖启动。同文件内含 `mysql` 和 `redis` profile（`---` 分隔），一个文件搞定所有环境 |
| [DataAgentConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/config/DataAgentConfig.java) | **全项目最重要文件**——组装 DataAgentBootstrap，注册 Model Bean，`configureAllAgents()` 加 Plan Mode/Compaction/Memory/Permission/Subagents |

### Step 2 — 启动管线（10 分钟）

| 文件 | 看什么 |
|------|--------|
| [DataAgentBootstrap.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/DataAgentBootstrap.java) | 从 `agentscope.json` 构建 HarnessAgent → 创建 Gateway → 绑定 Channel → 初始化 SessionAgentManager |
| [SessionAgentManager.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/session/SessionAgentManager.java) | 会话查询/管理中枢（但不创建会话）。按 gateKey 索引查 session、reset 会话、维护清理 |
| [UserSandboxRegistry.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/workspace/UserSandboxRegistry.java) | Docker 沙箱容器池。按 `(userId, agentId)` 懒创建/复用/回收 |
| [SecurityConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/config/SecurityConfig.java) | JWT 过滤器链、`/api/` 路径权限 |

### Step 3 — 对话是怎么走的（15 分钟）

| 文件 | 看什么 |
|------|--------|
| [ChatController.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/api/ChatController.java) | **入口**。`POST /stream` 是核心——把一个用户消息变成 SSE 事件流：token → tool_call → tool_result → done |
| [DataAgentToolkit.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) | Agent 实际调用的四个工具：`list_data_sources` / `describe_table` / `run_sql_preview` / `render_chart` |
| [AnalyticsDataConfig.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/tools/data/AnalyticsDataConfig.java) | 独立的 H2 分析数据库 + DataSourceRegistry |
| [OutboundTool.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundTool.java) | Agent 向 IM 通道推送消息的 `outbound_send` 工具 |

### Step 4 — 权限与分享（10 分钟）

| 文件 | 看什么 |
|------|--------|
| [AgentAclService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/share/AgentAclService.java) | Tier 三级(CLONE<RUN<EDIT) + Scope 三种(global/user/share)。`tierFor()` 算用户最高权限 |
| [AgentAccessGuard.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/share/AgentAccessGuard.java) | `guard.require(userId, agentId, tier)` 两道门：可见性(404) + 权限级别(403) |
| [AgentCatalogService.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/catalog/AgentCatalogService.java) | `grantShare()`/`revokeShare()` 实现，upsert 语义 |

### Step 5 — 高级功能（选读）

| 文件 | 看什么 |
|------|--------|
| `web/marketplace/` | 技能贡献 → 审批 → 共享库流程 |
| `runtime/marketplace/` | Git/Nacos 市场的适配器 |
| `runtime/channel/webhook/` | HTTP Webhook 入站通道（签名验证） |
| [UserSandboxRegistry.java](file:///e:/demo/agentscope-dataagent/src/main/java/io/agentscope/dataagent/web/workspace/UserSandboxRegistry.java) | Docker 沙箱按 (userId, agentId) 生命周期管理 |

---

## 十四、沙箱隔离体系详解

> UserSandboxRegistry 是多租户隔离的核心——它为每个 `(userId, agentId)` 组合懒创建、缓存复用、空闲回收 Docker 沙箱容器，确保用户之间的文件系统完全物理隔离。

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
| `invalidate(userId, agentId)` | 关闭并移除沙箱；userId=null 时清该 Agent 所有用户的 | MarketContributionService（审批通过后强制重建容器加载新共享内容） |
| `evictIdle()` | 后台定时扫描，超过 idleTtl（默认 15 分钟）的容器自动关闭 | 内部定时线程 |
| `shutdownAll()` | 关闭所有沙箱，清空缓存 | Spring `@PreDestroy`（应用停机时） |

### 14.4 容器创建过程

```
borrow() 首次调用
    │
    ▼
createAndStart()
    ├── 1. buildWorkspaceSpec(key)
    │       构建 WorkspaceSpec：把 {cwd}/shared/agents/{agentId}/ 下的
    │       AGENTS.md / skills/ / subagents/ / knowledge/ 投影（挂载）到容器
    │
    ├── 2. client.create(ws, NoopSnapshotSpec, options)
    │       创建 Docker 容器
    │
    ├── 3. sandbox.start()
    │       启动容器
    │
    └── 4. recordLifecycle(key, sandbox)
            写 DB 记录（SandboxLifecycleRecord），跟踪容器 ID/状态/心跳
```

**共享内容投影**：每个新容器启动时，自动获得该 Agent 的共享内容（技能、子代理、知识库、AGENTS.md），但用户自己的文件是空的。

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
| **纯内存** | `entries` 是 `ConcurrentHashMap`，重启即丢失。DB 记录用于重启后识别孤儿容器 |
| **单副本** | 多副本部署必须用 sticky session（按 userId 粘性路由），否则两个 pod 会为同一用户创建两个容器 |
| **懒创建** | 容器不会预创建，首次 `borrow()` 才启动（冷启动延迟） |
| **空闲回收** | 默认 15 分钟不活跃就关闭，下次 `borrow()` 重新创建 |
| **共享内容投影** | 新容器自动挂载 `{cwd}/shared/agents/{agentId}/` 下的只读内容 |

### 14.6 用一个比喻总结

> **UserSandboxRegistry 就像酒店的"房间管理台"**：
> - `borrow()` = 客人来了，有房间就给钥匙，没房间就开一间新的
> - `peek()` = 只是看看客人有没有房间，不开新房
> - `invalidate()` = 客房服务更新了，让客人换一间新房间
> - `evictIdle()` = 客人走了很久，把房间收回
> - `shutdownAll()` = 酒店打烊，所有房间都关了
> - 每个房间（Docker 容器）里都有标准配置（共享内容投影），但客人的私人物品互不可见

---

## 十五、会话管理体系详解

> Session* 文件构建的是一个"聊天管理后台"，而不是"打电话的能力"。ChatController 的 stream/send 是"打电话"（harness 提供），Session* 文件是"聊天记录管理"（本项目实现）。

### 15.1 核心架构：两个接口各司其职

| 接口 | 方法 | 职责 | 是否调用 SessionAgentManager |
|------|------|------|---------------------------|
| `GET /session` | `currentSession()` | 前端探测"有没有进行中的对话" | ✅ 调用 `findByGateKey()` |
| `POST /stream` | `stream()` | 发送消息，触发 Agent 处理 | ❌ 不调用，会话创建由 harness 框架内部处理 |

**`stream()` 和 `currentSession()` 不是串行关系，而是并行关系**：
- `currentSession()` → 算 key → 查会话 → 返回"有/无"（纯查询）
- `stream()` → 直接发消息 → Gateway 自动处理会话（黑盒）

### 15.2 SessionAgentManager 的真实定位

**SessionAgentManager 没有任何 `create()`/`register()`/`add()` 方法。** 它是会话的"消费者和管理者"，不是"生产者"。

| 角色 | 组件 | 职责 |
|------|------|------|
| **会话生产者** | harness 框架 | 创建会话、直接写 `sessions.json`、写聊天日志 |
| **会话消费者/管理者** | SessionAgentManager | 查询、重置、删除、过期清理（启动时从 `sessions.json` 加载数据） |

**数据流**：
```
harness 框架（运行时）──直接写──→ sessions.json ──启动时加载──→ SessionAgentManager（内存索引）
```

### 15.3 用微信类比理解

| 组件 | 对应微信的功能 | 在项目中 |
|------|--------------|---------|
| `ChatController` (stream/send) | 发消息/接电话 | **聊天能力**（harness 提供） |
| `SessionController` | 聊天列表 + 聊天记录 + 删除聊天 | **会话管理 UI** |
| `SessionAgentManager` | 后台的聊天记录数据库 | **会话数据层** |
| `SessionStore` | 聊天记录存到手机本地 | **持久化层** |
| `SessionTurnParser` | 把聊天记录格式化展示 | **日志解析器** |
| `SessionReadStateStore` | 未读红点提醒 | **已读状态跟踪** |
| `SessionLifecycleScheduler` | 自动清理过期聊天 | **定时任务** |

**如果你只关心"怎么发消息、怎么收到回复"，那确实不需要任何 Session* 文件。但如果你想做一个完整的聊天产品（有历史记录、有聊天列表、能删除、能重置），那就必须要有这套基础设施。**

---

## 十六、近期变更 (2026.07.02)

### 本次更新

| 模块 | 变更 | 效果 |
|------|------|------|
| **Agent 分享 API** | 新增 `POST/DELETE /api/agents/{id}/shares` 端点 + `grantShare`/`revokeShare` Service 方法 | 补全了之前"地基打好了楼没盖"的架构缺口——ACL 引擎、存储映射、撤销逻辑都齐了，现在写入端点也齐了 |
| **前端分享管理** | 新增 `/configure/shares` 页面 + `grantShare`/`revokeShare` API 函数 + ChatHeader 导航按钮 | owner 可在 UI 上管理 Agent 分享，非 owner 看到只读列表 |
| **SharedSandboxFilesystem** | 用框架 `FilesystemUtils.shellQuote()` 替代手写 `shellSingleQuote`；base64Content 也包单引号 | 对齐父类 quoting 逻辑，消除重复代码，增强防御性 |
| **DataAgentToolkit** | 重写为 JdbcTemplate 实现，用 ConnectionCallback + setMaxRows 解决 LIMIT 方言问题 | 402→347 行，修复 Oracle 不兼容的 LIMIT 子句 |
| **MarkdownTables** | 新建工具类，集中 markdown 表格渲染逻辑 | 消除 DataAgentToolkit 里的重复渲染代码 |
| **SandboxReaperService** | 修复 4 个问题：抽 `runDockerCmd()` 方法、ProcessBuilder.redirectOutput(DISCARD)、waitFor(60, SECONDS) 超时 | 解决死锁和调度线程阻塞 |
| **DataAgentConfig** | Redis 兜底（`@ConditionalOnExpression` 开关 + Lettuce 原生 RedisClient）、ModelRegistry 字符串、toolResultEviction、fallback model、弱化 JSON 配置 | 对齐 AgentScope 2.0 |
| **DataAgentBootstrap** | 删除死函数 `loadConfig()` | 清理 633→496 行 |
| **SandboxLifecycleRepository** | 新增 `findByStatus(Status)` 和 `findByStatusIn(List<Status>)` | 支持按状态查询容器 |

### 历史变更 (2026.06.30)

| 模块 | 变更 | 效果 |
|------|------|------|
| 子代理系统 | 删除自建 SessionsTool(579行)+AnnounceDispatcher(318行)，改用 2.0 SubagentsMiddleware | 子代理原生支持 |
| Session 管理 | SessionAgentManager 955→270 行，session/ 目录 19→7 文件 | 仅保留注册表 |
| SSE 事件流 | 3 个 ConcurrentHashMap → 1 个 ToolBuffer | 代码量 -60% |
| Session 查找 | O(n) 扫描 → O(1) gateKeyToSessionKey 索引 | 性能提升 |

### 编译状态

`mvn compile -DskipTests` → **BUILD SUCCESS**（0 errors）
