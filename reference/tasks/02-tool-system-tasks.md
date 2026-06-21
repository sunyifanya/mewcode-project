# MewCode 工具系统 — 任务分解

> 每个任务在半小时到一小时内可完成，按依赖顺序排列。最后两个任务永远是「接入主流程」和「端到端验证」。

---

## Task 1: 扩展配置模块（tool 超时 + 工作目录）

**影响文件**:
- `src/main/java/com/mewcode/config/AppConfig.java`（第 8-39 行，新增字段）
- `src/main/java/com/mewcode/config/ConfigLoader.java`（第 16-57 行，新增校验逻辑）
- `mewcode.yaml`（项目根目录，新增 `tool` 配置块）

**内容**:
1. `AppConfig.java`：新增两个字段
   - `toolTimeoutSeconds`（int，默认 30）
   - `workingDirectory`（String，默认 `"."`）
2. `ConfigLoader.java`：`validate()` 中新增校验
   - `toolTimeoutSeconds <= 0` → 设为默认 30
   - `workingDirectory` 为空 → 设为默认 `"."`
3. `mewcode.yaml`：新增 `tool` 配置块示例
   ```yaml
   tool:
     timeout_seconds: 30
     working_directory: .
   ```
   - 配置文件解析支持嵌套 `tool` 对象反序列化

**依赖**: 无（改现有文件）

**参考**:
- `AppConfig.java` 第 22 行（`thinkingBudget` 默认值写法）
- `ConfigLoader.java` 第 76 行（`thinkingBudget` 校验写法）
- Jackson 的 `@JsonProperty` 嵌套对象反序列化（可能需要新增一个 `ToolConfig` 内部类或用 `@JsonProperty("tool")` 标注的嵌套 POJO）

---

## Task 2: tool 包基础类型（Tool 接口 + ToolResult + ToolCall）

**影响文件**:
- `src/main/java/com/mewcode/tool/Tool.java`（新建）
- `src/main/java/com/mewcode/tool/ToolResult.java`（新建）
- `src/main/java/com/mewcode/tool/ToolCall.java`（新建）
- `src/main/java/com/mewcode/tool/ToolRegistry.java`（新建）

**内容**:
1. `Tool.java`：接口
   - `String getName()` — 工具唯一名称，如 `"read_file"`、`"grep"`
   - `String getDescription()` — 一行中文描述，给模型看的
   - `Map<String, Object> getParametersSchema()` — 完整 JSON Schema（Map 形式），如 `{"type": "object", "properties": {...}, "required": [...]}`
   - `ToolResult execute(Map<String, Object> params)` — 执行工具，参数名来自 schema 的 properties key
2. `ToolResult.java`：record 或 POJO
   - `boolean success`
   - `String content` — 成功时是工具输出文本，失败时是错误描述
   - `String errorCode` — 可选，`null` 表示成功；取值：`"FILE_NOT_FOUND"`、`"MATCH_NOT_FOUND"`、`"MULTIPLE_MATCHES"`、`"BLACKLISTED"`、`"TIMEOUT"`、`"IO_ERROR"`、`"INVALID_PARAMS"`
3. `ToolCall.java`：POJO
   - `String id` — Anthropic 分配的 tool_use id（如 `"toolu_001"`）
   - `String name` — 工具名
   - `Map<String, Object> input` — 解析后的参数键值对
4. `ToolRegistry.java`：
   - `void register(Tool tool)` — 注册一个工具
   - `Tool get(String name)` — 按名查找，找不到返回 null
   - `List<Map<String, Object>> toApiFormat()` — 生成符合 Anthropic API 规范的 tools 数组（每个元素是 `{"name": ..., "description": ..., "input_schema": ...}`）
   - 构造时接收 `Tool... tools` 可变参数一次性注册

**依赖**: Task 1（需要 `toolTimeoutSeconds` 和 `workingDirectory` 用于后续工具实现）

**参考**:
- `src/main/java/com/mewcode/provider/LLMProvider.java` 第 11-25 行（接口写法参考）
- `src/main/java/com/mewcode/conversation/Message.java` 第 6-27 行（POJO 写法参考）
- [Anthropic Tool Use 文档中 tool 格式](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)

---

## Task 3: 扩展 ChunkType + Message 模型

**影响文件**:
- `src/main/java/com/mewcode/provider/ChunkType.java`（第 6-12 行，新增枚举值）
- `src/main/java/com/mewcode/conversation/Message.java`（第 6-27 行，新增字段）
- `src/main/java/com/mewcode/conversation/ConversationManager.java`（第 13-127 行，新增方法）

**内容**:
1. `ChunkType.java`：新增 `TOOL_CALL` 枚举值
2. `Message.java`：新增两个可选字段（null 时表示普通文本消息）
   - `List<ToolCall> toolCalls` — assistant 消息中的工具调用列表
   - `ToolResult toolResult` — user 消息中的工具执行结果
   - 保持现有 `role` + `content` 字段不变，向后兼容
3. `ConversationManager.java`：
   - `addToolCallMessage(List<ToolCall> toolCalls, String textContent)` — 添加含 tool_use 的 assistant 消息
   - `addToolResultMessage(ToolResult result, String toolUseId)` — 添加含 tool_result 的 user 消息
   - `getMessages()` 不需要改动，返回的 `List<Message>` 已包含新字段

**依赖**: Task 2（需要 ToolCall、ToolResult 类型）

**参考**:
- `ChunkType.java` 全文（只有 3 个枚举值，新增一个）
- `Message.java` 第 12 行构造方法（保持两参数构造方法不变）
- `ConversationManager.java` 第 34-40 行（`addUserMessage`/`addAssistantMessage` 写法）

---

## Task 4: 读文件 + 写文件工具

**影响文件**:
- `src/main/java/com/mewcode/tool/impl/ReadFileTool.java`（新建）
- `src/main/java/com/mewcode/tool/impl/WriteFileTool.java`（新建）

**内容**:
1. `ReadFileTool.java`：
   - 构造参数：`String workingDirectory`
   - `getName()` → `"read_file"`
   - `getDescription()` → 中文描述："读取指定路径的文件全部内容，输出带行号，上限 2000 行"
   - `getParametersSchema()` → `{"type":"object","properties":{"file_path":{"type":"string","description":"要读取的文件路径，相对于项目根目录"}},"required":["file_path"]}`
   - `execute(params)`：
     - 从 params 取 `file_path` 字符串
     - 用 `Paths.get(workingDirectory).resolve(filePath).normalize()` 拼接路径
     - 读取全部行，行号格式化（`String.format("%6d\t%s", lineNum, line)`）
     - 超过 2000 行截断，末尾追加 `"... (截断，文件共 N 行)"`
     - 文件不存在 → `ToolResult(false, "文件不存在: {path}", "FILE_NOT_FOUND")`
     - 捕捉 IOException → `ToolResult(false, e.getMessage(), "IO_ERROR")`
2. `WriteFileTool.java`：
   - 构造参数：`String workingDirectory`
   - `getName()` → `"write_file"`
   - `getParametersSchema()` → file_path + content 两个必填参数
   - `execute(params)`：
     - 父目录不存在时 `Files.createDirectories()`
     - 写入后返回 `ToolResult(true, "已写入 N 字节到 {path}")`
     - 捕捉 IOException → `ToolResult(false, ..., "IO_ERROR")`

**依赖**: Task 2（需要 Tool 接口 + ToolResult）

**参考**:
- Java NIO `Files.readAllLines()`、`Files.writeString()`、`Files.createDirectories()`

---

## Task 5: 改文件工具

**影响文件**:
- `src/main/java/com/mewcode/tool/impl/EditFileTool.java`（新建）

**内容**:
1. `getName()` → `"edit_file"`
2. `getParametersSchema()` → file_path + old_string + new_string 三个必填参数
3. `execute(params)`：
   - 读取文件全部内容为单个 String（保留原始换行符）
   - 在内容中查找 `old_string`：
     - **零次匹配**：返回 `ToolResult(false, "未找到匹配文本。文件内容片段：\n{前后各5行}", "MATCH_NOT_FOUND")`
     - **多次匹配**（≥2）：返回 `ToolResult(false, "匹配到 N 处，请追加上下文使匹配唯一：\n- 第X行: {片段}\n- 第Y行: {片段}", "MULTIPLE_MATCHES")`
     - **唯一匹配**：替换为 `new_string`，写回文件
   - 返回 `ToolResult(true, "已在 {path} 第 X 行完成替换")`

**关键实现细节**:
- 用 `String.indexOf()` 做精确字符串匹配（不用正则）
- 多次匹配用循环 `indexOf(oldStr, fromIndex)` 找到所有位置
- 通过统计 `\n` 数量推算每个匹配位置的行号

**依赖**: Task 2（需要 Tool 接口 + ToolResult）

**参考**:
- `String.indexOf(String str, int fromIndex)` 循环查找所有匹配
- 匹配到 N 处的错误信息格式参考 Claude Code 的 edit 工具反馈风格

---

## Task 6: 按模式找文件 + 搜代码内容工具

**影响文件**:
- `src/main/java/com/mewcode/tool/impl/GlobTool.java`（新建）
- `src/main/java/com/mewcode/tool/impl/GrepTool.java`（新建）

**内容**:
1. `GlobTool.java`：
   - `getName()` → `"glob"`
   - `getParametersSchema()` → `pattern`（glob 模式，如 `**/*.java`）+ `path`（可选，搜索起始目录，默认项目根目录）
   - `execute(params)`：
     - 用 `Files.walkFileTree()` 或 `Files.find()` 递归遍历
     - 用 `FileSystem.getPathMatcher("glob:" + pattern)` 匹配
     - 按修改时间降序排列
     - 返回路径列表（相对于 workingDirectory）
     - 命中数上限 500
2. `GrepTool.java`：
   - `getName()` → `"grep"`
   - `getParametersSchema()` → `pattern`（正则）+ `path`（可选，搜索目录）+ `glob`（可选，文件过滤，如 `*.java`）
   - `execute(params)`：
     - 在 `path` 下递归遍历文件（可选 `glob` 过滤）
     - 逐行用 `Pattern.compile()` 匹配
     - 输出格式：`{filePath}:{lineNumber}: {lineContent}`
     - 结果上限 250 条，超出截断并在末尾注明
     - 正则编译失败 → `ToolResult(false, "正则表达式无效: ...", "INVALID_PARAMS")`

**依赖**: Task 2（需要 Tool 接口 + ToolResult）

**参考**:
- Java NIO `FileSystem.getPathMatcher()` for glob
- `java.util.regex.Pattern` for grep
- `Files.find()` 递归遍历

---

## Task 7: 执行命令工具

**影响文件**:
- `src/main/java/com/mewcode/tool/impl/ExecuteCommandTool.java`（新建）

**内容**:
1. 构造参数：`String workingDirectory`、`int timeoutSeconds`、`List<String> blacklistPatterns`（或硬编码黑名单）
2. `getName()` → `"execute_command"`
3. `getParametersSchema()` → `command`（字符串）+ `timeout_ms`（可选，覆盖默认超时）
4. **黑名单检查**（execute 第一步）：
   - 黑名单正则列表：
     - `rm\s+-rf\s+/`（及其变体 `rm\s+-r[f]?\s+/`）
     - `(shutdown|reboot|halt|poweroff|init\s+[06])`
     - `mkfs\.`
     - `dd\s+.*of=/dev/`
     - `>[\s]*/dev/sd[a-z]`
     - `format\s+[A-Z]:`
     - `:\(\)\s*\{`（fork 炸弹特征）
   - 任一命中 → `ToolResult(false, "命令被安全策略拒绝（匹配黑名单规则: {pattern}）", "BLACKLISTED")`
5. **命令执行**：
   - Windows：`cmd /c {command}`
   - Unix：`/bin/sh -c {command}`
   - 用 `ProcessBuilder`：`directory(workingDir)`、`redirectErrorStream(false)`
   - `Process.waitFor(timeoutSeconds, TimeUnit.SECONDS)`
   - 超时 → `process.destroyForcibly()` → `ToolResult(false, "命令超时（{N}秒）", "TIMEOUT")`
   - 正常完成 → `ToolResult(true, "{stdout}\n{stderr}\n退出码: {exitCode}")`
   - 读取 stdout/stderr 用独立线程防死锁（`ProcessBuilder` 不 `redirectErrorStream` 时需两个读取线程）

**依赖**: Task 1, Task 2（需要配置的 timeout + Tool 接口）

**参考**:
- `ProcessBuilder` API
- Java `Process` 的 `waitFor(long, TimeUnit)` with timeout
- 超时后 `destroyForcibly()` 确保子进程被杀

---

## Task 8: AnthropicProvider 工具调用改造

**影响文件**:
- `src/main/java/com/mewcode/provider/AnthropicProvider.java`（第 17-204 行，多处改动）

**内容**:
1. **构造参数新增**：接收 `ToolRegistry`，存为字段
2. **`buildRequestBody()` 改造**：
   - 新增顶层 `tools` 字段（调用 `toolRegistry.toApiFormat()` 序列化为 JSON 数组）
   - 当 Message 含 `toolCalls` 时，`content` 序列化为数组格式：
     ```java
     // 伪代码
     if (msg.getToolCalls() != null) {
         var contentArray = jsonMapper.createArrayNode();
         if (msg.getContent() != null && !msg.getContent().isEmpty()) {
             contentArray.add(textBlock(msg.getContent()));
         }
         for (ToolCall tc : msg.getToolCalls()) {
             contentArray.add(toolUseBlock(tc));
         }
         msgNode.set("content", contentArray);
     }
     ```
   - 当 Message 含 `toolResult` 时，role 为 `"user"`，content 为 tool_result 块数组
   - 普通文本 Message 仍序列化为 `"content": "字符串"`（不改现有逻辑）
3. **`processSSEEvent()` 改造**（第 150-190 行）：
   - 新增 `content_block_start` 处理：
     - 记录当前 content block 的 type（`text` / `tool_use` / `thinking`）
     - 如果是 `tool_use`：记录 `id`（来自 `content_block.id`）和 `name`（来自 `content_block.name`）
   - 新增 `content_block_delta` 中 `input_json_delta` 处理：
     - 累积 `delta.partial_json` 到 StringBuilder
   - 新增 `content_block_stop` 处理：
     - 如果当前 block 是 `tool_use`：将累积的 JSON 解析为完整 toolCall 对象，用 `onChunk(toolCallJson, TOOL_CALL)` 回调
4. **状态变量**：需要在 `parseSSEStream` 或 AnthropicProvider 实例中维护当前 content block 的状态（type / id / name / jsonBuffer）

**依赖**: Task 2, Task 3（需要 ToolRegistry、ToolCall、ChunkType.TOOL_CALL）

**参考**:
- `AnthropicProvider.java` 第 87-121 行（`buildRequestBody` 当前实现）
- `AnthropicProvider.java` 第 150-190 行（`processSSEEvent` 当前 switch 分支）
- [Anthropic SSE 流中 tool_use 事件结构](https://docs.anthropic.com/en/docs/build-with-claude/tool-use#streaming)

---

## Task 9: 工具执行器（MewCode 中的 tool dispatch 逻辑）

**影响文件**:
- `src/main/java/com/mewcode/MewCode.java`（第 16-81 行，新增工具执行逻辑）
- 可能抽取 `src/main/java/com/mewcode/tool/ToolDispatcher.java`（新建，可选）

**内容**:
1. 在 `MewCode.main()` 中：
   - 创建 `ToolRegistry` + 注册 6 个工具（传入 `workingDirectory` 和 `timeoutSeconds`）
   - 创建 `AnthropicProvider` 时传入 `ToolRegistry`
2. 修改 `StreamCallback` 实现（第 51-73 行的匿名类）：
   - 新增 `List<ToolCall> collectedToolCalls` 用于收集流中的工具调用
   - `onChunk(text, TOOL_CALL)` → 解析 JSON 为 `ToolCall` 对象，加入 `collectedToolCalls`
   - `onComplete()` → 检查 `collectedToolCalls`：
     - 若不为空且 `!hasExecutedTools`（首轮标志）：
       ```java
       hasExecutedTools = true;
       // 1. 收集文本 content（tool_use 前的文本）
       // 2. 添加 assistant tool_use 消息到 conversation
       conversation.addToolCallMessage(collectedToolCalls, textContent);
       // 3. 逐个执行工具
       for (ToolCall tc : collectedToolCalls) {
           ui.getWriter().println("正在执行工具 " + tc.getName() + "...");
           Tool tool = toolRegistry.get(tc.getName());
           ToolResult result = tool != null
               ? tool.execute(tc.getInput())
               : new ToolResult(false, "未知工具: " + tc.getName(), "UNKNOWN_TOOL");
           conversation.addToolResultMessage(result, tc.getId());
       }
       // 4. 发起第二次请求
       fullResponse.setLength(0); // 重置文本缓冲
       collectedToolCalls.clear();
       provider.streamChat(conversation.getMessages(), this); // 递归调用
       // 5. 第二次回复到达后，hasExecutedTools 已为 true，不会再执行工具
       ```
3. 工具执行异常兜底：`try-catch` 包裹 `tool.execute()`，异常 → `ToolResult(false, e.getMessage(), "EXECUTION_ERROR")`
4. 第二次 SSE 流中若仍有 TOOL_CALL → 忽略（`hasExecutedTools` 为 true），只收集文本

**依赖**: Task 2, Task 3, Task 8（需要 ToolCall、ToolRegistry、AnthropicProvider 改造）

**参考**:
- `MewCode.java` 第 50-73 行（当前 `StreamCallback` 匿名类实现）
- `AnthropicProvider.java` 第 36 行 `streamChat` 方法签名

---

## Task 10: 接入主流程（整合所有组件）

**影响文件**:
- `src/main/java/com/mewcode/MewCode.java`（第 16-81 行）
- `mewcode.yaml`（新增 tool 配置块）

**内容**:
1. 确保 Task 9 的工具注册 + AnthropicProvider 构造在主流程中正确串联
2. 确认 `mewcode.yaml` 中 tool 配置正确反序列化
3. 确认 `ConverationManager` 的 `addToolCallMessage` / `addToolResultMessage` 能正确影响 `buildRequestBody` 的输出格式
4. 编译通过，`mvn clean compile` 零错误

**依赖**: Task 1-9 全部

**参考**:
- `MewCode.java` 第 23-73 行（主流程）
- `ProviderFactory.java` 第 15-22 行（工厂方法，可能需要扩展以传入 ToolRegistry）

---

## Task 11: 端到端验证

**影响文件**: 无新代码，验证已有产出

**内容**:
1. 编译：`mvn clean compile` 零错误
2. 配置有效的 Anthropic API key，启动 MewCode
3. 验证清单（详见 `checklist/02-tool-system.md`）：
   - 输入"读一下 pom.xml" → 模型调用 read_file → 工具执行 → 模型描述 pom.xml 内容
   - 输入"在 src/ 下找所有 .java 文件" → 模型调用 glob → 返回文件列表
   - 输入"搜索 main 方法" → 模型调用 grep → 返回匹配行
   - 输入"创建一个 test.txt 写 hello world" → 模型调用 write_file → 文件被创建
   - 修改文件 + 故意给错误的 old_string → edit_file 报 MATCH_NOT_FOUND
   - 输入危险命令 `rm -rf /` → 工具拒绝执行 → 返回 BLACKLISTED
   - 连续两次对话，确认工具调用消息不影响下一轮纯文本对话
4. 修复验证中发现的问题

**依赖**: Task 10
