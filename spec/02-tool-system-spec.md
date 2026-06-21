# MewCode 工具系统 — 需求规格说明

## 背景

当前 MewCode 已完成纯对话能力：用户输入文本，模型流式返回文本回复。但它只能"说"不能"做"——无法读文件、写文件、搜索代码、执行命令。这一步给 MewCode 装上工具系统，让模型能识别自己要用哪个工具，MewCode 去执行，再把结果喂给模型，模型据此给出文本回复。从聊天机器人变成真正能干活的 Agent。

## 目标用户

使用 MewCode 进行日常编码工作的开发者。他们的典型场景：让 AI 看某个文件的代码逻辑、搜索使用了某个函数的所有地方、创建新文件、修改现有代码、运行构建或测试命令看结果。

## 能力清单

1. **统一 Tool 接口**：每个工具实现同一接口 `Tool`，声明 `getName()`、`getDescription()`、`getParametersSchema()`（完整 JSON Schema）、`execute(Map<String, Object> params)` 返回结构化 `ToolResult`（含 `success`、`content`、`errorCode`）
2. **工具注册中心**：启动时集中登记所有可用工具，按名查找，能生成符合 Anthropic API 规范的 `tools` 数组供每次 `/messages` 请求携带
3. **读文件工具**：读取指定路径的文件全部内容，输出带行号（`cat -n` 格式），上限 2000 行；超出时截断并在末尾注明；文件不存在或不可读时返回 `ToolResult(success=false, content=错误描述)`
4. **写文件工具**：将内容写入指定路径，父目录不存在时自动递归创建；目标已存在则直接覆盖；返回写入字节数或错误描述
5. **改文件工具**：在文件中做精确字符串替换——`old_string` 在文件内容中做完全匹配（含所有空白和缩进）；仅当匹配唯一时执行替换并返回成功；匹配零次返回"未找到"错误并附带文件片段（前后各 5 行上下文）供模型对照；匹配多次返回每个匹配位置（行号）让模型追加上下文使匹配唯一。不支持正则、不支持行号定位
6. **执行命令工具**：在系统 Shell 中执行命令字符串（Windows: `cmd /c`，Unix: `/bin/sh -c`），带超时控制（默认 30s，配置文件可改）；返回 `stdout`、`stderr` 和退出码；执行前用正则黑名单匹配命令字符串，命中则拒绝执行并返回 `ToolResult(success=false, errorCode="BLACKLISTED")`
7. **按模式找文件工具**：在指定目录下递归匹配 glob 模式（如 `**/*.java`、`src/**/*.ts`），返回命中文件路径列表，按修改时间降序排列
8. **搜代码内容工具**：在指定目录下用正则表达式搜索文件内容，返回匹配文件路径、行号、匹配行内容；支持按 glob 模式过滤文件类型；单次返回结果上限 250 条防止撑爆上下文
9. **流式工具调用解析**：AnthropicProvider 在 SSE 流中识别 `tool_use` 内容块——`content_block_start(type=tool_use)` 记录 `id` 和 `name`，`content_block_delta(type=input_json_delta)` 累积 JSON 分片，`content_block_stop` 时通过现有 `StreamCallback.onChunk()` 回调 + 新增的 `ChunkType.TOOL_CALL` 一次性交付完整工具调用 JSON
10. **工具执行与结果回灌**：MewCode 主循环在流结束后检查收集到的工具调用；若有且是首轮调用，逐个执行工具，将结果以 Anthropic `tool_result` 内容块格式封入新的 `Message` 追加到对话历史，发起第二次 `/messages` 请求（仍带 tools 数组）获取模型的最终文本回复
11. **单轮工具调用策略**：一次用户输入最多经历一轮工具调用——model 发起 tool_use → MewCode 执行 → 结果回灌 → 再请求模型 → 拿到文本回复就停；第二轮回复若仍含 tool_use，忽略不做进一步循环

## 非功能要求

- **错误弹性**：工具执行过程中的任何异常（超时、IO 错误、非法参数、Shell 错误）必须被捕获并打包为 `ToolResult(success=false, content=描述)`，绝不能以未捕获异常的形式崩掉主进程
- **安全黑名单**：`ExecuteCommandTool` 在执行前用正则黑名单匹配命令字符串，命中则拒绝执行；黑名单至少覆盖以下危险模式：
  - 递归根目录删除：`rm\s+-rf\s+/`、`rm\s+-r\s+/`、`del\s+/[fs]`（Windows）
  - 系统关机重启：`shutdown`、`reboot`、`halt`、`poweroff`、`init\s+[06]`
  - 磁盘格式化：`mkfs\.`、`format\s+[A-Z]:`
  - 块设备覆写：`dd\s+.*of=/dev/`
  - Fork 炸弹：`:\(\)|fork\s*bomb`
- **超时控制**：每个工具执行有统一超时时间，由配置 `tool.timeout_seconds` 指定，默认 30 秒；超时后工具执行被强制中断，返回 `ToolResult(success=false, errorCode="TIMEOUT")`
- **跨平台**：6 个核心工具在 Windows 和 Unix 下均能正常工作；文件路径统一使用 `/`
- **流式体验**：工具执行期间在终端输出状态提示，工具结果在后台处理不打断流式显示的连贯性

## 设计骨架

```
com.mewcode.tool
  ├── Tool              (接口: getName, getDescription, getParametersSchema, execute)
  ├── ToolResult        (记录: boolean success, String content, String errorCode)
  ├── ToolCall          (数据: String id, String name, Map<String,Object> input)
  ├── ToolRegistry      (注册/查找/生成 tools JSON 数组)
  └── impl
       ├── ReadFileTool
       ├── WriteFileTool
       ├── EditFileTool
       ├── ExecuteCommandTool
       ├── GlobTool
       └── GrepTool

com.mewcode.config
  └── AppConfig          (新增: toolTimeoutSeconds, workingDirectory)

com.mewcode.conversation
  └── Message            (新增字段: toolCalls List<ToolCall>, toolResult ToolResult)
  └── ConversationManager (新增方法: addToolResultMessage)

com.mewcode.provider
  └── ChunkType          (新增枚举值: TOOL_CALL)
  └── AnthropicProvider  (构造参数新增 ToolRegistry)
                         (buildRequestBody: content 数组格式 + 顶层 tools 字段)
                         (processSSEEvent: 识别 tool_use 事件 → 组装 → onChunk TOOL_CALL)

com.mewcode
  └── MewCode            (工具执行逻辑: 收集 toolCall → 执行 → 回灌 → 二次 streamChat)
```

### Anthropic API 消息格式适配

当 Message 含 toolCalls 时，`content` 序列化为数组：
```json
{
  "role": "assistant",
  "content": [
    {"type": "text", "text": "让我为你搜索..."},
    {"type": "tool_use", "id": "toolu_001", "name": "grep", "input": {"pattern": "main"}}
  ]
}
```

当 Message 含 toolResult 时：
```json
{
  "role": "user",
  "content": [
    {"type": "tool_result", "tool_use_id": "toolu_001", "content": "src/Main.java:5: ..."}
  ]
}
```

普通文本 Message 的 `content` 仍为 String（向后兼容）。

### 关键数据流

```
用户输入 → streamChat(messages + tools, callback)
  → Anthropic SSE 流
    → content_block_start (type=tool_use) → 记录 id/name，初始化 JSON 缓冲
    → content_block_delta (delta.type=input_json_delta) → 累积 partial_json
    → content_block_stop → callback.onChunk(完整 toolCallJSON, TOOL_CALL)
    → (可能有文本 content_block 交叉)
    → message_stop → callback.onComplete()
  → MewCode 检查收集到的 toolCalls
    → 有 toolCall 且是首轮:
        终端提示 "正在执行工具 {name}..."
        逐个: toolRegistry.get(name).execute(input) → ToolResult
        构造 tool_result Message 追加到对话历史
        streamChat(更新后的 messages, callback) 第二次请求
        展示文本回复
    → 无 toolCall: 展示文本回复（现有行为）
```

## Out of Scope

- **Agent Loop**：模型自动判断"还需要更多信息"并持续调用工具的多轮自主循环留到下一章
- **工具调用用户确认**：写文件和命令执行前不弹确认提示，仅依赖黑名单
- **工作目录沙箱**：文件操作不限制在项目根目录内
- **OpenAIProvider 工具调用**：本章仅改造 AnthropicProvider
- **MCP 协议**：外部工具服务器协议不在此步
- **工具列表动态增删**：启动时一次性注册，运行时不可变
- **流式工具结果**：工具执行完毕后一次性回灌
- **并发工具执行**：若模型一次请求多个 tool_use，按顺序逐个执行
