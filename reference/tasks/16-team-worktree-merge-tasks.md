# 16-team-worktree-merge-tasks.md

## 任务总览

| # | 任务 | 依赖 | 影响文件 |
|---|------|------|----------|
| T1 | 扩展 Team 成员状态模型 | 无 | `TeamManager.java` |
| T2 | 修正 teammate 命名与派生输入 | T1 | `AgentTool.java`、`SpawnDispatcher.java` |
| T3 | 统一 SpawnDispatcher in-process/tmux 派生 | T1, T2 | `SpawnDispatcher.java`、`AgentTool.java`、`MewCode.java` |
| T4 | 增加单成员停止能力 | T1, T3 | `TeamTools.java`、`TeamManager.java`、`TmuxBackend.java` |
| T5 | 增加 worktree 变更摘要能力 | T1 | `WorktreeChanges.java`、新增 `TeamWorktreeSummary.java` |
| T6 | teammate 完成通知附带 worktree 信息 | T1, T5 | `TeammateRunner.java`、`AgentTool.java`、`MewCode.java` |
| T7 | 实现 TeamMerge 预览模式 | T1, T5 | `TeamTools.java`、新增 `TeamWorktreeMerge.java` |
| T8 | 实现确认式合并模式 | T7 | `TeamWorktreeMerge.java`、`TeamTools.java` |
| T9 | 强化安全检查和失败回滚 | T8 | `TeamWorktreeMerge.java`、`WorktreeChanges.java` |
| T10 | tmux teammate 路径对齐 worktree 与停止语义 | T3, T4, T6 | `MewCode.java`、`SpawnDispatcher.java`、`TeamManager.java` |
| T11 | 接入主流程 | T4, T7, T8, T10 | `MewCode.java`、`AgentTool.java` |
| T12 | 端到端验证 | T11 | 无新文件 |

---

## T1: 扩展 Team 成员状态模型

**影响文件：**
- `src/main/java/com/mewcode/teams/TeamManager.java`

**依赖任务：** 无

**参考资料定位：**
- `TeamManager.Member` 当前字段：`src/main/java/com/mewcode/teams/TeamManager.java` 中 `Member` 内部类
- `AgentWorktree.Result` 字段：`src/main/java/com/mewcode/worktree/AgentWorktree.java`
- `TmuxBackend.spawnTmuxTeammate` 返回 pane name：`src/main/java/com/mewcode/teams/TmuxBackend.java`

**步骤：**
1. 在 `TeamManager.Member` 中增加可选 worktree 信息字段，用于记录 teammate 隔离工作区路径、分支、初始 HEAD 和 git root。
2. 在 `TeamManager.Member` 中增加可选 tmux pane 标识字段，用于后续停止 tmux teammate。
3. 在 `TeamManager.Member` 中增加最近一次完成状态/摘要字段，保存最近一次完成原因和变更摘要，供 TeamMerge 预览使用。
4. 为 `Team` 增加按成员名安全获取成员信息的方法，避免外部直接操作成员 map。
5. 保持现有 `addMember`、`stopMember`、`stopAll` 的调用兼容性。

**验证：**
- 运行 `mvn test` 或项目当前编译命令，期望编译通过。
- 搜索 `worktree` 相关字段，确认只在 `Member` 或辅助方法中新增，不影响 `TeamCreate` 基础路径。

---

## T2: 修正 teammate 命名与派生输入

**影响文件：**
- `src/main/java/com/mewcode/subagent/AgentTool.java`
- `src/main/java/com/mewcode/teams/SpawnDispatcher.java`

**依赖任务：** T1

**参考资料定位：**
- `AgentTool` schema 中 `name` 参数：`src/main/java/com/mewcode/subagent/AgentTool.java`
- `AgentTool.runAsTeammate` 当前从 description 派生成员名的位置：`src/main/java/com/mewcode/subagent/AgentTool.java`
- `SpawnDispatcher.SpawnConfig` 当前字段：`src/main/java/com/mewcode/teams/SpawnDispatcher.java`

**步骤：**
1. 调整 `AgentTool.execute` 的 team 分支，把 `name` 参数传入 teammate 派生方法。
2. 修改 teammate 成员名规则：优先使用显式 `name`；未提供时才从 `description` 派生。
3. 对显式 `name` 和派生名使用同一套清洗规则：空白转 `-`、转小写、长度上限、非法字符保守替换。
4. 保留重名自动追加 `-2`、`-3` 的逻辑。
5. 更新工具描述，明确 `name` 是 teammate 的稳定通信名。
6. 将最终 memberName 写入 `SpawnConfig`，后续统一由 `SpawnDispatcher` 使用。

**验证：**
- 运行编译，期望通过。
- 静态检查 `AgentTool` 中 team 分支确实传递 `name`。
- 手工调用 Agent 工具 schema 时能看到 `name` 和 `team_name` 两个参数说明。

---

## T3: 统一 SpawnDispatcher in-process/tmux 派生

**影响文件：**
- `src/main/java/com/mewcode/teams/SpawnDispatcher.java`
- `src/main/java/com/mewcode/subagent/AgentTool.java`
- `src/main/java/com/mewcode/MewCode.java`

**依赖任务：** T1, T2

**参考资料定位：**
- `SpawnDispatcher.spawnTeammate` 当前未被主路径调用
- `AgentTool.runAsTeammate` 当前手写首轮同步执行逻辑
- `MewCode.runTeammateMode` 当前 tmux teammate 入口逻辑

**步骤：**
1. 让 `AgentTool.runAsTeammate` 构造完整 `SpawnConfig`，并调用 `SpawnDispatcher.spawnTeammate`，避免重复维护 in-process 和 tmux 分支。
2. 在 `SpawnConfig` 中补齐 teammate 所需的权限、max turns、stream timeout、workdir、worktree 信息和工具注册表。
3. in-process 路径继续创建 `ConversationManager`、`Member`、虚拟线程，并由 `TeammateRunner.runInProcessTeammate` 完成首轮和 idle loop。
4. tmux 路径先写入初始任务到成员 inbox，再启动独立 teammate 进程，并登记 placeholder member、pane 信息和 worktree 信息。
5. 保证 `AgentTool` 返回内容中包含最终 memberName、teamName、mode，以及如果有 worktree 则包含 worktree 路径/分支。
6. 保持普通 sync/background/fork sub-agent 路径不经过 `SpawnDispatcher`。

**验证：**
- `grep -r "SpawnDispatcher.spawnTeammate" src/main/java/com/mewcode/subagent/AgentTool.java` 返回 ≥1 条。
- 编译通过。
- 不传 `team_name` 的 Agent 调用路径仍进入原 sync/background/fork 分支。

---

## T4: 增加单成员停止能力

**影响文件：**
- `src/main/java/com/mewcode/teams/TeamTools.java`
- `src/main/java/com/mewcode/teams/TeamManager.java`
- `src/main/java/com/mewcode/teams/TmuxBackend.java`

**依赖任务：** T1, T3

**参考资料定位：**
- `TeamManager.Team.stopMember` 当前内部方法
- `TeammateRunner.SHUTDOWN_PREFIX` 常量
- `TmuxBackend.stopTmuxTeammate` 当前停止 tmux window 方法
- `TeamDeleteTool` 工具结构可作为新增工具参考

**步骤：**
1. 新增 `TeamStopMemberTool`，参数为 `team_name` 和 `member_name`。
2. 工具执行时先查 team，再查 member；不存在时返回明确错误。
3. 对 in-process teammate：向成员 inbox 写入 `[shutdown]` 消息，并 interrupt 成员线程。
4. 对 tmux teammate：向成员 inbox 写入 `[shutdown]` 消息；如果成员记录中有 pane 标识，则调用 tmux 停止方法做 best-effort 清理。
5. 修改 `TeamDeleteTool` 或 `TeamManager.stopAll`，确保删除 team 时也尽量使用统一停止语义，返回被停止成员列表。
6. 工具返回中包含 team、member、停止方式、是否找到线程/pane。

**验证：**
- 编译通过。
- `TeamTools` 中存在 `TeamStopMember` 工具名。
- 对不存在成员调用时返回错误，不抛异常。
- 对某个成员停止后，同 team 其他成员仍在成员列表中。

---

## T5: 增加 worktree 变更摘要能力

**影响文件：**
- `src/main/java/com/mewcode/worktree/WorktreeChanges.java`
- `src/main/java/com/mewcode/teams/TeamWorktreeSummary.java`（新建）

**依赖任务：** T1

**参考资料定位：**
- `WorktreeChanges.hasChanges` 和 `countChanges`
- `AgentWorktree.Result` 的 `worktreePath`、`worktreeBranch`、`headCommit`、`gitRoot`

**步骤：**
1. 新增 team 专用 worktree 摘要结构，包含 worktree 路径、分支、是否有变更、变更文件数、提交数、状态文本和错误信息。
2. 复用或扩展 `WorktreeChanges.countChanges`，支持从 `AgentWorktree.Result` 生成摘要。
3. 生成摘要时遵循 fail-closed：无法判断时返回 unknown/unsafe 状态，而不是假定无变更。
4. 为后续预览工具提供短文本格式化方法，避免多个工具重复拼接输出。

**验证：**
- 编译通过。
- 对不存在 worktree 路径生成摘要时返回 unsafe/unknown，不返回 clean。
- 对无改动 worktree 返回 changedFiles=0、commits=0。

---

## T6: teammate 完成通知附带 worktree 信息

**影响文件：**
- `src/main/java/com/mewcode/teams/TeammateRunner.java`
- `src/main/java/com/mewcode/subagent/AgentTool.java`
- `src/main/java/com/mewcode/MewCode.java`

**依赖任务：** T1, T5

**参考资料定位：**
- `TeammateRunner.createIdleNotification`
- `TeammateRunner.drainLeadMailbox`
- `AgentTool.runAsTeammate` 当前 worktree 创建逻辑
- `AgentWorktree.buildNotice`

**步骤：**
1. 在 teammate 创建时把 `AgentWorktree.Result` 保存到 `Member`。
2. `TeammateRunner` 每次完成初始任务或 follow-up 后，根据成员 worktree 信息生成变更摘要。
3. idle 通知中附带 worktree 路径、分支和变更状态；没有 worktree 时保持现有简短 idle 通知。
4. 保存最近一次 worktree 摘要到 `Member`，供后续 TeamMerge 预览使用。
5. tmux teammate 入口也能从参数或成员登记信息中保留 worktree 信息；无法保留时明确标记 unavailable。

**验证：**
- 编译通过。
- 使用 worktree 隔离的 teammate 完成后，lead mailbox 中的消息包含 `Worktree`、路径和 branch。
- 未使用 worktree 的 teammate 完成后，不出现误导性的 worktree 路径。

---

## T7: 实现 TeamMerge 预览模式

**影响文件：**
- `src/main/java/com/mewcode/teams/TeamTools.java`
- `src/main/java/com/mewcode/teams/TeamWorktreeMerge.java`（新建）

**依赖任务：** T1, T5

**参考资料定位：**
- `TeamTools.TeamCreateTool` / `TeamDeleteTool` 工具实现风格
- `WorktreeChanges.countChanges`
- `AgentWorktree.Result`

**步骤：**
1. 新增 `TeamMergeTool`，参数包含 `team_name`、`member_name`、`mode`、`confirm`。
2. `mode` 默认值为预览；未传 `confirm=true` 时不修改主工作区。
3. 预览输出包含 team、member、worktree path、branch、changed files、commits、`git status --porcelain` 摘要和短 diff/stat。
4. 当成员没有 worktree 信息时，返回明确错误，提示该 teammate 未使用 worktree 隔离或信息不可用。
5. 当 worktree 无变更时，返回 clean 状态，不执行合并。
6. 输出中明确提示：若要合并，需再次调用并显式确认。

**验证：**
- 编译通过。
- `TeamMerge` 不带确认参数时，不修改主工作区。
- 对无 worktree 成员调用时返回可读错误。

---

## T8: 实现确认式合并模式

**影响文件：**
- `src/main/java/com/mewcode/teams/TeamWorktreeMerge.java`
- `src/main/java/com/mewcode/teams/TeamTools.java`

**依赖任务：** T7

**参考资料定位：**
- `AgentWorktree.create` 创建的 worktree 分支命名
- `WorktreeChanges.hasChanges` fail-closed 语义
- 项目现有 `WorktreeManager.detectChanges` 可参考 git 命令执行方式

**步骤：**
1. 确认模式下，先检查主工作区是否安全：没有未提交变更，且当前 git 状态可读取。
2. 检查 teammate worktree 是否存在且可读取。
3. 如果 teammate worktree 有未提交变更，先生成 patch 并应用到主工作区；如果 teammate worktree 有新 commit，则优先使用 git merge/cherry-pick 分支变更。
4. 合并前再次输出将执行的策略，并要求工具参数中的 `confirm=true` 才继续。
5. 合并成功后返回合并策略、变更文件数和后续建议。
6. 合并后不自动删除 teammate worktree，保留给用户确认。

**验证：**
- 编译通过。
- confirm=false 时只预览。
- confirm=true 且主工作区 clean 时，能把一个简单文件变更合入主工作区。
- 合并后 teammate worktree 仍存在。

---

## T9: 强化安全检查和失败回滚

**影响文件：**
- `src/main/java/com/mewcode/teams/TeamWorktreeMerge.java`
- `src/main/java/com/mewcode/worktree/WorktreeChanges.java`

**依赖任务：** T8

**参考资料定位：**
- `WorktreeChanges` 注释中的 fail-closed 语义
- `AgentWorktree.remove` 中 best-effort git 命令处理风格

**步骤：**
1. git 命令统一设置 `GIT_TERMINAL_PROMPT=0` 和空 `GIT_ASKPASS`，避免交互式阻塞。
2. 所有 git 命令设置超时，超时则中止并返回错误。
3. 合并前记录主工作区 HEAD 和 status；失败时不执行 destructive reset，除非能证明只应用了当前工具生成的 patch 且可安全回退。
4. 冲突时返回冲突文件列表和建议命令，不自动解决冲突。
5. 任何异常情况下保留 teammate worktree 和分支。
6. 输出中区分 `blocked`、`conflict`、`failed`、`merged`、`preview` 状态。

**验证：**
- 编译通过。
- 主工作区有未提交变更时 confirm=true 返回 blocked，不修改文件。
- 构造冲突场景时返回 conflict/failed 状态，并保留 worktree。

---

## T10: tmux teammate 路径对齐 worktree 与停止语义

**影响文件：**
- `src/main/java/com/mewcode/MewCode.java`
- `src/main/java/com/mewcode/teams/SpawnDispatcher.java`
- `src/main/java/com/mewcode/teams/TeamManager.java`

**依赖任务：** T3, T4, T6

**参考资料定位：**
- `MewCode` 中 `--teammate --team-name --agent-name` 参数解析
- `MewCode.runTeammateMode` 当前 teammate CLI 模式
- `SpawnDispatcher.buildTeammateCLI`

**步骤：**
1. tmux spawn 命令中携带必要的 teammate 元信息，至少保证 team name、agent name、working directory 一致。
2. 独立 teammate 进程启动后创建 placeholder team/member，并正确注册 `SendMessage`。
3. tmux teammate 收到 `[shutdown]` 时退出 idle loop。
4. 如果 tmux teammate 使用 worktree，确保其工作目录就是对应 worktree，且完成通知能包含该路径。
5. Lead 进程中的 placeholder member 保存 pane 信息，方便 `TeamStopMember` best-effort 停止。

**验证：**
- 编译通过。
- `SpawnDispatcher.buildTeammateCLI` 仍输出 `--teammate --team-name --agent-name`。
- 在无 tmux 环境下不影响 IN_PROCESS 路径。

---

## T11: 接入主流程

**影响文件：**
- `src/main/java/com/mewcode/MewCode.java`
- `src/main/java/com/mewcode/subagent/AgentTool.java`

**依赖任务：** T4, T7, T8, T10

**参考资料定位：**
- `MewCode.java` 中 Team 工具注册位置
- `AgentTool` 中 team manager 注入位置
- `Coordinator.ALLOWED_TOOLS` 白名单

**步骤：**
1. 在主 `ToolRegistry` 注册 `TeamStopMember` 和 `TeamMerge`。
2. 如果 coordinator 模式启用，将新增工具加入 coordinator 白名单。
3. 确保 teammate 子 registry 中有必要的协作工具，但普通 sub-agent 不额外获得 Team 工具。
4. `AgentTool` 返回给 Lead 的 teammate 创建结果包含可用于后续停止/合并的成员名。
5. 保持 `TeamDelete`、`SendMessage`、Task 工具注册顺序和现有行为。

**验证：**
- 编译通过。
- 主工具列表中能看到新增 Team 工具。
- 普通 sub-agent 不因本次改动获得新增 Team 工具。

---

## T12: 端到端验证

**影响文件：** 无新文件

**依赖任务：** T11

**参考资料定位：**
- `reference/spec/16-team-worktree-merge-spec.md` 验收标准 AC1-AC12
- `reference/checklist/16-team-worktree-merge-checklist.md`

**步骤：**
1. 编译整个项目。
2. 手工或测试方式创建 team，启动显式命名 teammate，确认 SendMessage 能按名称送达。
3. 停止单个 teammate，确认其他 teammate 不受影响。
4. 使用 worktree 隔离启动 teammate，让其产生一个简单代码或文本变更。
5. 预览 TeamMerge，确认只输出状态和 diff 摘要，不修改主工作区。
6. 在 clean 主工作区中确认合并，确认变更进入主工作区且 worktree 保留。
7. 构造主工作区 dirty 或冲突场景，确认合并中止且不丢 worktree。
8. 在无 tmux 环境下确认 in-process 路径可用；如有 tmux 环境，确认 tmux 命令构造和停止语义可用。

**验证：**
- checklist 中所有可执行条目通过。
- 未通过条目记录实际输出和修复方案。
