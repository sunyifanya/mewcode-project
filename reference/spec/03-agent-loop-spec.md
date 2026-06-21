# spec.md — Agent Loop

## 背景
当前 MewCode 只支持"用户输入 → 模型回复（含工具调用）→ 执行工具 → 再调一次模型 → 最终回复"的两轮对话。模型不能自己决定"结果不够，再调一次工具"，用户必须手动催促下一轮。这不是真正的 Agent——真正的 Agent 应该能自主循环：思考、调工具、看结果、调整策略、再调工具，直到任务完成。

## 目标用户
使用 MewCode 处理多步骤编程任务的开发者。典型场景："帮我重构这个模块"、"在项目中找所有 SQL 注入风险并修复"、"给这个类写单元测试"——任何单次 LLM 调用装不下的复杂任务。

## 能力清单

1. **ReAct 循环**：Agent 在"推理 → 工具调用 → 观察结果 → 继续推理"之间自动循环，直到模型判定任务完成（stop_reason 不再是 tool_use）。
2. **多停止条件**：循环在任意一种条件触发时立即终止——模型自然结束、达到迭代上限、用户取消、任一工具调用指向未知工具、流式传输错误。迭代上限是兜底安全网，防止无限循环。
3. **异步事件流**：Agent 运行在独立线程，通过 `BlockingQueue<AgentEvent>` 向 UI 层推送事件（文本增量、工具调用开始/结束、Token 用量、进度消息），UI 消费者线程独立读取并渲染，Agent 与界面彻底解耦。
4. **流式双路收集**：StreamingCollector 一边将文本/thinking 事件实时推入事件队列供 UI 渲染，一边在内存累积完整响应（文本字符串 + 工具调用列表 + stop_reason），供循环判断用。
5. **工具执行排序**：模型一次返回多个工具调用时，所有只读工具（`isReadOnly()=true`）先在线程池中并发执行，等待全部完成后，有副作用工具按原始顺序串行执行。
6. **Plan Mode 两段式**：用户在聊天框输入 `/plan` 后，后续 LLM 请求只携带只读工具子集，模型只能调研和出计划；输入 `/do` 后恢复全工具集，模型可执行读写操作。
7. **Token 用量追踪**：每轮 LLM 调用完成后，从响应中提取 input/output token 数，累计并通过事件推送。

## 非功能要求

- Agent Loop 不阻塞终端 readline 循环（运行在独立线程）
- 事件队列有界（默认容量 1000），背压保护
- 工具并发线程池大小 = CPU 核数 × 2（上限 10）
- 迭代上限可通过 mewcode.yaml 配置
- 单轮 LLM 流超时（默认 300 秒）可通过 mewcode.yaml 配置
- 用户 Ctrl+C 时 Agent Loop 能优雅退出（不丢最后的事件）

## 设计骨架

```
agent/                          ← 新包
├── AgentLoop                   ← 实现 Runnable，ReAct 循环编排
├── AgentEvent                  ← 不可变事件数据类
├── AgentEventType              ← 事件类型枚举
├── StreamingCollector          ← 实现 StreamCallback，双路收集
├── ToolExecutionStrategy       ← 工具分组 + 并发/串行执行
└── StopReason                  ← stop_reason 枚举映射

修改:
├── tool/Tool.java              ← +isReadOnly()
├── tool/ToolRegistry.java      ← +getReadOnlyTools() + toApiFormat(List<Tool>)
├── tool/impl/ReadFileTool.java ← isReadOnly()=true
├── tool/impl/GlobTool.java     ← isReadOnly()=true
├── tool/impl/GrepTool.java     ← isReadOnly()=true
├── tool/impl/WriteFileTool.java← isReadOnly()=false
├── tool/impl/EditFileTool.java ← isReadOnly()=false
├── tool/impl/ExecuteCommandTool.java ← isReadOnly()=false
├── provider/StreamCallback.java← onComplete(String stopReason)
├── provider/LLMProvider.java   ← 适配新回调签名（如需要）
├── provider/AnthropicProvider.java ← message_stop 中提取 stop_reason
├── config/AppConfig.java       ← +maxIterations 字段
├── config/ConfigLoader.java    ← +maxIterations 默认值校验
├── tui/TerminalUI.java         ← 事件消费线程 + /plan /do 命令处理
└── MewCode.java                ← 用 AgentLoop 替代旧的两轮回调
```

## Out of Scope

- 权限系统（用户确认工具执行）
- 上下文自动压缩（ConversationManager 已有基础压缩，本次不改）
- 用户交互式确认
- 工具执行超时后的自动重试（现有 ExecuteCommandTool 的 timeout 保留不变）
- 多 Agent 协作
- Agent 状态持久化与恢复
- 循环检测（连续 N 轮调用相同工具且结果不变 → 应警告退出）
- API 返回 error 类型的 message_stop 时按 error 处理（本次超出范围）
