# 16-team-worktree-merge-spec.md

## 背景

ch15 已经实现了 Team Lead 的基础能力：Lead 可以创建团队、启动长期 teammate、通过文件邮箱收发消息，teammate 完成任务后进入 idle 状态并等待后续指令。ch14 已经实现了 worktree 隔离能力，普通 sub-agent 在独立 worktree 中产生变更后可以保留工作区供后续处理。

当前 team 能完成“派人”和“通信”，但在实际协作编码场景里还有几个断点：

1. **停止粒度不够**：只能删除整个 team 或用约定消息让 teammate 停止，缺少面向单个成员的停止工具。
2. **worktree 成果不可追踪**：teammate 使用隔离 worktree 修改代码后，Lead 不容易知道具体 worktree 路径、分支和变更状态。
3. **合并缺少安全闭环**：teammate 改完代码后，系统没有提供“先预览再合并”的用户可控流程。
4. **成员命名行为不一致**：工具描述支持命名 teammate，但实际创建路径主要从任务描述派生成员名。
5. **后端分发不一致**：已有多后端分发骨架，但当前 teammate 主路径没有完整复用该骨架，导致 in-process 与 tmux 行为不统一。

本次完善目标是把 team 从“能派人通信”推进到“能安全停止、追踪成果、预览并合并 teammate 代码”的完整协作闭环。

## 目标用户

使用 MewCode 进行多 Agent 编码、审查、迁移和并行修复的开发者。典型场景：

- Lead 启动多个 teammate 分别修改不同模块，并在每个 teammate 完成后查看其独立 worktree 变更。
- Lead 只想停止某个空闲 teammate，而不是删除整个 team。
- Lead 在合并 teammate 代码前先查看变更摘要，确认无误后再执行合并。
- 在 tmux 环境中，teammate 也应按统一派生逻辑工作，不出现与 in-process 路径不一致的行为。

## 能力清单

- 提供停止单个 teammate 的工具，并支持通过消息协议优雅退出。
- teammate 记录自己的隔离 worktree 信息，并在任务完成通知中向 Lead 汇报可追踪的成果位置。
- 提供 team/worktree 变更预览能力，展示 teammate 的 worktree 路径、分支、变更文件和 diff 摘要。
- 提供确认式合并能力：默认只预览，只有明确确认时才把 teammate 变更合入主工作区。
- 合并流程遇到冲突、未提交变更或 git 异常时保守中止，不静默覆盖主工作区内容。
- teammate 命名优先使用显式名称；未提供名称时再从任务描述派生。
- teammate 派生统一走多后端分发路径，in-process 与 tmux 的初始任务、工具注入、worktree 信息和成员登记行为保持一致。
- Lead 能看到 teammate 完成后的 idle 通知、执行结果和 worktree 变更提示。

## 非功能要求

- **安全优先**：所有合并相关操作默认只读预览；写入主工作区必须显式确认。
- **fail-closed**：git 状态无法判断、diff 无法生成、worktree 缺失、主工作区不干净或发生冲突时，默认中止并保留所有文件。
- **可观测**：每次停止、预览、合并都返回明确结果，包含成功/失败原因和下一步建议。
- **兼容现有功能**：不破坏普通 sub-agent、background sub-agent、worktree isolation、TeamCreate、TeamDelete、SendMessage、Task 工具既有行为。
- **跨后端一致**：in-process 与 tmux teammate 在成员登记、邮箱通信和 worktree 汇报上使用同一套语义。
- **最小自动化**：本期不做自动冲突解决，不自动提交 teammate 代码，不自动推送远端。

## 设计骨架

```text
teams/
├── TeamManager              ← 扩展 Member 信息，记录 worktree、pane、停止状态
├── TeamTools                ← 新增单成员停止工具和 team 变更合并工具
├── TeammateRunner           ← 完成通知附带 worktree 成果摘要
├── SpawnDispatcher          ← 统一 teammate 派生入口，接管 in-process/tmux 分支
├── TmuxBackend              ← tmux 停止信息接入成员记录
└── FileMailBox              ← 继续作为停止消息和任务消息通道

subagent/
└── AgentTool                ← 修正 team 路径命名、worktree 记录、多后端分发接入

worktree/
├── WorktreeChanges          ← 复用变更检测能力
└── 新增/扩展合并辅助能力     ← 预览 diff、检查主工作区、执行确认式合并

MewCode 主流程
└── 注册新增 Team 工具，确保 Lead 与 teammate 可见性符合协作语义
```

## Out of Scope

- 不做自动冲突解决。
- 不做自动提交、自动推送或远端 PR 创建。
- 不做跨机器 team 或远程 worktree 合并。
- 不做复杂审批流协议，仅使用工具参数中的显式确认作为合并开关。
- 不重写文件邮箱存储格式。
- 不实现图形化 diff UI。
- 不保证进程崩溃后恢复所有 in-memory teammate 状态；本期只保证已有落盘 worktree 和 inbox 不被误删。

## 验收标准

- AC1：创建 teammate 时传入显式名称，后续 SendMessage 和停止操作均能按该名称定位成员。
- AC2：未传显式名称时，系统仍能从任务描述生成稳定且不冲突的成员名。
- AC3：停止单个 teammate 后，该成员不再继续轮询 inbox；同 team 的其他成员不受影响。
- AC4：删除整个 team 时，所有成员停止，且返回被停止成员列表。
- AC5：teammate 使用 worktree 隔离并产生代码变更后，Lead 收到的完成通知包含 worktree 路径、分支和变更提示。
- AC6：预览工具可以列出目标 teammate 的 worktree 状态、变更文件数量、提交数量和 diff 摘要。
- AC7：未显式确认时，合并工具只返回预览信息，不修改主工作区。
- AC8：显式确认且主工作区安全时，合并工具能把 teammate worktree 的变更合入主工作区。
- AC9：合并发生冲突或主工作区不干净时，工具中止并报告原因，不丢弃 teammate worktree。
- AC10：in-process teammate 与 tmux teammate 都通过统一派生入口登记成员、接收初始任务并汇报 worktree 信息。
- AC11：普通未指定 team 的 sub-agent 行为不变。
- AC12：项目编译通过，已有 team、subagent、worktree 相关测试或手工验收场景不回退。
