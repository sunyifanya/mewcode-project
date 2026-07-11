# 15-team-lead-checklist.md

## 数据层验收

- [ ] **CHK01**：`grep -r "class TaskList" src/` 返回 1 条结果，位于 `com.mewcode.task` 包
- [ ] **CHK02**：`grep -r "class TaskTools" src/` 返回 1 条结果
- [ ] **CHK03**：TaskList 持久化文件在 `.mewcode/tasks/<listId>.json`，JSON 中 tasks 数组元素含 `id`、`subject`、`description`、`status`、`owner`、`blocks`、`blockedBy`、`metadata` 字段
- [ ] **CHK04**：TaskCreate 调用后，tasks.json 中新增一条 status=`"pending"` 的记录
- [ ] **CHK05**：TaskUpdate 设置 status=`"deleted"` 后，tasks.json 中对应 id 的记录被移除
- [ ] **CHK06**：TaskList 列出任务时，已完成的 blocker 不显示在 `[blocked by: ...]` 中
- [ ] **CHK07**：`grep -r "shouldDefer" src/main/java/com/mewcode/task/TaskTools.java` 返回 4 条 `return true` 结果
- [ ] **CHK08**：`grep -r "class FileMailBox" src/` 返回 1 条结果，位于 `com.mewcode.teams` 包
- [ ] **CHK09**：MailMessage record 含 6 个字段：`from`、`text`、`timestamp`、`read`、`color`、`summary`
- [ ] **CHK10**：`FileMailBox` 构造函数不传 timestamp 时自动填入 ISO Instant 格式时间戳且 `read=false`
- [ ] **CHK11**：lock 文件超时阈值：`grep -r "minusSeconds(10)" src/main/java/com/mewcode/teams/FileMailBox.java` 返回 ≥1 条
- [ ] **CHK12**：lock 重试次数上限：`grep -r "MAX_RETRIES" src/main/java/com/mewcode/teams/FileMailBox.java` 返回值为 `10`
- [ ] **CHK13**：lock 退避随机范围：`grep -r "MIN_SLEEP_MS\|MAX_SLEEP_MS" src/main/java/com/mewcode/teams/FileMailBox.java` 返回 5 和 100
- [ ] **CHK14**：`withLock` 方法用 `finally { Files.deleteIfExists(lock) }` 释放锁
- [ ] **CHK15**：`grep -r "class AgentNameRegistry" src/` 返回 1 条结果
- [ ] **CHK16**：`AgentNameRegistry` 是单例模式（`getInstance()` 静态方法）
- [ ] **CHK17**：`grep -r "class SharedTaskStore" src/` 返回 1 条结果，位于 `com.mewcode.teams` 包
- [ ] **CHK18**：SharedTask 的 `blocks` 和 `blockedBy` 字段类型为 `List<Integer>`

## TeamManager 验收

- [ ] **CHK19**：`grep -r "class TeamManager" src/` 返回 1 条结果，位于 `com.mewcode.teams` 包
- [ ] **CHK20**：`TeamManager` 内部类 `Team` 含 `name`、`mode`、`members`（`Map<String, Member>`）、`mailBox` 字段
- [ ] **CHK21**：`TeamMode` 枚举含 `IN_PROCESS` 和 `TMUX` 两个值
- [ ] **CHK22**：`detectBackend()` 方法检测 `TMUX` 环境变量和 `which tmux` 命令
- [ ] **CHK23**：`Member` 内部类含 `name`、`agent`（AgentLoop 类型）、`conv`（ConversationManager 类型）、`active`（volatile boolean）、`thread` 字段
- [ ] **CHK24**：`createTeam()` 创建的 team 的 mailBox 目录为 `.mewcode/teams/<name>/inboxes/`
- [ ] **CHK25**：`deleteTeam()` 调用 `team.stopAll()` 将所有 member.active 置 false

## 后端验收

- [ ] **CHK26**：`grep -r "class TmuxBackend" src/` 返回 1 条结果
- [ ] **CHK27**：`spawnTmuxTeammate` 执行命令为 `tmux new-window -d -n <paneName> <cliCommand>`
- [ ] **CHK28**：`stopTmuxTeammate` 先 `tmux send-keys -t <paneName> C-c` 等 200ms 再 `tmux kill-window -t <paneName>`
- [ ] **CHK29**：`grep -r "class TeammateRunner" src/` 返回 1 条结果
- [ ] **CHK30**：`LEAD_NAME = "lead"` 常量定义
- [ ] **CHK31**：`SHUTDOWN_PREFIX = "[shutdown]"` 常量定义
- [ ] **CHK32**：空闲轮询间隔：`grep -r "IDLE_POLL_MS" src/main/java/com/mewcode/teams/TeammateRunner.java` 返回 `500`
- [ ] **CHK33**：`[idle]` 通知格式为 `"[idle] <name>: <reason> (at <timestamp>)"`
- [ ] **CHK34**：`drainLeadMailbox` 格式化输出含 `<team-notification team="...">` XML 标签
- [ ] **CHK35**：`buildTeammateAddendum` 输出中告知队员其 team 名称、自己的名字、其他队员列表、可用 SendMessage 通信
- [ ] **CHK36**：`injectPendingMessages` 把未读消息拼为 `"You have new messages:\n\nFrom <x>: <text>"` 格式注入 system reminder
- [ ] **CHK37**：`grep -r "class SpawnDispatcher" src/` 返回 1 条结果
- [ ] **CHK38**：`SpawnConfig` record 含 `team`、`memberName`、`task`、`addendum`、`client`、`registry`、`protocol`、`workdir`
- [ ] **CHK39**：IN_PROCESS 路径起 virtual thread（`Thread.startVirtualThread`）
- [ ] **CHK40**：TMUX 路径的 CLI 命令格式为 `cd <workdir> && <mewcode> --teammate --team-name <t> --agent-name <n>`

## TeamTools 验收

- [ ] **CHK41**：`grep -r "class TeamTools" src/` 返回 1 条结果，位于 `com.mewcode.teams` 包
- [ ] **CHK42**：`SendMessageTool` 构造函数接收 `TeamManager` + `senderName` 两个参数
- [ ] **CHK43**：`SendMessageTool` 的 schema 中 `to` 和 `content` 均为 required
- [ ] **CHK44**：发给 "lead" 的消息能正确路由（`SendMessageTool.execute` 中检测 `"lead".equals(to)`）
- [ ] **CHK45**：`TeamCreateTool` 的 schema 中 `team_name` 为 required
- [ ] **CHK46**：`TeamCreateTool` 在重名时自动追加 `-2`、`-3` 后缀（`grep -r "baseName" src/main/java/com/mewcode/teams/TeamTools.java` 返回 ≥1 条）
- [ ] **CHK47**：`TeamDeleteTool` 删除前输出被停止的成员列表
- [ ] **CHK48**：`SendMessageTool`、`TeamCreateTool`、`TeamDeleteTool` 均 `shouldDefer() = false`

## AgentTool 扩展验收

- [ ] **CHK49**：Agent 工具 schema 含 `team_name` 参数（`grep -r "team_name" src/main/java/com/mewcode/subagent/AgentTool.java` 返回 ≥2 条）
- [ ] **CHK50**：`AgentTool` 有 `setTeamManager(TeamManager)` 方法
- [ ] **CHK51**：`team_name` 非空时先于 Fork/subagent_type 分支判断（`execute()` 方法中 `if (teamName != null && !teamName.isEmpty() && teamManager != null)` 在最前面）
- [ ] **CHK52**：队员派生时，子注册表注册了 `SendMessageTool`
- [ ] **CHK53**：队员派生时，子注册表注册了 `TaskCreateTool`、`TaskGetTool`、`TaskListTool`、`TaskUpdateTool`
- [ ] **CHK54**：`team_name` 为空时（普通子 Agent），子注册表不包含上述协作工具
- [ ] **CHK55**：memberName 从 description 提取（替换空白为 `-`，限 30 字符）
- [ ] **CHK56**：重名 memberName 自动追加 `-2`、`-3` 后缀

## Coordinator 验收

- [ ] **CHK57**：`grep -r "class Coordinator" src/` 返回 1 条结果，位于 `com.mewcode.teams` 包
- [ ] **CHK58**：`ALLOWED_TOOLS` 包含 12 个工具名：Agent、SendMessage、TaskCreate、TaskGet、TaskList、TaskUpdate、TeamCreate、TeamDelete、ReadFile、Glob、Grep、Bash
- [ ] **CHK59**：`ALLOWED_TOOLS` 不包含 `WriteFile` 和 `EditFile`
- [ ] **CHK60**：`grep -r "team.coordinator.enabled" src/main/java/com/mewcode/config/` 返回 ≥1 条
- [ ] **CHK61**：`AppConfig` 中 `teamCoordinatorEnabled` 默认值为 `false`
- [ ] **CHK62**：环境变量 `MEWCODE_COORDINATOR` 检查逻辑存在（`grep -r "MEWCODE_COORDINATOR" src/` 返回 ≥1 条）
- [ ] **CHK63**：只有配置开关和环境变量同时开启时 Coordinator 模式才生效
- [ ] **CHK64**：Coordinator 开启后，通过 tool filter 裁剪到 `ALLOWED_TOOLS`

## CLI 接入验收

- [ ] **CHK65**：`grep -r "\-\-teammate" src/` 返回 ≥1 条（CLI 参数解析）
- [ ] **CHK66**：`grep -r "\-\-team-name" src/` 返回 ≥1 条
- [ ] **CHK67**：`grep -r "\-\-agent-name" src/` 返回 ≥1 条
- [ ] **CHK68**：`/coordinator` slash command 注册在 CommandRegistry

## 主流程接入验收

- [ ] **CHK69**：`MewCode.java` 中初始化了 `TeamManager` 实例
- [ ] **CHK70**：`MewCode.java` 中调用了 `agentTool.setTeamManager(teamManager)`
- [ ] **CHK71**：`TeamCreateTool` 注册到主 ToolRegistry
- [ ] **CHK72**：`TeamDeleteTool` 注册到主 ToolRegistry
- [ ] **CHK73**：Agent loop 每轮迭代开始前调用 `drainLeadMailbox()` 并将结果注入 system reminder
- [ ] **CHK74**：Agent loop 支持 `toolNameFilter` predicate（Coordinator 模式时过滤工具列表）

## 持久化验收

- [ ] **CHK75**：`.mewcode/teams/<name>/inboxes/lead.json` 文件在 Team 创建后存在
- [ ] **CHK76**：`.mewcode/teams/<name>/inboxes/<member>.json` 文件在队员派生后存在
- [ ] **CHK77**：`.mewcode/teams/<name>/tasks.json` 文件在 SharedTaskStore 首次写入后存在
- [ ] **CHK78**：JSON 文件中 `timestamp` 字段为 ISO 8601 格式（`grep -r "ISO_INSTANT\|DateTimeFormatter.ISO_INSTANT" src/main/java/com/mewcode/teams/FileMailBox.java` 返回 ≥1 条）

## 端到端验收

- [ ] **CHK79**：启动 MewCode，输入"帮我创建一个叫 demo 的团队，派一个队员去搜索 src/ 中的 TODO"，确认返回结果中包含 team 名称和 member 名称
- [ ] **CHK80**：队员跑完后，主 Agent 的 system reminder 中出现 `<team-notification team="demo">` 含 `[idle]` 消息
- [ ] **CHK81**：在一个 team 中创建 2 个队员，A 调 SendMessage 发给 B，B 下一轮收到并回复 A，两轮消息都在 inbox JSON 文件中可查
- [ ] **CHK82**：Lead 调 TaskCreate 创建 3 个任务，队员调 TaskList 看到 3 个任务，队员调 TaskUpdate 认领其中 1 个并标记 in_progress，Lead 调 TaskList 看到 owner 已更新
- [ ] **CHK83**：TeamDelete 后，`.mewcode/teams/demo/` 目录下的 inbox 和 tasks.json 仍保留（不删除数据文件），但内存中 team 被移除
- [ ] **CHK84**：普通 Agent 调用（不传 `team_name`）的子 Agent 调 SendMessage 时报 "SendMessage not found" 或模型看不到该工具
- [ ] **CHK85**：两把锁只开一把时，Lead 的工具列表全量（含 WriteFile/EditFile）
- [ ] **CHK86**：两把锁全开时，Lead 调 `Agent` 工具返回的工具列表中不含 `WriteFile` 和 `EditFile`
- [ ] **CHK87**：并发写同一邮箱文件（2 个线程同时 send 给同一 recipient），最终 JSON 文件中消息数量 = 发送总次数（不丢消息）
