# 2026-07-10 当前交付状态与架构校准

本文以 2026-07-10 的源码为准，记录沙箱生命周期、聊天可观测性和业务能力的实际状态。
历史设计稿会被保留，但不能据此判断某个类或 API 仍然存在。

## 1. 今日验收结果

| 范围 | 状态 | 依据 |
|---|---|---|
| 后端单元/集成测试 | 通过 | `mvn test`：29 项测试，0 failure/error，11 项为原有跳过项 |
| 前端生产构建 | 通过 | `npm.cmd run build`：TypeScript 检查与 Vite 构建均通过 |
| 聊天历史 | 已实现 | `SessionsSidebar` 与 `ChatPanel` 支持收件箱、会话恢复和轮次加载 |
| 工具执行可观测性 | 已实现 | 每条助手消息有可折叠执行轨迹，可查看参数、结果、状态和调用 ID |
| 人工确认 | 已实现 | `confirm` SSE 事件会回到对应工具调用，可在原位置批准或拒绝 |

上述检查证明了当前构建和单元测试可用；尚不能替代一次真实 Docker 工具调用和 ASK
权限流程的浏览器冒烟测试。

## 2. 沙箱生命周期最终方案

### 结论

采用 AgentScope 的框架生命周期，不再为同一 Docker 容器维护第二套应用侧容器池、清理器、
心跳端点或回合锁。

应用仍然负责产品策略：用户和 Agent 身份、工作区投影、沙箱客户端选择、快照后端选择和
API 授权；AgentScope 通过 `SandboxManager` 负责获取、启动、持久化、释放、恢复和删除沙箱状态。

### 当前执行链路

```text
Agent 工具调用或浏览器工作区操作
  -> SharedSandboxFilesystem
  -> SandboxManager.acquire(context, RuntimeContext(userId))
  -> sandbox.start()
  -> execute / upload / download
  -> SandboxManager.persistState(...)
  -> SandboxManager.release(...) + lease.close()
```

隔离键由 `IsolationScope.USER` 和 `RuntimeContext.userId` 提供。浏览器工作区操作刻意复用
与 Agent 执行相同的框架生命周期，不会缓存原始 `Sandbox` 或 Docker 容器引用。

### 代码职责

| 组件 | 当前职责 |
|---|---|
| `DataAgentWorkspaceConfig` | 装配 AgentScope `SandboxClient`、状态存储、快照规格和执行守卫 |
| `WorkspaceManagerFactory` | 创建浏览器工作区管理器，不维护应用侧容器缓存 |
| `SharedSandboxFilesystem` | 每次文件操作获取框架 lease，持久化状态后释放 |
| `FrameworkDockerSandboxClient` | 仅做 Windows Docker 启动和反序列化后远程快照客户端重绑 |
| `RetryStartDockerSandbox` | 仅做 Docker Desktop/Windows 启动韧性处理，不成为第二套生命周期管理器 |

### 已删除的旧设计

以下组件已不属于当前架构；没有新的独立设计决策时，不应重新引入：`UserSandboxPool`、
`UserSandboxContextMiddleware`、`SandboxLock`、`SandboxReaperService`、
`SandboxHeartbeatController` 和沙箱生命周期审计实体。

### Windows `WorkspaceStartException` 修复记录

**现象：** `WorkspaceStartException: Failed to start workspace at \workspace`。

**根因：** Windows 中 `Path.of("/workspace")` 会变为 `\workspace`。该值传入 Docker 的
Linux shell 后，`\w` 被作为转义处理，命令创建的是相对路径 `workspace`，而不是框架需要的
绝对路径 `/workspace`；后续工作区打包或初始化因找不到 `/workspace` 而失败。

**修复：** `RetryStartDockerSandbox` 在框架启动尝试前后，使用硬编码正斜杠命令创建
`/workspace`，并保留有界重试和容器状态检查。`FrameworkDockerSandboxClient` 只负责把
框架 Docker 沙箱包装为该兼容适配器。

**为什么这是最优方案：** 修复仅限平台兼容层；生命周期归属、状态恢复和并发语义仍留在
AgentScope 内部，避免与框架重复实现并产生冲突。

## 3. 聊天可观测性与人工确认

`tool_call` 与 `tool_result` SSE 事件现在均携带 `toolCallId`。前端据此把状态精确绑定到
对应调用，即使同一工具连续调用多次也不会串行匹配错误。

| 可见行为 | 当前实现 |
|---|---|
| 对话历史 | 收件箱恢复会话轮次；有消息预览时侧栏不再优先显示 UUID |
| 执行过程 | 每条助手消息只有一个可折叠执行轨迹；单次调用可展开查看 ID、输入和结果 |
| 执行状态 | 明确显示执行中、已完成、失败、等待批准和已拒绝 |
| 人工确认 | 确认卡嵌在对应执行轨迹下方，不再在输入区重复出现 |
| 恢复执行 | 批准/拒绝将原始工具调用 ID 作为 `confirmResults` 回传；决策前输入框保持禁用 |

## 4. 业务能力审查

| 能力 | 状态 | 说明与下一步 |
|---|---|---|
| JWT 登录、多用户 Agent 访问、分享、克隆、工作区、技能、市场、贡献审批 | 已实现 | 路由和应用服务均存在；后续补充授权边界的接口测试 |
| 流式聊天、多会话收件箱、会话元数据持久化、已读/重置/删除 | 已实现 | 已有聊天 UI 和 REST 路由；建议补充切换、刷新恢复的浏览器回归测试 |
| 工具调用审计和 HITL 批准 | 已实现 | 已完成 SSE 关联和界面；应补真实沙箱下的 ASK 端到端测试 |
| `list_data_sources` | 已实现 | 从已配置的数据源目录读取 |
| `describe_table`、`run_sql_preview` | 条件实现 | 配置 `JdbcTemplate`/连接器时可用；独立模式会明确返回 `not implemented` |
| SQL 安全 | 基础实现 | 已有限制 SELECT/WITH、拦截 DDL/DML 关键字、限制行数；生产环境应增加数据库只读账号或 SQL 解析器 |
| 图表渲染 | 未端到端实现 | 后端为 `StubChartRenderer`，聊天前端未渲染 Vega-Lite 或表格；需要真实渲染器和安全的前端可视化组件 |
| 聊天 Markdown/表格渲染 | 未实现 | 当前聊天按纯文本显示；数据分析产品应补充消毒后的 Markdown 和表格渲染 |
| 管理端运行概览 | 部分实现 | Agent/会话/用户数可用；`RuntimeController` 的通道数和近期活动仍是占位数据 |
| 沙箱运维控制面 | 未实现 | 旧文档描述的管理员沙箱 API 当前不存在；应先评估框架可提供的指标和管理接口 |
| 多副本沙箱行为 | 未验证 | 不应再声称已实现粘性路由、应用分布式锁或自定义清理器；需要按目标部署拓扑验证 AgentScope 的状态存储和执行守卫 |

## 5. 推荐下一轮工作

1. 做一次手工 Docker 冒烟：新会话、工具执行、批准、工具结果、刷新和历史恢复。
2. 增加 Playwright 回归：会话切换、执行轨迹折叠、批准与拒绝两种路径。
3. 在聊天中渲染消毒后的 Markdown 表格，并接入真实 Vega-Lite 图表组件。
4. 增加 Chat SSE 事件形状和确认恢复的 HTTP/Controller 测试。
5. 提交或部署前，将 `application.yml` 中的默认模型 API Key 改为仅允许环境变量注入的必填值。
