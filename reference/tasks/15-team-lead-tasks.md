# 15-team-lead-tasks.md

## 任务总览

| # | 任务 | 依赖 | 涉及文件 |
|---|------|------|----------|
| 1 | TaskList + TaskTools 实现 | 无 | 新建 2 个文件 |
| 2 | FileMailBox + AgentNameRegistry 实现 | 无 | 新建 2 个文件 |
| 3 | SharedTaskStore 实现 | 无 | 新建 1 个文件 |
| 4 | Coordinator 白名单 | 无 | 新建 1 个文件 |
| 5 | TeamManager 实现 | T2 | 新建 1 个文件 |
| 6 | TmuxBackend 实现 | 无 | 新建 1 个文件 |
| 7 | TeammateRunner 实现 | T2, T5 | 新建 1 个文件 |
| 8 | SpawnDispatcher 实现 | T5, T6, T7 | 新建 1 个文件 |
| 9 | TeamTools 实现 | T5 | 新建 1 个文件 |
| 10 | AgentTool 扩展 team_name | T3, T5, T8, T9 | 修改 2 个文件 |
| 11 | Config + CLI 接入 | T4, T5 | 修改 4 个文件 |
| 12 | 接入主流程 | T10, T11 | 修改 3 个文件 |
| 13 | 端到端验证 | T12 | 无新建 |

---

## 任务 1：TaskList + TaskTools 实现

**依赖**：无

**目标**：实现 JSON 持久化的任务清单及四个 MCP 风格工具。

**新建文件**：
- `src/main/java/com/mewcode/task/TaskList.java`
- `src/main/java/com/mewcode/task/TaskTools.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/task/TaskList.java`（完整参考实现）
- `D:/mewcode-java/java/src/main/java/com/mewcode/task/TaskTools.java`（完整参考实现）

**关键点**：
- `TaskList` 内部类 `Task`：字段 `id`（`t` + 8 位 hex）、`subject`、`description`、`activeForm`、`status`（`pending`/`in_progress`/`completed`）、`owner`、`blocks`（`List<String>`）、`blockedBy`（`List<String>`）、`metadata`（`Map<String, Object>`）
- 持久化到 `.mewcode/tasks/<listId>.json`
- `update()` 方法支持增量字段更新，status=`"deleted"` 时删除任务
- 四个 Tool 均 `shouldDefer() = true`，`category() = COMMAND`
- `TaskListTool` 展示时自动过滤已完成的 blocker
- 用 Jackson `ObjectMapper` 序列化，`INDENT_OUTPUT` 美化

---

## 任务 2：FileMailBox + AgentNameRegistry 实现

**依赖**：无

**目标**：实现文件系统邮箱和全局名称注册表。

**新建文件**：
- `src/main/java/com/mewcode/teams/FileMailBox.java`
- `src/main/java/com/mewcode/teams/AgentNameRegistry.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/FileMailBox.java`
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/AgentNameRegistry.java`

**关键点**：
- `FileMailBox`：`MailMessage` record（`from`、`text`、`timestamp`、`read`、`color`、`summary`）。邮件文件为 `<agentId>.json`，锁文件为 `<agentId>.json.lock`
- `withLock`：尝试 `Files.createFile(lock)` → `FileAlreadyExistsException` 时检查 stale（>10s）→ 随机 sleep 5-100ms 重试 → 最多 10 次 → 操作后 `finally` 删锁
- `AgentNameRegistry`：单例，`ConcurrentHashMap<String, String>` 存 name→agentId 映射。`resolve(nameOrId)` 先查 name 再查 value

---

## 任务 3：SharedTaskStore 实现

**依赖**：无

**目标**：实现 team 作用域内的共享任务清单（JSON 持久化，带依赖字段）。

**新建文件**：
- `src/main/java/com/mewcode/teams/SharedTaskStore.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/SharedTaskStore.java`

**关键点**：
- `SharedTask` record：`id`（int，自增）、`title`、`description`、`status`（String：`"todo"`/`"in_progress"`/`"done"`）、`assignee`、`blocks`（`List<Integer>`，task ID 列表）、`blockedBy`（`List<Integer>`）、`createdBy`
- 持久化到 `<teamDir>/tasks.json`
- `update()` 返回新的 SharedTask（immutable 风格）
- 构造时从文件 load，每次变更后 save

---

## 任务 4：Coordinator 白名单

**依赖**：无

**目标**：定义 Coordinator 模式下的工具白名单。

**新建文件**：
- `src/main/java/com/mewcode/teams/Coordinator.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/Coordinator.java`

**关键点**：
- `ALLOWED_TOOLS` = `Set.of("Agent", "SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "TeamCreate", "TeamDelete", "ReadFile", "Glob", "Grep", "Bash")`
- `isCoordinatorTool(name)` 静态方法用于 predicate 过滤

---

## 任务 5：TeamManager 实现

**依赖**：T2（FileMailBox）

**目标**：实现小组生命周期管理器。

**新建文件**：
- `src/main/java/com/mewcode/teams/TeamManager.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/TeamManager.java`

**关键点**：
- 顶层 `TeamManager` 持有 `Map<String, Team>`
- `Team` 内部类：`name`、`mode`（`TeamMode` 枚举）、`members`（`Map<String, Member>`）、`mailBox`（`FileMailBox`，路径 `.mewcode/teams/<name>/inboxes/`）
- `Member` 内部类：`name`、`agent`（`com.mewcode.agent.AgentLoop`）、`conv`（`ConversationManager`）、`active`、`thread`
- `TeamMode` 枚举：`IN_PROCESS`、`TMUX`
- `detectBackend()`：检查 `System.getenv("TMUX")` 或 `which tmux` → TMUX，否则 IN_PROCESS
- `createTeam()` 返回 Team，`deleteTeam()` 调 `team.stopAll()` 后移除
- `sendMessage()` 委托给 `mailBox.send()`

---

## 任务 6：TmuxBackend 实现

**依赖**：无

**目标**：实现 tmux 窗格管理后端。

**新建文件**：
- `src/main/java/com/mewcode/teams/TmuxBackend.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/TmuxBackend.java`

**关键点**：
- `spawnTmuxTeammate(teamName, memberName, cliCommand)`：`tmux new-window -d -n <paneName> <cliCommand>`，30s 超时
- `stopTmuxTeammate(paneName)`：先 `tmux send-keys -t <paneName> C-c`，等 200ms，再 `tmux kill-window -t <paneName>`
- 所有异常静默 catch（best-effort）

---

## 任务 7：TeammateRunner 实现

**依赖**：T2（FileMailBox）、T5（TeamManager）

**目标**：实现 IN_PROCESS 队员的主循环 + Lead 通知排空。

**新建文件**：
- `src/main/java/com/mewcode/teams/TeammateRunner.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/TeammateRunner.java`

**关键点**：
- `LEAD_NAME = "lead"`、`SHUTDOWN_PREFIX = "[shutdown]"`、`IDLE_POLL_MS = 500`
- `runInProcessTeammate(team, member, initialPrompt, addendum)`：
  1. 注入 addendum 为 system reminder（如有）
  2. `injectPendingMessages()` 注入已有未读邮件
  3. 以 initialPrompt 为 user 消息跑 `member.agent.run(member.conv)`，drain 事件队列
  4. 跑完后发 `[idle]` 通知到 Lead 邮箱
  5. 进入轮询循环（500ms sleep → 读邮箱 → 格式化未读消息 → 跑新一轮）
  6. 检测到 `[shutdown]` 前缀消息退出
  7. 最后 `member.active = false`
- `drainLeadMailbox(teamMgr)`：遍历所有 team，读 lead 的未读邮件，格式化为 `<team-notification team="...">` XML 块
- `buildTeammateAddendum(teamName, memberName, otherMembers)`：构建队员系统提示，告知其所在 team、自己的名字、其他队员列表、可用 SendMessage 通信
- `injectPendingMessages(team, memberName, conv)`：读未读邮件，拼成 system reminder 注入 conv
- `createIdleNotification(memberName, reason)`：`"[idle] <name>: <reason> (at <timestamp>)"`

---

## 任务 8：SpawnDispatcher 实现

**依赖**：T5（TeamManager）、T6（TmuxBackend）、T7（TeammateRunner）

**目标**：实现多后端统一派生入口。

**新建文件**：
- `src/main/java/com/mewcode/teams/SpawnDispatcher.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/SpawnDispatcher.java`

**关键点**：
- `SpawnConfig` record：`team`、`memberName`、`task`、`addendum`、`client`（`LLMProvider`）、`registry`（`ToolRegistry`）、`protocol`、`workdir`
- `SpawnResult` record：`mode`（`TeamMode`）、`paneId`（String，TMUX 时非 null）
- `spawnTeammate(config)`：
  - IN_PROCESS：调 `team.addMember()` → 起 virtual thread 跑 `TeammateRunner.runInProcessTeammate()`
  - TMUX：先 `team.sendMessage(LEAD_NAME, memberName, task)` 写任务到邮箱 → `buildTeammateCLI()` 构建命令 → `TmuxBackend.spawnTmuxTeammate()`
- `buildTeammateCLI(teamName, memberName, workdir)`：`cd <workdir> && <mewcode> --teammate --team-name <t> --agent-name <n>`

---

## 任务 9：TeamTools 实现

**依赖**：T5（TeamManager）

**目标**：实现 SendMessage、TeamCreate、TeamDelete 三个 Tool。

**新建文件**：
- `src/main/java/com/mewcode/teams/TeamTools.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/teams/TeamTools.java`

**关键点**：
- `SendMessageTool`：构造函数注入 `TeamManager` + `senderName`。`execute` 查找 sender 所在 team → 调 `team.sendMessage(sender, to, content)`。支持发给 "lead"
- `TeamCreateTool`：`execute` 调 `TeamManager.detectBackend()` → `teamMgr.createTeam(name, mode)`。重名自动追加后缀
- `TeamDeleteTool`：`execute` 调 `teamMgr.getTeam(name)` → `teamMgr.deleteTeam(name)`
- 三个工具均 `shouldDefer() = false`（始终可见）

---

## 任务 10：AgentTool 扩展 team_name

**依赖**：T3（SharedTaskStore）、T5（TeamManager）、T8（SpawnDispatcher）、T9（TeamTools）

**目标**：Agent 工具新增 `team_name` 参数，实现队员派生路径。

**修改文件**：
- `src/main/java/com/mewcode/subagent/AgentTool.java`
- `src/main/java/com/mewcode/subagent/ToolFilter.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/AgentTool.java`（`runAsTeammate` 方法，第 457-517 行）

**关键点**：
- `AgentTool` 新增 `setTeamManager(TeamManager)` 方法
- schema 新增 `team_name` 参数（string，可选）
- `execute()` 中在 Fork/subagent 分支之前检查 `team_name`：非空且 `teamManager != null` → `runAsTeammate()`
- `runAsTeammate()` 方法：
  1. `teamManager.getTeam(teamName)` 取 Team，不存在返回 error
  2. 从 description 提取 memberName（替换空白为 `-`，限 30 字符，去重追加后缀）
  3. `ToolFilter.filterForAgent()` 构建子注册表
  4. 注册 `SendMessageTool` + 共享任务 CRUD 工具到子注册表
  5. `TeammateRunner.buildTeammateAddendum()` 构建 addendum
  6. 可选 worktree 隔离（复用现有 `generateWorktreeSlug()` + `AgentWorktree.create()`）
  7. `SpawnDispatcher.spawnTeammate()` 派生
- `ToolFilter` 如需新增 `buildForkFilter()` 静态方法（参考实现第 236 行）

---

## 任务 11：Config + CLI 接入

**依赖**：T4（Coordinator）、T5（TeamManager）

**目标**：配置文件新增 team 段、新增 CLI 参数 `--teammate`、新增 `/coordinator` 命令。

**修改文件**：
- `src/main/java/com/mewcode/config/AppConfig.java`
- `src/main/java/com/mewcode/config/ConfigLoader.java`
- `src/main/java/com/mewcode/command/CommandRegistry.java`（或 `CommandType.java`）
- `src/main/java/com/mewcode/tui/TerminalUI.java`

**关键点**：
- `AppConfig` 新增 `teamCoordinatorEnabled: boolean`（默认 false）
- `ConfigLoader` 解析 `mewcode.yaml` 中 `team.coordinator.enabled` 字段
- `MewCode.java` 新增 CLI 参数 `--teammate`、`--team-name <name>`、`--agent-name <name>`（TMUX 队员进程入口，本期解析占位并启动 TeammateRunner 轮询）
- `TerminalUI` 新增 `/coordinator` slash command 处理——切换 coordinator 模式开关。需检查 `AppConfig.teamCoordinatorEnabled` 为前提

---

## 任务 12：接入主流程

**依赖**：T10（AgentTool 扩展）、T11（Config + CLI）

**目标**：在 MewCode 主类中初始化 TeamManager，注入 Agent tool 和 Agent loop。

**修改文件**：
- `src/main/java/com/mewcode/MewCode.java`
- `src/main/java/com/mewcode/agent/AgentLoop.java`
- `src/main/java/com/mewcode/tool/ToolRegistry.java`

**参考资料**：
- `D:/mewcode-java/java/src/main/java/com/mewcode/agent/Agent.java`（`notificationFn` 字段，第 44 行；`toolNameFilter` 字段，第 46 行）

**关键点**：
- `MewCode.java`：
  - 初始化 `TeamManager` 单例
  - 初始化 `SharedTaskStore`（如需要）
  - 把 TeamManager 注入 `AgentTool.setTeamManager()`
  - 注册 `TeamCreateTool`、`TeamDeleteTool` 到主 ToolRegistry
  - 如果配置和 env 开启 coordinator 模式，设置 tool filter
- `AgentLoop.java`（或 Agent 类）：
  - 新增 `notificationFn` 字段（`Supplier<List<String>>`）
  - 每轮迭代开始时调用 `notificationFn.get()` 获取 team 通知并注入为 system reminder
  - 新增 `toolNameFilter` 字段（`Predicate<String>`），在 `getAllSchemas` 后过滤工具列表
- `ToolRegistry.java`：
  - 新增 `getDeferredToolNames()` 方法（如尚未实现）

---

## 任务 13：端到端验证

**依赖**：T12（接入主流程）

**目标**：运行完整流程验证所有功能点。

**验证步骤**：
1. 启动 MewCode，确认 TeamCreate/TaskCreate/SendMessage 等工具出现在主 Agent 工具列表
2. 执行 TeamCreate → Agent(team_name=...) → TaskList → SendMessage → TeamDelete 完整链路
3. 验证 FileMailBox 并发安全（多线程同时写同一邮箱不丢消息）
4. 验证 coordinator 模式两把锁逻辑：单开配置不开 env → 不生效；单开 env 不开配置 → 不生效；都开 → 工具列表被裁剪
5. 验证普通子 Agent（无 team_name）的工具列表中不出现 Team 协作工具
6. 验证 `.mewcode/teams/<name>/` 目录结构和 JSON 文件内容正确
7. 验证队员 idle 通知正确注入 Lead 的对话
