# 12-hook-system-tasks.md

## 任务概览

| # | 任务 | 依赖 | 影响文件 |
|---|------|------|---------|
| 1 | 数据模型与 YAML 反序列化 | 无 | 新建 `hook/` 包 |
| 2 | 条件求值器 | 1 | `hook/ConditionEvaluator.java` |
| 3 | 模板变量替换 | 1 | `hook/TemplateEngine.java` |
| 4 | Command 动作执行器 | 1, 3 | `hook/CommandExecutor.java` |
| 5 | Prompt 动作执行器 | 3 | `hook/PromptExecutor.java` |
| 6 | HTTP 动作执行器 | 3 | `hook/HttpExecutor.java` |
| 7 | HookEngine 核心 | 1, 2 | `hook/HookEngine.java` |
| 8 | YAML 加载与集中校验 | 1, 7 | `hook/HookLoader.java` |
| 9 | 错误日志隔离 | 7 | `hook/HookErrorLogger.java` |
| 10 | AgentLoop 生命周期集成 | 7, 8 | `agent/AgentLoop.java` |
| 11 | pre_tool_use 拦截集成 | 7 | `agent/AgentLoop.java`, `tool/ToolExecutionStrategy.java` |
| 12 | 主流程接入与端到端验证 | 10, 11 | `MewCode.java` |

---

## 任务 1：数据模型与 YAML 反序列化

**影响文件**：新建 `src/main/java/com/mewcode/hook/HookConfig.java`、`HookCondition.java`、`HookAction.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/hook/HookEngine.java` 第 17-57 行（EventName、ActionType、数据记录）
- 现有 YAML 反序列化模式 `src/main/java/com/mewcode/permission/RuleEntry.java`（Jackson 注解 + resolve() 模式）
- 现有权限配置加载 `src/main/java/com/mewcode/permission/PermissionConfig.java`（YAML 文件读取模式）

**内容**：
- 定义 `EventName` 枚举（9 个事件值，带 `@JsonProperty` 支持小写下划线）
- 定义 `ActionType` 枚举（command / prompt / http / sub_agent）
- 定义 `HookCondition` record：`variable`（变量名）、`operator`（== / != / =~ / glob）、`value`（匹配值）
- 定义 `HookAction` record：`type`（ActionType）、`command`（仅 command）、`message`（仅 prompt）、`url`/`method`/`headers`/`body`（仅 http）、`timeout`（仅 command，默认 30）、`background`（仅 command，默认 false）
- 定义 `HookConfig` record：`id`、`event`（EventName）、`conditions`（List<HookCondition>，可空）、`conditionMode`（all/any，默认 all）、`action`（HookAction）、`reject`（默认 false）、`runOnce`（默认 false）
- Jackson 注解：`@JsonProperty` 映射 YAML 字段名，枚举用 `@JsonValue` / `@JsonCreator`
- YAML 示例：
```yaml
id: auto-format
event: post_tool_use
if:
  mode: all
  conditions:
    - variable: tool
      operator: "=="
      value: WriteFile
action:
  type: command
  command: "mvn spotless:apply"
  timeout: 30
  background: true
```

**对应 checklist**：1-4

---

## 任务 2：条件求值器

**依赖**：任务 1

**影响文件**：新建 `src/main/java/com/mewcode/hook/ConditionEvaluator.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/hook/HookEngine.java` 第 126-169 行（evaluateCondition、resolveVar、stripQuotes）
- 现有 glob 匹配 `src/main/java/com/mewcode/permission/RuleEngine.java` 第 103-111 行（matchGlob）

**内容**：
- `boolean evaluate(List<HookCondition> conditions, String mode, HookContext ctx)`
  - mode=all：全部满足才返回 true（空列表返回 true）
  - mode=any：任一满足即返回 true（空列表返回 false）
- `boolean evaluateOne(HookCondition c, HookContext ctx)`：根据 operator 分发
  - `==`：`resolveVar(c.variable(), ctx).equals(c.value())`
  - `!=`：`!resolveVar(c.variable(), ctx).equals(c.value())`
  - `=~`：`Pattern.matches(c.value(), resolveVar(c.variable(), ctx))`
  - `glob`：复用 `RuleEngine.matchGlob(c.value(), resolveVar(c.variable(), ctx))`
- `String resolveVar(String name, HookContext ctx)`：
  - `tool` → `ctx.toolName()`
  - `event` → `ctx.event().value()`
  - `file_path` → `ctx.filePath()`
  - `message` → `ctx.message()`
  - `error` → `ctx.error()`
  - `args.<key>` → `ctx.toolArgs().get(key)`
  - 未知变量 → `""`
  - null 值 → `""`

**对应 checklist**：5-7

---

## 任务 3：模板变量替换

**依赖**：任务 1

**影响文件**：新建 `src/main/java/com/mewcode/hook/TemplateEngine.java`

**参考资料**：
- 任务 2 的变量解析逻辑（复用相同的变量名集合）

**内容**：
- `String render(String template, HookContext ctx)`：替换 `${var}` 占位符
- 正则匹配 `${...}` 模式，对每个匹配调用条件求值器同款 `resolveVar()`
- 未定义变量替换为空串，不抛异常
- 无需替换的文本原样返回

**对应 checklist**：8

---

## 任务 4：Command 动作执行器

**依赖**：任务 1, 3

**影响文件**：新建 `src/main/java/com/mewcode/hook/CommandExecutor.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/hook/HookEngine.java` 第 196-220 行（executeCommand）

**内容**：
- `HookResult execute(HookConfig hook, HookContext ctx)`：
  - 先对 command 字符串做模板替换
  - 用 `ProcessBuilder("bash", "-c", renderedCommand)` 执行
  - 注入环境变量：`MEWCODE_EVENT`、`MEWCODE_TOOL`
  - 非 background：`proc.waitFor(timeout, TimeUnit.SECONDS)`，超时则 `proc.destroyForcibly()`
  - background：在虚拟线程或守护线程中执行，立即返回 success=true
  - 收集 stdout + stderr 合并为 output
  - 返回 `HookResult(hookId, output, exitCode==0)`
- 超时时 output 追加 `[Hook timed out after ${timeout}s]`
- 执行异常时 catch IOException/InterruptedException，返回 `HookResult(hookId, errorMsg, false)`

**对应 checklist**：9-11

---

## 任务 5：Prompt 动作执行器

**依赖**：任务 3

**影响文件**：新建 `src/main/java/com/mewcode/hook/PromptExecutor.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/hook/HookEngine.java` 第 190 行（PROMPT 分支）

**内容**：
- `HookResult execute(HookConfig hook, HookContext ctx)`：
  - 对 `action.message()` 做模板替换
  - 返回 `HookResult(hookId, renderedMessage, true)`
- Prompt 结果由 HookEngine 收集后，调用方负责将 renderedMessage 注入对话

**对应 checklist**：12

---

## 任务 6：HTTP 动作执行器

**依赖**：任务 3

**影响文件**：新建 `src/main/java/com/mewcode/hook/HttpExecutor.java`

**参考资料**：Java 11 `java.net.http.HttpClient`

**内容**：
- `HookResult execute(HookConfig hook, HookContext ctx)`：
  - 对 url / headers / body 分别做模板替换
  - 用 `java.net.http.HttpClient` 发送请求
  - 支持 method: GET / POST / PUT / DELETE
  - headers 是 `Map<String, String>` 或 `List<String>`（每行 `Key: Value` 格式）
  - body 仅在 method 为 POST/PUT 时发送
  - 超时 10 秒
  - 返回 `HookResult(hookId, responseBody, statusCode < 400)`
- 连接超时 / 读取异常返回 `HookResult(hookId, errorMsg, false)`

**对应 checklist**：13-14

---

## 任务 7：HookEngine 核心

**依赖**：任务 1, 2

**影响文件**：新建 `src/main/java/com/mewcode/hook/HookEngine.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/hook/HookEngine.java` 全文
- 现有事件分发模式 `src/main/java/com/mewcode/agent/AgentLoop.java` 的 offerEvent 机制

**内容**：
- `void loadHooks(List<HookConfig> configs)`：替换全部规则
- `List<HookResult> runHooks(EventName event, HookContext ctx)`：
  - 遍历 hooks，匹配 event + 条件求值
  - 跳过 `runOnce=true` 且已执行过的（用 `Set<String> executedOnceIds` 内存去重）
  - `reject=true` 的 pre_tool_use hook 不看 runOnce（每次都检查）
  - 按配置调用对应 ActionExecutor
  - 捕获所有 Throwable，失败时写错误日志，不中断遍历
- `PreToolResult runPreToolHooks(String toolName, Map<String, Object> args)`：
  - 遍历 hooks，匹配 `PRE_TOOL_USE` + 条件求值
  - 第一个 `reject=true` 且执行成功的 Hook 返回 `PreToolResult(true, output)`
  - 没有匹配的或没有 reject 的返回 `PreToolResult(false, "")`
- `HookContext` record：`event`、`toolName`、`toolArgs`、`filePath`、`message`、`error`（全可空）
- `PreToolResult` record：`rejected(boolean)`、`message(String)`
- `HookResult` record：`hookId`、`output`、`success`、`reject`

**对应 checklist**：15-17

---

## 任务 8：YAML 加载与集中校验

**依赖**：任务 1, 7

**影响文件**：新建 `src/main/java/com/mewcode/hook/HookLoader.java`

**参考资料**：
- 现有会话加载 `src/main/java/com/mewcode/session/SessionManager.java`（文件遍历模式）
- YAML 解析：Jackson `ObjectMapper` + `YAMLFactory`

**内容**：
- `LoadedHooks load(Path hooksDir)`：
  - 遍历 `.mewcode/hooks/` 下所有 `.yaml` / `.yml` 文件
  - 用 Jackson 反序列化为 `HookConfig`
  - 集中校验：
    - `id` 非空
    - `event` 是合法值（EventName 枚举）
    - `action.type` 是合法值
    - command 类型必须有 `command` 字段非空
    - prompt 类型必须有 `message` 字段非空
    - http 类型必须有 `url` 字段非空
    - `reject=true` 且 `background=true` → Warning
    - `if.mode` 非法值 → 默认 all
  - 校验失败的条目跳过并记录 Warning
  - 返回 `LoadedHooks(List<HookConfig> valid, List<String> warnings)`
- `LoadedHooks` record

**对应 checklist**：18-21

---

## 任务 9：错误日志隔离

**依赖**：任务 7

**影响文件**：新建 `src/main/java/com/mewcode/hook/HookErrorLogger.java`

**内容**：
- `void log(Path logFile, String hookId, String error)`：
  - 追加写入 `.mewcode/hooks-errors.log`
  - 格式：`[2026-06-27T14:30:00] [hook-id] error message`
  - 带时间戳
  - 写入失败（如磁盘满）fallback 到 `System.err.println`
- HookEngine 在 catch 块中调用此方法

**对应 checklist**：22-23

---

## 任务 10：AgentLoop 生命周期集成

**依赖**：任务 7, 8

**影响文件**：`src/main/java/com/mewcode/agent/AgentLoop.java`

**参考资料**：AgentLoop.run() 第 382-651 行

**埋点位置**：
1. `session_start`：在 `offerEvent(LOOP_STARTED)` 之后（约第 386 行后）
2. `turn_start`：在每轮迭代 `offerEvent(PROGRESS)` 之后（约第 399 行后）
3. `turn_end`：在每轮迭代末尾（约第 641 行，if 块结束前）
4. `session_end`：在 `run()` 方法正常 return 之前（约第 512 行 return、第 524 行 return、第 535 行 return）
5. `pre_send`：在 LLMProvider 调用 API 之前（需在 AgentLoop 调用 provider 处添加，约第 433 行）
6. `post_receive`：在 LLMProvider 收到响应之后（约第 478 行 collector 读取完成后）
7. `post_tool_use`：在每个工具执行完成后（约第 625 行 offerEvent 之后）
8. `shutdown`：不在此处，见任务 12

**内容**：
- `HookEngine` 作为 AgentLoop 的成员字段，构造函数注入
- 在每个埋点位置调用 `hookEngine.runHooks(eventName, ctx)`
- `pre_send` / `post_receive` 的 HookContext 构建需要 toolName（传 null）、message（API 请求/响应摘要）
- `turn_end` 的 HookContext 不涉及具体工具，toolName 传 null
- Hook 执行结果目前不改变主流程（pre_tool_use 除外，见任务 11）

**对应 checklist**：24-25

---

## 任务 11：pre_tool_use 拦截集成

**依赖**：任务 7

**影响文件**：`src/main/java/com/mewcode/agent/AgentLoop.java`（工具执行部分，约第 589 行前）、`src/main/java/com/mewcode/tool/ToolExecutionStrategy.java`

**内容**：
- 在 `executionStrategy.execute()` 调用之前插入 pre_tool_use hook 检查：
```java
for (ToolCall tc : executableCalls) {
    PreToolResult pre = hookEngine.runPreToolHooks(tc.getName(), tc.getInput());
    if (pre.rejected()) {
        preResolved.put(i, new ToolResult(false, pre.message(), "HOOK_REJECTED"));
        continue; // 跳过权限检查，直接拒绝
    }
    // 继续走权限检查...
}
```
- `HOOK_REJECTED` 作为 isError 标记，让模型看到错误并调整
- 注意：`background: true` 的 pre_tool_use hook 在加载校验时已拒绝，此处不需要额外判断

**对应 checklist**：26-27

---

## 任务 12：主流程接入与端到端验证

**依赖**：任务 10, 11

**影响文件**：`src/main/java/com/mewcode/MewCode.java`、AgentLoop 构造处

**内容**：
- 在 MewCode 启动时调用 `HookLoader.load()`，传入 `.mewcode/hooks/` 路径
- 打印 warnings 到终端
- 构造 `HookEngine` 并注入到 `AgentLoop` 构造函数
- `shutdown` 事件：在 MewCode 的 shutdown 流程或 `Runtime.getRuntime().addShutdownHook()` 中触发
- 端到端验证：
  1. 创建一个测试 hook YAML（post_tool_use 写日志）
  2. 启动 MewCode 跑一轮对话
  3. 确认 hook 被触发，日志文件产生
  4. 创建 pre_tool_use reject hook，确认危险命令被拦截

**对应 checklist**：28-30
