# tasks.md — Agent Loop 实现任务

## Task 1: Tool 接口增加 isReadOnly() + 全部实现类适配
**影响文件**: `tool/Tool.java`, `tool/impl/ReadFileTool.java`, `tool/impl/WriteFileTool.java`, `tool/impl/EditFileTool.java`, `tool/impl/GlobTool.java`, `tool/impl/GrepTool.java`, `tool/impl/ExecuteCommandTool.java`
**依赖**: 无
**参考**:
- `tool/Tool.java:33` — execute 方法所在位置，在接口中新增 `boolean isReadOnly()` 默认返回 false
- `tool/impl/ReadFileTool.java:1` — 类声明，新增 `@Override public boolean isReadOnly() { return true; }`
- `tool/impl/GlobTool.java`, `tool/impl/GrepTool.java` — 同上，返回 true
- `tool/impl/WriteFileTool.java`, `tool/impl/EditFileTool.java`, `tool/impl/ExecuteCommandTool.java` — 返回 false（或依靠接口默认值不写）

---

## Task 2: ToolRegistry 增加只读子集 + API 格式重载
**影响文件**: `tool/ToolRegistry.java`
**依赖**: Task 1
**参考**:
- `tool/ToolRegistry.java:19` — register 方法，理解内部 Map 结构
- `tool/ToolRegistry.java:58` — toApiFormat() 方法，新增 `toApiFormat(List<Tool>)` 重载
- 新增 `List<Tool> getReadOnlyTools()`：遍历 tools.values()，过滤 `Tool::isReadOnly`
- 新增 `List<Map<String, Object>> toApiFormat(List<Tool> subset)`：对指定子集生成 API schema

---

## Task 3: StreamCallback 改造 + AnthropicProvider 捕获 stop_reason
**影响文件**: `provider/StreamCallback.java`, `provider/AnthropicProvider.java`, `provider/LLMProvider.java`
**依赖**: 无
**参考**:
- `provider/StreamCallback.java:6` — 移除 @FunctionalInterface；`provider/StreamCallback.java:21` — onComplete() 改为 `onComplete(String stopReason)`，原无参版本删除
- `provider/AnthropicProvider.java:295` — message_stop case：从 `data` JSON 中提取 `message.stop_reason` 字段，存入实例变量 `private String lastStopReason`
- `provider/AnthropicProvider.java:217` — parseSSEStream 末尾调用 `callback.onComplete(lastStopReason)`
- `provider/LLMProvider.java:19` — streamChat 签名不变，纯适配

---

## Task 4: AgentEvent 事件模型
**影响文件**: `agent/AgentEventType.java`（新建）, `agent/AgentEvent.java`（新建）
**依赖**: 无
**事件类型枚举值**:
- `TEXT_DELTA` — 文本增量（附 text, chunkType）
- `TOOL_CALL_START` — 工具调用开始（附工具名、callId）
- `TOOL_CALL_RESULT` — 工具执行结果（附工具名、callId、ToolResult）
- `TOKEN_USAGE` — Token 用量（附 inputTokens, outputTokens, totalAccumulated）
- `PROGRESS` — 进度消息（附 message 字符串）
- `LOOP_STARTED` — 单次用户请求的 Agent 循环开始
- `LOOP_FINISHED` — Agent 循环正常结束
- `ERROR` — 错误终止（附 errorMessage）
- `CANCELLED` — 用户取消
- `UNKNOWN_TOOL` — 遇到未知工具（附工具名）

AgentEvent 用 final class + 全参构造器，字段按 type 语义留空（调用方通过 type 判断读哪个字段）。

---

## Task 5: StreamingCollector 双路收集器
**影响文件**: `agent/StreamingCollector.java`（新建）
**依赖**: Task 3 (新 StreamCallback), Task 4 (AgentEvent)
**参考**: `MewCode.java:63-143` 当前手动收集逻辑（fullResponse StringBuilder + collectedToolCalls List）
- 实现 StreamCallback 接口
- 构造器接收 `BlockingQueue<AgentEvent>`，实时推送 TEXT_DELTA 事件
- 内部 StringBuilder fullText + List<ToolCall> toolCalls + String stopReason + Throwable error
- 维护 CountDownLatch(1)，onComplete/onError 时 countDown
- onChunk：TEXT/THINKING → 推事件 + 追加文本；TOOL_CALL → 推 TOOL_CALL_START 事件 + 加入列表
- onComplete(String stopReason)：保存 stopReason，countDown
- onError：保存 error，推 ERROR 事件，countDown
- 公开 getter：getFullText(), getToolCalls(), getStopReason(), getError()
- `void awaitCompletion(long timeoutMs)` 阻塞等待 CountDownLatch

---

## Task 6: ToolExecutionStrategy 工具执行排序
**影响文件**: `agent/ToolExecutionStrategy.java`（新建）
**依赖**: Task 1 (isReadOnly)
**参考**: `MewCode.java:102-121` 当前串行执行逻辑
- 接收 `List<ToolCall>` + `ToolRegistry`
- 过滤分组：`List<ToolCall> readOnly` + `List<ToolCall> sideEffects`
- 只读组用 ExecutorService（`min(Runtime.availableProcessors() * 2, 10)` 线程）invokeAll 并发执行
- 副作用组在原列表顺序下逐次调用 `tool.execute(tc.getInput())`
- 按输入 ToolCall 顺序组装 `List<ToolResult>` 返回（原顺序索引映射）
- 未知工具返回 `new ToolResult(false, "未知工具: " + name, "UNKNOWN_TOOL")`

---

## Task 7: AgentLoop 核心编排
**影响文件**: `agent/AgentLoop.java`（新建）, `agent/StopReason.java`（新建）
**依赖**: Task 2, Task 4, Task 5, Task 6
**参考**: `MewCode.java:63-143` 当前两轮回调的整体流程
- StopReason 枚举：`END_TURN("end_turn")`, `TOOL_USE("tool_use")`, `MAX_TOKENS("max_tokens")`, `STOP_SEQUENCE("stop_sequence")`，带 `fromString(String)` 静态工厂
- AgentLoop 实现 Runnable：
  - 构造器：LLMProvider, ToolRegistry, ConversationManager, BlockingQueue<AgentEvent>, maxIterations, streamTimeoutSeconds
  - `volatile boolean cancelled` + `cancel()` 方法
  - `run()` 核心逻辑：
    ```
    推 LOOP_STARTED 事件
    for iter in 0..<maxIterations:
      if cancelled → 推 CANCELLED，退出
      推 PROGRESS（当前轮次/总上限）
      创建 StreamingCollector(queue)，调 provider.streamChat(messages, collector)
      collector.awaitCompletion(streamTimeoutSeconds * 1000L)
      if collector.getError() != null → 推 ERROR，退出
      推 TOKEN_USAGE 事件
      stopReason = StopReason.fromString(collector.getStopReason())
      if stopReason != TOOL_USE → 退出循环
      for each toolCall in collector.getToolCalls():
        if toolRegistry.get(tc.name) == null → 推 UNKNOWN_TOOL，退出
      conversation.addToolCallMessage(fullText, toolCalls)
      results = executionStrategy.execute(toolCalls)
      for each result → 推 TOOL_CALL_RESULT 事件
      for each result → conversation.addToolResultMessage(id, result)
    推 LOOP_FINISHED 事件
    ```
- Token 累计：在每轮流完成后获取用量（可以通过 AnthropicProvider message_delta 事件或 StreamingCollector 扩展），累计并推 TOKEN_USAGE

---

## Task 8: /plan 和 /do 斜杠命令
**影响文件**: `tui/TerminalUI.java`
**依赖**: Task 2 (getReadOnlyTools), Task 7 (AgentLoop)
**参考**: `tui/TerminalUI.java:60` — ui.start() 的输入回调
- TerminalUI 持有 `boolean planMode = false`
- 在输入处理中检测 `/plan` → 设置 planMode=true，AgentLoop 切换为只读工具集
- 检测 `/do` → planMode=false，恢复全工具集
- 两个命令不触发 LLM，仅输出中文确认信息
- 输入其他文本 → 正常传给 AgentLoop.run()

---

## Task 9: 接入主流程
**影响文件**: `MewCode.java`, `config/AppConfig.java`, `config/ConfigLoader.java`
**依赖**: Task 7, Task 8
**参考**: `MewCode.java:28-152` 整个 main 方法
- `AppConfig.java` 新增 `int maxIterations` 字段（getter/setter）
- `ConfigLoader.java` 校验：`maxIterations <= 0` 时默认 25
- `MewCode.main()` 变更：
  - 创建 `BlockingQueue<AgentEvent>`（`new LinkedBlockingQueue<>(1000)`）
  - 创建 AgentLoop 实例
  - 启动事件消费线程：循环 `queue.take()`，根据事件类型调用 StreamingDisplay / 输出
  - 启动 AgentLoop 线程处理用户输入
  - 删除旧的 selfRef StreamCallback 匿名类（约 80 行）
  - 注册 Ctrl+C 钩子（`Runtime.getRuntime().addShutdownHook`）调用 agentLoop.cancel()

---

## Task 10: 端到端验证
**影响文件**: 无（手动验证）
**依赖**: Task 9
**验证步骤**:
1. 启动 → 输入"读 pom.xml 分析项目依赖" → 观察 Agent 自动 read_file → 输出分析
2. 输入"在 src/main/java/com/mewcode/agent 下创建一个 .gitkeep 空文件" → 观察自动 glob 检查目录 → write_file 创建
3. 输入 `/plan` → 确认回显 → 输入"删除 src 下所有 .java" → 确认模型无法调 write/edit/exec
4. 输入 `/do` → 确认恢复 → 重复上一步确认可以执行
5. 修改代码临时移除一个工具注册 → 触发 UNKNOWN_TOOL 终止
6. Ctrl+C → Agent 优雅退出
