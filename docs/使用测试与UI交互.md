# agentscope-dataagent 使用、测试与 UI 交互指南

> 更新时间：2026-07-13
> 面向对象：使用者、测试人员、前端/后端联调人员

## 1. 页面认路

| 页面 | 路径 | 用途 |
|---|---|---|
| 登录 | `/login` | 使用账号密码登录 |
| 聊天 | `/chat` | 和当前 data agent 对话，查看历史、工具调用、人工确认 |
| 工作区 | `/workspace` | 浏览 `/workspace` 文件、计划、图表、报告、本地镜像路径 |
| 技能 | `/configure/skills` | 查看、安装、管理技能 |
| 子 Agent | `/configure/subagents` | 管理子代理定义 |
| 工具 | `/configure/tools` | 查看内置工具和 MCP 工具 |
| 分享 | `/configure/shares` | 授权其他用户运行、编辑或克隆 |
| 设置 | `/configure/settings` | 修改 Agent 名称、提示词、模型等 |
| 用量 | `/usage` | 查看请求级 Token、缓存 Token、成本、耗时和模型分布 |
| 运行记录 | `/traces` | 查看按一次请求折叠的 Agent、模型、工具 OTel span |
| 模型连接 | `/models` | 配置当前租户的模型凭据、Base URL、模型名和单价 |
| 管理后台 | `/admin/*` | 用户、Agent、审批、会话、运行概览 |

默认账号：

| 用户名 | 密码 | 权限 |
|---|---|---|
| `admin` | `admin` | 普通用户 + 管理员 |

## 2. 启动检查

必须启动：

- MySQL：业务库、会话索引、用户、审批、Agent 元数据。
- Redis：建议开发和生产都开；用于 AgentScope runtime state、sandbox snapshot、execution guard。
- Docker：只有需要工具执行、文件生成、sandbox 工作区时才必须。
- 模型 API：LongCat/OpenAI-compatible 或 Ollama。

关键配置：

```yaml
spring:
  profiles:
    default: mysql,redis

dataagent:
  runtime:
    redis:
      enabled: true
      key-prefix: dataagent:runtime:
```

Redis 不是聊天 session 库。聊天会话元数据在 MySQL，Agent 运行期状态在 Redis。

生产还必须设置：

```text
LONGCAT_API_KEY=...
DATAAGENT_CREDENTIAL_ENCRYPTION_KEY=...
```

第二项是租户模型凭据的加密主密钥，不能在生产环境临时更换。

## 3. 聊天测试

登录后进入 `/chat`，依次发送：

| 步骤 | 输入 | 预期 |
|---|---|---|
| 1 | `列出所有可用的数据源` | 触发 `list_data_sources`，返回 `analytics_db` |
| 2 | `看看 analytics_db 里 orders 表结构和样例` | 触发 `describe_table`，返回列信息和样例行 |
| 3 | `查询最近 7 天的日销售额` | 触发 `run_sql_preview`，出现人工确认，批准后返回表格 |
| 4 | `把日销售额画成折线图` | 触发 `render_chart`，聊天气泡内展示 Vega-Lite 图表 |
| 5 | `把计划保存下来` | Plan Mode 产生 `plans/PLAN.md`，工具结果里的路径可点击 |

聊天 UI 应看到：

- 用户消息靠右，助手消息靠左。
- 助手消息上方有“执行轨迹”折叠块。
- 每个工具调用能展开查看调用 ID、输入参数、执行结果和状态。
- `run_sql_preview` 会出现人工确认卡片，批准或拒绝后继续当前会话。
- 工具结果中出现 `plans/...` 或 `artifacts/...` 路径时，可以直接点击打开工作区文件。

## 4. 工作区测试

进入 `/workspace`。

页面顶部应展示三类路径：

| 字段 | 含义 |
|---|---|
| 运行时工作区 | 容器内视角，固定为 `/workspace` |
| Agent 定义目录 | 宿主机上的 agent workspace 路径 |
| 本地镜像 | `local-workspaces/{userId}/{agentId}`，只读查看 sandbox 内容 |

页面还会展示统计：

- 技能数量
- 子 Agent 数量
- 记忆文件数量
- 产物总数
- 图表、报告、数据集数量

重要语义：

- Docker 容器执行后消失是正常现象。
- 长期文件以 workspace/snapshot 为准。
- 本地镜像用于查看，不建议手工改；写入请通过 UI 或 Agent 工具。

### 计划文件

当工具结果出现：

```text
plans/PLAN.md
```

点击链接会跳转 `/workspace?path=plans%2FPLAN.md` 并选中文件。

### 图表产物

当前 `render_chart` 采用服务端校验、前端渲染模式。图表会直接显示在聊天气泡中；如果后续工具把图表另存为 `artifacts/charts/...`，工作区页面会统计并可点击打开。

## 5. 会话与历史

左侧会话列表支持：

| 操作 | 预期 |
|---|---|
| 新建对话 | 当前输入区清空，URL 切到新的 `session` |
| 切换历史 | 聊天区恢复该会话消息与工具轨迹 |
| 删除会话 | 会话索引从 MySQL 删除 |
| `/reset` | 清空当前前端会话绑定，重新开始 |

数据边界：

- 会话元数据：MySQL `conversation_session`。
- 已读状态：MySQL `conversation_read_state`。
- 聊天原文：workspace JSONL 或 AgentStateStore fallback。
- Agent 运行期上下文：Redis AgentStateStore。

## 6. 人工确认测试

触发方式：

```text
查询 orders 表最近 10 条订单
```

预期流程：

1. Agent 生成 SQL。
2. `run_sql_preview` 进入 `awaiting_approval`。
3. 聊天气泡内出现人工确认卡。
4. 点击“批准并继续”。
5. 原工具调用状态从等待批准变成执行中，最后变成已完成。
6. SQL 结果表格出现在助手回复中。

如果确认状态失效，页面会显示“人工确认状态已失效”，可重置会话后重试。

## 7. 工具四件套验收

| 工具 | 当前状态 | 备注 |
|---|---|---|
| `list_data_sources` | 完整 | 返回已配置数据源 |
| `describe_table` | 完整 | 需要 `JdbcTemplate` |
| `run_sql_preview` | 完整 | 只允许 SELECT/WITH，带人工确认 |
| `render_chart` | 完整 | 校验内联 Vega-Lite，前端渲染 |

SQL 安全基线：

- 只允许 `SELECT` / `WITH`。
- 拦截 `insert/update/delete/drop/alter/create/truncate/merge/replace`。
- 使用 JDBC `setMaxRows` 控制返回行数。
- 生产环境仍建议使用只读数据库账号和更严格 SQL parser。

## 8. 管理与共享能力测试

### Agent 设置热生效

1. admin 登录。
2. 进入 `/configure/settings`。
3. 在系统提示词末尾加一句明显标记。
4. 保存。
5. 回到聊天页发送 `你好`。
6. 预期新回复体现新提示词，不需要重启。

### 贡献审批

1. 用户在个人 workspace 或贡献页面提交技能、子 Agent、图表模板。
2. 管理员进入 `/admin/approvals` 审批。
3. 审批通过后进入共享库。
4. 其他用户从技能/市场页面安装对应版本。

## 9. 用量、运行记录与模型连接测试

### 请求级用量

1. 完成一段会触发工具和模型调用的对话，例如“查询最近 7 天日销售额并画图”。
2. 打开 `/usage`。
3. 预期“总 Token”“缓存 Prompt Token”“已计成本”“平均耗时”均来自请求级记录，而不是只显示轮次。
4. 在“模型分布”确认模型、输入、缓存、输出、总 Token、耗时和成本均有值。
5. 未配置模型单价时成本为 `$0`，这是预期行为，不能把它解释为没有 token 用量。

### 运行记录

1. 打开 `/traces` 并刷新。
2. 展开刚完成的记录。
3. 预期能看到根请求下的 Agent、模型、工具 span，以及模型请求 token 和工具名称等属性。
4. 聊天消息中的“执行轨迹”仍应存在；它服务于阅读本次回答，运行记录服务于跨会话排障，两者不互相替代。
5. 点击聊天的停止后，运行记录状态应为 `CANCELLED`；异常结束应为 `ERROR`。

### 租户模型连接

1. 打开 `/models`，新增逻辑模型 `longcat`，提供商选择“OpenAI 兼容”。
2. 填写模型名、Base URL 和 API Key；可选填写每百万 token 的 micro USD 单价。
3. 保存后列表只显示“API key 已配置”，不能显示原始 key。
4. 新建或重新创建个人 Agent 后发起对话，确认它走该模型连接；删除配置后回退静态 `longcat/local`。
5. LongCat 不应强制 native JSON Schema；结构化输出继续走兼容 fallback。

## 10. 常见问题

### Docker 里看到文件，但 UI 里看不到

优先检查 UI 是否走同一个 Agent/user/session。当前工作区 API 复用框架 `SandboxManager`，理论上不会再出现两套容器状态槽。若仍复现，记录：

- 用户 ID
- Agent ID
- 会话 URL 中的 `session`
- Docker 容器 ID
- 文件完整路径
- 后端日志中的 sandbox acquire/start/persist/release 片段

### 容器执行完就没了，是不是文件丢了？

不一定。容器是执行环境，可回收。文件应该通过 workspace snapshot 和本地镜像查看。请到 `/workspace` 看 `artifacts/`、`plans/` 或本地镜像路径。

### 图表没有显示

检查三点：

1. `render_chart` 工具结果是否为 `ok`。
2. 工具输入里是否有 `vega_lite_spec` 且包含 `data.values`。
3. 浏览器控制台是否有 Vega-Lite 渲染错误。

### 可以不用 JPA 吗？

可以，但当前版本不建议边改 runtime 边全量迁移持久层。当前落地是 MySQL + JPA；若后续改 MyBatis，优先替换 `infrastructure` 层，保留 application/domain API 不变。

## 11. 当前验收清单

发布或演示前至少跑通：

- 登录 `admin/admin`
- 新建对话
- 四个工具依次调用
- `run_sql_preview` 人工确认批准和拒绝各一次
- 图表在聊天气泡内渲染
- `plans/PLAN.md` 链接能跳工作区
- `/workspace` 显示运行时路径、定义目录、本地镜像和产物统计
- 刷新浏览器后历史会话可恢复
- `/usage` 显示 token、缓存 token、成本、耗时和模型分布
- `/traces` 可查看 Agent、模型、工具 span
- `/models` 不回显 API Key，且更新后个人 Agent 可使用新的连接
- `mvn test` 通过
- `frontend` 下 `npm run build` 通过
