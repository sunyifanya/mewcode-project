# spec.md — 结构化系统提示与缓存策略

## 背景
当前 MewCode 的系统提示是 ConversationManager 里硬编码的三行中文。所有指令挤在一个字符串里，无法按职责拆分，也无法区分哪些内容该走 Anthropic 的 prompt cache（稳定指令）哪些该随轮次变化（环境信息、Plan Mode 开关）。结果是：每次请求都全量发送，缓存利用率低；关键规则（如"编辑前先读"）只在首轮出现，后续轮次被消息冲走，模型遵守率下降。

## 目标用户
MewCode 的开发者，需要在迭代提示词质量时能快速定位模块、调整优先级、验证缓存策略是否生效。

## 能力清单

1. **模块化提示词组装**：将全局指令按职责拆成独立模块（身份、行为准则、工具使用指南、代码质量规范、安全边界、任务执行模式、输出风格），每个模块有名称、内容、优先级、放置位置（system 或 reminder），按优先级排序后拼装为最终提示词。
2. **稳定/动态分层**：稳定的模块（身份、行为准则、代码质量规范、安全边界完整版、输出风格）放入 Anthropic `system` 顶层字段走 prompt cache；每轮变化的内容（工具使用指南关键规则、安全精简提醒、任务执行模式、环境信息）通过 `<system-reminder>` 消息注入。
3. **`<system-reminder>` 注入机制**：在每次 `getMessages()` 调用时，将动态模块内容包裹在 `<system-reminder>...</system-reminder>` 标签中，作为本轮最新用户消息的前缀注入。不走 system 字段，不破坏 prompt cache。
4. **Plan Mode 注入节奏**：Plan Mode 指令按轮次控制注入频率——首轮注入完整指令，之后每 3 轮（第 3、6、9…轮）重复注入，其余轮次不注入。非 Plan Mode 的 reminder 内容每轮都注入。
5. **缓存命中验证**：Provider 从 API 响应中提取缓存相关指标（缓存创建 token 数、缓存读取 token 数），暴露可编程接口；AgentLoop 每轮结束时读取并在终端显示缓存命中状态。
6. **未来扩展槽**：system 层预留自定义指令（项目级 CLAUDE.md）和长期记忆两个空模块；reminder 层预留已激活 Skill 列表空模块。本轮不实现内容，只留槽位。

## 非功能要求

- 拼装操作在 `getMessages()` 中完成，不引入可感知的延迟（纯字符串拼接 + 列表操作）
- 模块内容修改后不影响 Provider 层的请求构建逻辑（Provider 只接收组装好的消息列表）
- 缓存指标提取失败时不影响正常流式响应（降级为不显示缓存信息，不抛异常）

## 设计骨架

```
conversation/
├── SystemPromptModule       ← 新，模块定义（名称、内容、优先级、放置位置）
├── SystemPromptBuilder      ← 新，接收模块列表，按优先级 + 放置位置拼装
├── ConversationManager      ← 改，持有 SystemPromptBuilder，getMessages() 改为动态组装
└── Message                  ← 不改（<system-reminder> 是普通 user 消息的内容前缀）

provider/
├── AnthropicProvider        ← 改，SSE 解析中提取 usage 对象，暴露 getLastCacheMetrics()
├── CacheMetrics             ← 新，不可变数据类（cacheCreationTokens, cacheReadTokens）
└── LLMProvider              ← 不改接口

agent/
├── AgentLoop                ← 改，每轮结束后读取 CacheMetrics，推 TOKEN_USAGE 事件时附带缓存信息
└── AgentEvent               ← 可能改，TOKEN_USAGE 事件增加缓存字段

config/
└── AppConfig                ← 不改（本轮无新配置项）
```

## Out of Scope

- 项目指令文件加载（CLAUDE.md 读取与注入）——后面章节
- 自动记忆系统——后面章节
- 真实 MCP 接入与工具描述动态生成——后面章节
- 自动化评估（缓存命中率统计脚本、AB 对比框架）——后面章节
- Skill 系统的实际实现——后面章节，本轮只留槽位
- 模块内容的热重载（修改模块文本后无需重启）——不在本轮范围
- 缓存策略的定量评估报告——本轮只做人工定性观察
