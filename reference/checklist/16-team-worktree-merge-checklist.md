# 16-team-worktree-merge-checklist.md

> 每一项都必须能通过运行命令、调用工具或观察文件状态验证。

## 文档与接入

- [ ] **CHK01**：`reference/spec/16-team-worktree-merge-spec.md`、`reference/tasks/16-team-worktree-merge-tasks.md`、`reference/checklist/16-team-worktree-merge-checklist.md` 三个文件存在。
- [ ] **CHK02**：`grep -r "TeamStopMember" src/main/java/com/mewcode` 返回 ≥1 条工具实现或注册结果。
- [ ] **CHK03**：`grep -r "TeamMerge" src/main/java/com/mewcode` 返回 ≥1 条工具实现或注册结果。
- [ ] **CHK04**：`grep -r "SpawnDispatcher.spawnTeammate" src/main/java/com/mewcode/subagent/AgentTool.java` 返回 ≥1 条，说明 teammate 主路径接入统一分发。

## 成员命名与生命周期

- [ ] **CHK05**：Agent 工具 schema 中 `name` 参数说明包含 teammate 稳定通信名含义（验证：查看工具描述或搜索 `name` 参数描述）。
- [ ] **CHK06**：使用 `Agent(team_name="demo", name="reviewer", description="security review", prompt="...")` 后，返回结果中的 member 名称为 `reviewer` 或其去重后缀形式。
- [ ] **CHK07**：不传 `name` 时，member 名称仍从 `description` 派生，空白转 `-`，长度不超过 30 个字符。
- [ ] **CHK08**：同一 team 中创建两个同名 teammate，第二个名称自动追加 `-2` 或更高后缀。
- [ ] **CHK09**：`TeamStopMember(team_name="demo", member_name="reviewer")` 返回成功，并包含 team、member 和停止方式。
- [ ] **CHK10**：停止一个 member 后，同 team 的其他 member 仍可通过 `SendMessage` 收到消息。
- [ ] **CHK11**：对不存在的 team 调用 `TeamStopMember` 返回错误文本，不抛未捕获异常。
- [ ] **CHK12**：对不存在的 member 调用 `TeamStopMember` 返回错误文本，不影响已有 member。
- [ ] **CHK13**：`TeamDelete` 返回文本包含被停止成员列表。

## worktree 信息记录与汇报

- [ ] **CHK14**：`TeamManager.Member` 中存在记录 teammate worktree 路径、分支、初始 HEAD 或等价 worktree result 的字段。
- [ ] **CHK15**：`TeamManager.Member` 中存在记录 tmux pane 或外部后端标识的字段。
- [ ] **CHK16**：使用 `isolation="worktree"` 创建 teammate 后，返回结果中包含 worktree path 和 branch。
- [ ] **CHK17**：使用 `isolation="worktree"` 的 teammate 完成初始任务后，lead inbox 或 `<team-notification>` 中包含 worktree path 和 branch。
- [ ] **CHK18**：未使用 worktree 的 teammate 完成任务后，通知中不出现假的 worktree path。
- [ ] **CHK19**：worktree 路径不存在时，摘要状态为 unknown/unsafe 或错误状态，不显示为 clean。
- [ ] **CHK20**：worktree 无变更时，预览摘要显示 changed files = 0 且 commits = 0。

## TeamMerge 预览

- [ ] **CHK21**：`TeamMerge` 参数 schema 包含 `team_name`、`member_name`、`mode` 或等价操作参数、`confirm`。
- [ ] **CHK22**：`TeamMerge(team_name="demo", member_name="reviewer")` 默认只预览，不修改主工作区。
- [ ] **CHK23**：预览输出包含 team 名、member 名、worktree path、branch。
- [ ] **CHK24**：预览输出包含 changed files 数量或 `git status --porcelain` 摘要。
- [ ] **CHK25**：预览输出包含 diff stat 或短 diff 摘要。
- [ ] **CHK26**：对没有 worktree 信息的 member 调用 `TeamMerge` 返回明确错误，提示无法合并或未使用 worktree。
- [ ] **CHK27**：对 clean worktree 调用预览时显示无变更，不要求合并。
- [ ] **CHK28**：未显式 `confirm=true` 时，主工作区 `git status --porcelain` 与调用前一致。

## TeamMerge 确认式合并

- [ ] **CHK29**：主工作区 clean 且 teammate worktree 有一个简单未提交文件变更时，`TeamMerge(..., confirm=true)` 能把变更应用到主工作区。
- [ ] **CHK30**：主工作区 clean 且 teammate worktree 有新 commit 时，`TeamMerge(..., confirm=true)` 能把 commit 变更合入或报告已采用的等价合并策略。
- [ ] **CHK31**：合并成功后返回状态为 merged 或等价成功状态。
- [ ] **CHK32**：合并成功后返回变更文件数量和后续建议。
- [ ] **CHK33**：合并成功后 teammate worktree 目录仍存在。
- [ ] **CHK34**：合并工具不会自动 push 远端（验证：代码中无 `git push` 调用或手工日志无 push）。
- [ ] **CHK35**：合并工具不会自动 commit teammate 未提交变更，除非用户另行执行 git commit。

## 安全与失败场景

- [ ] **CHK36**：主工作区存在未提交变更时，`TeamMerge(..., confirm=true)` 返回 blocked/failed，不修改主工作区。
- [ ] **CHK37**：worktree 路径缺失时，合并返回 failed/unknown，不删除任何分支或目录。
- [ ] **CHK38**：构造同一文件同一行冲突时，合并中止并报告冲突或失败状态。
- [ ] **CHK39**：冲突或失败后 teammate worktree 仍存在。
- [ ] **CHK40**：git 命令执行环境设置了非交互行为（验证：代码中设置 `GIT_TERMINAL_PROMPT=0` 和 `GIT_ASKPASS` 或等价处理）。
- [ ] **CHK41**：git 命令有超时控制，超时后返回错误而不是无限等待。
- [ ] **CHK42**：任何合并失败路径都不会调用无条件 destructive reset 清空用户改动（验证：代码审查或搜索危险命令）。

## SpawnDispatcher 与 tmux 对齐

- [ ] **CHK43**：`AgentTool` team 分支不再手写完整 in-process 首轮执行逻辑，而是通过 `SpawnDispatcher` 统一分发。
- [ ] **CHK44**：in-process 分支创建 virtual thread 并调用 `TeammateRunner.runInProcessTeammate`。
- [ ] **CHK45**：tmux 分支先向成员 inbox 写入初始任务，再构建 `--teammate --team-name --agent-name` 命令。
- [ ] **CHK46**：tmux placeholder member 保存 pane 标识，可被 `TeamStopMember` 使用。
- [ ] **CHK47**：`MewCode` 的 teammate CLI 模式收到 `[shutdown]` 消息后退出 idle loop。
- [ ] **CHK48**：无 tmux 环境下，TeamCreate 仍能创建 IN_PROCESS team 并正常启动 teammate。

## 主流程与工具可见性

- [ ] **CHK49**：`MewCode.java` 注册 `TeamStopMember` 和 `TeamMerge`。
- [ ] **CHK50**：coordinator 白名单包含新增的 team 停止和合并工具。
- [ ] **CHK51**：Lead 可以调用 `TeamStopMember`、`TeamMerge`、`SendMessage`、`TeamCreate`、`TeamDelete`。
- [ ] **CHK52**：普通未指定 `team_name` 的 sub-agent 不额外获得 `TeamStopMember` 或 `TeamMerge`。
- [ ] **CHK53**：teammate 仍至少拥有 `SendMessage`，可以向 lead 汇报结果。
- [ ] **CHK54**：已有 `TaskCreate`、`TaskGet`、`TaskList`、`TaskUpdate` 注册行为不回退。

## 编译与回归

- [ ] **CHK55**：运行项目编译命令成功，退出码为 0。
- [ ] **CHK56**：运行现有测试成功，退出码为 0；如没有测试，记录实际原因。
- [ ] **CHK57**：`grep -r "TeamCreate" src/main/java/com/mewcode` 仍能找到原有创建工具。
- [ ] **CHK58**：`grep -r "TeamDelete" src/main/java/com/mewcode` 仍能找到原有删除工具。
- [ ] **CHK59**：普通 Agent 调用不传 `team_name` 时，仍能按原 sync/background/fork 逻辑完成任务。

## 端到端场景

- [ ] **CHK60**：端到端：创建 team `demo` → 启动 `name="worker"` 的 teammate → teammate 完成后 lead 收到 `<team-notification>`，其中包含 `[idle]`。
- [ ] **CHK61**：端到端：Lead 给 `worker` 发送后续消息 → `worker` 被唤醒并完成 follow-up → lead 收到新的 idle 通知。
- [ ] **CHK62**：端到端：启动两个 teammate → 停止其中一个 → 另一个仍可接收 SendMessage 并回复。
- [ ] **CHK63**：端到端：使用 worktree 隔离 teammate 产生简单文件变更 → TeamMerge 预览显示该变更 → confirm=true 后主工作区出现该变更。
- [ ] **CHK64**：端到端：主工作区 dirty 时重复 TeamMerge confirm=true → 工具拒绝合并，并且 teammate worktree 仍保留。
