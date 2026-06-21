# MewCode — 纯对话阶段 spec

## 背景
从零构建一个终端 AI 编程助手（Coding Agent），本阶段只实现交互式对话能力，不涉及 tool use、文件操作、代码编辑等 agent 功能。

## 目标用户
在终端工作的开发者，希望用自然语言与 LLM 对话，获得流式回复体验。

---

## 能力清单

1. **交互式 TUI 对话**：用户启动程序后进入 JLine 驱动的终端界面，可输入问题、看到流式回复、继续下一轮提问
2. **双 API 后端**：支持 Anthropic（Claude）和 OpenAI 两种 LLM API，通过 YAML 配置文件切换
3. **SSE 流式输出**：通过 Server-Sent Events 实时接收模型回复，逐 chunk 打印到终端，不等全部生成完
4. **Extended Thinking 支持**：调用 Claude 时开启 extended thinking，思考过程用不同颜色/前缀区分显示
5. **多轮对话记忆**：对话历史保存在内存中，AI 能记住本轮会话中之前说过的话
6. **上下文窗口管理**：接近模型上下文上限时，对旧消息做关键词提取后保留摘要，超出则丢弃最旧消息
7. **基础斜杠命令**：`/clear` 清空对话历史重新开始，`/exit` 退出程序
8. **YAML 配置驱动**：配置文件指定 protocol（anthropic / openai）、model、base_url、api_key
9. **友好的错误处理**：配置文件缺失或格式错误时打印明确错误信息并退出；API 网络错误指数退避重试

---

## 非功能要求

- JDK 21，Maven 构建
- TUI 基于 JLine，输入支持行编辑和历史翻页（上下箭头）
- 回复内容纯文本输出，不做 Markdown 渲染/语法高亮
- 退出方式：`/exit` 命令或 Ctrl+C
- Provider 层抽象为统一接口，方便后续添加新 LLM 后端

---

## 设计骨架

```
mewcode/
├── pom.xml                          # Maven, JDK 21
├── mewcode.yaml                     # 用户配置文件（放在项目根目录）
└── src/main/java/com/mewcode/
    ├── MewCode.java                 # 入口 main()
    ├── config/
    │   ├── AppConfig.java           # YAML → 配置对象
    │   └── ConfigLoader.java        # 文件读取 + 校验
    ├── provider/
    │   ├── LLMProvider.java         # 统一接口 (streaming chat)
    │   ├── AnthropicProvider.java   # Anthropic Messages API (SSE)
    │   └── OpenAIProvider.java      # OpenAI Chat Completions API (SSE)
    ├── conversation/
    │   ├── ConversationManager.java # 消息列表 + 上下文窗口管理
    │   ├── Message.java             # 单条消息 (role + content)
    │   └── KeywordExtractor.java    # 关键词提取（上下文压缩用）
    └── tui/
        ├── TerminalUI.java          # JLine 交互循环 + 命令处理
        └── StreamingDisplay.java    # 流式输出渲染（含 thinking 着色）
```

### 配置格式 (mewcode.yaml)
```yaml
protocol: anthropic          # anthropic | openai
model: claude-sonnet-4-6     # 模型 ID
base_url: https://api.anthropic.com/v1
api_key: sk-xxx              # API 密钥，明文
thinking_budget: 16000       # Claude extended thinking 预算（可选，仅 anthropic）
```

### 数据流
```
用户输入 → TerminalUI
         → ConversationManager.addUserMessage()
         → LLMProvider.streamChat(messages, callback)
         → HTTP POST (SSE) → 逐 chunk 回调
         → StreamingDisplay 逐 chunk 打印
         → ConversationManager.addAssistantMessage()
         → 回到 TerminalUI 等待下一轮输入
```

---

## Out of Scope（本阶段明确不做）

- Tool use / function calling / agent 自主执行
- 文件系统读写（配置文件读取除外）
- 代码编辑、apply patch
- MCP (Model Context Protocol) 集成
- 对话历史持久化到磁盘
- 多 profile 切换
- Markdown / 语法高亮渲染
- `/help`、`/model` 等高级斜杠命令（只做 `/clear` 和 `/exit`）
- 多模态（图片/文件输入）
- 系统 prompt 自定义
- tab 自动补全
- 并发多轮对话 / session 管理
