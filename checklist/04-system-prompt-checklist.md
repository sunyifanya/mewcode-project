# checklist.md — 结构化系统提示与缓存策略

## 模块拆分

- [ ] `grep -r "SystemPromptModule" src/` 返回 ≥ 1 个文件
- [ ] `grep -r "enum.*Placement\|SYSTEM.*REMINDER" src/conversation/` 返回 ≥ 1 条匹配
- [ ] 七个默认模块（身份、行为准则、工具使用指南、代码质量规范、安全边界、任务执行模式、输出风格）的 name 字段各不相同
- [ ] 五个 SYSTEM 模块的 priority 各不相同且按升序排列后顺序为：身份 → 行为准则 → 代码质量规范 → 安全边界 → 输出风格
- [ ] REMINDER 模块中工具使用指南和安全精简提醒的 priority 排在任务执行模式之前

## 系统提示组装

- [ ] `SystemPromptBuilder.composeSystem()` 返回的字符串长度 > 500 字符
- [ ] `SystemPromptBuilder.composeSystem()` 返回的字符串包含 "MewCode" 字样（身份模块）
- [ ] `SystemPromptBuilder.composeSystem()` 返回的字符串包含 "优先" 字样（工具使用指南没有进 system，但代码质量规范可能包含）
- [ ] `SystemPromptBuilder.composeReminder(ctx)` 在 planMode=false 时返回的内容以 `<system-reminder>` 开头、以 `</system-reminder>` 结尾
- [ ] `SystemPromptBuilder.composeReminder(ctx)` 在 planMode=true 且 iteration=0 时返回的内容包含 Plan Mode 完整指令
- [ ] `SystemPromptBuilder.composeReminder(ctx)` 在 planMode=true 且 iteration=2（第 3 轮）时返回的内容包含 Plan Mode 完整指令
- [ ] `SystemPromptBuilder.composeReminder(ctx)` 在 planMode=true 且 iteration=1（第 2 轮）时返回的内容不包含 Plan Mode 完整指令，但包含精简版

## `<system-reminder>` 注入

- [ ] `ConversationManager.getMessages(0, false)` 返回的列表中，最后一条 user 消息的 content 以 `<system-reminder>` 开头
- [ ] `ConversationManager.getMessages(n, mode)` 调用后，内部存储的用户消息 content 不变（不包含 `<system-reminder>` 前缀）
- [ ] `ConversationManager.getMessages(0, false)` 返回的列表第一条消息的 role 为 `"system"`
- [ ] 连续两次调用 `getMessages(0, false)` 和 `getMessages(1, false)`，两次返回的 reminder 内容不同（iteration 变化）
- [ ] 原始用户输入 "你好" 在内部存储中保持 "你好"，不被 `<system-reminder>` 污染

## AnthropicProvider 缓存提取

- [ ] `AnthropicProvider` 类中存在 `getLastCacheMetrics()` 方法
- [ ] `grep -r "cache_creation_input_tokens" src/provider/` 返回 ≥ 1 条匹配
- [ ] `grep -r "cache_read_input_tokens" src/provider/` 返回 ≥ 1 条匹配
- [ ] `grep -r "message_start" src/provider/AnthropicProvider.java` 返回 ≥ 1 条匹配（SSE 处理新增了 message_start case）
- [ ] 当 API 响应中缺失缓存字段时，`getLastCacheMetrics()` 返回 null（不抛异常）

## 缓存展示

- [ ] 启动 MewCode，输入任意问题 → 终端出现 `[cache hit: ...]` 字样（第一轮应显示 cache_read=0, cache_creation>0）
- [ ] 在同一会话中再次输入问题 → 终端 `[cache hit: ...]` 中 cache_read > 0
- [ ] 使用 OpenAI provider 时，终端不出现 NullPointerException 或 crash（缓存字段为 null 时优雅处理）

## Plan Mode 节奏

- [ ] 输入 `/plan` → 终端确认进入规划模式
- [ ] 在规划模式下输入第 1 个问题 → API 请求中的 user 消息包含完整 Plan Mode 指令
- [ ] 在规划模式下输入第 2 个问题（同一次 /plan 内连续） → API 请求中的 user 消息包含精简版 Plan Mode 指令
- [ ] 在规划模式下输入第 3 个问题 → API 请求中的 user 消息再次包含完整 Plan Mode 指令
- [ ] 输入 `/do` → 终端确认退出规划模式，下一个请求不含 Plan Mode 指令

## 回归验证

- [ ] 无参 `new ConversationManager()` 仍然可用（内部使用默认模块列表）
- [ ] `ConversationManager.clear()` 后 system 消息仍然存在
- [ ] `ConversationManager.compressIfNeeded()` 行为不变（上下文压缩不因 system prompt 改造而异常）
- [ ] ToolRegistry + AnthropicProvider 的 `streamChat(messages, callback, toolSubset)` 三重载仍然正常工作
- [ ] AgentLoop 的 maxIterations 终止条件不受影响
