# 12-hook-system-spec.md

## 背景
Agent 运行过程中有很多固定时刻需要做固定的事——保存文件后自动格式化、敏感命令执行前拦截、轮次开始时注入上下文提醒。这些重复性工作目前需要用户手动操作或完全不做，MewCode 应该在合适的时刻自动执行。

## 目标用户
- 希望自动格式化代码、运行 lint 的开发者
- 需要细粒度安全策略（基于工具参数拦截）的项目维护者
- 想在关键节点注入自定义提示词的调试者

## 能力清单

### 事件模型
1. 支持 9 个生命周期事件：`session_start`、`session_end`、`turn_start`、`turn_end`、`pre_send`、`post_receive`、`pre_tool_use`、`post_tool_use`、`shutdown`
2. 每条 Hook 规则由「事件 + 条件 + 动作」三要素组成，条件可省略表示无条件触发
3. `pre_tool_use` 事件支持拦截：Hook 返回拒绝后，工具不执行，拒绝原因作为工具结果返回给模型

### 条件表达式
4. 复用参考代码的变量体系：`tool`、`event`、`file_path`、`message`、`args.<key>`
5. 支持四种匹配运算：`==`（精确）、`!=`（反向）、`=~`（正则）、`glob`（glob 模式）
6. 多条条件通过 `mode: all`（全部满足）或 `mode: any`（任一满足）组合，不混用嵌套

### 动作类型
7. `command`：执行 shell 命令，支持超时（秒）和后台异步
8. `prompt`：注入提示词文本，支持 `${var}` 模板变量替换
9. `http`：发 HTTP 请求，字段为 url + method + headers + body，body 支持 `${var}` 模板变量替换
10. `sub_agent`：启动子 Agent（先占位，不实现真实运行）

### 执行控制
11. 命令动作支持 `timeout`（超时秒数，默认 30 秒）和 `background`（后台异步执行）
12. 拦截类 Hook（`reject: true` 的 `pre_tool_use`）不允许 `background: true`
13. 支持 `runOnce` 标记（本次先不做持久化，进程内去重即可）

### 加载与校验
14. Hook 规则从 `.mewcode/hooks/` 目录加载，一个 YAML 文件一条规则
15. 启动时集中校验所有 YAML：格式错误、字段缺失、事件名非法等统一报 Warning，跳过非法条目
16. Hook 自身执行失败只写 `.mewcode/hooks-errors.log`，绝不抛异常中断 Agent 主流程

### 执行顺序
17. `pre_tool_use` 事件中 Hook 拦截先于权限检查：Hook reject 直接拒绝，权限检查不再触发

## 非功能要求
- Hook 执行不能显著增加单轮延迟：命令类 Hook 非 background 模式下有 timeout 兜底
- 模板变量替换后不应对变量未定义的情况崩溃，未定义变量替换为空串
- YAML 解析失败不影响 MewCode 正常启动，仅输出 Warning

## 设计骨架

```
.mewcode/hooks/
  auto-format.yaml
  block-dangerous.yaml
  inject-reminder.yaml

HookConfig (YAML 反序列化)
  ├── id, event, reject, runOnce
  ├── ConditionGroup { mode: all|any, conditions: [Condition] }
  │     └── Condition { variable, operator(==|!=|=~|glob), value }
  └── Action { type: command|prompt|http|sub_agent, ...各类型字段 }

HookEngine
  ├── loadHooks(Path hooksDir) → List<HookConfig> + List<String> warnings
  ├── runHooks(EventName event, HookContext ctx) → List<HookResult>
  └── runPreToolHooks(toolName, args) → PreToolResult

HookContext { event, toolName, toolArgs, filePath, message, error }
PreToolResult { rejected, message }

ActionExecutor (interface)
  ├── CommandExecutor   (ProcessBuilder + timeout + background 线程)
  ├── PromptExecutor   (模板替换 → 返回文本)
  ├── HttpExecutor     (HttpClient + 模板替换)
  └── SubAgentExecutor (占位，抛 UnsupportedOperationException)
```

### 生命周期埋点位置

| 事件 | 埋点位置 |
|------|---------|
| `session_start` | AgentLoop.run() 开头，在 LOOP_STARTED 事件之后 |
| `session_end` | AgentLoop.run() 正常 return 之前 |
| `turn_start` | AgentLoop 每轮迭代开头，在 PROGRESS 事件之后 |
| `turn_end` | AgentLoop 每轮迭代末尾（API 返回后、工具执行前） |
| `pre_send` | LLMProvider 发送 API 请求之前 |
| `post_receive` | LLMProvider 收到 API 响应之后 |
| `pre_tool_use` | ToolExecutionStrategy.execute() 执行之前，在权限检查之前 |
| `post_tool_use` | ToolExecutionStrategy.execute() 每个工具执行完成之后 |
| `shutdown` | JVM shutdown hook 或 MewCode 正常退出时 |

## Out of Scope（本期不做）
- `sub_agent` 动作类型的真实运行（等 SubAgent 章节对接后实现）
- `runOnce` 标记的跨会话持久化（本期仅进程内内存去重）
- Hook 执行顺序的显式优先级（按文件名字母序执行）
- 条件表达式的嵌套逻辑组合（all/any 二选一，不混用）
- Hook 的热加载（修改 YAML 后需要重启）
