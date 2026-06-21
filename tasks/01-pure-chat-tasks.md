# MewCode 任务分解

> 每个任务在半小时到一小时内可完成，按依赖顺序排列。

---

## Task 1: 项目骨架初始化

**影响文件**: `pom.xml`, 所有包目录

**内容**:
1. 修改 `pom.xml`：JDK 17 → 21，添加依赖
2. 创建包结构：
   - `com.mewcode.config`
   - `com.mewcode.provider`
   - `com.mewcode.conversation`
   - `com.mewcode.tui`
3. 更新/重写 `MewCode.java`（入口类，移动到 `com.mewcode` 包下，暂时只打印 "MewCode starting..."）
4. 删除旧的 `ai.test.Main.java`

**依赖**: 无

**参考**:
- 当前 `pom.xml`（第 11-12 行 compiler version）
- 当前 `src/main/java/ai/test/Main.java`（待删除）

---

## Task 2: 配置模块

**影响文件**:
- `src/main/java/com/mewcode/config/AppConfig.java`
- `src/main/java/com/mewcode/config/ConfigLoader.java`
- `mewcode.yaml`（项目根目录，示例配置文件）

**内容**:
1. `AppConfig.java`：POJO，字段 `protocol`, `model`, `baseUrl`, `apiKey`, `thinkingBudget`（int，默认 16000）
2. `ConfigLoader.java`：
   - `load(String filePath)` → 读 YAML，反序列化为 `AppConfig`
   - 校验四个必填字段非空
   - 校验 `protocol` 值只能是 `"anthropic"` 或 `"openai"`（大小写不敏感，内部转为小写）
   - 文件不存在或格式错误时：打印明确错误信息 + `System.exit(1)`
3. `mewcode.yaml`：包含所有字段的示例配置（占位值）

**依赖**: Task 1

**参考**: SnakeYAML 的 `Yaml` 类用法

---

## Task 3: Provider 抽象接口 + 数据模型

**影响文件**:
- `src/main/java/com/mewcode/conversation/Message.java`
- `src/main/java/com/mewcode/provider/LLMProvider.java`
- `src/main/java/com/mewcode/provider/StreamCallback.java`

**内容**:
1. `Message.java`：record 或 POJO
   - `role`: String（"user" / "assistant" / "system"）
   - `content`: String
2. `StreamCallback.java`：函数式接口
   - `onChunk(String text, ChunkType type)` — `ChunkType` 枚举：`THINKING`, `TEXT`, `ERROR`
   - `onComplete()` — 流结束回调
   - `onError(Throwable t)` — 异常回调（在 `LLMProvider.streamChat` 外部 try-catch 捕获不到的网络错误）
3. `LLMProvider.java`：接口
   - `void streamChat(List<Message> messages, StreamCallback callback)` — 发起流式请求，逐 chunk 回调
   - `String getProviderName()` — 返回 "anthropic" 或 "openai"

**依赖**: Task 1

---

## Task 4: Anthropic Provider 实现

**影响文件**: `src/main/java/com/mewcode/provider/AnthropicProvider.java`

**内容**:
1. 构造时接收 `AppConfig`
2. 实现 `streamChat`：
   - 构建 HTTP POST 请求到 `{baseUrl}/messages`（baseUrl 去尾部斜杠）
   - 请求头：`x-api-key`, `anthropic-version: 2023-06-01`, `Content-Type: application/json`
   - 请求体 JSON：
     ```json
     {
       "model": "...",
       "max_tokens": 4096,
       "stream": true,
       "thinking": { "type": "enabled", "budget_tokens": 16000 },
       "messages": [...]
     }
     ```
     - 注：`system` role 不放在 messages 数组里，要用顶层 `system` 字段
   - 解析 SSE 响应流，事件类型处理：
     - `content_block_start`：如果 `content_block.type == "thinking"`，后续 chunk 标记为 THINKING
     - `content_block_delta`：`delta.thinking` → THINKING；`delta.text` → TEXT
     - `message_stop` → `onComplete()`
   - 网络异常/非 2xx → `onError()`
3. 重试逻辑：网络 IO 异常时指数退避重试最多 3 次（间隔 1s / 2s / 4s）；4xx 状态码不重试

**依赖**: Task 2, Task 3

**参考**:
- [Anthropic Messages API 流式文档](https://docs.anthropic.com/en/api/messages-streaming)
- OkHttp 的 `Response.body().source()` 逐行读取 SSE

---

## Task 5: OpenAI Provider 实现

**影响文件**: `src/main/java/com/mewcode/provider/OpenAIProvider.java`

**内容**:
1. 构造时接收 `AppConfig`
2. 实现 `streamChat`：
   - 构建 HTTP POST 请求到 `{baseUrl}/chat/completions`
   - 请求头：`Authorization: Bearer {api_key}`, `Content-Type: application/json`
   - 请求体 JSON：
     ```json
     {
       "model": "...",
       "stream": true,
       "messages": [...]
     }
     ```
   - 解析 SSE 响应流，每行 `data: {...}` JSON：
     - `choices[0].delta.content` → TEXT（可能为 null）
     - `choices[0].finish_reason == "stop"` → `onComplete()`
3. 重试逻辑：同 AnthropicProvider（3 次指数退避，4xx 不重试）

**依赖**: Task 2, Task 3

**参考**: [OpenAI Chat Completions 流式文档](https://platform.openai.com/docs/api-reference/chat/streaming)

---

## Task 6: 对话管理器 + 上下文窗口

**影响文件**:
- `src/main/java/com/mewcode/conversation/ConversationManager.java`
- `src/main/java/com/mewcode/conversation/KeywordExtractor.java`

**内容**:
1. `ConversationManager.java`：
   - 内部维护 `List<Message>`，包含可选的 system message
   - `addUserMessage(String content)` — 追加 user 消息
   - `addAssistantMessage(String content)` — 追加 assistant 消息
   - `getMessages()` — 返回当前消息列表（含 system message 如果在最前面）
   - `clear()` — 清空到只剩 system message
   - `getEstimatedTokens()` — 粗略估算法：所有消息 content 字符数之和 ÷ 3.5
   - 上下文窗口管理（在 `addUserMessage` 前触发）：
     - 阈值：80% × 模型上下文窗口（默认 200K for Claude, 128K for GPT-4）
     - 超出时：对最旧的一对 user+assistant 消息做关键词提取，替换为一条 system 消息：`[Earlier conversation topics: keyword1, keyword2, ...]`
     - 如仍超出：删除最旧的消息对
2. `KeywordExtractor.java`：
   - `extract(String text, int topN)` → `List<String>`
   - 简单实现：按空格和标点分词 → 去停用词 → 词频排序 → 取 topN
   - 中文不要求分词精度，按字/二元组分即可（或用简单的标点/空格切分）

**依赖**: Task 3

---

## Task 7: 流式显示模块

**影响文件**: `src/main/java/com/mewcode/tui/StreamingDisplay.java`

**内容**:
1. 管理当前流式输出的终端渲染状态
2. `onChunk(String text, ChunkType type)`：
   - `THINKING`：打印 ANSI 灰色文本 + `[思考] ` 前缀（如 `\033[90m[思考] xxx\033[0m`）
   - `TEXT`：直接打印到 stdout，不换行追加
   - `ERROR`：打印 ANSI 红色错误信息
3. `onComplete()`：换行，打印提示符
4. 处理终端宽度，避免在单词中间断行（基础处理即可）
5. 确保 `System.out.flush()` 在每个 chunk 后调用

**依赖**: Task 3（需要 ChunkType 枚举）

---

## Task 8: TUI 终端界面（JLine）

**影响文件**: `src/main/java/com/mewcode/tui/TerminalUI.java`

**内容**:
1. 使用 JLine `TerminalBuilder` + `LineReader` 构建交互循环
2. 显示欢迎信息："MewCode - 终端 AI 助手 (输入 /exit 退出, /clear 清空对话)"
3. 输入提示符：`> `（绿色粗体）
4. 命令处理：
   - `/exit` → 打印 "Goodbye!" → System.exit(0)
   - `/clear` → 调用 `ConversationManager.clear()` → 打印 "对话已清空"
   - 以 `/` 开头的未知命令 → 打印 "未知命令: xxx"
   - 空输入 → 跳过
   - 其他 → 作为对话输入传给上层（由 MewCode.java 调度）
5. JLine 行编辑功能：上下箭头浏览历史、左右移动光标、Ctrl+C 退出
6. `start(InputHandler handler)` 方法，`InputHandler` 是 `void handle(String input)` 的函数式接口

**依赖**: Task 6（引用 ConversationManager 做 `/clear`）

---

## Task 9: Provider 工厂

**影响文件**: `src/main/java/com/mewcode/provider/ProviderFactory.java`

**内容**:
1. `static LLMProvider create(AppConfig config)`：
   - `config.getProtocol().equals("anthropic")` → `new AnthropicProvider(config)`
   - `config.getProtocol().equals("openai")` → `new OpenAIProvider(config)`
   - 其他 → 抛出 `IllegalArgumentException`
2. 仅做路由，不包含额外逻辑

**依赖**: Task 2, Task 4, Task 5

---

## Task 10: 接入主流程

**影响文件**: `src/main/java/com/mewcode/MewCode.java`

**内容**:
1. `main(String[] args)`：
   - 确定配置文件路径：`args.length > 0 ? args[0] : "./mewcode.yaml"`
   - `ConfigLoader.load(configPath)` → `AppConfig`
   - `ProviderFactory.create(config)` → `LLMProvider`
   - `new ConversationManager(config.getModel())` → 初始化（带一条 system message: "You are a helpful AI assistant."）
   - `new StreamingDisplay()`
   - `new TerminalUI()`
   - `terminalUI.start(input -> { ... })`：输入处理回调
     - `conversationManager.addUserMessage(input)`
     - 调用 `provider.streamChat(conversationManager.getMessages(), streamingDisplay)`
     - 流式回调中：`StreamingDisplay.onChunk(...)` 打印，同时把文本收集到 StringBuilder
     - `onComplete`：`conversationManager.addAssistantMessage(collectedText)`
2. 所有异常在最外层 catch，打印错误 + System.exit(1)

**依赖**: Task 2, Task 6, Task 7, Task 8, Task 9

---

## Task 11: 端到端验证

**影响文件**: 无新代码，验证已有产出

**内容**:
1. 编译：`mvn clean compile` 无错误
2. 配置真实的 `mewcode.yaml`，分别用 Anthropic 和 OpenAI 两个后端各跑一轮对话
3. 验证清单：
   - 流式输出逐 chunk 显示（不等全部生成完）
   - Thinking 内容以灰色 `[思考]` 前缀显示
   - `/clear` 后对话历史确实清空
   - 多轮对话 AI 能引用之前的内容
   - 不存在的配置文件 → 报错退出
   - 格式错误的 YAML → 报错退出
4. 修复验证中发现的问题

**依赖**: Task 10
