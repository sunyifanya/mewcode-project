# tasks.md — Context Compaction (两层层压缩)

## 任务概览

共 11 个任务，按依赖关系排序。任务 1-6 是无依赖的基础组件，任务 7-9 是核心逻辑，任务 10 是用户入口，任务 11 是端到端验证。

---

## 任务 1: Message 增加块级工具结果支持

**影响文件**: `src/main/java/com/mewcode/conversation/Message.java`

**依赖**: 无

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\conversation\Message.java`（ToolResultBlock、ToolUseBlock 的定义和使用）

**内容**:
- 新建内部类或独立 record: `ToolResultBlock(String toolUseId, String content, boolean isError)`
- Message 新增字段 `List<ToolResultBlock> toolResults`（替代当前的单个 `ToolResult toolResult` 和 `String toolUseId`）
- 保留旧字段但标记 `@Deprecated`，旧访问器内部委托到 blocks 列表
- 新增工厂方法 `Message.toolResultsMessage(List<ToolResultBlock> results)`
- 新增 getter/setter: `getToolResults()` / `setToolResults(List<ToolResultBlock>)`

---

## 任务 2: ConversationManager 改造

**影响文件**: `src/main/java/com/mewcode/conversation/ConversationManager.java`

**依赖**: 任务 1

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\conversation\ConversationManager.java:38-42`（addToolResultsMessage 方法）

**内容**:
- 删除 `compressIfNeeded()` 方法及其在 `addUserMessage()` 中的调用
- 新增 `addToolResultsMessage(List<ToolResultBlock> results)` —— 一条 user 消息携带多个 tool_result
- 新增 `addAssistantFull(String text, List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses)` —— 预留 thinking 参数（当前传 null）
- 新增 `getMessagesMutable()` —— 返回内部列表引用（供 ContextCompactor 重建用）
- 保留并委托旧方法: `addToolResultMessage(String toolUseId, ToolResult result)` 内部包装为单元素列表调新方法

---

## 任务 3: ContentReplacementState + ContentReplacementRecord + ApplyResult

**影响文件**:
- `src/main/java/com/mewcode/toolresult/ContentReplacementState.java`（新建）
- `src/main/java/com/mewcode/toolresult/ContentReplacementRecord.java`（新建）
- `src/main/java/com/mewcode/toolresult/ApplyResult.java`（新建）

**依赖**: 无（纯数据结构，不依赖其他任务）

**参考**:
- `D:\mewcode-java\java\src\main\java\com\mewcode\toolresult\ContentReplacementState.java`
- `D:\mewcode-java\java\src\main\java\com\mewcode\toolresult\ContentReplacementRecord.java`
- `D:\mewcode-java\java\src\main\java\com\mewcode\toolresult\ApplyResult.java`

**内容**:
- ContentReplacementState: 维护 `Set<String> seenIds` 和 `Map<String, String> replacements`，提供 `copy()` 方法
- ContentReplacementRecord: record 类型，字段 `String toolResultId` + `String replacementPreview`
- ApplyResult: record 类型，字段 `ConversationManager apiConv` + `List<ContentReplacementRecord> newRecords`

---

## 任务 4: RecoveryState

**影响文件**: `src/main/java/com/mewcode/compact/RecoveryState.java`（新建）

**依赖**: 无

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\compact\RecoveryState.java`

**内容**:
- 内部 record: `FileReadRecord(String path, String content, Instant timestamp)`
- 内部 record: `SkillInvocationRecord(String name, String body, Instant timestamp)`（预留，当前仅记录不使用）
- `recordFileRead(path, content)` —— 覆盖同路径旧记录
- `recordSkillInvocation(name, body)` —— 覆盖同名旧记录（预留）
- `snapshotFiles(int limit)` —— 返回最近 N 个文件记录，按时间倒序
- `snapshotSkills()` —— 返回所有 skill 记录（预留）
- 线程安全（synchronized），因为工具回调可能来自多个虚拟线程

---

## 任务 5: ToolResultBudget（Layer 1 核心）

**影响文件**: `src/main/java/com/mewcode/toolresult/ToolResultBudget.java`（新建）

**依赖**: 任务 1, 2, 3

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\toolresult\ToolResultBudget.java`（完整实现）

**内容**:
- 常量: `SINGLE_RESULT_LIMIT = 15_000`、`MESSAGE_AGGREGATE_LIMIT = 20_000`、`OLD_RESULT_SNIP_CHARS = 2_000`、`KEEP_RECENT_TURNS = 10`、`SPILL_SUBDIR = "tool_results"`
- `apply(ConversationManager conv, Path sessionDir, ContentReplacementState state)` → ApplyResult
- Pass 1: 单结果超 SINGLE_RESULT_LIMIT → spillAndPreview()
- Pass 2: 消息合计超 MESSAGE_AGGREGATE_LIMIT → 从大到小 spill
- Pass 3: snipStale() —— 早于 KEEP_RECENT_TURNS 轮的结果裁剪到 OLD_RESULT_SNIP_CHARS
- `spillAndPreview(Path spillDir, ToolResultBlock tr)` —— 写入文件，返回 "[Result of N chars saved to path]" 预览
- 幂等: 文件已存在且大小匹配则跳过写入
- 状态冻结: 已处理的 ID 加入 seenIds；被替换的 ID 加入 replacements
- 辅助方法: `isAlreadyReplaced(String)` —— 检测已处理标记前缀

---

## 任务 6: ReplacementRecordsIO

**影响文件**: `src/main/java/com/mewcode/toolresult/ReplacementRecordsIO.java`（新建）

**依赖**: 任务 3

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\toolresult\ReplacementRecordsIO.java`

**内容**:
- `append(Path sessionDir, List<ContentReplacementRecord> records)` —— 追加 JSONL 行到 `.mewcode/session/replacement-records.jsonl`
- 每行一个 JSON 对象: `{"toolResultId":"...", "replacementPreview":"..."}`
- 失败时静默忽略（best-effort 持久化，不影响主流程）

---

## 任务 7: ContextCompactor（Layer 2 核心 + 公共入口）

**影响文件**: `src/main/java/com/mewcode/compact/ContextCompactor.java`（新建）
**同时新建**: `src/main/java/com/mewcode/compact/AutoCompactTrackingState.java`

**依赖**: 任务 2, 4, 5

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\compact\ContextCompactor.java`（完整实现）

**内容**:

### AutoCompactTrackingState
- `consecutiveFailures` 计数器
- `isTripped()` → `consecutiveFailures >= 3`
- `recordFailure()` / `reset()`

### ContextCompactor
- 常量: `AUTOCOMPACT_THRESHOLD = 0.80`、`SAFETY_MARGIN_AUTO = 13_000`、`SAFETY_MARGIN_MANUAL = 3_000`、`MAX_CONSECUTIVE_FAILURES = 3`
- 恢复附件常量: `RECOVERY_FILE_LIMIT = 5`、`RECOVERY_TOKENS_PER_FILE = 5_000`、`RECOVERY_CHARS_PER_TOKEN = 3.5`

**公共 API**:
- `manage(ConversationManager conv, LlmClient client, int contextWindow, String workDir, AutoCompactTrackingState tracking, RecoveryState recovery, List<Map<String, Object>> toolSchemas)` → String
  - 无条件执行 Layer 1（委托 ToolResultBudget）
  - 按估算 token 占比判断是否触发 Layer 2
  - 熔断器检查
  - 返回日志消息（如 "spilled 3 tool result(s)..."）

- `forceCompact(ConversationManager conv, LlmClient client, int contextWindow, RecoveryState recovery, List<Map<String, Object>> toolSchemas)` → String
  - 跳过 token 检查，强制执行 Layer 2
  - 安全余量 3K

- `estimateTokens(List<Message> messages)` → int
  - 字符数 ÷ 3.5 + 固定开销（消息 4、工具调用 50+args估算、工具结果 10、thinking同文本）

**内部方法**:
- `autoCompact(...)` —— 序列化消息 → 请求摘要 LLM → 解析 `<summary>` → 构建恢复附件 → 替换对话
- `requestSummary(LlmClient client, String prompt)` —— 独立 ConversationManager，无工具，无系统提示，收集文本直到 StreamEnd
- `serializeForSummary(List<Message>, int toolResultCap=500)` —— 将消息列表转为 LLM 可读文本，工具结果截断到 toolResultCap
- `formatCompactSummary(String raw)` —— 提取 `<summary>...</summary>` 内容
- `buildRecoveryAttachment(RecoveryState, toolSchemas)` —— 渲染四段附件: 最近文件 + skill（预留）+ 工具列表 + 边界提示
- `replaceConversation(ConversationManager target, ConversationManager source)` —— 清空 target 历史，复制 source 历史

**摘要系统提示**: 固定文本（参考实现第 56-79 行），要求 `<analysis>` → `<summary>` 结构，禁止工具调用。

---

## 任务 8: AgentLoop 集成

**影响文件**: `src/main/java/com/mewcode/agent/AgentLoop.java`

**依赖**: 任务 7, 5

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\agent\Agent.java:151-159`（compact 调用点）和 `:120-121`（compact 状态初始化）

**内容**:
- 在 AgentLoop 构造函数或 init 中初始化:
  - `AutoCompactTrackingState compactTracking = new AutoCompactTrackingState()`
  - `RecoveryState recoveryState = new RecoveryState()`
  - `ContentReplacementState replacementState = new ContentReplacementState()`
- 在 `run()` 的每轮迭代开头（LLM 调用前，约第 116 行 `provider.streamChat(...)` 之前）插入:
  ```java
  String compactMsg = ContextCompactor.manage(
      conversation, provider, contextWindow, workingDirectory,
      compactTracking, recoveryState, toolSchemas);
  if (!compactMsg.isEmpty()) {
      offerEvent(AgentEvent.compactEvent(compactMsg));
  }
  
  // Apply Layer 1 budget
  ApplyResult applied = ToolResultBudget.apply(
      conversation, sessionDir, replacementState);
  // ... use applied.apiConv() for the LLM call
  ```
- 从 `StreamingExecutor.executeAll()` 中获取工具结果后，调用 `recoveryState.recordFileRead(path, content)`（当前 ReadingFileTool 等工具返回后）
- 在 context-too-long 错误恢复中调用 `ContextCompactor.forceCompact()`
- 新增 AgentEventType.COMPACT 和对应的 AgentEvent 子类型
- contextWindow 暂硬编码为 200_000（后续从 AppConfig 读取）

---

## 任务 9: AgentEvent 新增 CompactEvent

**影响文件**:
- `src/main/java/com/mewcode/agent/AgentEvent.java`
- `src/main/java/com/mewcode/agent/AgentEventType.java`

**依赖**: 无（可独立完成）

**内容**:
- AgentEventType 枚举新增 `COMPACT`
- AgentEvent 新增静态工厂 `compactEvent(String message)` 或内部类 `CompactEvent`
- CompactEvent 携带压缩日志消息（如 "spilled 3 tool result(s) to disk (~45K chars freed)"）

---

## 任务 10: /compact 手动触发

**影响文件**: `src/main/java/com/mewcode/tui/TerminalUI.java`

**依赖**: 任务 7, 8

**参考**: `D:\mewcode-java\java\src\main\java\com\mewcode\command\CommandRegistry.java`（斜杠命令注册方式，适配当前 MewCode 的 TerminalUI 架构）

**内容**:
- 在 TerminalUI 的命令处理分支中（或现有斜杠命令注册机制中）增加 `/compact` 命令
- `/compact` 行为:
  - 检查 AgentLoop 是否在运行中
  - 调用 `ContextCompactor.forceCompact(conversation, provider, contextWindow, recoveryState, toolSchemas)`
  - 给用户反馈压缩结果（如 "Compacted: 85000 -> 12000 estimated tokens"）
  - 重置熔断计数器

---

## 任务 11: 端到端验证

**影响文件**: 无（验证任务）

**依赖**: 任务 1-10 全部完成

**内容**:
- 启动 MewCode，与模型展开长对话（≥15 轮，每轮含工具调用）
- 观察 Layer 1 是否正常工作：大工具结果被 spill 到 `.mewcode/tool_results/`，对话中只留预览
- 模拟超大上下文：连续 ReadFile 大文件，观察 Layer 2 是否在超 80% 时触发
- 检查摘要后对话是否正常继续（模型能回答关于之前上下文的问题）
- 测试 `/compact` 手动触发是否生效
- 测试熔断器：连续制造 3 次 Layer 2 失败，观察是否停止自动压缩
- 检查恢复附件是否包含最近文件、工具列表和边界提示