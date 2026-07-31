# AgentScope DataAgent 使用测试与 UI 交互教程

> 更新时间：2026-07-29
>
> 目标：不读数据库、不调用后端接口，优先通过真实前端页面体验并验收当前项目。
>
> 演示账号：先用 `admin / admin` 在“管理员用户”创建一个仅含 `user` 角色的测试账号，再用该账号执行私有 Agent 和贡献测试；`admin` 只用于团队模板与审核。
>
> 演示业务：电商订单与日销售分析，分析库统一使用 MySQL `dataagent_analytics`。

## 0. 先看当前结论

这不是“所有功能都默认成功”的说明书。每个测试都给出操作、输入、预期和失败判定；遇到失败时先对照本文，不要反复重试模型调用。

| 简历能力 | 当前 UI 可测程度 | 现在能在页面看到什么 |
|---|---:|---|
| 多 Agent 统一运行时与上下文治理 | 可完整体验 | `data-agent` / `insight-agent` 切换、独立会话、`data-explorer` / `report-writer` 委派、主子会话树 |
| 数据工具、HITL 与流式事件链 | 可完整体验 | 数据源探查、表结构、SQL 待批准、批准/拒绝、图表、`Stop`、聊天执行轨迹 |
| Agent 生命周期、热重建与多模型路由 | 可体验主流程 | 设置保存后新请求生效、Agent/模型下拉切换、租户模型连接配置 |
| 分布式状态与用户级 Docker 沙箱 | 可体验结果，底层证明有限 | 刷新恢复会话、工作区文件持久可见、同路径的用户隔离；仅靠 UI 不能证明 Redis key 和容器进程隔离 |
| 私有 Agent 与团队能力复用 | 可完整体验 | 偏好学习、Skill 多文件贡献、管理员审核编辑、v1/v2 安装和回滚 |
| 请求级可观测与成本核算 | 可完整体验 | `/traces` 可见 `SUCCESS/CANCELLED/ERROR` 和 span；`/usage` 与管理端用量页均返回 JSON 摘要、趋势和聚合 |

当前已有的测试数据不要随意删除：

- `Data Agent (data-agent)` 与 `Insight Agent (insight-agent)` 已注册并可切换。
- `data-agent` 已配置 `data-explorer`、`report-writer` 子 Agent。
- 团队市场中的 `sql-analysis` 已归档 v1、v2，可选择版本安装；团队 live 目录当前已回滚为 v1。
- `Insight Agent` 的个人工作区当前仍安装 `sql-analysis v2`，并已有 `bar 100%` 图表偏好样例。
- 管理员审批页当前 `PENDING` 为空；提交新贡献后才会出现待审批项。

## 1. 启动与登录

### 1.1 启动前必须满足

| 依赖 | 默认地址 | 用途 | 缺失时的常见现象 |
|---|---|---|---|
| MySQL | `localhost:3306` | 用户、Agent、会话、审批、偏好、用量，以及电商分析库 | 登录失败、Agent 列表为空或数据工具报错 |
| Redis | `localhost:6379`，database `2` | Agent 运行态、sandbox snapshot、execution guard | 重启后运行态不能恢复，或使用非 Redis fallback |
| Docker Desktop | 本机 Docker Engine | Agent 文件工具与隔离工作区 | Trace 中出现 `docker run failed` / `docker_engine permission denied` |
| 模型服务 | LongCat 或本地 Ollama | 推理与工具选择 | 聊天长时间无回复或模型连接报错 |

业务库为 `agentscope_dataagent`，分析库为 `dataagent_analytics`。

### 1.2 推荐启动命令

在项目根目录执行：

```powershell
mvn.cmd -q -DskipTests package
D:\jdk21\bin\java.exe -jar target\agentscope-dataagent-2.0.0-SNAPSHOT-exec.jar `
  --server.port=8080 `
  --spring.profiles.active=mysql,redis
```

看到应用启动完成后打开：

```text
http://localhost:8080/
```

首次使用 `admin / admin` 登录后，先在 `/admin/users` 创建一个仅含 `user` 角色的测试账号。日常私有 Agent、工作区和贡献测试使用该账号；`admin` 仅用于管理全局模板、通道、用户和审批。成功后应进入 `/chat`，顶部 Agent 下拉至少包含：

- `Data Agent (data-agent)`
- `Insight Agent (insight-agent)`

如果仍看到“无法访问此站点”，先确认 Java 进程是否仍在运行，不要进入后续测试。

## 2. 页面认路

页面会记住当前 Agent。为避免串 Agent，测试时优先使用下表中的完整路径。

| 页面 | 推荐路径 | 页面操作 |
|---|---|---|
| Data Agent 聊天 | `/chat?agent=data-agent` | 对话、工具执行、HITL、Stop、Agent/模型切换 |
| Insight Agent 聊天 | `/chat?agent=insight-agent` | 私有 Agent、独立上下文与偏好测试 |
| 工作区 | `/workspace?agent=data-agent` | 查看计划、报告、图表、本地镜像结果；从电脑上传文件或文件夹 |
| 技能 | `/configure/skills?agent=insight-agent` | 查看本地技能、打开 Marketplace、按版本安装 |
| 子 Agent | `/configure/subagents?agent=data-agent` | 查看或添加可委派的子 Agent；正常应立即显示内置 `data-explorer`、`report-writer` |
| 工具 | `/configure/tools?agent=data-agent` | 核对内置工具和 MCP 工具 |
| 设置与偏好 | `/configure/settings?agent=insight-agent` | 修改 Agent 配置、查看学习偏好 |
| 贡献 | `/contributions?agent=insight-agent` | 从私有工作区提交多文件 Skill 到全局团队 Agent；顶部“返回工作区”保留当前 Agent |
| 模型连接 | `/models` | 配置租户级模型凭据和单价 |
| 运行记录 | `/traces` | 查看请求、Agent、模型、工具 span |
| 个人用量 | `/usage` | 查看 Token、缓存 Token、成本和耗时 |
| 管理员会话 | `/admin/sessions` | 查看主/子 Agent 会话树和工作区演化 |
| 管理员审批 | `/admin/approvals` | 审核编辑、批准、拒绝和回滚贡献版本；进入“管理后台”后，左侧固定导航第 6 项为“审批”，概览页快捷操作也有“审批贡献” |
| 管理员用户 | `/admin/users` | 创建第二个测试用户，验证用户隔离 |

顶部 Agent 下拉切换 Agent 时会进入该 Agent 的新聊天 URL，不会沿用另一个 Agent 的 `session`。左侧会话列表也会随 Agent 切换。

管理后台是独立工作台：进入 `/admin/*` 后只显示一套管理侧栏，不应再看到聊天会话侧栏或第二份用户菜单。管理页顶部提供“返回上一级”（列表页返回概览，Agent 详情返回 Agents）与“返回工作台”两个动作，前者保留管理上下文，后者才回到聊天主页面；用户菜单只保留账号信息与退出登录。

### 2.2 管理后台的“通道”

通道是外部消息入口与 Agent 的路由配置，不是聊天会话，也不会显示每一条聊天消息。当前本地演示的 `chatui` 通道承接浏览器聊天；后续接入企业微信、飞书等入口时，每个入口也会以一个通道出现。

在 `/admin/channels` 可以查看通道状态、默认 Agent 和绑定规则。绑定规则用于按外部身份、群组或命令把入站消息路由到指定 Agent；只有接入对应外部通道后才会影响真实分流。`chatui` 没有绑定规则时仍会正常使用默认 Agent，因此“绑定数为 0”不是故障。

### 2.1 全局配置、个人偏好与贡献的边界

这三件事名字相近，但不是同一层数据：

| 页面能力 | `data-agent`（全局 Agent） | `insight-agent`（私有 Agent） |
|---|---|---|
| 设置 | 管理员可保存，影响所有用户后续请求；不会删除全局 Agent | owner 可保存，只影响该私有 Agent |
| 学习偏好 | 仍按“当前登录用户 + data-agent”存储，不会修改全局设置 | 按“当前登录用户 + insight-agent”存储 |
| 贡献源文件 | 读取当前用户在全局 Agent 上的隔离工作区投影 | 读取当前用户的私有 Agent 工作区 |
| 审批后的目标 | 仅可写入全局团队 Agent 的共享层 `shared/agents/<agentId>/` | 同左；私有 Agent 不能作为贡献目标 |

因此，`设置`中的“作用域 global”不代表“学习偏好对所有人共享”，也不代表贡献直接改写全局文件。贡献必须经过另一位管理员审批才进入共享层；提交人不能批准、拒绝或回滚自己的提交。

## 3. 推荐测试顺序

第一次使用不要同时测试所有分支。按下面顺序最容易判断问题出现在哪一层。

1. 登录、切换两个 Agent，确认基础路由正常。
2. 用四条短 Prompt 分别触发数据源、表结构、SQL、图表工具。
3. 跑一遍“黄金主链”，体验子 Agent、HITL、图表、报告和会话树。
4. 单独测试拒绝和 Stop，不要在黄金主链中途故意中断。
5. 测试 Insight Agent 偏好学习和 Agent 间隔离。
6. 测试 Skill 贡献、审批、安装和回滚。
7. 最后测试设置热生效、模型切换、状态恢复、用户隔离和 Trace。

建议每项单独点击左侧“＋ 新建对话”。这样出错后不会污染下一项，也方便在 `/traces` 和 `/admin/sessions` 对照一次请求。

## 4. 十分钟基础冒烟测试

进入 `/chat?agent=data-agent`，每一步等待上一条完全结束再继续。

### 4.1 数据源发现

输入：

```text
请列出所有可用的数据源，只需要调用数据源发现工具并说明用途。
```

预期：

- 回复上方出现“执行轨迹”。
- 展开后能看到 `list_data_sources`。
- 结果包含分析数据源，通常显示为 `analytics_db`。

失败判定：没有任何工具调用，只凭文字编造数据源；或者工具状态为“失败”。

### 4.2 表结构与样例

输入：

```text
请查看 analytics_db 中 orders 表的字段、主键和 3 行样例数据，不要执行聚合查询。
```

预期：执行轨迹出现 `describe_table`，回复包含订单时间、金额、状态等真实字段。

### 4.3 SQL 与 HITL 批准

输入：

```text
请统计 2024-12-09 至 2024-12-24 每天已完成订单的销售额。
必须执行只读 SQL，执行前等待我批准，并在结果中说明表名、时间条件和返回行数。
```

预期操作：

1. 页面出现“需要人工确认”。
2. 展开工具输入，先阅读 SQL，确认是 `SELECT` 或 `WITH`。
3. 确认时间范围与 `completed` 条件正确。
4. 确认卡出现后“拒绝”和“批准并继续”应立即可点击，不应等待上一回合的沙箱收尾；点击“批准并继续”。
5. 页面出现系统提示“已批准，继续执行。”。
6. `run_sql_preview` 从“等待批准”变为执行中，最终变为已完成。

说明：Agent 为了生成正确 SQL，可能会在确认前执行 `describe_table`、`list_data_sources` 等只读元数据工具。这些结果不是待批准 SQL 的结果；真正受 HITL 控制的是 `run_sql_preview`。

失败判定：没有确认卡就执行 SQL；SQL 含 `UPDATE/DELETE/DROP/ALTER`；批准后无限等待；结果未标注数据来源。

### 4.4 图表

在同一会话继续输入：

```text
把刚才的每日销售额渲染为折线图。横轴是日期，纵轴是销售额，必须使用内联 data.values。
```

预期：执行轨迹出现 `render_chart`，聊天气泡内直接渲染 Vega-Lite 图表，而不是只返回 JSON。

完成这四步，说明模型、MySQL 数据工具、HITL 回传、SSE 流和前端图表的最短链路可用。

## 5. 黄金主链：一次走完多 Agent 分析

这一条最适合项目演示。完整执行可能需要 2 至 6 分钟，不要在模型仍运行时重复点击发送。

### 5.1 先核对子 Agent

1. 打开 `/configure/subagents?agent=data-agent`。
2. 确认列表中立即显示 `data-explorer` 和 `report-writer`。
3. 若 12 秒后显示“加载子 Agent 超时”，不要继续测黄金主链：重新执行打包与重启，再检查 Docker 和后端日志。
4. 点击“← 返回聊天”，再点击左侧“＋ 新建对话”。

### 5.2 复制黄金 Prompt

```text
请分析 2024-12-09 至 2024-12-24 的每日销售趋势。

执行要求：
1. 先调用 agent_spawn(agent_id=data-explorer, timeout_seconds=120)，同步等待它确认唯一推荐数据源、表结构、时间字段和样例 SQL；data-explorer 只能使用 list_data_sources 与 describe_table，禁止扫描项目文件；
2. 主 Agent 复核后直接调用 run_sql_preview，不要先用文字问我是否执行；由工具权限自动弹出确认卡，SQL 必须等待我在卡片中批准；
3. 批准后生成每日销售额折线图；
4. 最后调用 agent_spawn(agent_id=report-writer, timeout_seconds=120)，同步等待它基于已核验的数字、SQL 条件和图表生成管理层摘要；
5. 将最终 Markdown 保存为 reports/ui-golden-flow.md，只有 write_file 成功后才能告诉我已保存。

不要使用 timeout_seconds=0、wait_async_results 或 task_output 轮询子 Agent。

最终回复必须列出数据源、查询条件、返回行数、子 Agent 分工和报告路径。
```

### 5.3 页面上应该依次看到

1. 执行轨迹出现 `agent_spawn`，目标为 `data-explorer`。
2. `data-explorer` 只出现 `list_data_sources` 与 `describe_table`，不应出现 `glob_files`、`list_files`、`read_file`、`execute` 等仓库扫描工具；结果同步回到主 Agent后，主 Agent 再生成 SQL。
3. 主 Agent 必须先出现真实的 `run_sql_preview` 工具调用，然后页面暂停在“需要人工确认”，输入框提示“请先处理当前的人工确认”。只出现模型文字“请确认是否执行”但没有确认卡，直接判定失败。
4. 点击“批准并继续”后，SQL 返回表格。
5. `render_chart` 完成，聊天内显示折线图。
6. 执行轨迹再次出现 `agent_spawn`，目标为 `report-writer`。
7. 出现 `write_file` 成功结果，最终回复给出 `reports/ui-golden-flow.md`。

实际模型可能把多个工具折叠显示。点击“执行轨迹”展开后再判断，不要只看最终文字。

性能判定：Docker 冷启动可以有少量准备时间，但后端日志中不应在模型开始前连续出现两个约 30 秒的 `docker stop` 等待。若容器事件表现为创建、执行、快速 `destroy`，这是临时沙箱的正常生命周期，不是“反复重建故障”。

### 5.4 运行中切换页面回归

在第 5.2 的请求已经出现工具轨迹、但尚未出现最终回复时：

1. 点击顶部“工作区”，也可依次打开“工具”或“子 Agent”。
2. 停留至少 10 秒后，点击“返回聊天”。不要点击 `Stop`。
3. 应回到同一条带 `session` 参数的会话；任务仍在继续，完成后可恢复最终消息与工具结果。
4. 若任务在离开聊天期间停在 SQL 人工确认，返回后必须重新显示“需要人工确认”卡片，批准和拒绝都可继续当前会话。
5. 只有明确点击 `Stop` 或服务重启，运行记录才应显示 `CANCELLED` 或确认失效。

失败判定：仅因页面导航就出现 `CANCELLED`；返回聊天后一直“正在思考”但后台已完成；或有待确认 SQL 却没有确认卡。

### 5.5 验证报告确实落盘

1. 点击聊天顶部“工作区”，或打开 `/workspace?agent=data-agent`。
2. 点击“↻ 刷新”。
3. 展开 `reports`。
4. 点击 `reports/ui-golden-flow.md`。
5. 右侧编辑器应显示报告，并包含本次日期范围、数字、来源与结论。

失败判定：聊天声称“已保存”，但执行轨迹没有成功的 `write_file`；或工作区刷新后没有该文件。

### 5.6 验证工作区编辑、删除与 Docker 同步

1. 先直接打开工作区并等待文件树加载，再点击“↻ 刷新”。之前已经由 Agent 写入的 `reports`、`artifacts` 等目录应直接可见；不能必须先上传一个文件才出现，也不能在刷新时短暂变成空目录。
2. 点击“上传文件”，从电脑选择一个或多个本地文件；页面会打开“上传到工作区”对话框，在其中填写目标目录，例如 `knowledge`。上传完成后应显示“已上传 N 个文件”，而不是长期停在“处理中”。
3. 点击“上传文件夹”，从电脑选择一个含子目录的文件夹；浏览器应打开普通的系统文件夹选择器，而不是出现“localhost 请求访问文件”的授权弹窗。同样填写目标目录，例如 `knowledge`。文件夹中的文件以一次批量请求上传，刷新后应看到 `knowledge/原文件夹名/子目录/文件`，而不是把所有文件平铺到同一级。
4. 默认单文件上限为 `100 MB`、单次总上传上限为 `500 MB`。Markdown、PDF、Word、Excel、图片等文件均可上传；PDF/Office 等二进制文件只是不在浏览器编辑器中预览，上传成功后仍应出现在文件树中。超过上限时，页面应立即结束“处理中”状态并提示“上传文件超过限制”，而不是只在后端日志中出现 `MaxUploadSizeExceededException`。可通过 `DATAAGENT_WORKSPACE_MAX_FILE_SIZE` 和 `DATAAGENT_WORKSPACE_MAX_REQUEST_SIZE` 调整。
5. 若部分文件未上传，页面应同时给出成功数量、失败数量以及最多三个失败路径和原因；超过 60 秒仍未收到后端结果时，页面会结束等待并提示刷新后重试。
6. 浏览器不会把空文件夹作为上传结果返回；需要空目录时，点击“+ 文件夹”创建。点击文件夹会选中并展开它，此时“+ 文件”“+ 文件夹”和上传默认落在该文件夹；“重命名”“删除”也可直接作用于该文件夹。
7. 选中 `reports/ui-golden-flow.md`，在末尾增加一行 `workspace-sync-test`，点击“保存”。
8. 点击刷新并重新打开文件，修改应仍然存在。
9. 点击“+ 文件”，创建 `reports/workspace-delete-test.md`，写入任意内容并保存。
10. 点击左侧文件树中的“删除”，在页面内确认；刷新后该文件不得再次出现。
11. 可用 `docker exec <当前容器> ls -la /workspace/reports` 辅助核对，但这不是必需操作。页面保存、删除、重命名和上传本身就应同步运行时工作区与快照。
12. 容器被自动回收后重新打开工作区：已保存的修改仍存在，被删除的文件仍保持删除，不能因本地镜像残留而“复活”。

运行中打开工作区只允许读取本地镜像，不应抢占正在执行的 Redis 沙箱锁。若聊天后台仍在执行，先等待它完成，再做保存、删除等写操作。

### 5.7 查看主/子 Agent 会话树

1. 打开 `/admin/sessions`。
2. 在“按 agent ID 筛选”中输入 `data-agent`。
3. 找到刚才的 MAIN 会话，点击“树”。
4. 在“子 Agent 树”页签查看 Fan-out。
5. 应看到主会话下至少包含 `data-explorer` 和 `report-writer` 子节点。
6. 切到“工作区演化”，应能看到报告文件的路径、工具和变更类型。

左侧聊天历史只显示主会话。子 Agent 会话只在本页的会话树中出现，不占用“保留 N 条”的数量。

如果聊天成功但会话树没有子节点，说明只是主 Agent 自己完成了任务，不能算多 Agent 委派验收通过。

## 6. HITL 的批准、拒绝与失效分支

### 6.1 拒绝测试

新建对话并输入：

```text
请查询 orders 表中 2024-12-01 至 2024-12-31 的订单状态分布，执行 SQL 前等待我确认。
```

出现确认卡后点击“拒绝”。

预期：

- 页面出现“已拒绝该操作。”。
- 工具状态显示“已拒绝”。
- 不应出现真实 SQL 结果。
- Agent 应说明本次操作未执行，不能伪造统计数字。

允许保留确认前已完成的 `describe_table` 等元数据结果。例如页面可能已显示 `orders` 的字段和样例行，但被拒绝的 `run_sql_preview` 不得返回统计表、查询行或聚合数字。

### 6.2 确认状态失效

当确认卡出现后，若后端重启、运行态过期或 session 不匹配，页面可能显示“人工确认状态已失效”。这时只使用卡片上的：

- “重置会话”：清空当前绑定后重新开始。
- “重试上一条”：重建会话并重新发送上一条用户请求。

不要继续在失效会话中发送新问题，因为输入框会被确认状态锁定。

## 7. SSE 流式输出与 Stop

新建对话，输入一条会持续多步执行的任务：

```text
请依次检查 products、users、orders、daily_sales 四张表，比较候选指标口径，然后给出完整数据字典和分析计划。
```

看到输入区右侧按钮从“发送”变成 `Stop` 后点击它。

预期：

- 当前流式输出停止，按钮恢复为“发送”。
- 正在运行的工具在聊天轨迹中不再继续更新，可能标记为失败或中止。
- 打开 `/traces` 并刷新，最新请求应显示 `CANCELLED`。

失败判定：点击 `Stop` 后仍持续追加内容；或 Trace 最终显示 `SUCCESS` 且任务完整执行完毕。

注意：点击浏览器刷新不是 Stop 测试。真正的 Stop 会调用取消接口并给后端 execution guard 发送取消信号。

## 8. Agent 切换与上下文隔离

### 8.1 页面切换体验

1. 打开 `/chat?agent=data-agent`，点击“＋ 新建对话”。
2. 发送：`请记住本会话代号是 DATA-ONLY，先不要做其他事。`
3. 使用顶部 Agent 下拉切换为 `Insight Agent (insight-agent)`。
4. 确认 URL 变成 `/chat?agent=insight-agent`，输入框变成“向 insight-agent 发送消息...”。
5. 发送：`我刚才在另一个 Agent 的会话代号是什么？如果当前上下文没有，请明确说不知道。`

预期：Insight Agent 不应从当前会话直接读出 `DATA-ONLY`。切回 Data Agent 后，左侧选择原会话，才应恢复该上下文。

这项验证的是 Agent + session 维度的上下文边界。它不等于数据库级跨用户隔离，跨用户测试见第 13 节。

### 8.2 会话刷新恢复

1. 在任一 Agent 会话中完成一轮对话，记录 URL 的 `session`。
2. 按 `F5` 刷新。
3. 页面应显示“正在加载对话记录...”，随后恢复消息和工具轨迹。
4. 再追问：`继续刚才的话题，先复述已确认的时间范围。`

预期：历史消息、Agent 选择和 session 不变。仅刷新浏览器只能验证恢复链路，不能单独证明数据来自 Redis。

## 9. 私有 Agent 偏好学习

偏好按 `用户 + Agent` 隔离。只有成功 SQL 和实际图表工具调用会形成有效偏好；用户在聊天里说“我喜欢柱状图”、模型只把 SQL 写在回复中、拒绝或取消 HITL 确认，都不会增加统计。当前版本会从成功 SQL 自动归纳常用 SQL、常查数据表、查询习惯（筛选、聚合、排序、多表关联）和图表偏好，不是只固定两种。

### 9.1 查看现有偏好

打开 `/configure/settings?agent=insight-agent`，向下滚动到“个人学习偏好”。这是唯一的查看入口；“常用 SQL”会显示在该卡片的图表偏好上方。卡片默认只展示使用次数最高的 5 条，避免大量历史记录撑长设置页；展开任一条可查看完整 SQL，点击“查看全部”后按页加载其余模式。当前测试数据应能看到：

```text
图表偏好
bar 100%
```

这个预置样例只证明已有一次 `bar` 图表调用，不代表一定已有 SQL 记录。只有此前在同一账号的 `insight-agent` 上完成过成功 SQL 执行时，才会看到“常用 SQL”、常查数据表和查询习惯。旧版本保存的 SQL 最多只有 500 字符；新包开始保存正常分析 SQL 的完整文本，旧历史无法自动补全。

### 9.2 记录常用 SQL

进入 `/chat?agent=insight-agent`，新建对话，连续两次执行同一条 SQL：

```text
请原样执行下面的只读 SQL，不要改写；直接调用 run_sql_preview，执行前由工具权限弹出确认卡：
SELECT status, COUNT(*) AS order_count
FROM orders
WHERE created_at >= '2024-12-01 00:00:00'
  AND created_at < '2025-01-01 00:00:00'
GROUP BY status
ORDER BY order_count DESC;
```

每次都检查 SQL 后点击“批准并继续”，并确认执行轨迹出现 `run_sql_preview` 的成功结果；模型只展示 SQL 文本不算执行。第二次结束后重新打开设置页。

预期：第一次成功执行后显示 `1 次`，第二次成功执行后显示 `2 次`。如果模型改写了 SQL，系统会把它们当成两个不同模式，因此测试 Prompt 明确要求“原样执行”。批准 SQL 后的恢复流只会发送工具结果，因此必须运行包含“批准工具参数恢复”修复的最新服务包；重启到新包后，重新完成两次测试，再刷新或重新打开设置页。若页面仍为空，检查 Agent 是否仍为 `insight-agent`、两次 HITL 是否都批准，以及执行轨迹是否有 `run_sql_preview` 的成功结果。

### 9.3 更新图表偏好

在同一 Agent 中输入：

```text
把刚才的订单状态分布渲染为 bar 柱状图，不要只输出 Markdown 表格。
```

图表成功显示后返回设置页。预期 `bar` 的计数占比更新；如果又执行了其他图表类型，百分比会按实际调用次数重新计算。

### 9.4 验证 Agent 隔离与清空

切到 `/configure/settings?agent=data-agent`。它不应无条件显示 Insight Agent 完全相同的 SQL 次数和图表比例。

“清空”会删除当前用户在当前 Agent 下的全部 SQL、数据表、查询习惯和图表偏好，并使运行中的 Agent 配置失效后重建。该操作不可从 UI 撤销；保留当前演示数据时不要点击。

## 10. Skill 贡献、审核、安装与回滚

这一节会写入新的贡献记录和版本。建议先完成前面的只读测试。

### 10.1 从当前用户工作区提交多文件 Skill

1. 打开 `/contributions?agent=insight-agent`。页面为浅色高对比度界面；完成后可点击顶部“← 返回工作区”回到当前 Agent 的工作区。
2. “源 agent ID”保持 `insight-agent`。
3. 在“目标团队智能体 ID”下拉框选择 `Data Agent (data-agent)`，表示审批后进入团队共享库；私有 Agent 不会出现在该列表中。
4. 在文件树展开 `skills`，直接勾选 `sql-analysis` 文件夹前的复选框。目录会显示其包含的文件数，勾选后会递归选择整个文件夹。
5. 不需要逐个勾选 `SKILL.md`、`templates/query.sql` 或脚本文件；提交时系统会保留原有子目录结构。
   每个 Skill 文件夹的根目录都必须有 `SKILL.md`。页面会标出缺少该文件的目录，并阻止提交；请先补齐技能说明文件。
6. 确认“类型”自动为 `skill`，“目标路径”为 `sql-analysis`。
7. 在说明中填写：`UI 手工验收：多文件 Skill 贡献与版本归档`。
8. 点击“提交”。

预期：“我的提交”新增一条 `PENDING / skill / data-agent / sql-analysis`；管理员在审核页应同时看到 `SKILL.md`、`templates/query.sql` 及该文件夹中的其他文本资源。

### 10.2 管理员审核编辑并批准

1. 点击左下角 `admin`，选择“管理后台”。概览页左侧固定导航中点击“审批”，或在页面底部“Quick actions”点击“审批贡献”；也可直接打开 `/admin/approvals`。如果管理后台没有左侧固定导航，说明仍在使用旧前端构建，需要重新构建并重启 8080 服务。
2. 保持 `PENDING`。若显示 `No contributions with status PENDING.`，这表示当前没有待审核提交，不是页面故障；先完成上一节的“提交”。
3. 找到刚提交的记录，点击“查看内容”。
4. 分别点击 `SKILL.md` 与 `templates/query.sql`，确认两个文件都能预览。
5. 可点击“编辑”，在内容末尾加一行可识别的测试备注。
6. 点击“完成”；若不想保留修改，点击“还原”。
7. 填写审核备注，例如：`UI 验收通过：主文件与模板资源完整`。
8. 点击“批准”。

预期：记录从 `PENDING` 移到 `APPROVED`，并显示新的版本号。批准后的文件写入 `shared/agents/data-agent/` 团队层，不会直接覆盖所有人的私有工作区。

### 10.3 从 Marketplace 按版本安装

1. 打开 `/configure/skills?agent=insight-agent`。
2. 点击 `+ Install`。
3. 在“浏览 Marketplace”中展开 `team-shared`。
4. 找到 `sql-analysis`，点击“预览”检查 `SKILL.md` 和 Resources。
5. 使用“sql-analysis 版本”下拉选择目标版本，例如 `v2` 或刚生成的版本。
6. 点击“安装”。
7. 若提示同名冲突，确认当前是否允许覆盖；要验证升级时选择覆盖。
8. 关闭 Marketplace，在 Installed Skills 中重新选择 `sql-analysis`。

预期：主文件可阅读，资源数至少为 `1 resources`；安装其他版本后内容随版本变化。只看到 `SKILL.md`、看不到模板资源，说明多文件安装不完整。

### 10.4 回滚团队共享版本

1. 打开 `/admin/approvals`，切到 `APPROVED`。
2. 找到要恢复的旧版本，例如 `sql-analysis v1`。
3. 点击“查看内容”，核对归档文件。
4. 点击“回滚至 v1”。
5. 关闭后重新打开 Marketplace 并点击“预览”，通过正文确认团队共享实时内容已恢复到旧版本。

回滚团队共享层不会自动降级已经安装到个人 Agent 工作区中的 v2。要验证个人 Agent 降级，仍需在 Marketplace 版本下拉选择 v1 并重新安装。这正是“共享版本”与“个人已安装副本”的边界。

当前 Marketplace 列表中的 `v2` 标签表示“最新归档版本”，团队 live 目录回滚到 v1 后该标签仍可能显示 v2。判断回滚是否成功应看“预览”的 live 正文，不能只看列表标签。

## 11. Agent 生命周期热生效

### 11.1 系统提示词热重建

1. 打开 `/configure/settings?agent=insight-agent`。
2. 先保存原系统提示词到临时笔记，便于测试后恢复。
3. 在系统提示词末尾增加：`测试期间，每次回答末尾追加 [HOT-REBUILD-TEST]。`
4. 点击“保存更改”。
5. 点击“← 返回聊天”并新建对话。
6. 输入：`只用一句话说明你的职责。`

预期：不重启 Spring Boot，新请求的回答末尾出现 `[HOT-REBUILD-TEST]`。这说明保存动作使旧 UCA/runtime 失效，新请求按更新后配置重建。

测试后恢复原系统提示词并再次保存，避免污染后续演示。

### 11.2 主 Agent 与子 Agent 配置变化

在 `/configure/subagents?agent=data-agent` 可以：

- 点击 `+ 从 agent 添加`，把另一个已注册 Agent 转为当前 Agent 的子 Agent 定义。
- 点击 `+ 新建`，填写名称、描述、模型、最大迭代次数、工具和工作区模式。

“描述”决定编排器何时委派，因此测试时必须写清触发条件。仅创建定义但没有在聊天执行轨迹出现 `agent_spawn`，不能算委派成功。

#### 可直接复测的配置变化实例：`test-echo`

1. 打开 `/configure/subagents?agent=insight-agent`，点击“+ 新建”。
2. 按下列值保存：

| 字段 | 第一次配置 |
|---|---|
| 名称 | `test-echo` |
| 描述 | `仅当用户明确说“请调用 test-echo”时委派；把用户给出的 marker 和 timestamp 原样写入指定文件，写后必须 read_file 校验。` |
| 模型 | `默认` |
| 最大迭代次数 | `6` |
| 工具 | `write_file, read_file` |
| 工作区模式 | `SHARED` |

3. 返回聊天，新建会话并发送：

```text
请调用 test-echo 子代理，把下面内容原样写入 reports/test-echo-marker.md，写完后 read_file 并返回实际读取内容：
marker=SUBAGENT-CONFIG-V1
timestamp=2026-07-30
```

4. 展开执行轨迹，必须同时看到 `agent_spawn`、子 Agent 的 `write_file` 和 `read_file`；然后打开当前 Agent 的“工作区”，刷新后应出现 `reports/test-echo-marker.md`，文件内容必须与读取结果一致。
5. 回到子 Agent 配置，把描述末尾改为 `返回内容第一行必须加 prefix=V2`，最大迭代次数改为 `8` 并保存。
6. 新建聊天会话，发送同样指令但将 marker 改成 `SUBAGENT-CONFIG-V2`。

预期：不重启服务即可在新会话的子 Agent 返回中看到 `prefix=V2`，文件 marker 更新为 V2；管理端 `/admin/sessions` 的主/子 Agent 树出现 `test-echo` 子节点，“工作区演化”出现 `reports/test-echo-marker.md` 的 `EDIT` 记录。若只有自然语言声称写入成功，而文件树、`read_file` 或演化记录任一处没有该文件，均判定为失败。

为验证主 Agent 配置也会热更新，可再到 `/configure/settings?agent=insight-agent` 将系统提示词临时追加 `委派 test-echo 后，最终回答末尾追加 [MAIN-V2]`。新建会话复测应同时看到子 Agent 的 `prefix=V2` 和主 Agent 的 `[MAIN-V2]`；测试结束后恢复原提示词。

## 12. 多模型路由与成本配置

### 12.1 聊天顶部切模型

进入 `/chat?agent=insight-agent`，顶部“模型”下拉通常包含“默认”、本地 Ollama 和 LongCat。

1. 选择可用的目标模型。
2. 等待下拉恢复可操作状态。
3. 新建对话并发送：`只回复当前模型路由测试成功。`
4. 打开 `/traces`，找到该请求并展开。
5. 核对记录标题或模型 span 中的 model ID。

如果本地 Ollama 未启动，不要把切换失败解释为路由代码失败；先确认下拉选项是否可用及本地模型是否已拉取。

### 12.2 租户模型连接

打开 `/models`：

1. 点击“新建连接”。
2. 填写逻辑模型 ID，例如 `longcat-test`。
3. 提供商选择“OpenAI 兼容”或 `Ollama`。
4. 填写模型名、Base URL；OpenAI 兼容连接还需 API Key。
5. 如需验证成本，填写输入、缓存输入、输出单价。
6. 保持“启用此连接”，点击“保存”。

单价单位是 `micro USD / 1M tokens`，填 `0` 表示不计成本。保存后页面只能显示“API key 已配置”，不能回显原始密钥。

然后回到 Agent 设置或聊天顶部选择该逻辑模型，新建请求，再到 `/traces` 核对实际模型。不要在截图、简历或提交记录中暴露 API Key。

## 13. Docker 工作区与用户隔离

### 13.1 通过 UI 验证文件执行结果

在 Data Agent 新建对话并输入：

```text
请调用 write_file，把下面内容写入 reports/ui-sandbox-check.md：
owner=admin
agent=data-agent
marker=ADMIN-SANDBOX
写完后再调用 read_file 读取并原样返回，不能只口头说明成功。
```

预期：执行轨迹先有成功的 `write_file`，再有 `read_file`；随后到工作区刷新，能打开 `reports/ui-sandbox-check.md`。

工作区页可能显示三种路径：

| 路径 | 含义 |
|---|---|
| 运行时工作区 `/workspace` | Agent 在容器中的统一视角 |
| Agent 定义目录 | Agent 配置、Skill、子 Agent 的宿主机定义位置 |
| 本地镜像 | 按用户和 Agent 保存的 sandbox 文件镜像，供 UI 只读查看 |

容器执行后被回收不等于文件丢失；以工作区 snapshot 和本地镜像能否恢复为准。

### 13.2 创建第二用户验证同路径隔离

1. 以 admin 打开 `/admin/users`。
2. 切到“添加用户”，创建 `ui_tester`，密码至少 6 位，不勾选管理员角色。
3. 用浏览器无痕窗口登录 `ui_tester`。
4. 在相同 Agent 中写入相同路径 `reports/ui-sandbox-check.md`，但内容改为 `marker=TESTER-SANDBOX`。
5. 在两个浏览器各自打开工作区并刷新。

预期：admin 仍看到 `ADMIN-SANDBOX`，`ui_tester` 看到 `TESTER-SANDBOX`。若任一用户看到另一个用户的内容，用户级 sandbox 隔离失败。

UI 能验证“同路径内容互不覆盖”和“重进页面仍可见”，但不能单独证明 Docker namespace、Redis key 组成和 snapshot CAS 细节；这些需要结合代码、日志或 Redis/Docker 命令解释。

### 13.3 Docker 权限故障识别

若 `/traces` 中出现：

```text
docker run failed
permission denied while trying to connect to ... docker_engine
```

说明请求没有真正进入可用沙箱。先确认 Docker Desktop 正在运行、当前 Windows 用户能访问 named pipe，再新建会话重试。不要把这类 ERROR 记录当作成功的隔离演示。

### 13.4 容器反复重建或首字延迟很高

沙箱容器是临时执行环境：租约结束前先保存 workspace snapshot，随后容器会被删除；下一次租约创建新容器是正常设计，不能仅凭容器 ID 变化判定故障。

本项目适配器已绕过框架默认的 `docker stop --time=30`：快照保存后先保留短暂的会话镜像宽限期，再异步强制移除临时容器。宽限期内持久化状态会保留仍然有效的容器 ID，供紧随其后的工作区同步和会话树镜像复用；容器真正回收后，下一次租约会自动丢弃这个过期 ID。这个宽限期让框架在回合结束后完成会话树写入，不会阻塞 SSE 完成或首字输出。正确表现是：

1. 日志可以出现容器创建、短暂存活与回收，但单次回收不再固定等待 30 秒；回合完成后几秒内的清理属于正常生命周期。
2. 宽限期内的工作区同步可以复用同一个仍在运行的容器，不应出现 `container name ... is already in use`；容器删除后的新租约不应反复尝试上一个已删除的 ID，也不应连续输出 `Container ... not found`。
3. 首个 Agent/模型事件前不应因两次沙箱释放额外等待约 60 秒；剩余耗时再按模型首 Token、数据库和子 Agent 分开判断。
4. 页面空闲、切换到工作区或查询聊天状态时不应每 2 秒创建新容器；只有真实工具、工作区同步或新一轮 Agent 请求才可能取得沙箱租约。
5. 如果新容器在任务仍运行时自行退出、`session-tree-mirror` 出现 `No active sandbox`、`docker rm --force` 超时，或 Docker Engine 不可访问，才按沙箱故障处理：检查 Docker Desktop/WSL 后新建会话复测。正常情况下，页面导航、空闲的聊天状态轮询和回到聊天页都不会创建容器。

可用 `docker events` 对照时间线。正常清理通常直接出现 `destroy`；旧版本会先发 `SIGTERM`，等待 30 秒后再出现 `SIGKILL / exitCode=137 / destroy`。

## 14. Trace、Token 与成本

### 14.1 聊天内执行轨迹

每条助手消息上方的“执行轨迹”服务于阅读单次回答。展开后重点看：

- 工具名称与调用 ID。
- 输入参数，尤其 SQL 和文件路径。
- `运行中 / 等待批准 / 已完成 / 已拒绝 / 失败` 状态。
- 工具返回结果是否与最终文字一致。

### 14.2 跨会话 Trace

1. 完成一次成功请求、一次 Stop 请求和一次有意失败请求。
2. 打开 `/traces`，点击“刷新”。
3. 展开对应记录。
4. 成功请求应为 `SUCCESS`，Stop 请求应为 `CANCELLED`，异常请求应为 `ERROR`。
5. 核对根请求下的 Agent、模型与工具 span，以及耗时和模型名称。

当前页面已有历史 `SUCCESS/CANCELLED/ERROR` 样例，可先展开熟悉结构，不必为了制造 ERROR 反复破坏配置。

### 14.3 `/usage` 管理端用量接口

旧构建打开 `/usage` 曾看到类似：

```text
Unexpected token '<', "<!doctype "... is not valid JSON
```

根因是用量汇总的 JPA 聚合结果存在一层嵌套数组，旧代码把该数组直接强转为数值后抛出异常，浏览器最终把回退页面当成 JSON。当前版本已展开该聚合行；重启最新构建后应验证：

- `/api/usage/me/summary` 与 `/api/admin/usage/summary` 的 `Content-Type` 为 JSON。
- 摘要卡、模型聚合、小时/日趋势和 Top 用户/智能体均能加载；无数据时显示空态而不是 JSON 解析错误。
- 管理端用量页的标题、指标卡、趋势和空态均为中文；模型 ID 是配置值，保留原样显示。
- 页面显示 `$0` 成本通常表示未配置单价，Token 仍应可以有记录。

修复后再按以下标准复测：

1. 摘要卡显示总请求、Token、缓存 Prompt Token、成本和平均耗时。
2. “模型分布”显示模型、输入、缓存、输出、总 Token、耗时和成本。
3. 未配置单价时成本为 `$0`，但 Token 仍应大于 0。
4. 配置非零单价并完成新请求后，成本按请求新增。

## 15. 常见问题与快速判断

| 页面现象 | 先检查什么 | 正确处理 |
|---|---|---|
| “最近 7 天”无数据 | 演示数据都在 2024 年 | 使用本文给出的明确日期，不用相对时间 |
| SQL 一直没有执行 | 是否停在 HITL 卡片 | 展开 SQL，点击“批准并继续”或明确拒绝 |
| 模型只回复“请确认是否执行”，没有确认卡 | 执行轨迹里是否真的调用 `run_sql_preview` | 判定失败；新建会话并确认运行的是最新包，工具调用会由权限中间件自动弹卡，不能用文字确认代替 |
| 输入框不可编辑 | 是否有待处理确认 | 先处理确认；失效时重置会话 |
| 拒绝后仍看到表结构或样例行 | 是否为 `describe_table` 等确认前工具 | 允许；核对被拒绝的 `run_sql_preview` 没有结果 |
| 反复出现“Container not found, creating a new one” | 是否仍运行旧包，或 Redis 中仍是旧容器状态 | 重新打包并重启；新版会在恢复时丢弃已不存在的旧容器 ID |
| 首字前固定等待约 30/60 秒 | Docker 事件是否有 `SIGTERM` 后隔 30 秒才 `SIGKILL` | 说明仍运行旧版 `docker stop --time=30` 路径，重新打包并重启 |
| 批准后一直思考，约 180 秒后出现 `sleep interrupted` | 上一轮是否在 `done` 后仍持有沙箱锁 | 说明仍运行旧版终态时机；新版只在持久化、释放沙箱和镜像同步完成后发送 `done` |
| 黄金链路卡在 `wait_async_results` | `agent_spawn` 是否用了 `timeout_seconds=0` | 新建会话，使用本文同步 `timeout_seconds=120` Prompt，不做异步轮询 |
| data-explorer 扫描整个仓库 | 是否出现 `glob_files/list_files/read_file/execute` | 判定失败；新版只允许 `list_data_sources/describe_table` |
| 设置保留 4 条仍看到很多记录 | 是否把子 Agent 会话误当聊天历史 | 左侧只应显示 4 条主会话；到管理后台查看子 Agent 会话树 |
| 聊天说文件已保存，但工作区没有 | 是否真的出现成功 `write_file` | 以工具结果和工作区刷新为准 |
| 删除文件后刷新又出现 | 本地镜像是否仍残留旧文件 | 说明仍是增量 `docker cp`；新版会先生成 staging 快照，再原位同步到镜像目录，删除会同步 |
| 图表没有显示 | `render_chart` 是否成功、是否有 `data.values` | 检查工具输入和浏览器渲染错误 |
| 子 Agent 列表显示加载中 | 页面请求尚未完成或 Agent 参数错误 | 等待后刷新，确认 URL 是 `agent=data-agent` |
| 审批页 PENDING 为空 | 尚未提交新贡献 | 先在 `/contributions` 提交 |
| Skill 安装后没有资源文件 | 提交时只选了 `SKILL.md` | 提交并安装完整 bundle，包括 `templates/query.sql` |
| 切换 Agent 后历史不见了 | 左侧会话按 Agent 隔离 | 切回原 Agent，再选择原会话 |
| Stop 后 Trace 仍是 SUCCESS | 点击时任务已经结束 | 新建较长任务，在仍显示 `Stop` 时取消 |
| `/usage` JSON 解析失败 | 是否仍运行旧构建，或接口返回了 HTML | 重新打包并重启；确认摘要接口 Content-Type 为 JSON |
| Docker 中有文件但 UI 没有 | user、agent、session 是否一致 | 记录三者并刷新工作区；检查 snapshot/persist 日志 |

## 16. 每次测试怎么记录

手工测试不需要做自动化证据平台，但要留下足够信息，便于后续准备面试和复现问题。每个功能用下面模板记录：

```text
功能：
时间：
登录用户：
Agent：
Session URL：
输入 Prompt：
点击操作：
实际工具链：
页面结果：通过 / 部分通过 / 失败
Trace 状态：SUCCESS / CANCELLED / ERROR
产物路径：
遇到的问题：
原因判断：
解决或下一步：
```

优先保留四类信息：完整 Prompt、HITL 中实际 SQL、Trace 状态、工作区产物路径。它们足以支撑后续针对“方案、实现、困难、解决方式”的面试复盘。

## 17. 最终验收清单

完成一项就手工打勾，不要一次性全标完成。

- [ ] `admin/admin` 登录成功，能看到两个 Agent。
- [ ] Data Agent 与 Insight Agent 切换后 URL、输入框、左侧会话同步变化。
- [ ] `list_data_sources`、`describe_table`、`run_sql_preview`、`render_chart` 各成功一次。
- [ ] HITL 批准后 SQL 执行，拒绝后 SQL 不执行。
- [ ] 点击 `Stop` 后 Trace 出现 `CANCELLED`。
- [ ] 黄金主链调用 `data-explorer` 与 `report-writer`。
- [ ] `/admin/sessions` 能看到 MAIN → SUBAGENT Fan-out。
- [ ] `reports/ui-golden-flow.md` 在工作区可打开。
- [ ] 工作区可保存、重命名、上传和删除文件；容器回收后修改仍在、删除文件不复现。
- [ ] Insight Agent 的常用 SQL 与图表偏好按真实工具调用更新。
- [ ] Data Agent 与 Insight Agent 的偏好和会话不串用。
- [ ] 多文件 Skill 提交后能预览 `SKILL.md` 与 `templates/query.sql`。
- [ ] 审批可编辑、批准并生成新版本。
- [ ] Marketplace 可选择 v1/v2 或新版本安装。
- [ ] 团队版本回滚与个人已安装副本的边界符合预期。
- [ ] 系统提示词保存后无需重启即可影响新请求，测试后已恢复。
- [ ] 模型切换后 Trace 中的实际模型正确。
- [ ] 浏览器刷新后原 session 和历史轨迹恢复。
- [ ] 两个用户写同一路径时内容互不覆盖。
- [ ] `/traces` 可区分 `SUCCESS/CANCELLED/ERROR`。
- [ ] `/usage` 与管理端用量页可加载 Token、缓存 Token、成本和趋势，不出现 JSON 解析错误。
