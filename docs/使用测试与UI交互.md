# agentscope-dataagent 使用、测试与 UI 交互指南

> 本文档面向**使用者 / 测试人员**：按功能模块逐一说明「功能在哪、怎么在页面上操作、预期看到什么」，并补充聊天链路在底层是怎么协作的（帮助你理解 UI 行为背后的原因）。
> 所有步骤均在**浏览器前端页面**完成，不需要 curl 或命令行工具。
>
> 想看架构与代码协作，见 [project-overview.md](file:///e:/demo/agentscope-dataagent/docs/project-overview.md)。
>
> **2026-07-10 更新：** 沙箱生命周期现由 AgentScope `SandboxManager` 管理。旧排障文字中
> 涉及 `UserSandboxPool`、自定义清理器、心跳或锁的内容均为历史资料；当前解决方案、交付矩阵
> 与必须执行的手工冒烟检查见
> [current-delivery-status-2026-07-10.md](current-delivery-status-2026-07-10.md)。

---

## 页面总览（先认路）

系统是一个 **SPA 单页应用**，左侧固定侧边栏 + 右侧内容区。所有操作都在 `http://localhost:8080` 一个地址内完成。

### 布局结构

```
┌─────────────────────────────────────────────────────┐
│  左侧边栏（固定 280px）    │      右侧内容区          │
│                            │                         │
│  ┌──────────────────┐     │  ┌───────────────────┐  │
│  │ ＋ 新建对话       │     │  │ Data Agent 📊      │  │
│  └──────────────────┘     │  │ 描述文字…           │  │
│                            │  │ [技能][子Agent]…   │  │
│  今天                      │  ├───────────────────┤  │
│  ┃ 新对话 1:2              │  │                     │  │
│  ┃ xxx 的对话        刚刚  │  │   聊天消息区域      │  │
│                            │  │   （SSE 流式输出）   │  │
│  昨天                      │  │                     │  │
│  ┃ 数据分析对话      2小时  │  │                     │  │
│                            │  ├───────────────────┤  │
│  底部用户菜单：             │  │ 向 dataagent 发送…[发送]│  │
│  👤 admin  ▼              │  └───────────────────┘  │
│    → 个人资料 / 外观 / …   │                         │
│    → 管理后台（管理员可见） │                         │
│    → 退出登录              │                         │
└─────────────────────────────────────────────────────┘
```

### 各页面路径一览

| 页面 | 路径 | 从哪进 | 说明 |
|---|---|---|---|
| 登录页 | `/login` | 打开首页自动跳转 | 输入用户名密码 |
| 聊天页 | `/chat` | 登录后默认页 | 核心交互区 |
| 工作区 | `/workspace` | 头部「工作区」按钮 | 浏览 Agent 生成的文件 |
| 技能配置 | `/configure/skills` | 头部「技能」按钮 | 安装/管理 Agent 技能 |
| 子 Agent | `/configure/subagents` | 头部「子Agent」按钮 | 管理子代理定义 |
| 通道配置 | `/configure/channels` | 头部「通道」按钮 | IM 通道绑定 |
| 工具配置 | `/configure/tools` | 头部「工具」按钮 | 启用/禁用数据分析工具 |
| 分享管理 | `/configure/shares` | 头部「分享」按钮 | 授权其他用户访问 |
| Agent 设置 | `/configure/settings` | 头部「设置」按钮 | 名称、提示词、模型等 |
| 个人资料 | `/profile` | 左下角菜单→个人资料 | 修改头像、昵称 |
| 外观 | `/appearance` | 左下角菜单→外观 | 主题、字体大小 |
| 贡献 | `/contributions` | 左下角菜单→贡献 | 查看我提交的技能/模板 |
| 绑定 | `/bindings` | 左下角菜单→绑定 | 第三方账号绑定 |
| 用量 | `/usage` | 左下角菜单→用量 | Token 使用量 |

> **注意：当前版本只有一个 Agent**，ID 固定为 `data-agent`。聊天页头部显示 "Data Agent"，没有切换下拉框。如需管理/创建更多 Agent，通过**管理后台 → Agent 管理**进入。

---

## 0. 前置准备

### 0.1 启动后端

用 IDEA 运行 `DataAgentApp`（Spring Boot），或命令行：

```bash
java -jar target/agentscope-dataagent-*-exec.jar
```

启动日志确认：
```
Started DataAgentApp in X seconds   ← 看到这行说明启动成功
No active profile set, falling back to 2 default profiles: 'myal', 'redis'
```

默认端口 **8080**。

> **⚠️ 改过后端代码后必须重启一次**：如果你刚刚拉入了新后端（例如本次新增的「全局 Agent 热重建」功能），**当前正在跑的进程仍是旧二进制**——请先停止并重新启动应用（IDEA 重新 Run，或重新 `java -jar`）加载新代码。这次重启是**一次性**的；之后 admin 在 UI 上编辑 `data-agent` 就会**热生效，无需再重启**（见 §3.1）。

### 0.2 前置依赖检查

| 依赖 | 怎么确认 | 不开的话会怎样 |
|---|---|---|
| MySQL | 后端启动不报连接错即可 | 整个应用起不来 |
| Redis | 日志出现 `Spring Data Redis - Could not safely identify store assignment...` 是警告不是错误；如果 profile 含 redis 则必须开 | 会话状态退回内存模式，重启丢失 |
| Docker | 不影响登录和普通对话 | **用到沙箱的功能**（Agent 执行任务生成文件）会报错 |
| DashScope API Key | 在 `application-myal.yml` 里配了就能用 | 对话时模型调用失败，返回错误信息 |

### 0.3 登录

1. 打开浏览器访问 `http://localhost:8080`
2. 自动跳转到登录页 `/login`
3. 输入默认管理员账号：

| 字段 | 值 |
|---|---|
| 用户名 | `admin` |
| 密码 | `admin` |

4. 点「登录」按钮
5. **预期结果**：跳转到聊天页 `/chat`，看到：
   - 左侧边栏有紫色「＋ 新建对话」按钮
   - 右侧顶部显示 **"Data Agent 📊"** 和描述文字
   - 中间区域显示提示文字「开始新对话，输入 /help 可查看空间指令。」
   - 底部有输入框，placeholder 写着「向 dataagent 发送消息…」

---

## 1. 对话（SSE 流式聊天）

**功能是什么**：和 AI Agent 对话，Agent 边思考边回答（一个字一个字流出来），过程中可能调用工具。

### 测试步骤

总体测试步骤：
"帮我分析 analtics_db 库的销售数据：
1. 先看看有哪些表
2. 看看 orders 表的结构
3. 写 SQL 查最近 30 天的每日销售额
4. 跑一下看看结果
5. 画个折线图"

预期现象：Agent 先列数据源 → 看表结构 → Plan Mode 列出步骤 → 你确认 → 调 code-reviewer 审 SQL → 调 report-writer 出报告 → run_sql_preview 弹出权限确认 → Compaction 自动清上下文。如果某步卡了，就是具体可修的 bug——现在有日志和代码对照，排查会快得多。


1. 在底部输入框中输入任意问题，例如：
   ```
   你好
   ```
2. 点击右侧「发送」按钮（或按 Enter）
3. **预期效果**：
   - 输入框上方立刻出现你的消息气泡（靠右，蓝色/深色）
   - Agent 开始回复——文字是**逐字/逐句流式出现**的（不是等全部生成完才显示）
   - 如果 Agent 调用了工具（比如查数据），你会在回复中间看到类似 `▶ tool_call: list_data_sources` 的标记，然后是工具返回的结果，最后是 Agent 的总结
   - 回复结束后出现 `done` 标记

### 进阶测试：触发工具调用

依次问以下问题，验证四件套工具都能被正确调用：

| # | 在输入框输入 | 预期 Agent 行为 |
|---|---|---|
| 1 | `列出所有可用的数据源` | Agent 调用 `list_data_sources` 工具，返回数据源列表（如 `analytics_db`） |
| 2 | `看看 analytics_db 里 orders 表的结构` | Agent 调用 `describe_table` 工具，返回表结构和采样数据 |
| 3 | `查询最近 7 天的日销售额` | Agent 调用 `run_sql_preview` 工具，执行 SQL 并展示结果 |
| 4 | `画个折线图展示日销售额趋势` | Agent 调用 `render_chart` 工具，页面内嵌一张 Vega-Lite 图表 |

### 同步请求（非 SSE）

在对话进行中，也可以直接发送短消息（不走 SSE 流）：
1. 输入简短文本如 `你好`，点发送
2. **预期**：收到一次性 JSON 格式的完整回复（适合压测/调试，正常使用走 SSE 即可）

### 会话管理（左侧边栏）

| 操作 | 怎么做 | 预期效果 |
|---|---|---|
| **新建对话** | 点左侧「＋ 新建对话」按钮 | 右侧清空，URL 带 `?session=xxx`，侧边栏出现「新对话」条目（今天分组下） |
| **切换历史对话** | 点击侧边栏任意历史条目（如"xxx 的对话"） | 右侧加载该对话的完整历史记录 |
| **删除对话** | 鼠标悬停在侧边栏某条目上 → 出现 `×` 按钮 → 点击 | 弹出确认框 → 确认后该条目消失 |

---

## 2. 数据分析工具（四件套）

这是 Agent 最核心的能力。通过对话自然语言触发，不需要手动调 API。

### 2.1 列出数据源

1. 在聊天输入框输入：「列出数据源」或「有哪些数据库可以查」
2. **预期**：Agent 回复中包含已配置的数据源名称，默认有一个 `analytics_db`（MySQL 里的示例库，含 products=15 条、users=20 条、orders=120 条）

### 2.2 查看表结构

1. 输入：「看看 orders 表有什么字段」
2. **预期**：Agent 调用工具后返回表的列名、类型、注释，以及前 5 行采样数据

### 2.3 执行 SQL 查询

1. 输入：「查询每个品类的总销售额」或「找出金额最大的 10 个订单」
2. **预期**：
   - Agent 自动构造 SELECT SQL 并执行
   - 结果以表格形式展示
   - 如果查询语法错误或有权限问题，Agent 会返回错误信息并尝试修正

### 2.4 渲染图表

1. 先让 Agent 查出一组数据（如上面的销售额查询），然后说：「画个柱状图」或「用折线图展示趋势」
2. **预期**：聊天区域内嵌入一张 **Vega-Lite 图表**（交互式图表，可以 hover 查看数值）

---

## 3. Agent 创建与管理

> 当前版本 Agent ID 硬编码为 `data-agent`，聊天页无切换器。Agent 的创建/修改通过**管理后台**操作。

> **关于聊天页头部的配置按钮（技能 / 子Agent / 通道 / 工具 / 分享 / 设置）**：
> 以 **admin 登录**时，这六个按钮都会显示在 `data-agent`（内置全局 Agent）的聊天页头部。**全部可用**：
> - **技能、子Agent、通道、工具** —— 直接打开并配置（写入该 Agent 的工作区）。
> - **设置、分享** —— admin 可在线编辑全局 Agent 的**定义**（名称 / 描述 / 系统提示词 / 模型 / 最大迭代 / 工具与技能开关 / 身份 / 沙箱模式），也可给它添加分享授权。编辑会**持久化到数据库**，目录里立即生效；**更重要的是运行中的 `data-agent` 会立即热生效，无需重启**——保存后新的系统提示词 / 模型 / 身份 / 工具与技能开关等会立刻作用于正在运行的 Agent 实例（会话与记忆不丢失）。
>
> **机制说明**：全局 Agent 在启动时由框架从 `agentscope.json` 注册，其定义不在用户库里。admin 的在线编辑以"覆盖存储（GlobalAgentOverrideStore）"形式落库——读路径把覆盖叠加在 bootstrap 定义之上（目录立即反映）；保存时 `DataAgentBootstrap.rebuildGlobalAgent(id)` 会**用合并后的覆盖重新构建运行实例、重新挂上 `DataAgentToolkit` / `contribute_to_workspace` 等实例级工具，并原子替换网关中的 Agent**，使运行中实例即时生效。普通用户（非 admin）对全局 Agent 仍只有 RUN 权限，看不到编辑按钮。
>
> 普通用户若想拥有**完全自主**的 Agent（自己当 owner、可随意增删），请在「🛡 管理后台 → Agent 管理」里**创建一个自定义 Agent**，然后对那个自定义 Agent 用头部的设置/分享即可。

### 3.1 验证 admin 在线编辑「保存即热生效」（无需重启）

> 前提：应用已用**本次新代码**启动（若你之前就在跑旧实例，请先按 §0.1 重启一次加载新二进制）。本小节用于确认「保存后运行中 Agent 立即生效」。

**测试步骤**：

1. 以 **admin** 登录，进入 `data-agent` 聊天页，点头部「⚙ 设置」。
2. 在「系统提示词」末尾追加一句明显标记，例如：
   ```
   【热生效验证】请在回复结尾加上"✅已应用热更新"。
   ```
3. 点「保存」。
4. **不要重启应用**，回到聊天页，新建或打开一个对话，发一句：`你好`。
5. **预期**：Agent 的回复结尾出现"✅已应用热更新"——说明新的系统提示词已经作用于**正在运行的实例**；整个过程中**进程没有重启**。
6. **反向验证**：把刚才加的标记删掉再保存，发新消息，回复不再带该标记——证明覆盖可被再次热替换。
7. **（可选）改名称 / 模型**：在设置里把名称改成 `Data Agent (hot)`，或切换模型后保存，观察聊天页标题 / 对话行为随之变化（名称变更前端有缓存，刷新页面即更新）。

**安全断言（说明热重建不会破坏现有能力）**：

- 进行中的回合**不会被打断**：网关按会话串行化回合，旧实例上的回合会干净收尾，新回合立即走新实例。
- **会话历史与记忆不丢失**：状态以 `agentId + conversationId` 为键，与 Agent 实例解耦。
- **工具不丢失**：重建后会自动重新挂上 `DataAgentToolkit`（四件套工具）与 `contribute_to_workspace`，沙箱 / 工作区能力照常可用。

> 如果保存后 Agent 行为**没有任何变化**，先确认：① 你重启加载的是新二进制（§0.1）；② 当前登录的是 `admin`（非 admin 只有 RUN 权限，看不到设置按钮）；③ 改动的是**系统提示词 / 模型 / 名称 / 工具·技能开关 / 身份**这类运行期字段（分享授权走的是另一路径，目录即时反映，但不需要热重建）。

### 进入 Agent 管理

1. 在左下角点击用户名 **「admin ▼」** 展开菜单
2. 点击 **「🛡 管理后台」**
3. 进入管理面板后，找 **「Agent 管理」** 入口（或在地址栏输入 `/admin/agents`）
4. **预期**：看到 Agent 列表，其中至少有一条 `data-agent`

### 创建新 Agent

1. 在 Agent 管理页点 **「创建 Agent」** 按钮
2. 填写表单：
   - **名称**：如 `我的助手`
   - **描述**：如 `通用助手`
   - **系统提示词**：Agent 的角色设定
3. 点保存
4. **预期**：列表中出现新 Agent，状态为「活跃」

> 注意：新创建的 Agent 目前不会出现在聊天页面的切换列表中（当前硬编码 data-agent）。这是已知限制。

---

## 4. 分享与权限

**功能是什么**：把你自己创建的 Agent 分享给其他用户，控制他们的访问级别。

### 进入分享管理

1. 在聊天页顶部的按钮栏里，找到并点击 **「👥 分享」** 按钮
2. 跳转到 `/configure/shares` 页面
3. **预期**：看到当前 Agent 的分享列表（初始为空）

### 授权给某个用户

1. 在分享页找到表单区域：
   - 选择授权类型：**USER**（指定用户）或 **WORKSPACE**（所有登录用户）
   - 如果选 USER，填写对方用户名/ID
   - 选择权限等级：
     - **CLONE**：只能克隆一份到自己名下
     - **RUN**：可以使用但不能改配置
     - **EDIT**：完整编辑权限
2. 点 **「授权」** 按钮
3. **预期**：下方表格新增一行，显示被授权人、类型、等级、时间戳

### 撤销授权

1. 在分享列表中找到要撤销的那一行
2. 点该行的 **「撤销」** 按钮
3. **预期**：该行立即从列表中移除

### 权限验证

- 非 owner 用户打开同一个 Agent 时，看到的按钮取决于其 tier：
  - **CLONE**：只能看到克隆按钮
  - **RUN**：可以对话但看不到配置按钮
  - **EDIT**：和 owner 一样的完整界面

---

## 5. 克隆 Agent

1. 以一个被授予 **CLONE** 权限的用户登录
2. 打开被分享的 Agent 页面
3. 点 **「克隆」** 按钮
4. **预期**：在自己名下创建了一份该 Agent 的独立副本（配置相同但 owner 变为自己）

---

## 6. 工作区文件

**功能是什么**：Agent 在执行任务时会生成文件（分析报告、图表数据等），这些文件存在工作区里。

### 浏览工作区

1. 在聊天页头部，点击 **「📁 工作区」** 按钮
2. 跳转到 `/workspace` 页面
3. **预期**：左侧显示文件树（初始可能有 MEMORY.md），右侧是文件内容预览

### 文件操作

| 操作 | 怎么做 | 预期 |
|---|---|---|
| **点击文件** | 在文件树中点击文件名 | 右侧显示文件内容（支持 Markdown 渲染） |
| **查看摘要** | 页面顶部有工作区摘要卡片 | 显示文件数、总大小等信息 |

> 工作区的实际文件存储在后端配置的 `dataagent.workspace.root` 目录下（本地磁盘或共享存储）。如果 Agent 还没执行过生成文件的任务，工作区可能是空的。

---

## 7. 子 Agent

**功能是什么**：在主 Agent 下定义"子代理"——独立的助手角色，各有自己的提示词。

### 进入子 Agent 管理

1. 聊天页头部点 **「🧩 子Agent」** 按钮
2. 跳转 `/configure/subagents`

### 创建子 Agent

1. 在页面中找到 **「新建子代理」** 区域
2. 输入名称（如 `helper`）
3. 在编辑器中编写子代理的系统提示词（Markdown 格式）
4. 点保存
5. **预期**：下方列表出现新的子代理条目

### 从现有 Agent 创建

1. 点 **「从 Agent 导入」** 按钮
2. 选择一个已有的 Agent 作为模板
3. **预期**：自动将该 Agent 的配置导入为子代理定义

### 删除子 Agent

1. 在子代理列表中找到目标项
2. 点对应的 **「删除」** 按钮
3. **预期**：该子代理从列表中移除

---

## 8. 技能管理

**功能是什么**：为 Agent 安装额外技能（来自技能仓库或市场）。

### 进入技能页

1. 聊天页头部点 **「🛠 技能」** 按钮
2. 跳转 `/configure/skills`

### 查看可用技能仓库

1. 页面上方显示已注册的技能仓库列表
2. **预期**：至少看到一个仓库（即使空的也会列出）

### 安装技能

1. 展开某个仓库，浏览里面的技能列表
2. 找到想安装的技能（如 `chart-rendering`）
3. 点 **「安装」** 按钮
4. **预期**：安装成功后，技能出现在「已安装」区域

### 从市场安装

1. 点 **「从市场安装」** 按钮/标签
2. 浏览市场可用技能
3. 选择并安装
4. **预期**：市场技能下载并注册到当前 Agent

---

## 9. 工具配置

**功能是什么**：控制 Agent 可以使用哪些工具（启用/禁用、调整允许列表）。

### 进入工具配置

1. 聊天页头部点 **「🧰 工具」** 按钮
2. 跳转 `/configure/tools`

### 查看工具目录

1. 页面分两个 Tab：**内置工具** 和 **MCP 服务器**
2. **内置工具** Tab 列出了 Agent 可调用的所有工具：
   - `list_data_sources` — 列出数据源
   - `describe_table` — 描述表结构
   - `run_sql_preview` — 执行只读 SQL
   - `render_chart` — 渲染图表
   - `outbound_send` — 向 IM 通道推送消息
   - `contribute_to_workspace` — 向工作区贡献文件

### 修改工具配置

1. 找到要修改的工具
2. 调整启用状态或参数
3. 点 **「保存」**
4. **预期**：下次对话时 Agent 按新配置使用工具（已启用的才能被调用）

---

## 10. 通道绑定

**功能是什么**：将 Agent 连接到外部 IM 通道（如企业微信、钉钉、飞书等），实现「在外部 IM 里 @机器人」即可对话。

### 进入通道配置

1. 聊天页头部点 **「📡 通道」** 按钮
2. 跳转 `/configure/channels`

### 查看已绑定的通道

1. 页面显示通道绑定表格
2. **初始状态可能是空表**（尚未绑定任何通道）

### 管理员视角（全局通道）

1. 左下角菜单 → **「🛡 管理后台」** → **「通道管理」** (`/admin/channels`)
2. 可以看到系统中所有已注册的通道类型和实例
3. 点击某通道可查看详情 (`/admin/channels/:id`)

---

## 11. Agent 设置

**功能是什么**：修改当前 Agent 的核心配置——名称、描述、系统提示词、模型选择等。

### 进入设置

1. 聊天页头部点 **「⚙ 设置」** 按钮
2. 跳转 `/configure/settings`

### 可修改项

| 配置项 | 说明 | 示例 |
|---|---|---|
| 名称 | Agent 显示名称 | `Data Agent` |
| 描述 | 一句话介绍 | `基于 AgentScope 2.0 的企业数据分析助手` |
| 系统提示词 | Agent 角色和行为指令 | 编辑器内修改 Markdown 文件内容 |
| 模型 | LLM 模型选择 | 取决于后端配置（DashScope/Ollama） |
| 最大迭代轮数 | 单次对话最多工具调用次数 | 默认 20 |
| 记忆开关 | 是否开启上下文记忆 | 开/关 |
| 工具缓存 | 是否缓存工具调用结果 | 开/关 |

3. 修改后点 **「保存」**
4. **预期**：下次对话生效（正在进行的对话不受影响）

---

## 12. 贡献审批（管理员）

**功能是什么**：用户可以向系统贡献技能/模板，管理员需要审核后才能上架。

### 进入审批

1. 左下角菜单 → **「🛡 管理后台」** → **「贡献审批」** (`/admin/approvals`)
2. 或直接访问 `/contributions`（用户视角：查看自己提交的贡献）

### 审批流程

| 步骤 | 操作 | 说明 |
|---|---|---|
| 1 | 用户提交贡献 | 在「贡献」页上传技能/模板 |
| 2 | 管理员查看待审列表 | 审批页显示所有 pending 状态的贡献 |
| 3 | 管理员点 **「通过」** 或 **「拒绝」** | 通过的贡献变为 active，被拒绝的返回修改建议 |
| 4 | 用户查看结果 | 回到「贡献」页看状态变化 |

---

## 13. 用户管理（管理员）

### 进入用户管理

1. 左下角菜单 → **「🛡 管理后台」** → **「用户管理」** (`/admin/users`)
2. **预期**：看到用户列表，至少有 `admin` 账号

### 操作

| 操作 | 怎么做 | 预期 |
|---|---|---|
| **查看用户列表** | 页面加载即显示 | 表格列出用户名、角色、创建时间 |
| **重置密码** | 找到目标用户 → 点操作列的重置按钮 | 密码被重置为新随机值（显示一次） |

---

## 14. Outbound（对外推送）

**功能是什么**：Agent 可以主动向外部 IM 通道推送消息（需要先绑定通道）。

### 测试前提

- 已在第 10 步完成了通道绑定

### 触发方式

1. 在对话中对 Agent 说：
   ```
   把这条消息发到企业微信：项目进度已完成 80%
   ```
2. Agent 调用 `outbound_send` 工具
3. **预期**：消息被推送到对应的外部 IM 通道（需确认通道 webhook 可达）

---

## 15. Webhook 回调

**功能是什么**：接收外部系统的事件回调（如 GitHub PR、CI/CD 状态变更）。

### 测试方式

Webhook 是被动触发的，需要外部系统向本服务发 POST 请求。可以通过以下方式模拟：

1. 确认后端的 Webhook 端点 URL（通常形如 `/api/webhook/{channelType}/{channelId}`）
2. 用 Postman 或 curl 向该地址发送 POST 请求（带正确的签名头）
3. **预期**：后端处理后可在相关会话中看到事件触发的响应

> 此功能主要供集成测试使用，日常前端交互不太容易直接触发。

---

## 16. AI 草稿

**功能是什么**：AI 辅助创建/编辑 Agent 配置（基于自然语言描述自动生成草稿）。

### 进入方式

AI 草稿功能通常集成在 **Agent 设置** (`/configure/settings`) 页面中：
1. 在设置页找到 **「AI 草稿」** 或 **「智能描述」** 区域
2. 输入你想要的 Agent 能力描述（自然语言）
3. 点 **「生成草稿」**
4. **预期**：AI 自动填入建议的名称、提示词、推荐技能等，你可以在此基础上手动微调后保存

---

## 17. 模板市场

**功能是什么**：预制的 Agent 配置模板，一键应用到新 Agent。

### 进入方式

1. 管理后台 → **「市场」** 相关入口
2. **预期**：浏览可用模板列表，预览模板详情，一键应用

---

## 18. 活动日志

### 查看 Agent 运行实例

1. 管理后台 → **「运行实例」** (`/admin/instances`)
2. **预期**：表格列出每个 agentId 的运行状态：
   - 最后活动时间
   - 活跃会话数
   - 所在 Pod/进程信息

### 查看全局会话

1. 管理后台 → **「会话管理」** (`/admin/sessions`)
2. **预期**：看到所有用户的会话列表（可按 agentId 过滤），每行显示会话 ID、关联 agent、创建时间、状态
3. 点某行的 **「详情」** 展开：看到完整的会话元数据（agentId、conversationId、gateKey 等）

---

## 19. 排错指南

### 常见问题速查

| 现象 | 可能原因 | 解决方法 |
|---|---|---|
| 登录后白屏 | Token 未写入 localStorage | 按 F12 打开控制台看是否有 JS 报错 |
| 发送消息无反应 | SSE 连接断开 | 检查网络标签页，看 `/stream` 请求是否 pending |
| Agent 回复"我无法执行此操作" | 工具未启用或权限不足 | 去「工具配置」页确认工具已启用 |
| Agent 调工具报错 | Docker 未启动或沙箱容器异常 | 确保 Docker Desktop 正在运行；重启应用 |
| 分享页空白 / 无按钮 | 当前用户非 EDIT 权限 | 只有 Agent owner 或被授予 EDIT 的用户能看到配置按钮 |
| 403 Forbidden | 尝试访问管理员页面但非 admin 角色 | 用 admin 登录，或联系管理员赋权 |
| Redis 连接警告 | `Could not safely identify store assignment` | 这是 INFO 级别警告，不影响单实例使用 |
| 前端编译 TS2345 错误 | 类型不匹配（如 granteeType） | 已修复（`api/agents.ts` 中 `AgentShareGrant.granteeType` 收窄为 `'USER'\|'WORKSPACE'`）；重新 `npm run build` 即可 |
| SSE 流式对话报 `AccessDeniedException` / `Unable to handle the Spring Security Exception because the response is already committed` | **已修复**：旧代码的 `JwtAuthFilter` 继承 Spring Framework 的 `OncePerRequestFilter`，该过滤器在**异步分发时默认跳过**；而聊天 `/stream` 是 `SseEmitter` 异步请求，容器异步重跑 Security 链时 JWT 未被解析 → 请求变匿名 → `AuthorizationFilter` 拒绝。表现为聊天能正常回复、但随后日志炸一串 AccessDenied ERROR | 需**重启应用加载新代码**：`SecurityConfig.JwtAuthFilter` 已改为继承 `GenericFilterBean`（普通 `Filter`，在每次分发含异步都执行，JWT 解析幂等），异步分发时重新恢复认证，不再匿名 |
| 沙箱 WARN：`Failed to deserialize Docker sandbox state` / `Could not resolve type id 'docker'` | **已修复（2026-07-08）**：根因是 `DataAgentWorkspaceConfig.sandboxClient()` 给 `DockerSandboxClient` 传入的 `ObjectMapper` 未注册框架的 `HarnessSandboxJacksonModule`，导致 `SandboxState` 多态子类型 `docker`（`DockerSandboxState`）反序列化失败、每次重启都退化成全新容器（工作区文件不跨重启）。现改为与框架无参构造一致（注册该模块），并让应用关闭时**保留容器**（`UserSandboxPool.shutdownAll` 改调 `stop()` 而非 `close()`，不再 `docker rm`、不 `clearState`）→ 重启后 `SandboxManager` 的 Priority-3 恢复路径重新挂载**同一个**容器、复用其文件系统（Branch A），实现沙箱**跨重启恢复** | 需**重启应用加载新代码**；若旧 Redis 里残留的坏状态仍导致某次恢复失败，清理该 admin 作用域的沙箱键即可（不影响聊天记录，聊天在 MySQL） |
| 聊天报 `Agent is paused for human-in-the-loop confirmation: <tool> ... in ASKING state` / 工具调用一直不执行、间歇伴随沙箱 `Container not found` | **已修复（2026-07-08）**：根因是 `AgentRuntimeConfigurer.buildPermissionContext()` 里 `addAllowRule` / `addAskRule` 的**第一个参数（规则表 key）写成了逻辑分组名 `"default"` / `"sql_execution"`，而不是真实工具名**。框架 `PermissionEngine` 按 `table.get(tool.getName())` 查规则，key 不匹配 → 所有 ALLOW 规则静默失效 → 工具调用在 `DEFAULT` 模式下全部降级为 `ASK`（需人工确认）；而当前 Chat 链路未实现 HITL 确认回传，agent 卡死在 ASKING 状态，长时间暂停期间沙箱被回收/重建，连带 `Container not found`。日志表现为 `list_data_sources (id=...) in ASKING state` 后 `Chat stream error` | 需**重启应用加载新代码**：已把每个规则的 key 改回真实工具名（如 `list_data_sources`、`run_sql_preview`），并把本地可信的 `data-agent` 模式设为 `PermissionMode.BYPASS`（SQL 工具自身有只读 + 黑名单 + 行数上限防护），所有数据工具不再卡确认。若日后要恢复对 SQL 的人工确认：在 `ChatController` 接通确认回传通道，并把 `run_sql_preview` 规则改回 `ASK`（或去掉 BYPASS、改为依赖显式 ALLOW 规则） |
| 前端人机确认（HITL）链路已实现 | 之前前端无任何 HITL 交互（chat.ts 的 `ChatEvent` 仅 `token/tool_call/tool_result/done/error`，`ChatPanel` 无 `confirm` 分支），ASK 模式必卡死（上条根因之一）。现已打通：后端 `ChatController` 将 `RequireUserConfirmEvent` 转 SSE `confirm` 帧（携带 `replyId` + 待确认 `toolCalls` 的 id/name/input）并暂存 `pendingConfirms`（按 conversationId）；前端 `ChatPanel` 收到后渲染确认卡片（工具名 + 参数 + 允许/拒绝），点选后经 `runStream({confirmResults:[{toolCallId,approved}]})` 回传；后端用 `confirmResults` 构造 `List<ConfirmResult>` 塞入 `UserMessage.metadata(Msg.METADATA_CONFIRM_RESULTS, ...)` 重新 dispatch 恢复暂停的 agent | 权限：`PermissionMode.BYPASS` + 单条 `addAskRule("run_sql_preview", ASK)`。引擎 `step2(ask)` 先于 `step5(BYPASS)`，仅 `run_sql_preview` 触发人工确认，其余所有工具（含框架内置 `agent_*`/`memory_*`、技能工具）在 BYPASS 下自动放行——**无需枚举任何工具名**，避免漏列重新卡死。需**重启应用**加载新二进制 |

### 如何判断当前是单实例还是多副本

| 检查方法 | 单实例 | 多副本 |
|---|---|---|
| 后台日志 PID 数量 | 只有 1 个 `PID xxxx` | 多个不同 PID |
| Nginx/负载均衡 | 没有 | 有 upstream + proxy_pass |
| Redis 状态键 | 一个来源写入 | 可能多个来源写入（需粘性会话保证一致性） |

---

## 底层协作原理：聊天链路是怎么跑起来的

> 本节帮助你理解「UI 为什么这样表现」：一条消息从前端进来后，后端各组件怎么配合干活；会话重启后为什么还在；sessionKey / userId / agentId 三层 key 是什么；数据存在哪。
>
> **架构版本说明（2026-07 会话域提取重构后）**：会话元数据持久化已从旧的「内存 `SessionAgentManager` + `sessions.json`」改为 **JPA `SessionEntity`（MySQL `conversation_session` 表）+ `ConversationService`**。本文描述的就是这套**当前架构**。旧的 `SessionAgentManager`/`SessionStore` 类已删除，若在其他文档里看到它们，以本文为准。
>
> **沙箱校准（2026-07-10）：** 本文中残留的 `UserSandboxPool` 引用和图示均为历史内容。
> 当前 Agent 与浏览器工作区操作都经 `SharedSandboxFilesystem` 使用 AgentScope
> `SandboxManager`，不存在应用侧容器池。详见《整体介绍》文档末尾「当前交付状态与架构校准（2026-07-10）」一节。

### 一、先看懂业务：这是个什么系统

本项目是一个**多租户数据分析 Agent 平台**。可以类比成一个「AI 数据分析师的客服中心」：

- 多个用户（租户）可以登录
- 每个用户能选择不同的 Agent（数据分析师）聊天
- 每个用户跟每个 Agent 可以有多个独立会话（像 ChatGPT 左侧栏那样）
- 用户发消息后，Agent 会调用工具（查数据库、画图、生成报表）来回答

**关键约束**：用户 A 绝不能看到用户 B 的会话，用户 A 跟 Agent1 的会话也不能串到 Agent2。这就是「多租户隔离」。

### 二、角色分工（餐厅比喻）

把整个系统想象成一家**连锁餐厅**，每个组件扮演一个角色：

| 组件 | 餐厅角色 | 实际职责 |
|---|---|---|
| **ChatController** | 服务员 | 接待顾客点单（收消息）、上菜（SSE 流式回复） |
| **SessionController** | 前台经理 | 管理包间（会话列表、查历史、重置、删除、标记已读） |
| **ConversationService** | 账房先生 | 会话元数据的**查询 / 重置 / 删除 / 已读**（基于 JPA，数据在 MySQL） |
| **Harness 框架** | 后厨调度 | 创建会话、算 gateKey 路由、把对话日志写进 workspace 的 JSONL 文件 |
| **UserSandboxPool** | 后厨隔离间 | 为每个 `(userId, agentId)` 懒创建、复用、回收一个 Docker 沙箱容器 |
| **AgentStateStore** | 厨师的记忆本 | Agent 的工作记忆（Redis 或内存，按 `userId + agentId` 维度） |

### 它们之间的关系

```
顾客（用户浏览器）
    │
    │ HTTP 请求
    ▼
┌─────────────────────────────────────────────┐
│  服务员 ChatController                        │
│  ├─ 接点单（chat/stream）                      │
│  ├─ 同步上菜（chat/send）                      │
│  └─ 探路（chat/session）                       │
└────────────────────┬────────────────────────┘
                     │
         ┌───────────┴────────────┐
         │ 发消息走「通道线」        │ 查会话走「查询线」
         ▼                        ▼
┌─────────────────┐    ┌──────────────────────────────────┐
│ Harness 框架     │    │ 前台经理 SessionController          │
│ (ChatUiChannel/  │    │ ├─ inbox（会话列表）               │
│  Gateway)        │    │ ├─ turns（对话详情）              │
│  ├─ 算 gateKey   │    │ ├─ reset / read / delete          │
│  ├─ 查/建会话    │    └──────────────┬───────────────────┘
│  ├─ 写 JSONL 日志│                   │ 调 ConversationService
│  └─ 路由到 Agent │                   ▼
└────────┬────────┘    ┌──────────────────────────────────┐
         │             │ ConversationService（JPA 服务层）  │
         │             │ ├─ inbox / getTurns / reset        │
         │             │ ├─ markRead / deleteSession        │
         │             │ └─ requireOwnedSession（归属校验）  │
         │             └──────────────┬───────────────────┘
         │                            │
         │  Agent 干活（调工具）        ▼
         │             ┌──────────────────────────────────┐
         ▼             │ MySQL：conversation_session        │
                  │        conversation_read_state          │
                  │        （会话元数据 + 已读状态，跨副本共享）│
                  └──────────────────────────────────────┘

另一条线（运行时数据）：
┌─────────────────┐         ┌──────────────────────────────┐
│ UserSandboxPool │         │ AgentStateStore               │
│ (Docker 容器)    │         │ (Redis db2 / 内存)             │
│  按 (userId,     │         │  按 (userId, agentId) 存工作记忆│
│   agentId) 隔离  │         └──────────────────────────────┘
└─────────────────┘
```

**核心要点**：
- `ChatController` 和 `SessionController` 是**两个并列的入口**，一个管「发消息」，一个管「会话管理」
- `ConversationService` 是会话元数据的**消费者和管理者**，不是生产者——会话的创建由 harness 框架在 `dispatchStream` 内部完成
- 会话元数据在 **MySQL**，跨副本天然共享；聊天原文在 **workspace 的 JSONL 文件**；Agent 工作记忆在 **AgentStateStore**

### 三、场景一：用户发一条消息（最核心的链路）

这是最重要的一条链路。假设用户 `alice` 在 `data-agent` 这个 Agent 上发了一条消息：「帮我查一下上个月销量」。

#### ⚠️ 重要澄清：stream 接口不查会话、不创建会话

先纠正一个常见误解：`POST /api/agents/{agentId}/chat/stream` **不会**查会话、也不会创建会话。

- `stream()` 接口**只干一件事**：把消息打包丢给 harness 通道，然后把 Agent 的事件流翻译成 SSE
- **查会话**是另一个接口 `GET /chat/session`（`currentSession()`）干的
- **创建会话**是 harness 框架内部（Gateway + ChatUiChannel）干的，**本项目代码看不到**

`ChatController` 与 `ConversationService` 的交互只有两处：
1. `currentSession()` → `ConversationService.findByGateKey()`（查会话存不存在）
2. `/reset` 斜杠命令 → `ConversationService.resetSessionByKey()`

**发送消息这条主链路根本不直接调 ConversationService**。会话的创建/注册是 harness 框架在 `dispatchStream` 内部自动完成的。

#### 完整时序（当前架构）

```
alice 浏览器          ChatController          Harness 框架               Agent(后厨)
    │                      │                  （ChatUiChannel/网关）          │
    │ 1. POST /chat/stream │                      │                          │
    │ ├─ message: "查销量"  │                      │                          │
    │ └─ sessionKey: "s1"  │                      │                          │
    │ ────────────────────>│                      │                          │
    │                      │                      │                          │
    │                      │ 2. 验明身份（JWT）     │                          │
    │                      │ 3. 权限检查 guard      │                          │
    │                      │ 4. 生成 conversationId │                          │
    │                      │ 5. 是斜杠命令？否      │                          │
    │                      │                      │                          │
    │                      │ 6. executeChatStream()│                          │
    │                      │ ├─ 打包 InboundMessage │                          │
    │                      │ │  (userId, agentId,  │                          │
    │                      │ │   conversationId)   │                          │
    │                      │                      │                          │
    │                      │ 7. chatUiChannel.dispatchStream(inbound)        │
    │                      │ ────────────────────>│                          │
    │                      │                      │                          │
    │                      │   ⚠️ 从这里开始，     │                          │
    │                      │   ChatController     │                          │
    │                      │   不再参与，只等      │                          │
    │                      │   事件流回来         │                          │
    │                      │                      │                          │
    │                      │                      │ 8. 算出 gateKey          │
    │                      │                      │ ┌────────────────────┐  │
    │                      │                      │ │gateKey=            │  │
    │                      │                      │ │ |x:agentId=data-agent│
    │                      │                      │ │ |t:s1              │  │
    │                      │                      │ └────────────────────┘  │
    │                      │                      │                          │
    │                      │                      │ 9. 框架内部查/建会话      │
    │                      │                      │ （会话元数据落到 MySQL    │
    │                      │                      │   conversation_session； │
    │                      │                      │   日志写入 workspace JSONL）│
    │                      │                      │                          │
    │                      │                      │ 10. 把消息投给 Agent ──>│
    │                      │                      │                          │
    │                      │                      │                          │ 11. Agent 干活
    │                      │                      │                          │ ├─ 调 run_sql
    │                      │                      │                          │ ├─ 调 render_chart
    │                      │                      │                          │ └─ 生成回复
    │                      │                      │                          │
    │                      │ 12. 返回 Flux<AgentEvent>（事件流）              │
    │                      │ <────────────────────│                          │
    │                      │                      │                          │
    │                      │ 13. 翻译成 SSE 流     │                          │
    │                      │ ├─ token 事件        │                          │
    │                      │ ├─ tool_call 事件    │                          │
    │                      │ └─ done 事件         │                          │
    │                      │                      │                          │
    │ 14. SSE 流           │                      │                          │
    │ <────────────────────│                      │                          │
    │                      │                      │                          │
    │ 浏览器实时显示        │                      │                          │
```

#### 关键步骤详解

**步骤 1-5：接待与拦截（ChatController 的全部工作）**
用户消息进来，ChatController 先做四件事：
1. **验明身份**：从 JWT 拿到 `userId = "alice"`
2. **权限检查**：`AgentAccessGuard.require(userId, agentId, RUN)` —— 决定用户能否跟这个 Agent 对话（可见性 + 权限级别）
3. **确定会话**：用户传了 `sessionKey="s1"` 就用它，没传就生成 UUID 作为 conversationId
4. **斜杠命令短路**：如果是 `/new`、`/reset` 等命令，直接返回不经过 Agent

**注意**：这一步**不查会话存不存在**。即使会话不存在，消息也会照常发出去——harness 框架会在内部自动创建会话并落到 MySQL。

**步骤 6-7：打包与派发（ChatController → 通道）**
ChatController 把用户消息打包成 `InboundMessage`，贴上关键标签：
- `userId` = alice（谁发的）
- `preferredAgentId` = data-agent（给哪个 Agent，通过 catalog 查出来）
- `accountId` = s1（conversationId，哪个会话）

然后丢给 `chatUiChannel.dispatchStream(inbound)`。**从这一步开始，ChatController 就退出舞台了**，它只等着收事件流。

**步骤 8-9：框架内部干的事（本项目代码不可见）**
`dispatchStream` 进去之后，harness 框架（ChatUiChannel + Gateway）会：
1. **算 gateKey**：根据 InboundMessage 的三个标签算出 `|x:agentId=data-agent|t:s1`
2. **查/建会话**：框架内部维护会话生命周期。会话元数据落到 MySQL 的 `conversation_session` 表；对话原文以 JSONL 形式写入 workspace（`agents/{agentId}/sessions/{sessionId}.log.jsonl`）

**这部分代码不在本项目里**——`ChatUiChannel`、`Gateway` 都是 `agentscope-harness` 框架的类，本项目只是调用 `dispatchStream` 这个入口。

**步骤 10-11：Agent 干活**
框架把消息投给 Agent，Agent 调用工具（run_sql_preview、render_chart）生成回复。Agent 的工作记忆从 `AgentStateStore`（Redis 或内存，按 `userId + agentId`）加载，每轮结束后写回。

**步骤 12-14：流式返回**
Agent 每生成一个事件（吐一个字、调一次工具），框架就推给 ChatController。ChatController 把这些事件翻译成前端能懂的 SSE 格式，浏览器实时显示。

### 四、场景二：用户查看会话列表

用户 alice 打开网页，左侧栏要显示她所有的会话。这条链路跟聊天完全不同：

```
alice 浏览器          SessionController     ConversationService      MySQL
    │                      │                      │                    │
    │ 1. GET /sessions/inbox                      │                    │
    │ ────────────────────>│                      │                    │
    │                      │                      │                    │
    │                      │ 2. 拿 userId=alice    │                    │
    │                      │ 3. 调 inbox()         │                    │
    │                      │ ─────────────────────>│                    │
    │                      │                      │ 4. sessionRepo 查   │
    │                      │                      │   conversation_session│
    │                      │                      │   (按 userId 过滤+排序)│
    │                      │                      │ <────────────────────│
    │                      │ 5. 给每个会话算未读    │                    │
    │                      │ 6. 给每个会话读预览    │                    │
    │                      │ └─ 调 getTurns() 读    │                    │
    │                      │    workspace JSONL 日志 │                    │
    │                      │                      │                    │
    │ 7. 返回 InboxEntry[] │                      │                    │
    │ <────────────────────│                      │                    │
```

**跟场景一的区别**：
- 场景一**写**（触发 Agent 创建会话），场景二**读**（查已有会话）
- 场景一经过 Agent，场景二不经过 Agent
- 场景一的会话元数据由 harness 落到 MySQL；场景二由 `ConversationService` 从 MySQL 读出来

**注意**：`inbox()` 读的是 MySQL 的 `conversation_session` 表（会话元数据），每个会话的「最后一条消息预览」则是临时去读 workspace 里的 JSONL 日志得到的，不落库。

### 五、场景三：应用重启后恢复

这是 MySQL + AgentStateStore 存在的**核心理由**。进程重启后内存全没了，靠持久层恢复：

```
应用启动
    │
    ▼
ConversationMigrationService.@PostConstruct
    │  （仅一次：若 JPA 表为空且旧 sessions.json 存在，则迁移到 MySQL）
    ▼
ConversationService 就绪（直接查 MySQL，无内存索引要重建）
    │
    ▼
可以接客：
  • 会话列表/历史 → 从 MySQL conversation_session 读（天然跨副本共享）
  • Agent 工作记忆 → 从 AgentStateStore 读（Redis 配置下跨副本共享；内存配置下本 pod 丢失）
  • 聊天原文 → 从 workspace 的 JSONL 文件读（容器/磁盘持久）
```

**关键点**：
- **会话元数据**存在 MySQL `conversation_session`，重启不丢，且多副本天然共享
- **聊天原文**存在 workspace 的 JSONL 文件（容器磁盘或挂载卷）
- **Agent 工作记忆**存在 `AgentStateStore`：配了 `mysql,redis` profile 就在 Redis（跨副本共享），否则是内存（重启即丢、单机有效）
- 与旧架构相比，**不再有 `sessions.json` 启动加载、内存索引不同步的问题**——会话元数据直接是数据库记录

### 六、核心概念串联：三层 Key 体系

这是整个系统最容易搞混的地方。**一个会话有三个 key**，每个 key 服务于不同场景：

#### 三个 Key 是什么

| Key | 谁生成 | 格式 | 谁用 |
|---|---|---|---|
| **sessionKey** | harness / ConversationService | `main-<uuid>` 或 `subagent-<uuid>` | 后端内部存储主键（MySQL `conversation_session.session_key`） |
| **conversationId** | ChatController / 前端传入 | UUID | 前端 URL、用户可见 |
| **gateKey** | 网关 | `\|x:agentId=data-agent\|t:s1` | 网关路由用（MySQL `conversation_session.gate_key`） |

#### 三个 Key 怎么协作

```
前端（只认识 conversationId）
    │
    │ "我要看会话 s1 的历史"
    ▼
SessionController.turns(agentId, "s1", userId)
    │
    │ ① 先用 "s1" 当 sessionKey 查（findByKey）
    │   sessionRepo.findBySessionKey("s1")
    │
    │ ② 查不到？用 "s1" 当 conversationId 反查（findSessionByConversationId）
    │   遍历该用户 MAIN 会话，从 gateKey 提取 conversationId
    │   extractConversationId("|x:agentId=data-agent|t:s1") → "s1"
    │   匹配上就返回
    │
    │ ③ 找到 SessionEntry，里面有 sessionKey + sessionFilePath
    ▼
用 sessionKey / sessionFilePath 去读 workspace 的 JSONL 聊天日志
```

#### ChatController 怎么用这三个 Key

```java
// ChatController 里
String conversationId = req.sessionKey();  // 前端传来的（本质是 conversationId）
// ... 打包进 InboundMessage.accountId
chatUiChannel.dispatchStream(inbound);     // 网关算出 gateKey
// 网关内部：
//   gateKey = "|x:agentId=" + agentId + "|t:" + conversationId
//   （harness 据此路由 + 落 MySQL conversation_session.gate_key）
```

**为什么搞这么复杂**：因为同一个用户跟同一个 Agent 可以有多个会话。光靠 `(userId, agentId)` 无法区分，必须加 `conversationId`。而 `conversationId` 是前端生成的，后端要把它编进 `gateKey` 才能做路由和归属校验。

### 七、数据存在哪里（四类数据）

整个链路涉及四类数据，存在不同地方，容易搞混：

```
┌─────────────────────────────────────────────────────────────┐
│ 1. 会话元数据（MySQL: conversation_session）                  │
│    ├─ session_key, user_id, agent_id                         │
│    ├─ session_id, session_file_path                          │
│    └─ gate_key, label, last_activity_ms, kind                │
│    谁写：harness 创建会话后落到 MySQL（ConversationService 负责增删改查）│
│    谁读：ConversationService（inbox / turns / reset / delete）│
├─────────────────────────────────────────────────────────────┤
│ 2. 聊天原文（workspace JSONL 日志）                            │
│    └─ agents/{agentId}/sessions/{sessionId}.log.jsonl        │
│    谁写：harness 框架（Agent 每轮对话写入）                    │
│    谁读：ConversationService.getTurns() → SessionTurnParser 解析│
├─────────────────────────────────────────────────────────────┤
│ 3. Agent 状态（AgentStateStore：Redis db2 或内存）            │
│    └─ 对话上下文、记忆摘要、Plan、todo（按 userId+agentId）    │
│    谁写：Agent 每轮对话结束                                    │
│    谁读：Agent 每轮对话开始                                    │
├─────────────────────────────────────────────────────────────┤
│ 4. 已读状态（MySQL: conversation_read_state）                │
│    └─ 每个 (user_id, session_key) 的最后已读时间              │
│    谁写：ConversationService.markRead()                       │
│    谁读：ConversationService.inbox() 判断未读红点             │
└─────────────────────────────────────────────────────────────┘
```

**容易混淆的点**：
- **会话元数据 ≠ 聊天原文**。`conversation_session` 只存「有哪些会话」，不存对话内容；对话内容在 workspace 的 JSONL 文件
- **Agent 状态 ≠ 聊天原文**。Agent 状态是 Agent 的工作记忆（压缩后的摘要），聊天原文是完整的逐轮对话
- 重启后：会话元数据从 MySQL 恢复，聊天原文从 JSONL 读，Agent 状态取决于 AgentStateStore 后端（Redis 不丢，内存丢）

### 八、谁调谁（依赖关系图）

```
                    ┌──────────────────┐
                    │   前端浏览器      │
                    └────────┬─────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
    ┌─────────────────┐           ┌─────────────────┐
    │ ChatController  │           │SessionController│
    │  (聊天入口)      │           │  (会话管理)      │
    └────────┬────────┘           └────────┬────────┘
             │ 发消息                       │ 都调
             │ dispatchStream              ▼
             │                    ┌─────────────────────────────────┐
             │                    │ ConversationService             │
             │                    │ (JPA 会话服务层)                 │
             │                    │ ├─ inbox / getTurns             │
             │                    │ ├─ resetSessionByKey            │
             │                    │ ├─ markRead / deleteSession     │
             │                    │ └─ findByGateKey / requireOwned │
             │                    └────────────┬────────────────────┘
             │                                 │
             │                  JPA Repository │
             │                                 ▼
             │                    ┌─────────────────────────────────┐
             │                    │ MySQL                            │
             │                    │  conversation_session            │
             │                    │  conversation_read_state         │
             │                    └─────────────────────────────────┘
             ▼
    ┌─────────────────┐
    │ Harness 框架     │ ← ChatController 调 dispatchStream 时
    │ (AgentScope 网关) │   框架内部创建会话、写 JSONL、路由到 Agent
    └────────┬────────┘
             │
             │ 运行时数据
             ▼
    ┌─────────────────┐      ┌──────────────────────────────┐
    │ UserSandboxPool │      │ AgentStateStore               │
    │ (Docker 容器)    │      │ (Redis db2 / 内存)             │
    └─────────────────┘      └──────────────────────────────┘
```

**核心发现**：
1. **ConversationService 是两个 Controller 的汇聚点之一**（SessionController 直接调；ChatController 的 currentSession/reset 间接调）
2. **ConversationService 是 JPA 服务层**，不持有内存索引——所有查询直接走 MySQL（跨副本天然共享，无旧架构的内存/磁盘同步问题）
3. **ChatController 和 SessionController 互不调用**——它们是平行的两个入口
4. **harness 框架创建会话并落到 MySQL**，ConversationService 只做后续的管理（查询/重置/删除/已读）

### 九、一张图总结所有协作

```
┌────────────────────────────────────────────────────────────────────┐
│                        用户浏览器                                    │
│  ┌──────────────┐  ┌──────────────┐                                │
│  │ 聊天窗口      │  │ 会话列表侧栏  │                                │
│  └──────┬───────┘  └──────┬───────┘                                │
└─────────┼─────────────────┼────────────────────────────────────────┘
          │                 │
    POST /chat/stream  GET /sessions/inbox
          │                 │
          ▼                 ▼
┌─────────────────┐  ┌─────────────────┐
│ ChatController  │  │SessionController│
│  ├ 鉴权          │  │  ├ 鉴权          │
│  ├ 权限 guard    │  │  ├ 调 Conversation│
│  ├ 斜杠命令      │  │  └ 算未读/预览    │
│  └ 翻译 SSE     │  └────────┬────────┘
└────────┬────────┘          │
         │ dispatchStream    │ inbox/getTurns/reset/read/delete
         ▼                 │
┌─────────────────┐          │
│ Harness 框架     │          │
│ (AgentScope 网关)│          │
│  ├ 算 gateKey    │          │
│  ├ 路由到 Agent  │          │
│  └ 创建会话→MySQL│          │
│    +写 JSONL     │          │
└────────┬────────┘          │
         │                   │
         │ 落库 + 写日志       │ 查/改 MySQL
         ▼                   ▼
┌─────────────────┐  ┌──────────────────────────────────┐
│ workspace JSONL │  │ MySQL                             │
│ (聊天原文)       │  │  conversation_session             │
└─────────────────┘  │  conversation_read_state           │
                     └──────────────────────────────────┘

    运行时另一处：
┌─────────────────┐      ┌──────────────────────────────┐
│ UserSandboxPool │      │ AgentStateStore               │
│ (Docker 容器)    │      │ (Redis db2 / 内存)             │
└─────────────────┘      └──────────────────────────────┘
```

### 十、常见困惑 Q&A

#### Q1：为什么 ChatController 不直接创建会话？
**A**：因为会话创建是**网关的职责**。网关负责「把消息路由到哪个 Agent 的哪个会话」，这个路由过程会自动创建/查找会话。ChatController 只负责「打包消息丢给网关」，不关心会话怎么管理。这是**关注点分离**——ChatController 专注 HTTP 层，网关专注路由层。

#### Q2：会话元数据为什么在 MySQL，聊天原文却在 JSONL 文件？
**A**：聊天原文（完整逐轮对话，可能很长）由 harness 框架直接写到 workspace 的 JSONL 文件，不经过本项目的代码，也不适合塞进关系表。而会话「注册表」（有哪些会话、每个会话的 key / 文件路径 / 归属）需要被 `SessionController` 高效查询、过滤、分页，放在 MySQL 最合适。**职责边界**：框架管聊天原文，本项目管会话清单与索引。

#### Q3：会话是啥时候创建的？我没看到明显的 create 逻辑
**A**：会话是 **harness 框架内部自动创建**的。当 `chatUiChannel.dispatchStream(inbound)` 被调用时，框架根据 `accountId`（conversationId）找到或创建会话，把会话元数据落到 MySQL `conversation_session`，并把对话原文写入 workspace 的 JSONL 文件。这个逻辑在 AgentScope harness 框架里，不在本项目的代码里。`ConversationService` 没有 `create()`/`register()`/`add()` 方法——它是消费者/管理者，不是生产者。

#### Q4：gateKey 那个字符串格式 `|x:agentId=...|t:...` 是谁定义的？
**A**：是 AgentScope harness 框架定义的路由键。`|x:agentId=` 段表示路由到哪个 Agent，`|t:` 段表示哪个会话线程。本项目只是**解析**这个字符串（见 `ConversationSupport.extractConversationId` / `extractGatewayAgentId`），不生成它。

#### Q5：inbox 是全表扫描吗？性能如何？
**A**：`inbox()` 先按 `user_id` 查（`conversation_session` 上有 `ix_conv_session_user` 索引），在内存里按 `(userId, agentId)` 过滤再排序。中小规模完全够用。与旧架构相比，已去掉会话域的内存 ConcurrentHashMap 索引，改为直接查数据库。

#### Q6：会话重置（reset）和删除（delete）有啥区别？
**A**：
- **reset**：会话还在，只是分配新的 `sessionId`、换新的日志文件路径，Agent 记忆清空，旧的聊天原文文件不动。像「换个新本子」。
- **delete**：会话元数据从 MySQL 删除（磁盘 JSONL 日志按设计保留可追溯），像「把整个档案注销」。

#### Q7：多副本部署时，会话会串吗？
**A**：不会。会话元数据在共享 MySQL，`ConversationService` 按 `user_id + agent_id + gate_key` 过滤和归属校验（`requireOwnedSession`），天然隔离。真正需要注意隔离的是 **workspace 沙箱容器**（`UserSandboxPool` 默认内存态）和 **Agent 工作记忆**（`AgentStateStore`）——见 project-overview.md 第十六章。

### 十一、总结：一句话理解协作关系

> **ChatController 是「聊天入口」，SessionController 是「管理入口」，两者都把脏活累活丢给 ConversationService（JPA 会话服务层，数据在 MySQL）；harness 框架在背后创建会话、写 JSONL 日志、把元数据落到 conversation_session；UserSandboxPool 管 Docker 隔离，AgentStateStore 管 Agent 工作记忆。**

四个层次，一个比一个底层：
- ChatController / SessionController → **HTTP 层**（接请求）
- ConversationService → **业务层**（管会话——查询、重置、删除、已读，基于 JPA + MySQL）
- SessionEntity / SessionReadStateEntity → **持久层**（MySQL 表）
- harness 框架 → **会话生产者 + Agent 运行时**（创建会话、写日志、路由）

理解了这个层次关系，再回去看代码就清晰了。

### 附录：会话相关文件完整清单与职责

#### 第一层：数据模型（JPA 实体）

| 文件 | 职责 |
|------|------|
| `SessionEntity.java` | JPA 实体，映射 MySQL `conversation_session` 表（会话元数据） |
| `SessionReadStateEntity.java` | JPA 实体，映射 `conversation_read_state` 表（已读状态） |
| `SessionEntityRepository.java` / `SessionReadStateRepository.java` | Spring Data JPA 仓库 |

#### 第二层：业务服务（conversation/application）

| 文件 | 职责 |
|------|------|
| `ConversationService.java` | 会话核心服务：inbox / getTurns / reset / markRead / deleteSession / requireOwnedSession（归属校验）/ 维护清理 |
| `ConversationSupport.java` | 工具方法：gateKey 解析、agent 匹配、维护配置 |
| `SessionTurnParser.java` | 把 workspace 的 JSONL 日志翻译成结构化对话轮次（USER/ASSISTANT/TOOL） |
| `SessionLifecycleScheduler.java` | 定时任务：空闲重置、每日重置、定期清理 |
| `ConversationMigrationService.java` | 一次性启动迁移：旧 `sessions.json` → JPA 表（仅表空且文件存在时） |
| `UsageStore.java` | 用量统计存储 |

#### 第三层：领域模型（conversation/domain）

| 文件 | 职责 |
|------|------|
| `SessionEntry.java` | 会话档案的值对象（谁、跟谁聊、会话 key、文件路径、gateKey） |
| `SessionKind.java` | 会话类型：MAIN（主会话）vs SUBAGENT（子代理会话） |
| `SessionMaintenanceConfig.java` | 过期清理 / 总数限制配置 |
| `AgentManagerConfig.java` | 会话管理相关配置 |
| `HistoryResult.java` | 读取历史的结果包装 |

#### 第四层：Web 层（conversation/api）

| 文件 | 职责 | 对应前端页面 |
|------|------|---------------|
| `ChatController.java` | REST API：SSE 流式对话（`/stream`）、同步对话（`/send`）、会话探测（`/session`） | `/chat` 聊天页 |
| `SessionController.java` | REST API：收件箱、查看详情、重置、标记已读、删除 | 聊天页左侧会话列表 |

提供的接口（均为 `conversation/api`）：
```
POST   /api/agents/{id}/chat/stream     → SSE 流式对话（核心）
POST   /api/agents/{id}/chat/send        → 同步对话
GET    /api/agents/{id}/chat/session     → 会话探测（exists）
GET    /api/agents/{id}/sessions/inbox   → 会话收件箱
GET    /api/agents/{id}/sessions/{key}    → 对话详情
POST   /api/agents/{id}/sessions/{key}/reset → 重置会话
PATCH  /api/agents/{id}/sessions/{key}/read  → 标记已读
DELETE /api/agents/{id}/sessions/{key}   → 删除会话
```

#### 用微信类比理解

| 组件 | 对应微信的功能 | 在项目中 |
|------|--------------|---------|
| `ChatController` (stream/send) | 发消息 | **聊天能力**（harness 提供） |
| `SessionController` | 聊天列表 + 聊天记录 + 删除聊天 | **会话管理 UI** |
| `ConversationService` | 后台的聊天记录数据库 | **会话数据层**（JPA + MySQL） |
| `SessionEntity` (JPA) | 聊天记录存到云端 | **持久化层**（MySQL 表） |
| `SessionTurnParser` | 把聊天记录格式化展示 | **日志解析器** |
| `SessionLifecycleScheduler` | 自动清理过期聊天 | **定时任务** |

**如果你只关心「怎么发消息、怎么收到回复」，那确实不需要关心会话管理。但如果你想做一个完整的聊天产品（有历史记录、有聊天列表、能删除、能重置、能标已读），那就必须要有这套基础设施。**

---

## 附录 A：默认数据概览

| 数据 | 值 | 来源 |
|---|---|---|
| 默认管理员账号 | `admin` / `admin` | 初始化脚本 |
| 默认 Agent ID | `data-agent` | 前端硬编码 `ACTIVE_AGENT_ID` |
| 分析数据库 | `analytics_db` | MySQL（`data-analytics-mysql.sql` 初始化） |
| products 表 | **15** 条商品数据 | 种子数据 |
| users 表 | **20** 条用户数据 | 种子数据 |
| orders 表 | **120** 条订单数据 | 种子数据 |
| DB 表前缀 | `dataagent_` | `dataagent_agent`, `dataagent_agent_share`, `dataagent_user`, `conversation_session` |
| Redis key 前缀 | `dataagent:` | 如 `dataagent:session:{userId}:{agentId}` |
| 默认端口 | `8080` | `server.port` |
| JWT 过期时间 | Access 30min / Refresh 7d | `dataagent.jwt.expire.*` |
