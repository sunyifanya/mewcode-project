# 13-subagent-tasks.md

## 任务概览

| # | 任务 | 依赖 | 影响文件 |
|---|------|------|---------|
| 1 | 数据模型：SubAgentSpec + 相关 record | 无 | 新建 `subagent/` 包 |
| 2 | 内置 Agent 定义文件 | 1 | 新建 `src/main/resources/subagent/builtin/*.md` |
| 3 | AgentLoader：YAML 解析 + 三层加载 | 1, 2 | `subagent/AgentLoader.java` |
| 4 | ToolFilter：多层防线 | 1 | `subagent/ToolFilter.java` |
| 5 | TaskManager + BackgroundTask | 1 | `task/TaskManager.java` |
| 6 | AgentLoop 扩展：runToCompletion | 1, 4 | `agent/AgentLoop.java` |
| 7 | Fork 对话构建器 | 1 | `subagent/ForkBuilder.java` |
| 8 | 后台任务工具：TaskList/Get/Stop/SendMessage | 5 | `tool/impl/TaskListTool.java` 等 |
| 9 | 子 Agent 权限升级链 | 6 | `agent/AgentLoop.java`, `permission/PermissionChecker.java` |
| 10 | AgentTool 实现 | 1, 3, 4, 5, 6, 7, 8 | `subagent/AgentTool.java` |
| 11 | Skill fork 改造 | 10 | `agent/AgentLoop.java`, `skill/SkillForkHost.java` |
| 12 | 配置 + 主流程接入 | 10, 11 | `config/AppConfig.java`, `MewCode.java` |
| 13 | 端到端验证 | 12 | 无新建文件 |

---

## 任务 1：数据模型

**影响文件**：新建 `src/main/java/com/mewcode/subagent/SubAgentSpec.java`、`AgentDefinition.java`、`AgentCatalog.java`；新建 `src/main/java/com/mewcode/task/TaskNotification.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/SubAgentSpec.java`（完整 record 定义）
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/SubAgentProgress.java`（进度 record）
- 现有 record 模式见 `src/main/java/com/mewcode/hook/HookConfig.java`（Jackson 注解风格）

**工作内容**：

1. 创建 `SubAgentSpec` record：
   - 字段：`name`、`description`、`tools`（`List<String>`）、`disallowedTools`（`List<String>`）、`systemPromptOverride`（`String`，可为 null）、`maxTurns`（`int`）、`model`（`String`，可为 null）、`permissionMode`（`String`，可为 null）、`background`（`boolean`）
   - 静态常量 `GENERAL_PURPOSE`、`PLAN`、`EXPLORE`、`FORK`
   - `FORK` 按 spec F31 硬编码：maxTurns=50, permissionMode="default", model="inherit"

2. 创建 `AgentDefinition` record（加载期的中间表示，frontmatter 映射用）：
   - 字段与 `SubAgentSpec` 一致但不做默认值填充
   - 提供 `toSpec()` 方法做默认值填充和合法性校验

3. 创建 `AgentCatalog` 类：
   - `Map<String, AgentDefinition> agents` + `resolve(name) -> SubAgentSpec`
   - `register(definition)` — 同名覆盖
   - `listNames() -> List<String>` — 返回排序后的名称列表

4. 创建 `TaskNotification` record（`task/TaskNotification.java`）：
   - 字段：`taskId`、`name`、`status`（enum：`RUNNING`/`COMPLETED`/`FAILED`/`CANCELLED`）、`output`

**验收**：编译通过，`SubAgentSpec.FORK.name()` 返回 `"_fork"`，`SubAgentSpec.GENERAL_PURPOSE.maxTurns()` 返回 `30`

---

## 任务 2：内置 Agent 定义文件

**影响文件**：新建 `src/main/resources/subagent/builtin/general-purpose.md`、`explore.md`、`plan.md`

**参考资料**：
- 参考代码 `D:/mewcode-java/java` 中 `SubAgentSpec.java` 第 61-91 行（内置 SP 常量内容）
- 现有 classpath 资源加载模式见 `src/main/java/com/mewcode/config/ConfigLoader.java`（`loadFromClasspath`）

**工作内容**：

1. 创建 `src/main/resources/subagent/builtin/general-purpose.md`：
   ```yaml
   ---
   name: general-purpose
   description: 通用子 Agent，拥有完整工具访问权限，适合多步骤研究和实现任务
   tools: []
   disallowedTools: []
   model: inherit
   maxTurns: 30
   permissionMode: default
   ---
   # 身份
   你是 MewCode 的一个子 Agent。主 Agent 委派了一个具体任务给你。
   
   # 工作方式
   1. 仔细阅读任务描述，确保理解目标
   2. 使用工具直接完成任务——读文件、搜索代码、做修改
   3. 不需要向用户提问或请求确认——直接执行
   4. 任务完成后给出简洁的总结
   
   # 限制
   - 只能在任务范围内工作
   - 不能启动新的子 Agent
   - 不要做任务之外的事
   ```

2. 创建 `explore.md`（model: haiku, disallowedTools: [WriteFile, EditFile], maxTurns: 30）

3. 创建 `plan.md`（disallowedTools: [Agent, WriteFile, EditFile], maxTurns: 15, permissionMode: plan）

4. 验证 `Class.getResourceAsStream("/subagent/builtin/general-purpose.md")` 可读（单元测试）

**验收**：编译后 classpath 中可找到 3 个 `.md` 文件，内容含 frontmatter 和正文

---

## 任务 3：AgentLoader — YAML 解析 + 三层加载

**依赖**：任务 1、2

**影响文件**：新建 `src/main/java/com/mewcode/subagent/AgentLoader.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/AgentLoader.java`（完整加载逻辑，142 行）
- 现有 YAML 解析模式见 `src/main/java/com/mewcode/config/ConfigLoader.java`（Jackson `ObjectMapper` + `YAMLFactory`）
- 现有文件扫描模式见 `src/main/java/com/mewcode/skill/SkillCatalog.java`（`DirectoryStream` + `*.md` 过滤）

**工作内容**：

1. `loadBuiltins()`：从 classpath `subagent/builtin/` 扫描所有 `*.md`（用 `ClassLoader.getResources`），逐个 `parseAgentFile()` 解析，注册到 `AgentCatalog`

2. `loadDir(Path dir)`：扫描目录下 `*.md` 文件（`DirectoryStream`），逐个 `parseAgentFile()` 解析，注册到 `AgentCatalog`。目录不存在则静默跳过

3. `loadAll(Path projectRoot) -> AgentCatalog`：
   - 第一步：`loadBuiltins()` → catalog
   - 第二步：`loadDir(Paths.get(userHome, ".mewcode", "agents"))` → 覆盖同名
   - 第三步：`loadDir(projectRoot.resolve(".mewcode/agents"))` → 覆盖同名
   - 返回 catalog

4. `parseAgentFile(Path)`：读取文件内容，按 `---` 分割 frontmatter 和正文；用 SnakeYAML 或 Jackson YAML 解析 frontmatter 为 `Map`；构造 `AgentDefinition`；校验：
   - `name` 必填，格式 `[a-z0-9-]{1,32}`
   - `description` 必填
   - `model` 若填，必须在 `[haiku, sonnet, opus, inherit]` 内
   - `permissionMode` 若填，必须在 `[default, acceptEdits, plan, bypassPermissions, dontAsk]` 内
   - `tools` 和 `disallowedTools` 若填，必须是字符串数组
   - 校验失败 → stderr 警告，跳过该文件；`name` 缺失 → 跳过

**验收**：启动期加载内置 3 个定义；`~/.mewcode/agents/test.md` 存在时覆盖同名内置定义；项目级 `.mewcode/agents/test.md` 再覆盖用户级

---

## 任务 4：ToolFilter — 多层防线

**依赖**：任务 1

**影响文件**：新建 `src/main/java/com/mewcode/subagent/ToolFilter.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/ToolFilter.java`（完整过滤逻辑，143 行）
- 现有 `ToolRegistry` 见 `src/main/java/com/mewcode/tool/ToolRegistry.java`
- 现有 `Tool` 接口见 `src/main/java/com/mewcode/tool/Tool.java`

**工作内容**：

1. 定义常量集合：
   - `ALL_AGENT_DISALLOWED_TOOLS = Set.of("Agent")`
   - `CUSTOM_AGENT_DISALLOWED_TOOLS = Set.of()`（预留）
   - `ASYNC_AGENT_ALLOWED_TOOLS = Set.of("ReadFile", "WriteFile", "EditFile", "Glob", "Grep", "ExecuteCommand", "WebSearch", "WebFetch", "Skill", "ToolSearch", "NotebookEdit")`

2. 实现 `buildFilter(spec, isAsync, isFork) -> Predicate<String>`：
   - 返回一个 Predicate，用于 `ToolRegistry` 的 `getFilteredTools` 或传给 AgentLoop 的 toolFilter
   - 执行顺序按 F29：
     1. 非 Fork → 排除 `ALL_AGENT_DISALLOWED_TOOLS`
     2. 后台且非 Fork → 与 `ASYNC_AGENT_ALLOWED_TOOLS` 取交集（MCP 工具 `mcp__` 前缀始终放行）
     3. 应用 spec.disallowedTools 黑名单
     4. 应用 spec.tools 白名单（空 = 不限制）
     5. MCP 工具（`mcp__` 前缀）始终放行（除非被黑名单明确排除）

3. 静态辅助方法：`isMcpTool(name)`、`isAsyncAllowed(name)`

**验收**：define 式 explore Agent 走过滤后看不到 `WriteFile` 和 `Agent`；Fork 式可以看到所有工具（包括 `Agent`）；后台 define 式只看到基础工具

---

## 任务 5：TaskManager + BackgroundTask

**依赖**：任务 1

**影响文件**：新建 `src/main/java/com/mewcode/task/TaskManager.java`、`BackgroundTask.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/SubAgentTaskManager.java`（完整实现，195 行）
- `D:/mewcode-java/java/src/main/java/com/mewcode/toolresult/ContentReplacementState.java` 的 `copy()` 方法

**工作内容**：

1. 创建 `BackgroundTask` 类：
   - 字段按 F15：id、name、spec、status（enum）、result、error、startTime、endTime、cancelFlag（`volatile boolean`）、toolCount、thread、agent

2. 创建 `TaskManager` 类：
   - `Map<String, BackgroundTask> tasks`（`LinkedHashMap` 保持插入顺序）
   - `createTask(name, spec) -> String`：生成 ID `task_N`（`AtomicInteger` 自增），创建 `BackgroundTask`
   - `setRunning(id, thread)` / `setCompleted(id, output)` / `setFailed(id, error)` / `cancelTask(id)`
   - `getTask(id) -> BackgroundTask`
   - `listTasks() -> List<BackgroundTask>`：返回非 Terminated 状态的任务列表
   - `drainNotifications() -> List<TaskNotification>`：排出并清空通知列表

3. 实现 `launch(agent, conv, task, spec, provider, replacementState) -> String`（taskId）：
   - 在 virtual thread 中执行：`agent.runToCompletion(conv, task, spec)` 
   - try/catch 包裹：任何 `Throwable` → `setFailed(id, errorMsg)`
   - 完成后 `setCompleted(id, output)`

4. 实现 `adoptRunning(agent, conv, cancelFlag, partialOutput) -> String`（taskId）：
   - 前台切后台的接管入口：创建 task、设置 RUNNING、接管 agent 的 virtual thread

5. 实现 `findByName(name) -> BackgroundTask`：按 name 查找仍存活的任务

**验收**：`launch` 返回 taskId，`getTask(taskId)` 返回对应任务；`drainNotifications` 初始化返回空列表

---

## 任务 6：AgentLoop 扩展 — runToCompletion

**依赖**：任务 1、4

**影响文件**：修改 `src/main/java/com/mewcode/agent/AgentLoop.java`；可选新建 `src/main/java/com/mewcode/subagent/SubAgentRunner.java`

**参考资料**：
- 现有 `AgentLoop.run()` 方法（第 395-729 行）——子 Agent 复用同一段 ReAct 循环逻辑
- 现有 `SkillForkHost.runSubAgent()` 方法（第 248-357 行）——当前的 fork 子 Agent 实现，是 runToCompletion 的原型
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/agent/Agent.java` 第 98-108 行（`run(conv)` 方法，返回 `BlockingQueue<AgentEvent>`）

**工作内容**：

1. 在 `AgentLoop` 中新增构造函数/Builder 模式（或新增工厂方法）：
   - `withSystemPrompt(String prompt)`：子 Agent 启动时注入自定义系统提示（覆盖默认）
   - `withMaxTurns(int n)`：限制最大迭代轮数
   - `withProvider(LLMProvider p)`：切换 provider
   - `withPermissionMode(PermissionMode m)`：切换权限模式
   - `withReplacementState(ContentReplacementState parent)`：继承父的替换状态

2. 实现 `runToCompletion(ConversationManager conv, String task, SubAgentSpec spec) -> String`：
   - 如果 `spec.systemPromptOverride()` 非空，注入为 system reminder 到 conv
   - 追加 `task` 作为 user 消息
   - 调用 `run()` 的 ReAct 循环逻辑（抽取公共方法或复用现有 `run()` 的 virtual thread 模式）
   - 收集所有 `TEXT_DELTA` 事件的文本
   - 当 `LOOP_FINISHED` 或 `CANCELLED` 或 `ERROR` → 返回最终文本
   - 超时处理：120 秒无事件 → 返回超时通知

3. 选项 B（推荐）：抽取 `AgentLoop` 中的 ReAct 循环核心为 `doAgentLoop(conv, eventQueue, ...) -> void` 私有方法，`run()` 和 `runToCompletion()` 都调用它，区别在于构造 conv 和 event 消费方式

**验收**：`new AgentLoop(...).runToCompletion(conv, "在 src/ 里找 TODO", exploreSpec)` 返回包含搜索结果的文本

---

## 任务 7：Fork 对话构建器

**依赖**：任务 1

**影响文件**：新建 `src/main/java/com/mewcode/subagent/ForkBuilder.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/AgentTool.java` 第 314-338 行（`buildForkedConversation` 方法）
- 现有 `ConversationManager` 见 `src/main/java/com/mewcode/conversation/ConversationManager.java`
- 现有 `Message` 见 `src/main/java/com/mewcode/conversation/Message.java`

**工作内容**：

1. 定义 `ForkBuilder.FORK_BOILERPLATE_TAG = "<fork_boilerplate>"`

2. 定义 Fork Boilerplate 文本（按 F21）：
   ```
   <fork_boilerplate>
   You are a forked worker process. You are NOT the main agent.
   Rules (non-negotiable):
   1. Do NOT fork again.
   2. Do NOT converse, ask questions, or request confirmation.
   3. Use tools directly: read files, search code, make changes.
   4. Stay strictly within your assigned task scope.
   5. Final report must be under 500 characters, starting with "Scope:".
   </fork_boilerplate>
   ```

3. 实现 `buildForkedConversation(ConversationManager parent) -> ConversationManager`：
   - 深拷贝 parent 的全部消息（逐条 new Message 拷贝，包括 content、toolUses、toolResults、thinkingBlocks）
   - 检测末尾 assistant 消息中未完成的 `tool_use`（有 tool_use 但无对应 tool_result）→ 注入 placeholder tool_result（`"(tool execution interrupted by fork)"`, `isError: false`）
   - 返回新的 `ConversationManager`（不包含 system prompt——子 Agent 用自己的）

4. 实现 `buildTaskMessage(task) -> String`：拼接 `FORK_BOILERPLATE + "\n\nYour task:\n" + task`

5. 实现 `containsForkBoilerplate(ConversationManager conv) -> boolean`：扫描消息内容是否含 `FORK_BOILERPLATE_TAG`

**验收**：给定一个含 tool_use 但无 tool_result 的父对话，`buildForkedConversation` 产出的对话中 tool_use 后有 placeholder tool_result

---

## 任务 8：后台任务工具

**依赖**：任务 5

**影响文件**：新建 `src/main/java/com/mewcode/tool/impl/TaskListTool.java`、`TaskGetTool.java`、`TaskStopTool.java`、`SendMessageTool.java`

**参考资料**：
- 现有内置工具模式见 `src/main/java/com/mewcode/tool/impl/ToolSearchTool.java`（简单实现参考）
- 现有 `Tool` 接口见 `src/main/java/com/mewcode/tool/Tool.java`
- `ToolRegistry.register()` 注册模式见 `MewCode.java` 第 405-413 行

**工作内容**：

1. **TaskListTool**：
   - 名称：`TaskList`，分类：`COMMAND`
   - 无参数，返回当前 TaskManager 中所有非 Terminated 任务的简要列表（id、name、status、tool_count、last_activity）
   - 若 taskManager 未配置（为 null），返回 `"没有任务管理器"` 错误

2. **TaskGetTool**：
   - 名称：`TaskGet`，分类：`COMMAND`
   - 参数：`task_id`（string，必填）
   - 返回指定任务完整状态（含 result / error）
   - 找不到任务返回错误

3. **TaskStopTool**：
   - 名称：`TaskStop`，分类：`COMMAND`
   - 参数：`task_id`（string，必填）
   - 调用 `taskManager.cancelTask(taskId)`
   - 返回 `{status: "cancellation_requested"}`

4. **SendMessageTool**：
   - 名称：`SendMessage`，分类：`COMMAND`
   - 参数：`name`（string，必填）、`message`（string，必填）
   - 按 name 查找仍存活的后台 Agent（status=COMPLETED 且 Agent 仍在内存）→ 把 message 作为新 user 消息追加到 conv → 重新 `launch` 一轮跑动
   - 找不到或已 CANCELLED → 返回错误
   - 跑完结果作为新 `<task-notification>` 注入

5. 所有四个工具通过构造函数注入 `TaskManager` 引用（可为 null）

**验收**：`TaskList` 工具注册后 schema 正确；调用返回 JSON 格式任务列表

---

## 任务 9：子 Agent 权限升级链

**依赖**：任务 6

**影响文件**：修改 `src/main/java/com/mewcode/agent/AgentLoop.java`、`src/main/java/com/mewcode/permission/PermissionChecker.java`

**参考资料**：
- 现有权限决策逻辑见 `AgentLoop.run()` 第 588-627 行（`permissionChecker.check()` + `waitForPermission`）
- 现有 `PermissionChecker` 见 `src/main/java/com/mewcode/permission/PermissionChecker.java`
- 现有 `PermissionMode` 见 `src/main/java/com/mewcode/permission/PermissionMode.java`

**工作内容**：

1. 在 `PermissionChecker` 中新增方法 `setSubAgentMode(String subAgentName, PermissionMode mode)`：
   - 保存子 Agent 名称和 mode，供权限弹窗标注 `[来自 SubAgent X]`
   - 当 mode 为 `dontAsk` 时，所有 `ASK` 类决策转为 `ALLOW`
   - 当 mode 为 `bypassPermissions` 时，所有决策转为 `ALLOW`（但黑名单和沙箱仍拦截）

2. 修改 `AgentLoop.waitForPermission()` 或权限决策点：
   - 当 `isSubAgent` 为 true 时，弹窗消息前加 `[来自 SubAgent <name>]`
   - 权限等待超时 60 秒（子 Agent 场景专用常量 `SUB_AGENT_PERMISSION_TIMEOUT_MS`）
   - 超时后自动返回 `PermissionResponse.DENY`，子 Agent 继续运行

3. 在 `AgentLoop` 中新增字段：
   - `isSubAgent`（boolean，默认 false）
   - `subAgentName`（String，默认 null）
   - `subAgentPermissionMode`（PermissionMode，默认 null）

4. 子 Agent 构造时（来自 AgentTool），传入子 Agent 名称和 mode，设置上述字段

**验收**：子 Agent 权限弹窗显示 `[来自 SubAgent explore]`；`dontAsk` 模式下 Bash 直接放行；超时 60 秒自动拒绝

---

## 任务 10：AgentTool 实现

**依赖**：任务 1、3、4、5、6、7、8、9

**影响文件**：新建 `src/main/java/com/mewcode/subagent/AgentTool.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/AgentTool.java`（完整实现，557 行）
- 现有 `Tool` 接口见 `src/main/java/com/mewcode/tool/Tool.java`
- 现有 schema 构建模式见 `src/main/java/com/mewcode/tool/impl/ToolSearchTool.java`

**工作内容**：

1. 实现 `Tool` 接口：`name()` 返回 `"Agent"`，`category()` 返回 `COMMAND`

2. `schema()` 动态构建 JSON Schema（按 F1）：description、prompt（必填）、subagent_type（enum 来自 catalog.listNames）、model、run_in_background、name

3. `shouldDefer()` 返回 `false`（Agent 工具始终在主 Agent 工具列表中可见）

4. `execute(Map<String, Object> args)` 核心逻辑（按 F2）：
   ```
   if (subagent_type 非空):
       spec = catalog.resolve(subagent_type)
       if (spec == null): return 错误 "未知 subagent_type"
       if (spec.background || run_in_background):
           return launchBackground(spec, ...)
       else:
           return runSync(spec, ...)
   else:  // Fork 路径
       if (backgroundDisabled): return 错误 "后台禁用，无法 Fork"
       if (containsForkBoilerplate(parentConv)): return 错误 "不能嵌套 Fork"
       return runFork(description, prompt, modelOverride)
   ```

5. `runSync(spec, description, prompt, modelOverride)`：
   - `ToolFilter.buildFilter(spec, isAsync=false, isFork=false)` → 构建工具过滤器
   - 创建子 `AgentLoop`（withSystemPrompt, withMaxTurns, withPermissionMode）
   - 阻塞调用 `runToCompletion(conv, prompt, spec)` → 收集结果
   - 启动 120 秒超时计时器，超时则调 `taskManager.adoptRunning(...)` → 返回 `{task_id, status:"timed_out_to_background"}`
   - 正常完成 → 返回 `ToolResult.success(finalText)`

6. `runFork(description, prompt, modelOverride)`：
   - `ForkBuilder.buildForkedConversation(parentConv)` → 构建 fork 对话
   - 取 `SubAgentSpec.FORK` 作为 spec
   - 工具过滤：`isFork=true`（跳过全局禁止和后台白名单）
   - `taskManager.launch(agent, forkedConv, taskWithBoilerplate, FORK)` → 返回 tool_result 含 `{task_id, status:"async_launched"}`

7. `runBackground(spec, description, prompt, modelOverride)`：
   - 同上 launch 逻辑
   - 立即返回 `{task_id, status:"async_launched"}`

8. 拦截 Fork 嵌套（按 F22）：
   - 入口处检查 `ForkBuilder.containsForkBoilerplate(parentConv)` → 拦截
   - 入口处检查 parent is Fork 来源（通过 AgentLoop 的 `isFork` 标志） → 拦截

**验收**：`Agent` 工具注册到 ToolRegistry 后 schema 正确；`execute({prompt:"hello", subagent_type:"explore"})` 返回子 Agent 文本结果

---

## 任务 11：Skill fork 改造

**依赖**：任务 10

**影响文件**：修改 `src/main/java/com/mewcode/agent/AgentLoop.java`（`runSubAgent` 方法）、`src/main/java/com/mewcode/skill/SkillForkHost.java`（可能改签名）

**参考资料**：
- 现有 `AgentLoop.runSubAgent()` 方法（第 248-357 行）
- 现有 `SkillForkHost` 接口（`skill/SkillForkHost.java`）
- 现有 `SkillExecutor.executeFork()` 中调用 `runSubAgent` 的方式

**工作内容**：

1. 修改 `AgentLoop.runSubAgent()`：
   - 构造临时 `SubAgentSpec`：name=`"skill-fork-<skillName>"`，disallowedTools 从 `allowedTools` 反向推导（取父工具列表中不在 allowedTools 中的工具）
   - 或者直接设 tools 白名单为 `allowedTools`
   - 调用共享的 subAgent 构造逻辑（复用 ToolFilter + AgentLoop builder）
   - 行为与原来一致：同步阻塞返回 finalText

2. 可选：在 `SkillForkHost` 接口中新增 `getAgentTool()` 方法暴露 AgentTool 引用，让 Skill fork 直接调 AgentTool 的内部方法

3. 保持向后兼容：`SkillExecutor.executeFork()` 调用签名不变，最终行为不变

**验收**：现有 Skill fork 功能不受影响；`/skill-name` 仍然正常工作；`runSubAgent` 内部走 SubAgent 的 ToolFilter + AgentLoop builder 路径

---

## 任务 12：配置 + 主流程接入

**依赖**：任务 10、11

**影响文件**：修改 `src/main/java/com/mewcode/config/AppConfig.java`、`src/main/java/com/mewcode/MewCode.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/AgentLoader.java` 的 `loadAll()` 调用方式
- 现有 `MewCode.java` 中 ToolRegistry 初始化（第 400-415 行）和 AgentLoop 创建（第 244-261 行）
- 现有 `AppConfig.java`（`@JsonProperty` 注解模式）

**工作内容**：

1. 在 `AppConfig` 中新增配置节点 `subagent`：
   ```java
   @JsonProperty("subagent")
   private SubAgentConfig subagent = new SubAgentConfig();
   
   public static class SubAgentConfig {
       @JsonProperty("background")
       private BackgroundConfig background = new BackgroundConfig();
       @JsonProperty("max_turns")
       private int maxTurns = 25;
       
       public static class BackgroundConfig {
           @JsonProperty("enabled")
           private boolean enabled = true;
       }
   }
   ```

2. 在 `MewCode.main()` 中：
   - 步骤 1f 后：`AgentCatalog catalog = AgentLoader.loadAll(projectRoot);`
   - 步骤 2 后（ToolRegistry 构建后）：创建 `TaskManager taskManager = new TaskManager();`
   - 注册 `AgentTool`：`AgentTool agentTool = new AgentTool(provider, toolRegistry, catalog, taskManager, config);` → `toolRegistry.register(agentTool);`
   - 注册后台任务工具：`toolRegistry.register(new TaskListTool(taskManager));` 等
   - 创建 AgentLoop 时注入 `agentTool` 和 `taskManager` 引用

3. 在 `AgentLoop` 构造函数或 setter 中新增：
   - `setAgentTool(AgentTool agentTool)` — 供 fork 时用
   - `setTaskManager(TaskManager taskManager)` — 供 drainNotifications 时用
   - 在 `run()` 方法每轮开始时调用 `taskManager.drainNotifications()` 注入 `<task-notification>`

4. 添加 `conversation.injectLongTermContext` 中注入可用的 subagent_type 列表（作为系统提示的一部分，告诉模型有哪些类型可用）

**验收**：启动后 `grep -r "Agent"` 在输出中可见 Agent 工具注册；`catalog.listNames()` 返回 ≥ 3 个内置角色

---

## 任务 13：端到端验证

**依赖**：任务 12

**影响文件**：无新建文件（可能新增 `src/test/` 下的集成测试）

**工作内容**：

1. **定义式子 Agent 同步执行**：启动 MewCode，输入"用 explore 子 Agent 在 src/ 中找所有 System.out.println"，验证：
   - 子 Agent 启动（UI 显示进度）
   - 返回结果含文件路径列表
   - 没有权限弹窗（explore 只用只读工具）

2. **定义式子 Agent 工具限制**：验证 explore 子 Agent 看不到 WriteFile/EditFile/Agent → 通过日志或测试断言工具过滤结果

3. **Fork 子 Agent**：启动 MewCode，先读一个文件，再输入"帮我总结一下刚才读的文件"，验证 Fork 子 Agent 首条消息含 `<fork_boilerplate>`

4. **Fork 嵌套阻断**：在 Fork 子 Agent 中调用 Agent 工具 → tool_result 含 `"Fork 子 Agent 不能再启动 Agent"`

5. **后台任务**：`run_in_background:true` → tool_result 立即返回 task_id → 等待子 Agent 完成 → 下次输入时看到 `<task-notification>`

6. **超时切后台**：构造一个需要 130 秒的任务 → 120 秒后自动切后台 → tool_result 含 `status:"timed_out_to_background"`

7. **权限升级**：子 Agent 使用 dontAsk 模式 → Bash 不放行 → 子 Agent 使用 default 模式 + Bash → 弹窗出现 `[来自 SubAgent X]`

8. **配置开关**：`subagent.background.enabled: false` → Fork 调用返回错误

9. **自定义 Agent 定义**：在 `.mewcode/agents/custom.md` 创建定义 → `subagent_type=custom` 生效

10. **Skill fork**：激活一个 skill → 验证 skill fork 功能正常（与改造前行为一致）

**验收**：以上 10 项全部通过
