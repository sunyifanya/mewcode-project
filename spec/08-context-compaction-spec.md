# spec.md — Context Compaction (两层层压缩)

## 背景

当前 ConversationManager.compressIfNeeded() 在估计 token 超阈值时抽取关键词替换最早的用户-助手消息对。这丢失了几乎全部的上下文细节——文件路径、决策、错误信息都被压缩成了零散关键词。一旦对话累积到几十轮，模型会陷入失忆，用户必须手动清空历史或重新描述任务。

参考项目 mewcode-java 已实现成熟的两层层压缩：第一层对单条工具结果做存盘+裁剪，第二层在逼近上下文窗口上限时调用 LLM 生成结构化摘要并追加恢复附件。本 spec 描述将该方案迁移到 MewCode 的完整设计。

## 目标用户

使用 MewCode 处理长会话、多步骤复杂任务的开发者。典型场景：
- "帮我排查这个 bug"——需要读 10+ 文件、试 5+ 次修复
- "重构这个模块"——跨 20+ 个文件的多人天任务
- 任何单次 LLM 调用装不下的复杂对话

## 能力清单

1. **Layer 1 工具结果预算**：每次 API 请求前走三趟处理——单结果超 15K 字符就存盘留预览；单消息合计超 20K 字符就挑大的依次存盘；超过 10 轮的老结果裁剪到 2K 字符并打上过期标记。

2. **Layer 2 LLM 摘要**：估计 token 超过上下文窗口 80% 时触发，调 LLM 将全部对话压缩为一条结构化摘要。摘要消息后追一条模型确认应答，再追恢复附件（近期读过的文件 + 可用工具列表 + 边界提示），最后回到 agent loop 继续干活。

3. **结构化摘要 Prompt**：系统提示要求模型先输出 `<analysis>` 分析草稿，再输出 `<summary>` 正式摘要。分析草稿用完即丢弃，仅保留 `<summary>` 标签内的内容。摘要请求禁用工具调用。

4. **恢复附件**：最多保留 5 个最近读取的文件快照（每个截断到 5K token），完整工具列表，以及一条警告提示："以上是重构摘要，具体代码请重新读取，不要照摘要脑补"。

5. **熔断器**：Layer 2 连续失败 3 次后停止自动压缩，避免死循环。手动 `/compact` 命令可重置熔断计数器并强制执行。

6. **手动触发**：用户通过 `/compact` 斜杠命令手动触发压缩，安全余量收窄到 3K token（自动触发是 13K）。

7. **近似 Token 估算**：不做精确 tokenizer。增量消息用 字符数÷3.5 估算，每消息加 4 token 固定开销，每工具调用加 50 + args 估算，每工具结果加 10 固定开销。靠 13K（自动）/ 3K（手动）余量兜底误差。

8. **缓存稳定决策**：ContentReplacementState 冻结所有替换决策——同一 toolUseId 一旦被替换，后续轮次复用相同预览字符串，不让 Anthropic prompt-cache 前缀因为重新存盘而失配。

9. **自动触发时机**：Agent Loop 每一轮迭代开始，在构建 LLM 请求前：先无条件跑 Layer 1（ToolResultBudget），再按 token 占比判断是否跑 Layer 2（ContextCompactor.autoCompact）。

10. **用户消息平等参与**：Layer 2 摘要覆盖全部消息（含用户消息、助手消息、工具调用/结果），不做选择性保留。所有消息一起送入摘要 LLM；摘要后对话从摘要消息重新开始。

## 非功能要求

- Layer 1 纯内存 + 文件 IO 操作，无 LLM 调用，典型耗时 <100ms
- Layer 2 增加一次 LLM 往返（约 3-10 秒），仅在超 80% 阈值时触发
- 外溢文件写入 `.mewcode/tool_results/{toolUseId}`（相对工作目录），以 toolUseId 为文件名，幂等（文件已存在且大小一致则跳过）
- Layer 2 摘要请求使用独立 ConversationManager（无系统提示，无工具），不污染主对话
- 熔断器每 Agent Loop 实例独立，不跨会话
- 阈值 80% 基于上下文窗口上限计算（当前硬编码 200K，后续由 AppConfig 提供）

## 设计骨架

```
com.mewcode.compact/                  ← 新包
├── ContextCompactor                  ← 公共入口: manage(), forceCompact(), estimateTokens()
├── AutoCompactTrackingState          ← 熔断器（consecutiveFailures 计数）
└── RecoveryState                     ← 记录最近文件读取 + skill 调用快照

com.mewcode.toolresult/               ← 新包
├── ToolResultBudget                  ← Layer 1: apply() → ApplyResult
├── ContentReplacementState           ← 冻结决策: seenIds + replacements
├── ContentReplacementRecord          ← 单条决策: toolResultId → 替换后预览
├── ApplyResult                       ← 返回值: (apiConv, newRecords)
└── ReplacementRecordsIO              ← 追加记录到磁盘

修改:
├── conversation/Message.java         ← 支持 ToolResultBlock 列表（替换单 ToolResult）
├── conversation/ConversationManager  ← 删除 compressIfNeeded()，增加块级操作方法
├── agent/AgentLoop.java              ← 每轮开始前调 ContextCompactor.manage()
├── agent/AgentEvent.java             ← 新增 CompactEvent 子类型
├── agent/AgentEventType.java         ← 新增 COMPACT 事件枚举
└── tui/TerminalUI.java               ← 处理 /compact 斜杠命令
```

### 关键数据流

```
Agent Loop 每轮开始:
  1. Layer 1 — ToolResultBudget.apply(conv, sessionDir, replacementState)
     → 返回 ApplyResult(新的 ConversationManager, 本次新增记录)
     → 追加记录到 .mewcode/session/replacement-records.jsonl

  2. 估算 token = ContextCompactor.estimateTokens(apiConv.getMessages())
     ratio = token / contextWindow

  3. if ratio > 0.80 && !breaker.isTripped():
       Layer 2 — ContextCompactor.autoCompact(conv, client, contextWindow, recovery, toolSchemas)
       → 替换 conv 内部消息列表为 [摘要消息, 助手确认]
       → 推送 CompactEvent 到事件队列
       成功 → breaker.reset()
       失败 → breaker.recordFailure()

  4. 用 apiConv（来自步骤 1）调用 LLM
```

## Out of Scope

- 精确 tokenizer（tiktoken / Anthropic 官方计数）
- 摘要质量机器学习优化（Prompt 调优、分层摘要策略）
- Skill 系统集成（RecoveryState 保留 skill 记录接口，但文件读取记录是主要实现）
- 跨会话压缩状态持久化（ContentReplacementState 随进程生命周期）
- 子 Agent（forked conversation）的独立压缩
- 单条用户消息超大时的处理（如粘贴 5 万行日志——Layer 1 不处理用户消息）
- Thinking block 专项压缩（thinking 内容作为普通文本参与估算和摘要）
- 压缩后的增量恢复（不尝试从摘要反向重建原始对话）
