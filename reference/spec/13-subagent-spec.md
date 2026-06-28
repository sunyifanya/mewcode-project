# 13-subagent-spec.md

## 背景

MewCode 目前是单 Agent 架构：所有任务在同一个对话上下文里执行。这导致两个问题：

1. **上下文污染**：长任务后再做无关任务，前序中间结果（读过的文件、diff、错误回放）成为后续任务的噪声，token 飙升、响应质量下降
2. **无法并行**：没有把独立子任务分发出去并行执行的机制，主对话被长任务阻塞

当前项目已有「子 Agent 雏形」——`SkillForkHost.runSubAgent()` 在 `AgentLoop` 中实现，能创建受限子 Agent 跑完一轮。但还缺：

- 没有统一的、可被主 Agent 主动调用的 **Agent 工具**——子 Agent 只能由 Skill fork 触发
- 没有 **角色定义文件** 加载机制——Agent 角色全部写死在 fork 闭包里
- 没有 **后台任务管理**——所有子 Agent 当前都是阻塞前台模式
- 没有 **工具过滤多层防线**——子 Agent 理论上可以无限嵌套
- Skill fork 与未来 SubAgent 工具两套代码并存

本章把上述能力补齐，让 MewCode 从单 Agent 进化到可分发任务的主从架构。

## 目标用户

使用 MewCode 处理复杂多步骤任务的开发者。典型场景：
- "帮我在整个项目里找到所有 SQL 注入风险"——开 3 个 Explore 子 Agent 并行搜索不同模块
- "这个重构涉及 5 个文件，先让 Plan Agent 出一个方案"——定义式 Agent 规划、主 Agent 执行
- "帮我把这个长任务的中间结果总结一下继续"——Fork 子 Agent 继承上下文，借 prompt cache 省钱

---

## 目标

- **G1**：提供统一的 `Agent` 工具，主 Agent 通过 `subagent_type` 参数选择预定义角色或留空走 Fork 路径；工具列表对模型始终稳定（不因角色定义增减而变化）
- **G2**：子 Agent 拥有独立的运行时状态——**消息**、**权限账本**（独立 Engine 决策状态）、**文件读缓存**、**token 计数**；共享基础设施——LLM Provider、Hook 引擎、文件系统、`ToolRegistry`
- **G3**：支持两种创建模式：
  - **定义式**：指定 `subagent_type`，从空白对话 + 预定义角色 prompt 启动
  - **Fork 式**：不指定 `subagent_type`，克隆父对话历史并注入 Fork Boilerplate，借 prompt cache 降首次请求成本
- **G4**：角色定义为 Markdown + YAML frontmatter 文件；支持多来源加载，优先级：项目级 > 用户级 > 内置级 > 插件级（本期插件级恒为空）；同名定义按 source 优先级覆盖，前者覆盖后者
- **G5**：子 Agent 以 **RunToCompletion** 模式执行——任务直接注入对话，模型不再调工具即结束，返回最后一条 assistant 文本作为结果
- **G6**：子 Agent 在工具调用时遇到权限判定，按 **三层升级链** 处理：① 父对话已批准账本 → ② 角色 frontmatter 的 `permissionMode` 兜底 → ③ 仍无法决定时升级到主 TUI 询问用户（子 Agent 暂停、用户响应、子继续）；升级弹窗超时 60 秒自动 DenyOnce
- **G7**：支持后台任务：三种进入方式——① 显式 `run_in_background:true`、② 前台超时 120 秒自动切后台、③ ESC 手动切后台；Fork 路径无条件后台；Fork Boilerplate 注入到子 Agent 首条消息约束其行为
- **G8**：后台任务跑完通过 `<task-notification>` 自动注入主对话（主 Agent 下次 turn 即看到）；主 Agent 可通过 `TaskList`/`TaskGet`/`TaskStop` 工具主动查询和操控，可通过 `SendMessage` 给已跑完的、仍存活的后台 Agent 续派任务
- **G9**：工具过滤多层防线阻断子 Agent 无限嵌套——定义式子 Agent 从工具列表里直接剔除 `Agent` 工具（全局禁止）；Fork 式子 Agent 保留 Agent 工具但靠运行时拦截（QuerySource 检测 + Boilerplate 标记扫描）；后台白名单限制后台 Agent 工具范围
- **G10**：复用 SubAgent 底座统一 Skill fork 路径——`AgentLoop.runSubAgent()` 改为调用 SubAgent 公共启动函数，两条路径走同一段 Agent 构造逻辑
- **G11**：内置 3 个角色——`general-purpose`（全工具）、`explore`（只读探索，haiku）、`plan`（只读规划）；插件级保留接口占位但本期不实现真插件加载

---

## 功能需求

### Agent 工具

- **F1**：新建 `Agent` 工具类，实现 `Tool` 接口，注册到 `ToolRegistry`。参数（JSON Schema）：
  - `description`（string，必填）：3-5 词任务简述，供 UI 进度展示
  - `prompt`（string，必填）：交给子 Agent 的详细任务指令
  - `subagent_type`（string，可选）：指定预定义角色名，留空时走 Fork 路径
  - `model`（string，可选）：模型覆盖，取值 `haiku` / `sonnet` / `opus` / `inherit`；留空沿用 Agent 定义的 model
  - `run_in_background`（bool，可选）：true 时强制后台启动；Fork 路径忽略此字段（无条件后台）
  - `name`（string，可选）：给本次启动的子 Agent 命名，供 `SendMessage` 用；同名后启动的覆盖前面的弱引用
- **F2**：Agent 工具的 `execute` 逻辑：
  - `subagent_type` 非空：`catalog.resolve(name)` 取定义；不存在则返回结构化错误 `"未知 subagent_type: X"`，含可用列表
  - `subagent_type` 为空：走 Fork 路径，内部使用硬编码的 `_fork` 角色定义（maxTurns=50, permissionMode=default, model=inherit）
  - 按 `run_in_background` 与 Fork 强制规则，选择 inline 跑（阻塞返回 finalText）或 background 跑（返回 `{task_id, status:"async_launched"}`）
  - Fork 路径返回 `{task_id, status:"async_launched"}`（因为 Fork 无条件后台）
- **F3**：定义式子 Agent 从工具列表中剔除 `Agent` 工具（`ALL_AGENT_DISALLOWED_TOOLS`），从根源上断绝嵌套。Fork 式子 Agent **保留** Agent 工具（继承自父），但调用时被运行时拦截（见 F24）

### Agent 定义文件

- **F4**：Agent 定义文件是 Markdown，以 `---` frontmatter 块开头、紧跟正文（子 Agent 系统提示）。frontmatter YAML 字段：
  - `name`（必填）：角色名，小写字母/数字/连字符，长度 1-32
  - `description`（必填）：一句话描述，用于 Agent 工具的文档与 UI 列表
  - `tools`（可选，string array）：工具白名单；空列表或不存在 = 不限制（所有通过其他层过滤的工具都可用）
  - `disallowedTools`（可选，string array）：工具黑名单
  - `model`（可选）：`haiku` / `sonnet` / `opus` / `inherit`，缺省 `inherit`
  - `maxTurns`（可选，int）：最大迭代轮数，缺省 0 表示继承全局 `maxIterations`（当前默认 25）
  - `permissionMode`（可选）：`default` / `acceptEdits` / `plan` / `bypassPermissions` / `dontAsk`，缺省 `default`。`dontAsk` 是子 Agent 专属——自动批准所有规则未命中的工具（黑名单和沙箱仍然拦截）
  - `background`（可选，bool）：缺省 false；true 时 Agent 工具忽略 `run_in_background` 参数、强制后台
- **F5**：Catalog 三层加载（本期插件级恒为空），顺序：
  1. 内置级：classpath resource `subagent/builtin/*.md`（`Class.getResourceAsStream`）
  2. 用户级：`~/.mewcode/agents/*.md`
  3. 项目级：`<projectRoot>/.mewcode/agents/*.md`
  加载：先加载内置，再用户级覆盖同名，最后项目级覆盖同名。`resolve(name)` 返回优先级最高的版本。
- **F6**：同名定义按 source 优先级覆盖——项目级 > 用户级 > 内置级；`resolve(name)` 返回优先级最高的版本
- **F7**：Catalog 启动期加载，加载失败的单个文件（frontmatter 不合法、name 缺失、字段类型错误）走 stderr 警告并跳过，不阻断启动。内置定义 classpath 资源解析失败则抛 `RuntimeException`（代码 bug，fail-fast）
- **F8**：本期不引入插件加载器——`SourcePlugin` 常量保留供未来扩展；加载顺序里第四层恒为空 List

### 子 Agent 运行时

- **F9**：扩展 `AgentLoop` 增加 `runToCompletion(conv, task, spec) -> String` 方法：
  - 把 `task` 作为 user 消息追加到 conv
  - 进入 ReAct 循环，maxTurns 由 spec 的 `maxTurns` 决定（0 时用默认 25）
  - 模型不再调工具时结束循环，取末尾 assistant 文本返回
  - 触达 maxTurns 时返回最后一条 assistant 文本 + `"[达到最大轮数]"` 后缀
  - 同一段循环代码与主对话 `run()` 共用，不重复实现
- **F10**：子 Agent 构造支持参数化：
  - `systemPrompt(text)`：子 Agent 启动时把 text 作为系统提示注入（覆盖默认 MewCode 主 Agent 系统提示）
  - `provider(p)`：让子 Agent 用与父不同的 provider（model 覆盖时切换）
  - `maxTurns(n)`：限制本 Agent 的最大迭代轮数
  - `permissionMode(m)`：子 Agent 启动模式
  - `toolFilter(predicate)`：工具名称过滤器
  - `replacementStateCopy(parent)`：继承父的替换状态（Fork 用，保持 prompt cache 兼容）
- **F11**：子 Agent 的运行时状态隔离——独立 `ConversationManager`、独立 token 计数、独立 `RecoveryState`；但共享 `LLMProvider`（除非 `provider()` 覆盖）、`ToolRegistry`（过滤后副本）、`PermissionChecker`（引用传递但决策隔离）、`HookEngine`

### 权限决策

- **F12**：子 Agent 工具调用权限决策三层链：
  1. **父对话已批准账本**：父 `PermissionChecker` 已经 `ALLOW_ALWAYS` 过的精确规则匹配 → Allow
  2. **子角色 `permissionMode` 兜底**：`dontAsk` 模式直接放行所有 Allow/Ask 类规则未命中的；`acceptEdits` 放行写操作；`bypassPermissions` 全 Allow（黑名单/沙箱仍拦截）；其他模式仍走原 `modeFallback`
  3. **三层之外仍是 Ask**：升级到主 TUI——子 Agent 暂停，主 TUI 弹审批框（标注 `[来自 SubAgent X]`），用户响应后子 Agent 继续。弹窗超时 60 秒自动 DenyOnce
- **F13**：升级到主 TUI 的通信机制——子 Agent 通过 `waitForPermission` 机制阻塞等待（复用现有的 `permissionResponseQueue`），主 TUI 在事件循环中检测子 Agent 的权限请求并弹窗。弹窗显示：子 Agent 名称、任务描述、工具名和参数、触发审批的原因。主 Agent 在此期间被阻塞（因为它在等 Agent 工具的 tool_result 返回）。格式示例：`[SubAgent Explore] 请求执行 Bash: "rm -rf /tmp/cache" — 需要你的批准 [Y] 本次放行 [A] 总是放行 [N] 拒绝`
- **注**：Fork Boilerplate 里说"不要提问"指的是不要让模型生成向用户提问的自然语言，不是禁止权限系统弹窗。权限弹窗升级是系统级行为，子 Agent 暂停等待用户响应，用户批准/拒绝后子 Agent 继续。

### 后台任务管理

- **F14**：新建 `task/TaskManager` 类，持有 `Map<String, BackgroundTask>`，提供：
  - `launch(agent, conv, task, spec) -> taskId`
  - `get(id)` / `list()` / `stop(id)`
  - `adoptRunning(agent, conv, cancelFlag, eventQueue, partialOutput)` — 前台切后台的接管入口
  - `drainNotifications() -> List<TaskNotification>` — 排出已完成通知供主对话注入
- **F15**：`BackgroundTask` 字段：
  - `id`（String，manager 生成，格式 `task_N`）
  - `name`（String，F1 的 `name` 字段，可选）
  - `spec`（SubAgentSpec，关联的角色定义）
  - `status`（enum：`PENDING` / `RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`）
  - `result`（String，跑完后的最终文本）
  - `error`（String，失败时的错误描述）
  - `startTime` / `endTime`（Instant）
  - `cancelFlag`（`volatile boolean`，虚线程 cancel 钩子）
  - `toolCount`（int，工具调用次数计数器）
  - `thread`（Thread，虚线程引用，供 cancel）
- **F16**：`launch` 内部在 virtual thread 中执行：`agent.runToCompletion(ctx, conv, task)` → status 终态 → 推 `TaskNotification` 到通知队列 → 主 Agent 下次 turn 消费并注入 `<task-notification>`
- **F17**：三种进入后台的方式：
  1. **显式**：Agent 工具 `run_in_background:true` → 直接调 `launch`，工具 result 立刻返回 `{task_id, status:"async_launched"}`
  2. **超时自动**：Agent 工具默认前台 inline 跑，但前台 run 启动后开计时器（120 秒，常量 `AUTO_BACKGROUND_MS`），超时则：取消前台事件订阅 → 调 `manager.adoptRunning(...)` 接管事件流继续后台跑 → Agent 工具 result 改返回 `{task_id, status:"timed_out_to_background"}`
  3. **ESC 手动切**：用户在前台子 Agent 跑动期间按 ESC → TUI 调 `manager.adoptRunning(...)`，与超时路径走同一逻辑
- **F18**：Fork 路径 `run_in_background` 字段被强制视为 true（代码内 override）——Fork 无条件后台执行
- **F19**：后台任务完成时，Manager 把 `TaskNotification` 加入队列；主 Agent 下次 `AgentLoop` 迭代开始时调用 `drainNotifications()` 获取，把如下文本作为 system reminder 拼到主对话（不打断当前对话、对模型可见但对用户不直接显示为消息）：
  ```
  <task-notification>
  Task task_3 (name="search-sql"): completed
  Result: <最终文本，截断到 2000 字符>
  </task-notification>
  ```

### Fork 路径专用规则

- **F20**：`buildForkedConversation(parentConv, task)` 做三件事：
  1. 深拷贝 parentConv 的全部消息
  2. 把末尾 assistant 中未完成的 `tool_use`（无对应 tool_result）包装为 placeholder tool_result 消息，使消息格式合法
  3. 在末尾追加 user 消息，内容 = Fork Boilerplate + 任务文本
- **F21**：Fork Boilerplate 是一段 `<fork_boilerplate>` 包裹的指令，核心约束：
  - 不能再 Fork
  - 不要对话/提问/请求确认（指不要生成自然语言问题——权限弹窗升级是系统行为，不受此限）
  - 直接使用工具
  - 严格限制在分配的任务范围内
  - 最终报告以 `Scope:` 开头，500 字以内
- **F22**：Fork 子 Agent 嵌套阻断三道闸：
  1. **工具列表层**：Fork 子 Agent 的工具列表**保留** Agent 工具（继承自父），不通过 F3 全局禁止删除
  2. **QuerySource 检测**：Agent 工具入口检测 caller 来源，若 caller 是 Fork 路径产生，直接返回 `isError=true` 含 `"Fork 子 Agent 不能再启动 Agent"`
  3. **Boilerplate 标记扫描**：对话历史里如果含 `<fork_boilerplate>` 标记（QuerySource 失效兜底），也认定是 Fork 嵌套并拦截
- **F23**：定义式子 Agent 不走 Boilerplate（从空白启动）；嵌套阻断靠 F3 全局禁止 Agent 工具

### 工具过滤多层防线

- **F24**：全局禁止列表 `ALL_AGENT_DISALLOWED_TOOLS = ["Agent"]`（本期范围最小，后续可加 `AskUserQuestion` / `TaskStop`）。仅对**定义式**子 Agent 生效（从工具列表中剔除 `Agent`）
- **F25**：自定义 Agent 额外限制 `CUSTOM_AGENT_DISALLOWED_TOOLS`：本期为空，接口预留（用于将来用户自定义 Agent 一律不可访问某些核心工具）
- **F26**：后台 Agent 白名单 `ASYNC_AGENT_ALLOWED_TOOLS`，只列基础工具：`ReadFile`、`WriteFile`、`EditFile`、`Glob`、`Grep`、`Bash`、`WebSearch`、`WebFetch`、`Skill`、`ToolSearch`、`NotebookEdit`，以及所有 MCP 工具（`mcp__` 前缀）。后台执行的**定义式**子 Agent 工具集叠加此白名单交集
- **F27**：**Fork 子 Agent 不受 F26 后台白名单限制**——Fork 完整继承父工具集（目的就是让子 Agent 拥有和父相同的能力范围），行为约束靠 Fork Boilerplate 指令而非硬切工具
- **F28**：Agent 定义层 `tools`（白名单）与 `disallowedTools`（黑名单）组合应用——白名单先确定范围，黑名单再排除；空白名单 = 不限制
- **F29**：工具过滤合并执行顺序（在 Agent 工具的 `execute` 内，子 Agent 构造时）：
  1. 起点 = registry 的全部工具
  2. 如果是**定义式**子 Agent → 去掉 `ALL_AGENT_DISALLOWED_TOOLS`（Fork 跳过此步）
  3. 如果是**后台**且**非 Fork** → 取交集 `ASYNC_AGENT_ALLOWED_TOOLS`
  4. 应用定义的 `disallowedTools` 黑名单
  5. 应用定义的 `tools` 白名单（空白名单 = 不再收窄）
  6. 注入到子 Agent 的 `toolFilter`
- **F30**：工具列表对模型稳定——以上过滤只发生在子 Agent 构造时，主 Agent 看到的工具列表不变（Agent 工具始终在主 Agent 的工具列表中）

### Fork 内部角色定义

- **F31**：`_fork` 是内部硬编码角色，不走 classpath 资源文件（不可被用户定义覆盖），字段：

  | 字段 | 值 |
  |------|-----|
  | name | `_fork` |
  | description | "Forked worker process" |
  | tools | 空（不限制，继承父） |
  | disallowedTools | 空 |
  | model | `inherit` |
  | maxTurns | 50 |
  | permissionMode | `default` |

  Fork 不暴露在 `subagent_type` 的枚举列表里给模型选，只在 `subagent_type` 留空时内部使用。

### 内置角色与 Skill fork 改造

- **F32**：内置 3 个角色文件，作为 classpath resource 打入 jar（`subagent/builtin/`）：
  - `general-purpose.md`：`disallowedTools: []`（不限制），`model: inherit`，`maxTurns: 30`，`permissionMode: default`
  - `explore.md`：`disallowedTools: [WriteFile, EditFile]`，`model: haiku`，`maxTurns: 30`，`permissionMode: default`
  - `plan.md`：`disallowedTools: [Agent, WriteFile, EditFile]`，`maxTurns: 15`，`permissionMode: plan`
- **F33**：Skill fork 改造——`AgentLoop.runSubAgent()`（`SkillForkHost` 接口实现）改为：
  1. 构造一个临时 `SubAgentSpec`（`name="skill-fork-<skillname>"`，`disallowedTools` 取 skill 的 allowedTools 反向或限制），走 Fork 路径
  2. 复用 SubAgent 的工具过滤、消息装填、Agent 构造路径
  3. 返回 finalText 行为不变（`SkillExecutor.executeFork` 的调用方不受影响）

---

## 非功能需求

- **N1**：工具列表稳定——主 Agent 看到的工具集不因 `.mewcode/agents/` 增减或 Agent 工具被调用而变化（防止 prompt cache 抖动）
- **N2**：Fork 路径首次请求命中 prompt cache——`buildForkedConversation` 拼接的消息列表与父对话末尾完全一致，`ContentReplacementState` 通过 `copy()` 传递给子 Agent 保持 tool_use_id 决策一致
- **N3**：子 Agent 崩溃不影响主程序——`TaskManager.launch` 的 virtual thread 包 try/catch，任何 `Throwable` 转 `status=FAILED` + 错误信息回灌为 `<task-notification>`
- **N4**：启动期 fail-fast——内置定义 classpath 资源解析失败立刻抛 `RuntimeException`（代码 bug），用户/项目级定义文件解析失败仅 stderr 警告并跳过
- **N5**：与现有 ch11 Skill 系统、ch12 Hook 系统、ch08 权限系统、ch03 Agent Loop 协同，不破坏既有测试
- **N6**：配置 `subagent.background.enabled`（boolean，默认 true）关闭后，Agent 工具的 `run_in_background:true` / 超时切后台 / ESC 切后台全部失效，所有 SubAgent 强制前台同步；Fork 路径在此模式下报错 `"后台禁用，无法 Fork"`
- **N7**：`<task-notification>` 注入主对话不消耗主 Agent 的工具调用配额，不出现在用户视窗（只对模型可见），会出现在 session JSONL 持久化中
- **N8**：子 Agent 运行时文件读操作共享父 Agent 的 `RecoveryState`（通过引用传递），以便 Layer 2 压缩时子 Agent 读过的文件也能被快照恢复

---

## Out of Scope

- Worktree 文件隔离（独立章节）
- 多 Agent 团队编排（CrewAI / AutoGen 平等协作风格）
- 后台任务跨会话持久化——主程序退出后任务全部丢失
- 真正的插件系统（`SourcePlugin` 占位）
- 子 Agent 输出 schema 强制结构化（返回纯文本即可）
- Verification Agent 内置开关（`enableVerificationAgent` 不实现）
- `TaskCreate` 工具（Hook 触发用，本期仅实现 List/Get/Stop/SendMessage）
- 跨 SubAgent token 用量汇总到 `/status`（只在 TaskManager 内部记录）
- Fork 子 Agent 之间的相互通信（Fork 之间独立，只与主 Agent 单向通信）
- 子 Agent 的 `/clear` 和 `/compact` 命令支持

---

## 验收标准

- **AC1**：`Agent` 工具注册成功，主 Agent 的工具列表里 schema 一致；定义式子 Agent 看不到 `Agent` 工具
- **AC2**：`Agent` 工具调用 `{prompt:"在 src/ 找所有 TODO", subagent_type:"explore"}` 时，主 Agent 看到的 tool_result 是 explore 子 Agent 的最后一条 assistant 文本
- **AC3**：`Agent` 工具调用 `{prompt:"...", subagent_type:"non-existent"}` 时，tool_result 是结构化错误含 `"未知 subagent_type"` 和可用类型列表
- **AC4**：`Agent` 工具调用不传 `subagent_type` 时，子 Agent 收到的首条 user 消息以 `<fork_boilerplate>` 起头
- **AC5**：Fork 子 Agent 的工具列表里仍有 `Agent` 工具（F22），但调用 Agent 工具会被 QuerySource 拦截，tool_result 含 `"Fork 子 Agent 不能再启动 Agent"`
- **AC6**：定义式子 Agent 的工具列表里没有 `Agent` 工具（被 F24 全局禁止剔除）
- **AC7**：子 Agent 角色 frontmatter 写 `permissionMode: dontAsk`，Bash 等需要 Ask 的工具直接放行，无审批弹窗
- **AC8**：子 Agent 角色 frontmatter 不写 `dontAsk`，Bash 工具触发审批，弹窗带 `[来自 SubAgent X]` 标识
- **AC9**：`run_in_background:true` 时 tool_result 立即返回含 `task_id` 和 `status:"async_launched"`，主 Agent 不阻塞
- **AC10**：前台子 Agent 跑超过 120 秒，自动切后台，主 Agent 看到 tool_result 含 `status:"timed_out_to_background"`
- **AC11**：前台子 Agent 跑动期间用户按 ESC，切到后台，TUI 继续接收主 Agent 输入
- **AC12**：后台子 Agent 跑完，主 Agent 下次 run 的 reminder 区出现 `<task-notification>` 块，含 result
- **AC13**：`TaskList` 工具返回当前后台任务列表，字段含 id/name/status/tool_count
- **AC14**：`TaskGet({task_id})` 返回 result；`TaskStop({task_id})` 触发取消，任务 status 变 CANCELLED
- **AC15**：`SendMessage({name, message})` 让一个仍存活的后台 Agent 接到新任务并重新跑动，跑完结果作为新 `<task-notification>` 注入主对话
- **AC16**：项目级 `.mewcode/agents/explore.md` 覆盖内置 `explore`，`resolve("explore")` 返回项目级版本
- **AC17**：Skill fork 模式调用走 SubAgent 底座——`AgentLoop.runSubAgent()` 内部只是装饰参数后调用 SubAgent 公共函数
- **AC18**：N6 配置开关 `subagent.background.enabled: false` 时，Fork 路径调用 Agent 工具返回结构化错误
- **AC19**：`<fork_boilerplate>` 出现在对话历史里 + Agent 工具被调用 → 拦截（QuerySource 失效兜底）
- **AC20**：子 Agent throw → status=FAILED，主 Agent 收到 `<task-notification>` 含错误描述，主程序不崩
- **AC21**：项目级自定义 Agent（`.mewcode/agents/custom.md`）被 Catalog 加载；`subagent_type=custom` 调用时，frontmatter 的 `disallowedTools` / `permissionMode` / `maxTurns` / `systemPrompt` 全部生效
- **AC22**：Agent 定义 frontmatter 的非法字段（unknown model / unknown permissionMode）在加载时 stderr 警告并 fallback 到默认值（model→inherit，mode→default），MewCode 不阻断启动，该 Agent 仍可被 resolve 与调用
- **AC23**：Fork 路径子 Agent 的工具集 = 父工具列表（完整继承），不受后台白名单（F26）限制
- **AC24**：配置 `subagent.max_turns`（默认 25）控制定义式子 Agent 的默认最大轮数，frontmatter `maxTurns` 非 0 时覆盖全局配置
