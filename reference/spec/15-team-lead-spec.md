# 15-team-lead-spec.md

## 背景

ch13 实现了子 Agent 的分发与执行，ch14 实现了 worktree 文件隔离。但当前架构仍是严格的主从模式：主 Agent 派发任务给子 Agent，子 Agent 跑完返回结果，所有信息都经过主 Agent 中转。这导致三个问题：

1. **通信瓶颈**：队员之间无法直接协作，所有消息必须经 Lead 转发
2. **生命周期短**：子 Agent 跑完就销毁，无法持续存在、接收后续任务
3. **编排能力弱**：Lead 没有共享任务面板来拆解和追踪多队员的工作进度

本章把主 Agent 升级为 Team Lead：引入长期小组、共享任务清单、点对点邮箱通信、多后端派生、coordinator 模式。

## 目标用户

使用 MewCode 处理需要多 Agent 并行协作的复杂任务的开发者。典型场景：
- "审查这个 PR 的安全性、性能和代码风格"——派 3 个队员各负责一个维度，互相可以讨论
- "把整个模块迁移到新架构"——Lead 拆成 10 个任务写入共享清单，派 3 个队员各自认领
- "开启 coordinator 模式，我只做决策和分派，代码修改全部交给队员"

---

## 目标

- **G1**：提供 `TeamCreate` / `TeamDelete` 工具，让主 Agent 创建和销毁长期存在的小组对象。小组记名称、运行模式（IN_PROCESS / TMUX）、成员花名册，持久化到 `.mewcode/teams/<name>/` 目录
- **G2**：Agent 工具新增 `team_name` 参数——传入即走队员路径，不走子 Agent 路径。队员有两种运行后端：IN_PROCESS（同进程虚线程，轻量）和 TMUX（独立 tmux 窗格，强隔离）。后端按环境自动检测：检测到 tmux 则用 TMUX，否则回退 IN_PROCESS
- **G3**：队员拥有协作工具——`SendMessage`（点对点发消息）、共享任务 CRUD（`TaskCreate`/`TaskGet`/`TaskList`/`TaskUpdate`）。这些工具只注册给队员和 Lead，普通子 Agent 看不到
- **G4**：点对点消息走 FileMailBox——JSON 文件邮箱 + lock 文件并发控制。消息带发件人、正文、时间戳（落盘自动补）、是否已读、摘要。lock 文件 10 秒过期视为 stale。独立进程后端（TMUX）通过文件邮箱跨进程通信
- **G5**：队员生命周期——派生后立即开始执行初始任务；跑完自动发送 `[idle]` 通知给 Lead 并进入空闲轮询；Lead 发消息到其邮箱即可唤醒继续工作（IN_PROCESS 队员线程存活轮询邮箱；TMUX 队员独立进程轮询同一邮箱文件）
- **G6**：Lead 负责编排——把用户目标拆成带依赖的任务写进共享清单（`SharedTaskStore`，JSON 文件持久化）、派生队员；全部完成后用 git 合并各人 worktree 分支，冲突能解决就解决、搞不定就回滚上报
- **G7**：Coordinator 模式——两把锁控制：① 配置文件 `mewcode.yaml` 中 `team.coordinator.enabled: true` ② 用户通过 `/coordinator` 命令或环境变量主动启用。开启后 Lead 的工具集被裁剪为 `Coordinator.ALLOWED_TOOLS`（含 Agent、SendMessage、TaskCreate/Get/List/Update、TeamCreate、TeamDelete、ReadFile、Glob、Grep、Bash），剥夺 WriteFile/EditFile 等写文件工具，只保留派人、发消息、读代码、跑 git 合并的能力
- **G8**：小组配置持久化——成员邮箱（`inboxes/<name>.json`）和共享任务清单（`tasks.json`）均落在 `.mewcode/teams/<teamName>/` 下

## Out of Scope

- 跨机器的分布式团队
- 队员间实时流式通信（消息在每轮开始时批量注入）
- 复杂的任务依赖约束（仅支持 blocks/blockedBy ID 引用，不做 DAG 环路检测）
- 审批流（`[approval-request]` / `[approved]` / `[rejected]` 结构化协议消息）
- 上下文从磁盘恢复（队员线程存活期间不需要；进程退出则状态丢失）
- iTerm2 后端（仅实现 TMUX + IN_PROCESS）
- 广播消息
- 结构化协议消息类型（仅保留 `[idle]` 和 `[shutdown]` 前缀约定）
- 非 git 项目的 worktree 合并

---

## 功能需求

### TeamManager

- **F1**：`TeamManager` 类管理所有小组生命周期。内部持有 `Map<String, Team>`。提供 `createTeam(name, mode)`、`getTeam(name)`、`deleteTeam(name)`、`listTeams()`、`closeAll()`
- **F2**：`Team` 内部类：`name`（String）、`mode`（TeamMode 枚举：`IN_PROCESS` / `TMUX`）、`members`（`Map<String, Member>`）、`mailBox`（`FileMailBox`，目录指向 `.mewcode/teams/<name>/inboxes/`）
- **F3**：`TeamMode` 枚举含 `IN_PROCESS` 和 `TMUX`。`detectBackend()` 静态方法：检测环境变量 `TMUX` 或 `which tmux` 可用 → TMUX；否则 → IN_PROCESS
- **F4**：`Member` 内部类：`name`（String）、`agent`（Agent 引用，TMUX 模式下为 null）、`conv`（ConversationManager 引用）、`active`（volatile boolean）、`thread`（Thread 引用）
- **F5**：`TeamManager` 作为单例或由 MewCode 主类持有，Agent 工具和 Team 工具通过引用访问

### FileMailBox

- **F6**：`FileMailBox` 类，构造参数为 `baseDir`（Path）。内部方法：`send(recipient, MailMessage)`、`readUnread(agentId)`、`markAllRead(agentId)`
- **F7**：`MailMessage` record：`from`、`text`、`timestamp`（ISO Instant 字符串）、`read`（boolean）、`color`（String，预留）、`summary`（String，预留）。构造函数自动填 timestamp 和 read=false
- **F8**：邮箱文件为 `<agentId>.json`（JSON 数组），锁文件为 `<agentId>.json.lock`。`withLock` 方法：尝试创建 lock 文件（`Files.createFile`），`FileAlreadyExistsException` 时检查锁是否超过 10 秒（stale），未过期则随机 sleep 5-100ms 后重试，最多 10 次。操作完成后 `finally` 删除 lock 文件
- **F9**：读写用 Jackson ObjectMapper 序列化/反序列化 `List<MailMessage>`

### SpawnDispatcher

- **F10**：`SpawnDispatcher` 统一派生入口。`SpawnConfig` record：`team`、`memberName`、`task`、`addendum`、`client`（LLMProvider）、`registry`（ToolRegistry）、`protocol`、`workdir`。`spawnTeammate(config)` 方法按 team.mode 分派
- **F11**：IN_PROCESS 路径——调 `team.addMember()` 创建 Member，启动 virtual thread 跑 `TeammateRunner.runInProcessTeammate()`
- **F12**：TMUX 路径——先把 task 写入成员邮箱（让新进程第一轮轮询就能读到），再构建 CLI 命令（`cd <workdir> && <mewcode> --teammate --team-name <t> --agent-name <n>`），调 `TmuxBackend.spawnTmuxTeammate()` 在独立 tmux 窗格中启动

### TmuxBackend

- **F13**：`TmuxBackend` 静态工具类。`spawnTmuxTeammate(teamName, memberName, cliCommand)`：调 `tmux new-window -d -n <paneName> <cliCommand>`。`stopTmuxTeammate(paneName)`：先 `tmux send-keys -t <paneName> C-c` 再 `tmux kill-window -t <paneName>`

### TeammateRunner

- **F14**：`TeammateRunner` 静态工具类。`runInProcessTeammate(team, member, initialPrompt, addendum)` 方法：
  1. 注入 addendum 为 system reminder
  2. 调 `injectPendingMessages()` 注入邮箱中的未读消息
  3. 以 initialPrompt 作为 user 消息跑第一轮 Agent loop
  4. 跑完后发送 `[idle] <name>: completed initial task (at <timestamp>)` 到 Lead 邮箱
  5. 进入空闲轮询循环：每 500ms 检查自己的邮箱，有未读消息则注入为 system reminder 并跑新一轮 Agent loop
  6. 收到 `[shutdown]` 前缀消息则退出循环
- **F15**：`drainLeadMailbox(teamMgr)` 静态方法：遍历所有 Team，读 Lead 的未读消息，格式化为 `<team-notification team="...">` XML 块返回给主 Agent loop 注入

### SharedTaskStore

- **F16**：`SharedTaskStore` 类，持久化到 `<teamDir>/tasks.json`。内部持有 `List<SharedTask>` 和自增 ID。构造时从文件 load，每次变更后 save
- **F17**：`SharedTask` record：`id`（int）、`title`、`description`、`status`（String：`"todo"`/`"in_progress"`/`"done"`）、`assignee`（String）、`blocks`（`List<Integer>`）、`blockedBy`（`List<Integer>`）、`createdBy`（String）
- **F18**：CRUD 方法：`create(title, description, createdBy)`、`get(id)`、`listTasks(status, assignee)`、`update(id, status, assignee, addBlocks, addBlockedBy)`

### TaskList（共享任务工具底层）

- **F19**：`TaskList` 类（`com.mewcode.task` 包），JSON 文件持久化。`Task` 内部类字段：`id`、`subject`、`description`、`activeForm`、`status`（`pending`/`in_progress`/`completed`）、`owner`、`blocks`（`List<String>`）、`blockedBy`（`List<String>`）、`metadata`（`Map<String, Object>`）
- **F20**：CRUD 方法：`create(subject, description, activeForm, metadata)`、`get(id)`、`list()`、`update(id, updates)`。update 支持按字段增量更新，status 为 `"deleted"` 时删除任务

### TaskTools

- **F21**：`TaskCreateTool`：参数 `subject`（必填）、`description`（必填）、`activeForm`（可选）、`metadata`（可选）
- **F22**：`TaskGetTool`：参数 `taskId`（必填），返回任务详情含 blocks/blockedBy/owner
- **F23**：`TaskListTool`：无参数，列出所有非 `_internal` 任务，自动过滤已完成 blocker
- **F24**：`TaskUpdateTool`：参数 `taskId`（必填）+ 可选字段 `subject`/`description`/`activeForm`/`status`/`owner`/`addBlocks`/`addBlockedBy`/`metadata`。status 可选值：`pending`/`in_progress`/`completed`/`deleted`

### TeamTools（协作工具）

- **F25**：`SendMessageTool`：参数 `to`（必填，接收者名称）、`content`（必填，消息正文）。实现：查找 sender 所在的 team → 调 `team.sendMessage(sender, to, content)`。支持发给 "lead"
- **F26**：`TeamCreateTool`：参数 `team_name`（必填）、`description`（可选）。实现：检测后端 → `teamMgr.createTeam()` → 返回 team 名称和 mode。重名自动追加 `-2`、`-3` 后缀
- **F27**：`TeamDeleteTool`：参数 `team_name`（必填）。实现：`teamMgr.deleteTeam()` → 停止所有成员

### Agent 工具扩展

- **F28**：Agent 工具 schema 新增 `team_name` 参数（string，可选）。当 `team_name` 非空且 TeamManager 已配置时走队员派生路径
- **F29**：队员派生逻辑：
  1. 调 `teamManager.getTeam(team_name)` 取 Team
  2. 从 description 提取 memberName（替换空白为 `-`，限 30 字符，重名追加后缀）
  3. 调 `ToolFilter.filterForAgent()` 构建子工具注册表
  4. 向子注册表注册 `SendMessageTool`
  5. 调 `TeammateRunner.buildTeammateAddendum()` 构建队员系统提醒
  6. 可选的 worktree 隔离
  7. 调 `SpawnDispatcher.spawnTeammate()` 派生
- **F30**：队员的协作工具（SendMessage + 共享任务 CRUD）只在队员和 Lead 的工具列表中可见。普通子 Agent（未指定 `team_name` 的 Agent 工具调用）的工具列表中不出现这些工具
- **F31**：`TaskCreate`/`TaskGet`/`TaskList`/`TaskUpdate` 为 defer 工具（`shouldDefer() = true`），不随主工具列表加载

### Coordinator 模式

- **F32**：`Coordinator` 类定义 `ALLOWED_TOOLS` 白名单：`Agent`、`SendMessage`、`TaskCreate`、`TaskGet`、`TaskList`、`TaskUpdate`、`TeamCreate`、`TeamDelete`、`ReadFile`、`Glob`、`Grep`、`Bash`
- **F33**：两把锁控制：
  1. 配置文件（`mewcode.yaml`）中 `team.coordinator.enabled: true`（默认 false）
  2. 用户主动启用：环境变量 `MEWCODE_COORDINATOR=1` 或 slash command `/coordinator`
- **F34**：两把锁都打开时，Lead 的工具集被裁剪——通过 `toolNameFilter` predicate 过滤，只保留 `Coordinator.ALLOWED_TOOLS` 中的工具。注意 Bash 保留（Lead 需要通过 shell 跑 git 合并），这是有意为之
- **F35**：Coordinator 模式在运行时检查，可通过 `/coordinator off` 或清除环境变量退出

### AgentNameRegistry

- **F36**：`AgentNameRegistry` 单例，维护 name → agentId 的全局映射。方法：`register(name, agentId)`、`resolve(nameOrId)`（先查 name，再查是否是已有 ID）、`unregister(name)`、`listAll()`

---

## 非功能需求

- **N1**：fail-closed：所有 git 操作异常时默认保守处理（保留 worktree、保留文件），不误删
- **N2**：IN_PROCESS 队员的虚线程崩溃不影响主程序——TeammateRunner 内部 try/catch，异常时标记 member.active = false
- **N3**：FileMailBox 的 lock 文件并发安全——10 秒 stale 过期、最多 10 次重试、随机退避
- **N4**：与现有 ch13 SubAgent、ch14 Worktree、ch12 Hook、ch08 权限系统协同，不破坏既有测试
- **N5**：TMUX 路径下 MewCode 进程需要支持 `--teammate --team-name <t> --agent-name <n>` CLI 参数（本期做占位，实际 TMUX 后端逻辑后续实现）
- **N6**：`<team-notification>` 注入主对话不消耗工具调用配额，不出现在用户视窗（只对模型可见）

---

## 设计骨架

```
teams/                          ← 新包
├── TeamManager                 ← 小组生命周期管理
├── TeammateRunner              ← IN_PROCESS 队员主循环
├── TeamTools                   ← SendMessage / TeamCreate / TeamDelete 工具
├── FileMailBox                 ← JSON 文件邮箱 + lock 并发控制
├── SharedTaskStore             ← JSON 共享任务清单（简易版）
├── AgentNameRegistry           ← name→agent_id 全局注册表
├── SpawnDispatcher             ← 多后端统一派生入口
├── TmuxBackend                 ← tmux 窗格管理
└── Coordinator                 ← ALLOWED_TOOLS 白名单

task/                           ← 扩展
├── TaskList                    ← JSON 持久化任务清单（完整 CRUD）
└── TaskTools                   ← TaskCreate/Get/List/Update 工具实现

修改:
├── subagent/AgentTool.java     ← +team_name 参数 + runAsTeammate() 分支
├── subagent/SubAgentSpec.java  ← 可能需要加 background 字段（参考实现已有）
├── subagent/ToolFilter.java    ← +buildForkFilter() 静态方法
├── config/AppConfig.java       ← +team.coordinator.enabled 字段
├── config/ConfigLoader.java    ← +解析 team 配置段
├── agent/Agent.java            ← +notificationFn + toolNameFilter
├── agent/AgentLoop.java        ← +notificationFn 注入 + toolNameFilter 裁剪
├── tool/ToolRegistry.java      ← +getDeferredToolNames()
├── tui/TerminalUI.java         ← /coordinator 命令处理
└── MewCode.java                ← TeamManager 初始化 + 注入
```

## 验收标准

- **AC1**：`TeamCreate` 工具调用 `{team_name: "review"}` 返回创建的 team 名称和 mode
- **AC2**：Agent 工具调用 `{team_name: "review", description: "security-checker", prompt: "审查 src/ 的安全性"}` 后，队员被派生并开始工作
- **AC3**：队员跑完初始任务后，Lead 的下一轮对话的 system reminder 中出现 `<team-notification>` 含 `[idle]` 消息
- **AC4**：Lead 通过 `SendMessage` 给空闲队员发消息，队员在下一轮轮询中读取并继续工作
- **AC5**：两个队员之间可以通过 `SendMessage` 直接通信（不需要 Lead 中转）
- **AC6**：Lead 调用 `TaskCreate` 创建任务后，队员调用 `TaskList` 能看到该任务
- **AC7**：队员调用 `TaskUpdate` 将任务标记为 `in_progress` 并设 `owner` 为自己
- **AC8**：`TeamDelete` 删除 team 后，所有成员的 `active` 标记为 false
- **AC9**：普通子 Agent（未指定 `team_name`）的工具列表中不出现 `SendMessage`/`TaskCreate`/`TaskGet`/`TaskList`/`TaskUpdate`/`TeamCreate`/`TeamDelete`
- **AC10**：配置 `team.coordinator.enabled: true` 且设置 `MEWCODE_COORDINATOR=1` 后，Lead 的工具列表仅含 `Coordinator.ALLOWED_TOOLS` 中的工具
- **AC11**：两把锁只打开一把时，coordinator 模式不生效
- **AC12**：FileMailBox 并发写入同一邮箱文件不丢消息
- **AC13**：`.mewcode/teams/<name>/inboxes/` 下存在各成员的邮箱 JSON 文件
- **AC14**：`.mewcode/teams/<name>/tasks.json` 持久化共享任务清单
- **AC15**：`SharedTaskStore` 的 blocks/blockedBy 字段支持依赖追踪
