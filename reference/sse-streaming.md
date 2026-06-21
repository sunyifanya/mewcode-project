# SSE Stream 与大模型交互说明

> 基于 MewCode 中 `AnthropicProvider.parseSSEStream` 和 `OpenAIProvider.parseSSEStream` 的实际实现总结。

## 1. 什么是 SSE

**SSE (Server-Sent Events)** 是一种 HTTP 长连接协议，服务端通过同一连接持续推送数据行，客户端逐行读取。与 WebSocket 不同，SSE 是**单向**的（仅服务端→客户端），天然适合 LLM 流式输出的场景。

### 协议格式

```
event: <事件类型>          ← 可选，省略时视为 "message"
data: <JSON 数据>          ← 一行或多行，内容可以跨 data 行拼接
                           ← 空行表示一个事件结束
```

关键点：
- 每个事件由一个或多个 `data:` 行组成，遇到**空行**视为事件结束
- 多行 `data:` 在拼接后才构成完整 JSON
- `event:` 行声明事件类型，不同 LLM 厂商的事件类型完全不同

## 2. 两种 SSE 协议对比

MewCode 支持两个 provider，各自的 SSE 协议差异很大。

### 2.1 Anthropic 协议

请求端点：`POST /v1/messages`（header: `anthropic-version: 2023-06-01`, `stream: true`）

| SSE event 类型 | data 中关键字段 | 含义 |
|---|---|---|
| `message_start` | `message.id, message.model` | 流开始 |
| `content_block_start` | `content_block.type, content_block.id, content_block.name` | 一个新的内容块开始。type 为 `text`、`tool_use` 或 `thinking` |
| `content_block_delta` | `delta.type`, `delta.text` / `delta.thinking` / `delta.partial_json` | 内容增量。delta.type 决定增量类型 |
| `content_block_stop` | — | 当前内容块结束（tool_use 块结束时组装完整 JSON） |
| `message_delta` | `delta.stop_reason` | 全局增量。携带 `stop_reason` 和 usage 信息 |
| `message_stop` | `message.stop_reason` | **最重要**：整个消息结束，携带最终 `stop_reason` |
| `error` | `error.message` | API 错误 |
| `ping` | — | 保活心跳，直接忽略 |

#### Anthropic delta 的内部类型（`delta.type`）

| delta.type | 对应 ChunkType | 说明 |
|---|---|---|
| `text_delta` | `TEXT` | 普通文本增量，直接推给 UI |
| `thinking_delta` | `THINKING` | extended thinking 内容，推给 UI（可折叠/灰度显示） |
| `input_json_delta` | — | tool_use 参数的 JSON 片段，**本地累积**，等 `content_block_stop` 时组装成完整 `TOOL_CALL` |

#### Anthropic stop_reason 的可能值

| 值 | 含义 | Agent Loop 行为 |
|---|---|---|
| `end_turn` | 模型正常结束 | 保存最终文本，退出循环 |
| `tool_use` | 模型要调工具 | 解析 tool_use，执行工具，继续下一轮 |
| `max_tokens` | 达到 max_tokens 上限 | 当前实现视为结束 |
| `stop_sequence` | 命中自定义停止序列 | 当前实现视为结束 |

### 2.2 OpenAI 协议

请求端点：`POST /v1/chat/completions`（`stream: true`）

OpenAI 的协议**简单得多**——没有 `event:` 行，只有 `data:` 行：

| data 内容 | 含义 |
|---|---|
| `data: {"choices":[{"delta":{"content":"hello"}}],...}` | 文本增量，从 `choices[0].delta.content` 取 |
| `data: [DONE]` | 流结束，触发 `callback.onComplete("stop")` |

OpenAI Chat Completions API 原生**不支持 tool_use 的流式输出**——它只在最终响应里一次性返回工具调用。这是 Anthropic 协议与 OpenAI 协议最大的功能差距之一。

## 3. MewCode 中的 ChunkType（统一抽象）

为了抹平厂商差异，MewCode 定义了自己的 `ChunkType` 枚举，两个 provider 的 `parseSSEStream` 最终都把数据归一化到这个枚举：

```java
public enum ChunkType {
    TEXT,      // 普通文本增量（显示为打字效果）
    THINKING,  // 思考过程（显示为灰色/折叠区域）
    TOOL_CALL, // 完整的工具调用（已组装 id + name + input JSON）
    ERROR      // 错误信息
}
```

这是 MewCode 内部统一的"方言"，上层（`StreamingCollector`、`StreamingDisplay`、`AgentLoop`）不需要知道底层是 Anthropic 还是 OpenAI。

## 4. 从 SSE 到 UI 的完整数据流

```
HTTP Response Body (SSE 文本流)
    │
    ▼
parseSSEStream(response, callback)     ← 厂商特定解析
    │  ● 逐行读 BufferedReader
    │  ● 按 SSE 协议切 event/data
    │  ● 按事件类型分发处理
    │  ● 组装 tool_use JSON（跨多个 input_json_delta 拼接）
    │  ● 捕获 stop_reason
    │  ● 回调 callback.onChunk() / callback.onComplete()
    │
    ▼
StreamingCollector (implements StreamCallback)   ← 双路径收集器
    ├── onChunk(): 实时推 eventQueue → UI 线程
    └── 本地累积:
         ├── fullText (StringBuilder)      → Agent Loop 用来存对话历史
         ├── toolCalls (List<ToolCall>)    → Agent Loop 用来调度工具
         └── stopReason (String)           → Agent Loop 判断退出/继续

    ▼
eventQueue → event-consumer daemon 线程 → StreamingDisplay (终端渲染)
```

## 5. Anthropic SSE 事件生命周期示例

一次典型的 "你好，帮我查个文件" 请求，SSE 流的事件序列：

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_xxx","model":"claude-opus-4-8",...}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text",...}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"！有"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"什么可以帮"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_xxx","name":"read_file"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"file"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"_path\":\"src/main.java\"}"}}

event: content_block_stop                              ← 此时组装完整 tool_use → callback TOOL_CALL
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null}}

event: message_stop
data: {"type":"message_stop"}                           ← callback.onComplete("tool_use")
```

## 6. `parseSSEStream` 中的关键状态机

Anthropic 的解析不是无状态的——处理 `tool_use` 需要跨事件累积：

```
状态变量（per-stream，每次调用 parseSSEStream 时重置）：
┌─────────────────────────────────────────────┐
│ currentBlockType: "text" | "tool_use" | "thinking" │
│ currentToolUseId: "toolu_xxx"               │
│ currentToolUseName: "read_file"             │
│ toolInputJsonBuffer: StringBuilder          │ ← 累积 input_json_delta 的 partial_json
│ lastStopReason: "end_turn" | "tool_use" | ...│
└─────────────────────────────────────────────┘
```

流程：
1. `content_block_start` → 设置 `currentBlockType`，如果是 `tool_use` 则记录 id/name 并清空 buffer
2. N 次 `content_block_delta` → 根据 `delta.type` 分流：
   - `text_delta` / `thinking_delta` → 直接 `callback.onChunk()`
   - `input_json_delta` → `toolInputJsonBuffer.append(partialJson)` **只累积不推送**
3. `content_block_stop` → 如果当前块是 `tool_use`，把 `{id, name, input: 完整JSON}` 序列化 → `callback.onChunk(serialized, TOOL_CALL)`
4. `message_stop` → 捕获 `stop_reason` → `callback.onComplete(stopReason)`

## 7. 为什么 tool_use JSON 要跨事件拼接

Anthropic 把 tool_use 的参数以 `input_json_delta` 增量发送，每次只发一小段 JSON 片段。例如：

```
第1个 delta: {"file
第2个 delta: _path": "/
第3个 delta: etc/config.
第4个 delta: yaml"}
```

拼接后才是合法 JSON：`{"file_path": "/etc/config.yaml"}`

这避免了单次 `content_block_delta` 承载大量数据导致的网络层延迟感知。

MewCode 只累积，不做**增量解析**（即不尝试一边累积一边做流式反序列化）。`content_block_stop` 时一次性 `jsonMapper.readValue(fullJson, Map.class)` 得到结构化 `input`。

## 8. 错误处理

两个 provider 的 `parseSSEStream` 都不抛异常——任何单行解析失败都静默跳过：

```java
} catch (Exception e) {
    // Skip malformed SSE lines silently — they are rare and usually recoverable
}
```

因为 SSE 是单向推送，单行损坏不影响后续事件，静默跳过比中断整个流更合理。

真正的错误通过 SSE `error` 事件传递（Anthropic），或通过 HTTP 状态码在 `streamWithRetry` 的 `!response.isSuccessful()` 分支处理（两个 provider 共用）。

## 9. 总结对比

| 维度 | Anthropic Messages API | OpenAI Chat Completions API |
|---|---|---|
| SSE 协议复杂度 | 高（9 种 event 类型，4 种 delta 子类型） | 低（只有 `data:` 行，无 `event:` 行） |
| 流式工具调用 | ✅ 原生支持（`tool_use` 块 + `input_json_delta`） | ❌ 原生不支持 |
| 思考过程 | ✅ `thinking_delta` 独立传输 | ❌ 无此概念 |
| 终止信号 | `message_stop` SSE 事件 | `data: [DONE]` 哨兵值 |
| stop_reason | `end_turn` / `tool_use` / `max_tokens` / `stop_sequence` | 始终为 `stop` |
| 需要累积的状态 | currentBlockType / toolInputJsonBuffer / lastStopReason | 无 |
