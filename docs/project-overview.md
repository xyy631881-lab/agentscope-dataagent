# agentscope-dataagent 项目完整文档

> 版本：2.0.0-SNAPSHOT | 更新时间：2026-06-30

---

## 一、项目概览

### 1.1 一句话总结

dataagent 是一个**多租户、自进化的企业数据分析 Agent 平台**。每位数据分析师拥有一个私有 data agent（随个人习惯进化），团队把优秀的 SQL 技能、子智能体、图表模板通过审批流程沉淀到共享库，所有人都受益。

### 1.2 核心设计理念

- **多人并行进化、互不干扰。** 每个用户的 workspace 完全隔离（skills / memory / subagents / sessions），同一份初始 agent 在不同人手里会长成不同模样。
- **能力市场，不是大杂烩。** 磨出来的好内容（SQL 技能、子智能体、memory 备忘）可以提名 → 管理员审批 → 进入 `shared/` 共享库 → 下次所有人的 agent 自动看到。知识自下而上流动，但中间有道闸。
- **Sandbox 生命周期由应用方掌握。** Agent 执行的脚本在隔离 Docker 沙箱中运行，容器规格、回收策略、驱动工具链全部可由运维团队定制。

### 1.3 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3 + WebFlux (响应式) |
| AI 引擎 | AgentScope 2.0 HarnessAgent (ReAct + Plan Mode + SubagentsMiddleware) |
| LLM | DashScope (qwen-max) / 可替换 |
| 沙箱 | Docker (DockerFilesystemSpec, USER 隔离) |
| 持久化 | 嵌入式 H2 (默认) → MySQL/PostgreSQL (生产) |
| 分布式 | 可选 Redis (AgentState + ToolEventBus + RemoteFilesystem) |
| 前端 | React SPA (TypeScript + Vite) |
| 通信 | SSE 流式 + JWT 认证 + REST API |

---

## 二、完整业务闭环

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
│           { "targetType": "skill", "payload": "..." }             │
│     管理员: 在 Approvals 页审批                                     │
│     通过: 写入 shared/skills/ → 所有用户可见                       │
└──────────────────────┬───────────────────────────────────────────┘
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  6. 新用户加入 → 直接获得共享库积累的全部能力                       │
│     • OverlayFilesystem 自动融合 shared/ + per-用户 workspace     │
│     循环回到步骤 2                                                │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1 用户视角的一天

1. 登录 http://localhost:8080 → 看到自己的 agent 列表
2. 问："分析上月销售趋势，对比去年同期"
3. Agent 自动执行：`list_data_sources` → `describe_table` → Plan Mode 规划 → `agent_spawn code-reviewer` 审查 SQL → `agent_spawn report-writer` 生成报告 → `render_chart` 出图
4. 用户校对后，可以通过 `/admin/approvals` 把这次的 SQL 模板提名到共享库
5. 次日，团队其他人就能用这个模板了

### 2.2 管理员视角的一天

1. 配置 `agentscope.json` 添加数据源
2. 管理用户和 Agent 实例
3. 审批 marketplace 贡献
4. 查看平台用量统计
5. 调整 sandbox 策略 / 通道绑定

---

## 三、技术架构

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
│  │ web/api/  ── 13 个 REST Controller                  │  │
│  │ web/auth/  ── 认证 + 用户管理                       │  │
│  │ web/catalog/ ── Agent 目录 + 定义持久化              │  │
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

## 四、包结构与文件清单

> 共 98 个 Java 源文件，分布在 18 个包下。

### 4.1 `web/` — Web 层 (Spring Boot)

#### `web/config/` — 配置 (3 个)

| 文件 | 职责 |
|------|------|
| `DataAgentConfig.java` | **核心配置入口**。组装 DataAgentBootstrap，注册 Model Bean，配置所有 Agent 的 Plan Mode / Compaction / Memory / SubagentDeclarations / Permission / 模型容错，创建 ChatUiChannel |
| `SecurityConfig.java` | Spring Security 配置：JWT 过滤器、CORS、路径权限 (公开 / 用户 / 管理员) |
| `WebConfig.java` | CORS 跨域配置 |

#### `web/api/` — REST Controller (13 个)

| 文件 | 路径前缀 | 职责 |
|------|----------|------|
| `ChatController.java` | `/api/agents/{agentId}/chat` | **核心对话端点**。POST `/stream` (SSE 流式)、POST `/send` (同步)、GET `/session` (会话检查)、斜杠命令 `/new` `/reset` `/identity` |
| `SessionController.java` | `/api/sessions` | Session 列表、历史消息、树形结构、reset/delete |
| `AgentCatalogController.java` | `/api/catalog` | 浏览可用的 GLOBAL agent 模板 |
| `AgentCloneController.java` | `/api/me/agents/{id}/clone` | 克隆 agent |
| `AgentSkillsController.java` | `/api/me/agents/{id}/skills` | 查看/编辑/删除 workspace 中的 skill |
| `AgentToolsController.java` | `/api/me/agents/{id}/tools` | 查看/注册自定义工具 |
| `AgentWorkspaceController.java` | `/api/me/agents/{id}/workspace` | 浏览/读写 workspace 文件 |
| `AgentActivityController.java` | `/api/me/agents/{id}/activity` | Agent 活动日志 |
| `AgentBindingController.java` | `/api/user/bindings` | 用户通道绑定偏好 |
| `ChannelDirectoryController.java` | `/api/channels` | 通道目录 |
| `MarketplacesController.java` | `/api/me/marketplaces` | 用户 marketplace 订阅管理 |
| `AdminUserController.java` | `/api/admin/*` | 管理员端：用户管理、运行时概览、用量统计 |
| `SandboxHeartbeatController.java` | `/api/admin/sandbox` | Sandbox 健康检查端点 |

#### `web/auth/` — 认证 (4 个)

| 文件 | 职责 |
|------|------|
| `AuthController.java` | 登录 / 令牌刷新 |
| `JwtService.java` | JWT 签发与验证 |
| `UserController.java` | 用户信息查询 |
| `UserStore.java` | 用户存储接口 |

#### `web/catalog/` — Agent 目录 (4 个)

| 文件 | 职责 |
|------|------|
| `AgentCatalogController.java` | GLOBAL agent 模板列表 |
| `AgentCatalogService.java` | Agent 创建/克隆/配置服务，注入 ToolNotificationMiddleware |
| `AgentDefinition.java` | Agent 定义数据结构 |
| `UserAgentDefinitionStore.java` | 按用户持久化 Agent 定义 |

#### `web/marketplace/` — 能力市场 (6 个)

| 文件 | 职责 |
|------|------|
| `MarketContributionController.java` | 用户提交贡献 (POST /api/me/contributions) |
| `MarketContributionService.java` | 贡献存储与检索 |
| `ContributionApprovalController.java` | 管理员审批 (approve/reject) |
| `ContributeWorkspaceTool.java` | Agent 可调用工具：`contribute_to_workspace` |
| `ContributionToolRegistrar.java` | 将 ContributeWorkspaceTool 注册到所有 GLOBAL agent |
| `FileEntry.java` | 贡献文件实体 |

#### `web/persistence/jpa/` — JPA 持久化 (14 个)

| 文件 | 职责 |
|------|------|
| `UserEntity.java` / `UserEntityRepository.java` | 用户表 |
| `AgentEntity.java` / `AgentEntityRepository.java` | Agent 定义表 |
| `AgentShareEntity.java` | Agent 共享记录 |
| `ContributionEntity.java` / `ContributionRepository.java` | Marketplace 贡献表 |
| `UserMarketplaceEntity.java` / `UserMarketplaceRepository.java` | 用户 marketplace 订阅 |
| `SandboxLifecycleRecord.java` / `SandboxLifecycleRepository.java` | Sandbox 生命周期日志 |
| `JpaPersistenceConfig.java` | JPA 自动配置 |
| `JpaUserAgentDefinitionStore.java` | JPA 实现的 UserAgentDefinitionStore |
| `JpaUserStore.java` | JPA 实现的 UserStore |

#### `web/session/` — 会话管理 (3 个)

| 文件 | 职责 |
|------|------|
| `SessionLifecycleScheduler.java` | 定时任务：空闲重置 / 每日重置 / 维护清理 |
| `SessionReadStateStore.java` | 用户阅读状态追踪 |
| `SessionTurnParser.java` | 对话轮次解析 (JSONL → 结构化 turns) |

#### `web/workspace/` — Sandbox & 文件系统 (5 个)

| 文件 | 职责 |
|------|------|
| `UserSandboxRegistry.java` | 按 (userId, agentId) 管理 Docker 容器生命周期 |
| `SharedSandboxFilesystem.java` | Sandbox 文件系统适配 |
| `SharedWorkspaceSeeder.java` | 新用户 workspace 初始化 (种子数据) |
| `WorkspaceManagerFactory.java` | 按用户创建隔离的 WorkspaceManager |
| `DataAgentWorkspaceConfig.java` | Workspace 路径/存储配置 |

#### `web/` 其他文件 (11 个)

| 文件 | 包 | 职责 |
|------|-----|------|
| `DataAgentApp.java` | `web/` | Spring Boot 入口，启用定时任务 |
| `WorkspaceScaffolder.java` | `web/scaffold/` | 首次启动时自动创建 AGENTS.md / skills/ / subagents/ |
| `ToolEventBus.java` | `web/toolbus/` | 工具事件 SSE 总线 (Sinks.Many) |
| `ToolNotificationMiddleware.java` | `web/toolbus/` | HarnessAgent Middleware：工具调用前发布事件到 ToolEventBus |
| `IdentityLinkStore.java` | `web/identity/` | 用户身份链接 (dock 命令) |
| `AgentAccessGuard.java` | `web/share/` | Agent 访问权限守卫 |
| `AgentAclService.java` | `web/share/` | ACL 服务 (GLOBAL / USER / SHARED) |
| `AgentShareGrant.java` | `web/share/` | Agent 共享授权数据结构 |
| `TemplateController.java` | `web/template/` | Agent 模板管理 |
| `TemplateRegistry.java` | `web/template/` | 模板注册表 |
| `UsageStore.java` | `web/usage/` | 用量统计存储 |

#### `web/ai/` — AI 辅助 (2 个)

| 文件 | 职责 |
|------|------|
| `AgentDraftController.java` | POST `/api/ai/draft` — AI 辅助生成 Agent 配置 |
| `AgentDraftService.java` | 调用 LLM 根据一句话描述生成 Agent 草稿 |

#### `web/audit/` — 审计 (2 个)

| 文件 | 职责 |
|------|------|
| `ActivityEvent.java` | 活动事件数据结构 |
| `AgentActivityStore.java` | 活动日志存储 |

---

### 4.2 `runtime/` — 运行时核心层

#### `runtime/` 根目录

| 文件 | 职责 |
|------|------|
| `DataAgentBootstrap.java` | **编排核心**。组装 Agent + Session + Channel + Gateway 全链路。Builder 模式：加载 agentscope.json → 创建 SessionAgentManager + SessionStore → 创建 HarnessGateway + ChannelManager → 构建 HarnessAgent 实例 |

#### `runtime/session/` — 会话管理 (7 个)

| 文件 | 职责 |
|------|------|
| `SessionAgentManager.java` | **MAIN session 状态管理器**。内存注册表 (sessionsByKey/gateKeyToSessionKey)，增删查改、重置、空闲/每日重置、维护清理。已精简至 ~285 行（子代理执行/通知已迁移至 2.0 SubagentsMiddleware） |
| `SessionStore.java` | JSON 文件持久化 session 元数据 (save/load/touch/remove) |
| `SessionEntry.java` | Session 元数据记录 (sessionKey, agentId, sessionId, label, kind, userId, gateKey, ...) |
| `SessionKind.java` | Session 类型枚举：MAIN / SUBAGENT |
| `AgentManagerConfig.java` | Session 维护配置 (maintenanceConfig) |
| `SessionMaintenanceConfig.java` | 维护策略 (pruneAfterMs, maxEntries) |
| `HistoryResult.java` | Session 历史查询结果 |

#### `runtime/outbound/` — 出站消息 (4 个)

| 文件 | 职责 |
|------|------|
| `OutboundController.java` | POST `/api/outbound/send` — 通过 API 向 IM 通道发消息 |
| `OutboundService.java` | 出站消息投递逻辑：地址解析 + ChannelManager.deliver() |
| `OutboundTool.java` | Agent 工具：`outbound_send` — 主动向钉钉/企微等通道推送消息 |
| `OutboundRequest.java` | 出站请求数据结构 |

#### `runtime/channel/webhook/` — Webhook 通道 (7 个)

| 文件 | 职责 |
|------|------|
| `WebhookChannel.java` | Webhook 通道实现 |
| `WebhookChannelProperties.java` | Webhook 配置属性 |
| `WebhookCallbackController.java` | 入站 Webhook 端点 + 出站回调 |
| `WebhookInboundMapper.java` | 入站请求 → InboundMessage 转换 |
| `WebhookInboundRequest.java` | 入站请求数据结构 |
| `WebhookOutboundClient.java` | 出站回调 HTTP 客户端 |
| `WebhookSignature.java` | HMAC-SHA256 签名验证 |

#### `runtime/config/` — 配置解析 (8 个)

| 文件 | 职责 |
|------|------|
| `AgentscopeConfig.java` | `agentscope.json` 完整数据结构 |
| `AgentConfigEntry.java` | 单个 Agent 的 JSON 配置 |
| `ChannelConfigEntry.java` | 单个 Channel 的 JSON 配置 |
| `BindingConfigEntry.java` | Channel 绑定配置 |
| `ChannelTypeRegistry.java` | Channel 类型注册表 (chatui/dingtalk/webhook → ChannelFactory) |
| `MarketplaceConfigEntry.java` | Marketplace 配置 |
| `SkillRepositoryConfigEntry.java` | Skill 仓库配置 |
| `SkillRepositorySupport.java` | SkillRepository 创建工厂 |
| `SessionLifecycleConfig.java` | Session 生命周期配置 |

#### `runtime/marketplace/` — 市场适配器 (8 个)

| 文件 | 职责 |
|------|------|
| `DataAgentMarketplace.java` | Marketplace 接口 |
| `LocalApprovalMarketplace.java` | 本地审批市场 (从 shared/skills 读取) |
| `GitDataAgentMarketplace.java` | Git 仓库市场 (clone → 加载) |
| `NacosDataAgentMarketplace.java` | Nacos 配置中心市场 |
| `MarketSkillContent.java` | Skill 内容数据结构 |
| `MarketSkillSummary.java` | Skill 摘要数据结构 |
| `UserMarketplaceRegistry.java` | 按用户水化 marketplace 实例 |
| `UserMarketplacePersistence.java` | 用户 marketplace 订阅持久化 |

#### `runtime/middleware/` — 中间件 (1 个)

| 文件 | 职责 |
|------|------|
| `UserSandboxContextMiddleware.java` | 在每个 Agent 调用前注入 per-用户的 Docker Sandbox Context |

#### `runtime/tools/data/` — 数据分析工具 (8 个)

| 文件 | 职责 |
|------|------|
| `DataAgentToolkit.java` | **数据分析工具集**。暴露给 Agent 的工具：`list_data_sources` / `describe_table` / `run_sql_preview` / `render_chart` |
| `DataSource.java` | 数据源定义 |
| `DataSourceRegistry.java` | 数据源注册接口 (SPI) |
| `InMemoryDataSourceRegistry.java` | 内存实现 (admin 通过 agentscope.json 种子) |
| `ChartRenderer.java` | 图表渲染接口 (SPI) |
| `StubChartRenderer.java` | Stub 实现 |
| `DataToolkitConfig.java` | 工具配置 |
| `DataToolkitRegistrar.java` | 启动时将 DataAgentToolkit 注册到所有 GLOBAL agent |

---

## 五、功能清单与验证方法

### 5.1 多租户对话 (核心功能)

**功能**: 用户通过 Web UI 或 API 与自己的 data agent 对话，SSE 流式返回 token / tool_call / tool_result / done 事件。

**验证方法**:
```bash
# 1. 登录获取 JWT
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"bob"}'
# 保存返回的 token

# 2. SSE 流式对话
curl -X POST http://localhost:8080/api/agents/data-agent/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"列出所有可用的数据源"}'
# 返回: event:token / event:tool_call / event:tool_result / event:done

# 3. 同步对话
curl -X POST http://localhost:8080/api/agents/data-agent/chat/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'
```

### 5.2 子代理系统 (agent_spawn/send)

**功能**: Agent 通过 `agent_spawn` 创建子代理执行并行任务，`agent_send` 与运行中的子代理通信，子代理流式事件带 source 字段自动转发。

**配置** (DataAgentConfig.java):
```java
b.subagent(SubagentDeclaration.builder()
    .name("code-reviewer")
    .description("Code review specialist...")
    .model("dashscope:qwen-max")
    .maxIters(5)
    .build());
```

**验证方法**: 发送一条需要复杂分析的消息，观察 stream 事件中是否出现含 `source` 字段的子代理事件。
```bash
# 在 Web UI 中发送:
"分析销售数据并审查生成的 SQL 代码"
# 预期: 看到 agent_spawn → code-reviewer 思考 → agent_send → 结果
```

### 5.3 Plan Mode (任务规划)

**功能**: Agent 用 `plan_enter` 进入只读规划阶段，`plan_write` 写规划文件，`plan_exit` 提交给用户确认后才执行。

**验证方法**: Web UI 中发起复杂分析请求，应在工具调用中看到 `plan_enter` 和 `plan_write`。
```bash
# 发送需要规划的任务:
"分析全年的销售趋势，生成按季度、按地区、按品类的综合报告"
# 预期: Agent 先 plan_enter → plan_write 规划 → 用户确认 → 执行
```

### 5.4 记忆与压缩 (Compaction + Memory)

**功能**: 对话累积到 30 条消息自动压缩，保留最近 10 条 + 摘要；长期记忆定时刷新到 MEMORY.md。

**验证方法**:
```bash
# 1. 连续发 30+ 条消息
# 2. 检查 workspace/MEMORY.md 是否有新写入内容
# 3. 发送 "你还记得我们一开始讨论的数据源吗" 测试记忆检索
```

### 5.5 能力市场 (Marketplace)

**功能**: 用户提交 skill/subagent/memory 贡献 → 管理员审批 → shared/ 共享库。

**验证方法**:
```bash
# 1. 用户提交贡献
curl -X POST http://localhost:8080/api/me/contributions \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "targetType": "skill",
    "targetPath": "sql-template/SKILL.md",
    "rationale": "通用月报 SQL 模板",
    "payload": "# SQL Template\n..."
  }'

# 2. 管理员列出待审批
curl http://localhost:8080/api/admin/contributions?status=PENDING \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 3. 管理员审批通过
curl -X POST http://localhost:8080/api/admin/contributions/1/approve \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 4. 验证: 其他用户下次对话可用此技能
```

### 5.6 权限系统 (Permission)

**功能**: 三态权限引擎 (ALLOW / ASK / DENY)，SQL 执行需用户确认。

**验证方法**:
```bash
# 发送需要执行 SQL 的请求:
"执行 SELECT * FROM sales WHERE date > '2025-01-01'"
# 预期: Agent 请求 run_sql_preview → 前端弹出确认对话框 → 用户确认后执行
```

### 5.7 模型容错

**功能**: 主模型失败自动重试 2 次。

**验证方法**: 断网或使用无效 API Key 发送消息，检查日志中是否有重试记录。

### 5.8 Session 管理

**功能**: 查看 session 列表、历史消息、对话树、reset / delete 操作。

**验证方法**:
```bash
# 列出 session
curl http://localhost:8080/api/sessions \
  -H "Authorization: Bearer $TOKEN"

# 查看历史
curl http://localhost:8080/api/sessions/{sessionKey}/history \
  -H "Authorization: Bearer $TOKEN"

# 重置 session
curl -X POST http://localhost:8080/api/sessions/{sessionKey}/reset \
  -H "Authorization: Bearer $TOKEN"
```

### 5.9 出站消息推送 (Outbound)

**功能**: Agent 可通过 `outbound_send` 工具主动向钉钉/企微等 IM 通道推送消息。

**验证方法**: 在 Web UI 中要求 Agent 推送消息到指定通道。
```bash
# Agent 内部调用:
outbound_send channel_id="dingtalk-prod" peer_id="user123" text="分析完成，请查收报告"
```

### 5.10 Webhook 通道

**功能**: 外部系统通过 HTTP Webhook 与 data agent 交互。

**验证方法**:
```bash
# 计算 HMAC 签名
SIG=$(echo -n '{"externalUserId":"alice","message":"hello"}' | \
  openssl dgst -sha256 -hmac "$SHARED_SECRET" | cut -d' ' -f2)

# 发送 webhook
curl -X POST http://localhost:8080/api/webhook/ops-webhook/inbound \
  -H "X-DataAgent-Sig: $SIG" \
  -H "Content-Type: application/json" \
  -d '{"externalUserId":"alice","message":"how many users yesterday?"}'
```

### 5.11 Agent 草稿 (AI Draft)

**功能**: 输入一句话描述，AI 自动生成 Agent 配置 (name / sysPrompt / suggestedTools / suggestedSkills / suggestedSubagents)。

**验证方法**:
```bash
curl -X POST http://localhost:8080/api/ai/draft \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":"一个专门处理财务数据、生成财务报表的 agent"}'
```

### 5.12 分布式部署 (Redis)

**功能**: 启用 Redis 后，Agent 状态在所有副本间共享，支持无状态扩容。

**验证方法**:
```yaml
dataagent:
  session:
    redis:
      enabled: true
      host: redis.internal
```
启动 2 个副本，分别发送消息，验证 session 状态一致。
详见 [docs/cluster-deploy.md](docs/cluster-deploy.md)。

---

## 六、目录结构

```
agentscope-dataagent/
├── src/main/java/io/agentscope/dataagent/
│   ├── runtime/                          # 核心运行时
│   │   ├── DataAgentBootstrap.java       # 编排核心
│   │   ├── channel/webhook/              # Webhook 通道 (7 个文件)
│   │   ├── config/                       # 配置解析 (8 个文件)
│   │   ├── marketplace/                  # 市场适配器 (8 个文件)
│   │   ├── middleware/                   # 自定义中间件 (1 个)
│   │   ├── outbound/                     # 出站消息 (4 个)
│   │   ├── session/                      # 会话管理 (7 个)
│   │   └── tools/data/                   # 数据分析工具 (8 个)
│   ├── web/                              # Web 层
│   │   ├── DataAgentApp.java             # Spring Boot 入口
│   │   ├── ai/                           # AI 辅助 (2 个)
│   │   ├── api/                          # REST Controller (13 个)
│   │   ├── audit/                        # 审计 (2 个)
│   │   ├── auth/                         # 认证 (4 个)
│   │   ├── catalog/                      # Agent 目录 (4 个)
│   │   ├── config/                       # Spring 配置 (3 个)
│   │   ├── identity/                     # 身份链接 (1 个)
│   │   ├── marketplace/                  # 贡献/审批 (6 个)
│   │   ├── persistence/jpa/              # JPA 持久化 (14 个)
│   │   ├── scaffold/                     # 首次启动脚手架 (1 个)
│   │   ├── session/                      # 会话生命周期 (3 个)
│   │   ├── share/                        # Agent 共享 (3 个)
│   │   ├── template/                     # 模板管理 (2 个)
│   │   ├── toolbus/                      # 工具事件总线 (2 个)
│   │   ├── usage/                        # 用量统计 (1 个)
│   │   ├── util/                         # 工具类 (1 个)
│   │   └── workspace/                    # Sandbox + 文件系统 (5 个)
├── src/main/resources/
│   ├── application.yml                   # Spring Boot 配置
│   ├── static/                           # 前端 React SPA
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
│   └── project-overview.md               # 本文档
├── frontend/                             # React 前端源码
└── pom.xml                               # Maven 项目定义
```

---

## 七、配置参考

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `dataagent.dashscope.api-key` | — | DashScope API 密钥 |
| `dataagent.dashscope.model-name` | `qwen-max` | 模型名称 |
| `dataagent.workspace` | `$CWD` | 工作目录 (生产必填) |
| `dataagent.jwt.secret` | 开发占位 | JWT 签名密钥 (≥32 字符) |
| `dataagent.session.redis.enabled` | `false` | 启用 Redis 分布式状态 |
| `dataagent.session.redis.host` | `localhost` | Redis 地址 |
| `dataagent.marketplace.enabled` | `true` | 启用能力市场 |
| `dataagent.marketplace.max-contribution-bytes` | `1048576` | 最大贡献大小 |
| `dataagent.agent.name` | `data-agent` | Agent 显示名 |
| `dataagent.agent.sys-prompt` | 内置 | 系统提示词 |
| `server.port` | `8080` | HTTP 端口 |

---

## 八、构建与运行

```bash
# 构建
mvn -pl agentscope-examples/agents/agentscope-dataagent -am package -DskipTests

# 运行
java -jar target/agentscope-dataagent-*-exec.jar
# 打开 http://localhost:8080, 默认账号: bob/bob alice/alice
```

---

## 九、相关资源

- [AgentScope Java v2 官方文档](https://java.agentscope.io/v2/en/docs/index.html)
- [AgentScope Java GitHub](https://github.com/agentscope-ai/agentscope-java)
- [官方示例代码](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/documentation)
