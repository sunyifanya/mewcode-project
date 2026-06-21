# tasks.md — 结构化系统提示与缓存策略

## Task 1: SystemPromptModule 模块定义
**影响文件**: `conversation/SystemPromptModule.java`（新建）
**依赖**: 无
**要点**:
- 字段：name（模块名）、content（提示词文本）、priority（整数，越小越靠前）、placement（枚举 SYSTEM / REMINDER）
- placement 枚举可直接放在 SystemPromptModule 内或作为独立文件
- 纯数据类，构造器 + getter，无业务逻辑
- 参考：`agent/AgentEvent.java` 的数据类风格

---

## Task 2: ReminderContext 上下文对象
**影响文件**: `conversation/ReminderContext.java`（新建）
**依赖**: 无
**要点**:
- 字段：iteration（当前轮次，0-based）、planMode（布尔）、workingDirectory（字符串）、platform（字符串）、currentDate（字符串）
- 纯数据类，全参构造器 + getter
- iteration 和 planMode 由 AgentLoop 传入；workingDirectory/platform/currentDate 由 ConversationManager 在构造时缓存

---

## Task 3: SystemPromptBuilder 组装器
**影响文件**: `conversation/SystemPromptBuilder.java`（新建）
**依赖**: Task 1, Task 2
**要点**:
- 构造器接收 `List<SystemPromptModule>`
- `String composeSystem()`：过滤 placement=SYSTEM 的模块，按 priority 升序排列，用两个换行符连接
- `String composeReminder(ReminderContext ctx)`：过滤 placement=REMINDER 的模块，按 priority 排，拼接后包裹在 `<system-reminder>\n...\n</system-reminder>` 中
- Plan Mode 注入节奏逻辑放在 composeReminder 内：
  - 若 ctx.planMode == false → 跳过 plan_mode 模块
  - 若 ctx.planMode == true 且 ctx.iteration == 0 → 注入完整 plan_mode 内容
  - 若 ctx.planMode == true 且 (ctx.iteration + 1) % 3 == 0 → 注入完整 plan_mode 内容（第 3/6/9…轮）
  - 若 ctx.planMode == true 且其他轮次 → 注入精简版（一行 "[规划模式进行中]" 等效文本）
- SystemPromptModule 的 plan_mode 模块需定义两个内容变体（fullContent 和 terseContent），可在模块构造时传入或通过子类/Builder 模式处理

---

## Task 4: ConversationManager 改造
**影响文件**: `conversation/ConversationManager.java`
**依赖**: Task 3
**参考**: `conversation/ConversationManager.java:24-37` — 当前 system prompt 存储和构造逻辑
**要点**:
- 新增字段：`SystemPromptBuilder promptBuilder`、`ReminderContext` 中的环境信息部分（workingDirectory、platform、currentDate，在构造时缓存）
- 构造器改为接收 `SystemPromptBuilder`，保留无参构造器（内部用默认模块列表创建 builder）
- `getMessages()` 改为 `getMessages(int iteration, boolean planMode)`：内部创建完整的 ReminderContext，调用 composeReminder，找到消息列表中最后一条 role=user 的消息，将 reminder 文本前缀到该消息的 content 前面，返回修改后的副本。**内部存储的消息不被修改。**
- `clear()` 方法：清空消息列表后重新插入 system 消息（调用 `promptBuilder.composeSystem()`）
- 删除旧的硬编码三行中文 system prompt
- 保留 `compressIfNeeded()` 等上下文压缩逻辑不变
- 参考：`conversation/ConversationManager.java:71-73` — getMessages() 当前实现

---

## Task 5: CacheMetrics 数据类
**影响文件**: `provider/CacheMetrics.java`（新建）
**依赖**: 无
**要点**:
- 字段：cacheCreationTokens（long）、cacheReadTokens（long）
- 不可变，全参构造器 + getter
- 提供一个 `boolean isEmpty()` 方法：cacheCreationTokens == 0 && cacheReadTokens == 0 时返回 true

---

## Task 6: AnthropicProvider 缓存指标提取
**影响文件**: `provider/AnthropicProvider.java`
**依赖**: Task 5
**参考**: `provider/AnthropicProvider.java:235-343` — processSSEEvent 方法
**要点**:
- 新增实例变量 `private CacheMetrics lastCacheMetrics`
- 在 `processSSEEvent` 中新增 `"message_start"` case：从 JSON 中提取 `message.usage.cache_creation_input_tokens` 和 `message.usage.cache_read_input_tokens`，若都存在则构建 CacheMetrics 存入 lastCacheMetrics
- 新增 `public CacheMetrics getLastCacheMetrics()` 方法
- 在 `parseSSEStream` 开始时将 lastCacheMetrics 置为 null（每次新请求重置）
- 提取失败（字段缺失、JSON 异常）时 lastCacheMetrics 保持 null，不影响正常流式响应
- 参考：`provider/AnthropicProvider.java:240` — content_block_start case，message_start 与它同级

---

## Task 7: AgentLoop 缓存信息展示
**影响文件**: `agent/AgentLoop.java`, `agent/AgentEvent.java`
**依赖**: Task 6
**参考**: `agent/AgentLoop.java:92-111` — run() 核心循环中 TOKEN_USAGE 事件的推送位置
**要点**:
- AgentEvent 的 TOKEN_USAGE 事件增加两个可选字段：cacheCreationTokens、cacheReadTokens（-1 表示无数据）
- AgentLoop.run() 中：每轮 streamingCollector.awaitCompletion() 之后，调用 `provider.getLastCacheMetrics()`（需将 provider 引用从 LLMProvider 转为 AnthropicProvider 或通过 instanceof 判断）
- 若 CacheMetrics 非 null 且非空，将数值填入 TOKEN_USAGE 事件
- 事件消费线程（MewCode.startEventConsumer()）在收到 TOKEN_USAGE 时，若缓存字段 > 0，打印 `[cache hit: X tokens read, Y created]` 到终端
- 若 provider 不是 AnthropicProvider 实例（如 OpenAI），跳过缓存提取，TOKEN_USAGE 事件中缓存字段为 -1
- 参考：`src/main/java/com/mewcode/MewCode.java:130-131` — TOKEN_USAGE 当前处理

---

## Task 8: Plan Mode 节奏控制完善
**影响文件**: `conversation/SystemPromptBuilder.java`（已在 Task 3 中创建）
**依赖**: Task 3, Task 4
**要点**:
- 在 SystemPromptBuilder 中定义 plan_mode 模块的特殊处理逻辑
- plan_mode 模块存储完整版和精简版两份内容
- composeReminder 中根据 ReminderContext 选择对应版本
- 非 Plan Mode 时 plan_mode 模块不参与 assemble
- 同时在 AgentLoop 中确认 iteration 计数从 0 开始（首轮 iteration=0 → 完整版）

---

## Task 9: 接入主流程
**影响文件**: `MewCode.java`, `conversation/ConversationManager.java`
**依赖**: Task 4, Task 7, Task 8
**参考**: `src/main/java/com/mewcode/MewCode.java:51` — ConversationManager 创建位置；`src/main/java/com/mewcode/MewCode.java:77-84` — AgentLoop 创建位置
**要点**:
- MewCode.main() 中：
  1. 创建默认模块列表（身份、行为准则、工具使用指南、代码质量规范、安全边界、任务执行模式、输出风格），各自指定 placement，按 spec 中的分配决定哪些进 SYSTEM 哪些进 REMINDER
  2. 用模块列表创建 SystemPromptBuilder
  3. 将 SystemPromptBuilder 传入 ConversationManager 构造器
  4. AgentLoop 创建时无需改签名，但其 run() 内调用 `conversation.getMessages(iteration, planMode)` 替代原先无参 `getMessages()`
- 事件消费线程中增加 TOKEN_USAGE 缓存字段的展示逻辑（已在 Task 7 覆盖）
- 删除 MewCode.java 中不再需要的旧系统提示相关代码（如果有残留）

---

## Task 10: 端到端验证
**影响文件**: 无（手动验证）
**依赖**: Task 9
**验证步骤**:
1. 启动 MewCode，输入"你好" → 观察终端是否在回复结束后打印 `[cache hit: ...]` 信息
2. 连续输入两次"你好" → 第二次响应应显示 cache_read_tokens > 0（缓存命中）
3. 输入"在 src 下找所有 .java 文件" → 观察 Agent 是否优先使用 Glob 而非 Bash find
4. 输入 `/plan` → 输入"分析项目结构" → 观察 Agent 不会调用 WriteFile 等写工具（Plan Mode 生效）
5. 在 Plan Mode 下连续输入 4 次短问题 → 观察第 1 次和第 3 次终端输出中有完整规划模式提示（节奏验证）
6. 检查 AnthropicProvider 日志：`grep "cache_creation_input_tokens"` 在 SSE 流中能找到对应字段
