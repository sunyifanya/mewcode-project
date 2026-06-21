# checklist.md — Context Compaction (两层层压缩)

每项必须可勾选、可通过命令或观察直接验证。所有阈值和具体值与 spec/tasks 保持一致。

---

## Layer 1: 工具结果预算

- [ ] 构造单工具结果 20,000 字符，触发 agent loop 一轮后，检查该结果被替换为 `[Result of 20000 chars saved to ...]` 格式的预览字符串
- [ ] 验证外溢文件存在于 `.mewcode/tool_results/{toolUseId}`，内容完整（字节数 = 原始结果长度）
- [ ] 同一 toolUseId 重复触发 Layer 1 时，不重新写入文件（幂等），预览字符串保持相同
- [ ] 单条消息含 3 个工具结果（分别 5K、8K、12K 字符，合计 25K > 20K），触发后：最大的 12K 先被 spill，8K 其次，直到合计 ≤ 20K 或所有结果已处理
- [ ] 创建一个 ≥11 轮前的工具结果（3,000 字符），当前在第 12+ 轮：该结果被替换为 `[Stale output snipped: 3000 chars]`
- [ ] 最近 5 轮内的工具结果（3,000 字符）保持原文不变，不被 snip
- [ ] 已处理过的结果（前缀含 `[Result of ` 或 `[Stale output snipped:`）不被重复处理

## Layer 2: LLM 摘要

- [ ] 构造对话使估计 token 数超过 160,000（200K × 80%），观察 Layer 2 自动触发
- [ ] 摘要后的对话包含一条 user 消息（含 `[Compacted conversation summary]` 前缀和恢复附件）、一条 assistant 消息（确认文本）
- [ ] 摘要消息数量 < 原始消息数量（压缩有效果）
- [ ] 摘要内容中可找到原始对话中的关键文件路径和决策信息
- [ ] 恢复附件末尾包含边界提示：`Everything above the divider is reconstructed context. For exact code, error strings, or user-typed text, re-read the source rather than guess from the summary.`

## 熔断器

- [ ] 连续 3 次 Layer 2 失败（例如 API 返回错误），第 4 次需要 Layer 2 时不再触发自动压缩
- [ ] `/compact` 手动触发不受熔断器影响，强制执行
- [ ] 手动触发成功后熔断计数器重置为 0

## 手动触发

- [ ] 在对话中输入 `/compact`，Layer 2 强制执行，返回 "Compacted: N -> M estimated tokens"
- [ ] 手动触发时安全余量收窄到 3K token（即估计 token ≥ contextWindow - 3K 也可触发，不要求 ≥80%）

## Token 估算

- [ ] 空消息列表 `estimateTokens()` 返回 0
- [ ] 单条纯文本消息 "hello world"（11 字符），estimateTokens() ≈ 11/3.5 + 4 ≈ 7
- [ ] 工具调用消息（含 args JSON 100 字符），estimateTokens() ≥ 50 + 100/3.5 + 文本估算 + 4
- [ ] 工具结果消息（1000 字符），estimateTokens() ≥ 1000/3.5 + 10

## ContentReplacementState

- [ ] 同一 toolUseId 第一次经 ToolResultBudget 处理后，seenIds 和 replacements（如被替换）均包含该 ID
- [ ] 第二次处理同一 ID 时，直接从 replacements 返回缓存预览，不重复 spill
- [ ] `copy()` 返回独立副本，修改副本不影响原对象

## RecoveryState

- [ ] `recordFileRead("/path/to/file.java", "content...")` 后 `snapshotFiles(5)` 返回含该记录
- [ ] 同一路径重复 record，snapshot 只保留最新记录和时间戳
- [ ] `snapshotFiles(3)` 返回最近 3 条，不超 limit

## 集成验证

- [ ] `ConversationManager` 不再包含 `compressIfNeeded()` 方法（`grep -r "compressIfNeeded" src/` 返回空）
- [ ] `ConversationManager.addUserMessage()` 不再调用压缩逻辑（源码审查确认）
- [ ] AgentEventType 枚举含 `COMPACT` 值（`grep "COMPACT" src/main/java/com/mewcode/agent/AgentEventType.java` 返回 ≥1 条）
- [ ] AgentEvent 含 CompactEvent 子类型或 compactEvent 工厂方法

## 端到端验收

- [ ] 启动 MewCode，发送 "读 20 个不同的大文件（每个 >5K 行）并总结"——观察 Layer 1 spill 日志出现在终端输出，Layer 2 在超 80% 阈值后触发，压缩后对话正常继续，模型能引用之前读过的文件路径
- [ ] `/compact` 命令执行后，检查 `.mewcode/tool_results/` 目录内容完整，`.mewcode/session/replacement-records.jsonl` 有追加记录
- [ ] 在 Layer 2 触发后，问模型 "刚才你读过的 X 文件里有什么？"——模型应能说出关键内容（因恢复附件保留了文件快照），不应说 "我没有读到那个信息"
