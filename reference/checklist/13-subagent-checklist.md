# 13-subagent-checklist.md

## 数据模型

- [ ] 1. `grep -r "record SubAgentSpec" src/main/java/com/mewcode/subagent/` 返回 1 条匹配，包含 name / description / tools / disallowedTools / systemPromptOverride / maxTurns / model / permissionMode / background 字段
- [ ] 2. `grep -r "GENERAL_PURPOSE\|PLAN\|EXPLORE\|FORK" src/main/java/com/mewcode/subagent/SubAgentSpec.java` 返回 ≥ 4 条匹配（4 个静态常量）
- [ ] 3. `grep -r "FORK" src/main/java/com/mewcode/subagent/SubAgentSpec.java` 匹配行的 maxTurns 值为 50，permissionMode 为 "default"，model 为 "inherit"
- [ ] 4. `grep -r "class TaskManager" src/main/java/com/mewcode/task/` 返回 1 条匹配
- [ ] 5. `grep -r "class BackgroundTask" src/main/java/com/mewcode/task/` 返回 1 条匹配，含 status 枚举（PENDING / RUNNING / COMPLETED / FAILED / CANCELLED）

## 内置 Agent 定义文件

- [ ] 6. `jar tf build/libs/mewcode-*.jar | grep "subagent/builtin/"` 返回 ≥ 3 条：general-purpose.md、explore.md、plan.md
- [ ] 7. `grep -r "name: general-purpose" src/main/resources/subagent/builtin/general-purpose.md` 返回 1 条
- [ ] 8. `grep -r "disallowedTools:" src/main/resources/subagent/builtin/explore.md` 返回行含 `WriteFile` 和 `EditFile`（或 `[WriteFile, EditFile]`）
- [ ] 9. `grep -r "model: haiku" src/main/resources/subagent/builtin/explore.md` 返回 1 条

## AgentLoader

- [ ] 10. `AgentLoader.loadAll(projectRoot).listNames()` 返回 ≥ 3 个角色名，包含 "general-purpose"、"explore"、"plan"
- [ ] 11. 在 `~/.mewcode/agents/explore.md` 放置自定义 explore（maxTurns=5），`catalog.resolve("explore").maxTurns()` 返回 5
- [ ] 12. 在 `<project>/.mewcode/agents/explore.md` 再放置一个（maxTurns=10），`catalog.resolve("explore").maxTurns()` 返回 10（项目级覆盖用户级）
- [ ] 13. 在 `~/.mewcode/agents/bad.md` 放置 name 缺失的定义文件 → 启动 stderr 含警告信息 → MewCode 正常启动
- [ ] 14. 在 `~/.mewcode/agents/bad-model.md` 放置 `model: gpt-5` → 启动 stderr 警告含 "invalid model" → 启动不阻断 → `catalog.resolve("bad-model").model()` fallback 到 "inherit"

## ToolFilter

- [ ] 15. 定义式 explore Agent 调用 `ToolFilter.buildFilter(spec, false, false).test("WriteFile")` 返回 false
- [ ] 16. 定义式 explore Agent 调用 `ToolFilter.buildFilter(spec, false, false).test("Agent")` 返回 false
- [ ] 17. Fork 式 Agent 调用 `ToolFilter.buildFilter(FORK, false, true).test("Agent")` 返回 true（Fork 保留 Agent）
- [ ] 18. 后台定义式 Agent 调用 `ToolFilter.buildFilter(spec, true, false).test("WriteFile")` 返回 true（WriteFile 在白名单中）
- [ ] 19. 后台定义式 Agent 调用 `ToolFilter.buildFilter(spec, true, false).test("TaskStop")` 返回 false（不在白名单中）
- [ ] 20. Fork 式 Agent 调用 `ToolFilter.buildFilter(FORK, true, true).test("WriteFile")` 返回 true（Fork 不受后台白名单限制）
- [ ] 21. MCP 工具（如 `mcp__example_tool`）在所有过滤模式下 `test()` 返回 true

## Agent 工具 Schema

- [ ] 22. `Agent` 工具注册到 ToolRegistry 后，`toolRegistry.get("Agent").schema()` 返回的 JSON 含 `description`（必填）、`prompt`（必填）、`subagent_type`（可选，enum 列表）、`model`（可选）、`run_in_background`（可选）、`name`（可选）
- [ ] 23. `Agent` 工具的 `shouldDefer()` 返回 false（始终可见）
- [ ] 24. `Agent` 工具的 `category()` 返回 `COMMAND`

## AgentTool 执行 — 定义式

- [ ] 25. `execute({prompt: "说 hello", subagent_type: "explore"})` 返回成功，output 包含 "hello"（或子 Agent 的问候文本）
- [ ] 26. `execute({prompt: "...", subagent_type: "non-existent"})` 返回 isError=true，error 包含 "未知 subagent_type" 和可用类型列表
- [ ] 27. `execute({prompt: "...", subagent_type: "explore"})` 产生的子 Agent 无法调用 WriteFile 工具 → 若 prompt 要求写文件，模型返回错误或空结果
- [ ] 28. `execute({prompt: "调用 Agent 工具再做一件事", subagent_type: "explore"})` → 子 Agent 工具列表中无 Agent 工具 → 模型无法发起嵌套 Agent 调用

## AgentTool 执行 — Fork 式

- [ ] 29. `execute({prompt: "总结上文", description: "summarize"})`（不传 subagent_type）→ 返回 task_id，status 含 "async_launched"
- [ ] 30. Fork 子 Agent 的首条 user 消息以 `<fork_boilerplate>` 开头
- [ ] 31. Fork 子 Agent 对话历史的前缀消息与父对话一致（深拷贝验证）
- [ ] 32. Fork 子 Agent 调用 Agent 工具 → tool_result 含 "Fork 子 Agent 不能再启动 Agent"
- [ ] 33. 父对话中含 `<fork_boilerplate>` 标记 + 再调 Agent 工具 → tool_result 为错误（QuerySource 失效兜底）

## 后台任务

- [ ] 34. `execute({prompt: "...", subagent_type: "explore", run_in_background: true})` → tool_result 含 `task_id` 和 `"async_launched"`，执行 < 100ms 返回
- [ ] 35. 后台子 Agent 跑完后，`taskManager.drainNotifications()` 返回 1 条通知，status=COMPLETED
- [ ] 36. 主 Agent 下次 turn 开始时，reminder 区出现 `<task-notification>` 块含 result
- [ ] 37. 前台子 Agent 执行超过 120 秒 → tool_result 含 `status: "timed_out_to_background"` 和 task_id
- [ ] 38. 子 Agent 运行中 throw RuntimeException → `taskManager.getTask(id).status()` 为 FAILED → `<task-notification>` 含错误描述 → 主程序不崩

## 后台任务工具

- [ ] 39. `TaskList` 工具无参调用 → 返回当前非 Terminated 任务列表（含 id / name / status / tool_count 字段）
- [ ] 40. `TaskGet({task_id: "task_1"})` → 返回 task_1 完整状态（含 result 文本）
- [ ] 41. `TaskStop({task_id: "task_1"})` → 返回 `{status: "cancellation_requested"}` → `TaskGet({task_id: "task_1"})` 返回 status=CANCELLED
- [ ] 42. `SendMessage({name: "search-agent", message: "再做一轮"})` → 已完成的 name="search-agent" 任务重新跑动 → 新结果作为 `<task-notification>` 注入
- [ ] 43. `SendMessage({name: "nonexistent", message: "..."})` → 返回错误含 "找不到"

## 权限升级链

- [ ] 44. 子 Agent 角色 `permissionMode: dontAsk` → Bash 工具调用不放行（因为 Bash 默认是 ASK 类，dontAsk 直接通过） → 实际表现：Bash 命令直接执行，无弹窗
- [ ] 45. 子 Agent 角色 `permissionMode: default` → Bash 工具触发审批弹窗 → 弹窗文本含 `[来自 SubAgent X]`
- [ ] 46. 子 Agent 权限弹窗 60 秒无响应 → 自动拒绝（DenyOnce） → 子 Agent 继续运行
- [ ] 47. 子 Agent 权限弹窗时主 Agent 被阻塞（不能再接收新用户输入，直到子 Agent 权限弹窗被响应或超时）

## Fork 对话构建

- [ ] 48. 父对话末尾 assistant 消息含 tool_use 但无 tool_result → `ForkBuilder.buildForkedConversation` 自动插入 placeholder tool_result 消息（内容含 "interrupted by fork"）
- [ ] 49. Fork 对话构建后，`ForkBuilder.buildTaskMessage("test task")` 返回以 `<fork_boilerplate>` 开头、以 "Your task:\ntest task" 结尾的字符串
- [ ] 50. `ForkBuilder.containsForkBoilerplate(conv)` 对含 `<fork_boilerplate>` 消息的对话返回 true，对不含的返回 false

## 自定义 Agent 定义

- [ ] 51. 项目 `.mewcode/agents/custom.md`：
  ```yaml
  ---
  name: custom
  description: 自定义测试 Agent
  disallowedTools: [Glob, Grep]
  maxTurns: 5
  permissionMode: acceptEdits
  ---
  # 自定义系统提示
  你是一个测试 Agent。
  ```
  加载后 `catalog.resolve("custom")` 返回的 spec 中：
  - `disallowedTools` 含 "Glob" 和 "Grep"
  - `maxTurns` = 5
  - `permissionMode` = "acceptEdits"
  - `systemPromptOverride` = "你是一个测试 Agent。"

- [ ] 52. `execute({prompt: "用 Glob 找 *.java", subagent_type: "custom"})` → 子 Agent 看不到 Glob 工具 → 无法执行

## 配置

- [ ] 53. `grep -r "subagent" src/main/java/com/mewcode/config/AppConfig.java` 返回 ≥ 3 条匹配（类定义 + `background.enabled` + `max_turns`）
- [ ] 54. `mewcode.yaml` 中设 `subagent.background.enabled: false` → Fork 调用 Agent 工具返回 isError=true，error 含 "后台禁用"
- [ ] 55. `mewcode.yaml` 中设 `subagent.max_turns: 10` → 未在 frontmatter 设 maxTurns 的定义式子 Agent 默认 maxTurns=10
- [ ] 56. `mewcode.yaml` 中不设 `subagent` 节点 → 默认 `background.enabled=true`，`max_turns=25`

## Skill fork 兼容

- [ ] 57. 激活任意 skill → skill 的 fork 模式行为与改造前一致（能在子对话中搜索/读文件并返回结果）
- [ ] 58. `grep -r "SubAgentSpec\|ToolFilter\|ForkBuilder" src/main/java/com/mewcode/agent/AgentLoop.java` 在 `runSubAgent` 方法内返回 ≥ 1 条匹配（证明走 SubAgent 底座）

## 崩溃安全

- [ ] 59. 在子 Agent 执行中向 task 线程发送 `thread.interrupt()` → task status 变 FAILED → 主程序继续正常运行
- [ ] 60. 子 Agent 执行中 OOM → `TaskManager.launch` 的 try/catch 捕获 → task status 变 FAILED → 主程序不崩

## 端到端验收（手动）

- [ ] 61. 启动 MewCode → 输入 "用 explore 子 Agent 帮我在 src/ 中找所有 TODO 注释" → 等子 Agent 返回 → 看到搜索结果列表 → 继续正常对话
- [ ] 62. 输入 "帮我开一个后台子 Agent，搜索 .java 文件里有多少个 class 定义"（指定 run_in_background）→ 立刻收到 task_id → 输入 "hello" → 子 Agent 结果作为 notification 出现
- [ ] 63. 自定义角色文件 → 重启 MewCode → `subagent_type=custom` 出现在 Agent 工具的 enum 列表中 → 调用成功
- [ ] 64. 输入 "Fork 当前对话，总结我们到目前为止讨论了什么" → Fork 子 Agent 启动 → 返回总结（含对话历史上下文）
- [ ] 65. 关闭 `subagent.background.enabled` → Fork 路径报错 → 定义式前台仍正常工作
