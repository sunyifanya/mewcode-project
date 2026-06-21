# tasks.md — Slash Command System（斜杠命令注册与分发）

## 任务总览

共 10 个任务，按依赖关系排序。推荐执行顺序：A 核心数据结构 → B 注册中心 → C 内置命令注册 → D 界面集成 → E 端到端验证。

---

## Task 1: 创建 Command 与 CommandType 数据类

**影响文件**：新建 `com.mewcode.command.Command`、`com.mewcode.command.CommandType`

**依赖任务**：无

**参考资料**：
- 参考项目 `Command` record（`D:\mewcode-java\java\src\main\java\com\mewcode\command\Command.java:18-24`）
- 参考项目 `CommandType` 枚举（同上 `:27-34`）
- spec 能力清单第 1 条（命令元数据字段）、第 7 条（三种执行模式）

**工作内容**：
- 定义 `CommandType` 枚举：`LOCAL`（纯本地，返回文本）、`LOCAL_UI`（影响界面状态）、`PROMPT`（预设提示词送 LLM）
- 定义 `Command` 记录类，字段包含：`name`（String，不含前缀的小写名称）、`description`（String，一行描述）、`aliases`（String[]，别名列表）、`usage`（String，用法示例）、`type`（CommandType）、`paramHint`（String，可选参数提示）、`hidden`（boolean，是否隐藏）
- 实现 `matches(String input)` 方法：精确匹配 name 或任意 alias（大小写不敏感）
- 注意：`Command` 为纯元数据记录，不包含处理函数（处理函数在 CommandRegistry 中独立管理）

---

## Task 2: 创建 CommandContext 运行时上下文

**影响文件**：新建 `com.mewcode.command.CommandContext`

**依赖任务**：无

**参考资料**：
- 参考项目 `CommandContext` record（`D:\mewcode-java\java\src\main\java\com\mewcode\command\CommandContext.java:17-28`）
- spec 能力清单第 11 条（函数式接口注入）

**工作内容**：
- 定义 `CommandContext` 记录类，字段全部使用函数式接口以避免绑定具体渲染框架：
  - `args`（String）—— 命令参数（命令名之后的部分）
  - `workDir`（String）—— 当前工作目录绝对路径
  - `model`（String）—— 当前使用的模型名称
  - `permissionMode`（`Supplier<String>`）—— 当前权限模式名称
  - `planMode`（`Supplier<Boolean>`）—— 是否处于计划模式
  - `toolCount`（`IntSupplier`）—— 已注册工具数量
  - `tokenCount`（`Supplier<int[]>`）—— 返回 `[inputTokens, outputTokens]`
  - `memoryList`（`Supplier<List<String>>`）—— 记忆标题列表
  - `memoryClear`（`Runnable`）—— 清除全部记忆的回调
  - `sessionInfo`（`Supplier<String>`）—— 当前会话信息文本
- 所有字段均为 record component，由调用方在构造时注入具体实现

---

## Task 3: 创建 CommandRegistry 注册中心核心

**影响文件**：新建 `com.mewcode.command.CommandRegistry`

**依赖任务**：Task 1（Command）、Task 2（CommandContext）

**参考资料**：
- 参考项目 `CommandRegistry`（`D:\mewcode-java\java\src\main\java\com\mewcode\command\CommandRegistry.java:18-112`）
- spec 能力清单第 2 条（别名冲突检测）、第 3 条（命令查询）、第 4 条（隐藏控制）

**工作内容**：
- 维护内部数据结构：`List<Command>`（所有命令）和 `Map<String, Function<CommandContext, String>>`（命令名/别名 → 处理函数）
- 实现 `register(Command cmd, Function<CommandContext, String> handler)`：
  - 冲突检测：将 `cmd.name()` 和 `cmd.aliases()` 全部转小写，与已有命令的所有名称及别名（均转小写后）比较；发现重复则输出冲突详情（哪个名字与哪个命令冲突）并调用 `System.exit(1)`
  - 通过检测后：`cmd` 加入 `commands` 列表；`cmd.name()` 以及每个别名分别映射到 `handler`（如 handler 非 null）
  - 冲突检测时机：每次 register 调用时执行，不等所有注册完成后
- 实现 `search(String prefix)`：对所有非隐藏命令，按 name 和 aliases 做前缀匹配（`startsWith`，大小写不敏感），结果按 name 排序后返回
- 实现 `find(String name)`：精确匹配（大小写不敏感）name 或 alias，返回 `Optional<Command>`
- 实现 `execute(String name, CommandContext ctx)`：按 name 查 handler（name 可以是主名或别名），找到则 `handler.apply(ctx)` 返回文本；找不到 handler 返回 `"No handler for /" + name`
- 实现 `listVisible()`：返回所有非隐藏命令，按 name 排序
- 实现 `listAll()`：返回所有命令的不可变视图
- 构造函数不自动注册命令——内置命令注册统一在 Task 4 中通过 `registerDefaults()` 或显式调用完成

---

## Task 4: 注册内置 LOCAL 命令

**影响文件**：修改 `com.mewcode.command.CommandRegistry`（续 Task 3）

**依赖任务**：Task 3（registry 核心可用）

**参考资料**：
- 参考项目 `registerDefaults()` 中 LOCAL 命令注册（`D:\mewcode-java\java\src\main\java\com\mewcode\command\CommandRegistry.java:118-294`）
- spec 能力清单第 13-18 条

**工作内容**：
- 在 `CommandRegistry` 中新增 `registerDefaults()` 私有方法，注册以下 LOCAL 命令及其处理函数：

  **`/help`**（别名 `h`, `?`）：
  - 无参数时遍历 `listVisible()` 拼接可用命令列表（含名称、别名、描述）
  - 有参数时 `find(args)` 查找目标命令，显示详细用法（名称、描述、别名、用法示例）
  
  **`/status`**（别名 `s`）：
  - 读取 `ctx` 各字段，拼接状态文本（模式、模型、工作目录、Token 用量、工具数量、记忆条数）
  
  **`/compact`**（别名 `c`）：
  - 调用外部注入的 compact 回调（通过构造函数或 setter 传入），返回压缩结果文本
  - 注：compact 需要访问 AgentLoop，不能在 CommandRegistry 内部持有，需通过函数式接口注入
  
  **`/session`**（无别名）：
  - 解析子命令（`args` 按空格拆分第一个词），支持 `list` 和 `info`，无子命令默认 `list`
  - `list` 和 `info` 均返回 `ctx.sessionInfo().get()` 内容
  
  **`/memory`**（无别名）：
  - 解析子命令，支持 `list` 和 `clear`，无子命令默认 `list`
  - `list`：遍历 `ctx.memoryList().get()` 拼接记忆列表
  - `clear`：调用 `ctx.memoryClear().run()`
  
  **`/permission`**（别名 `perm`）：
  - 解析子命令，支持 `info` 和 `mode <name>`
  - `info`：返回当前权限模式 `ctx.permissionMode().get()`
  - `mode <name>`：返回权限模式说明文本（实际切换由 LOCAL_UI 处理，这里只返回信息）

---

## Task 5: 注册内置 LOCAL_UI 命令

**影响文件**：修改 `com.mewcode.command.CommandRegistry`（续 Task 4）

**依赖任务**：Task 4（registry 已有 register 能力）

**参考资料**：
- 参考项目 `registerDefaults()` 中 LOCAL_UI 命令注册（`D:\mewcode-java\java\src\main\java\com\mewcode\command\CommandRegistry.java:153-266`）
- spec 能力清单第 19-24 条

**工作内容**：
- 在 `registerDefaults()` 中注册以下 LOCAL_UI 命令（handler 传 `null`，执行在 TerminalUI 的 LOCAL_UI switch 中处理）：

  - `/clear`（无别名）—— 清空对话
  - `/plan`（别名 `p`）—— 进入计划模式
  - `/do`（无别名）—— 退出计划模式
  - `/mode`（无别名）—— 循环权限模式
  - `/exit`（别名 `q`）—— 退出应用
  - `/resume`（别名 `r`）—— 打开会话恢复界面

- 每条命令登记完整的元数据（name、description、aliases、usage 示例、type=LOCAL_UI、paramHint、hidden=false）
- 用法示例格式如 `"/plan — Enter plan mode"`、`"/mode — Cycle: DEFAULT→ACCEPT_EDITS→BYPASS→DEFAULT"`

---

## Task 6: 注册内置 PROMPT 命令

**影响文件**：修改 `com.mewcode.command.CommandRegistry`（续 Task 5）

**依赖任务**：Task 5

**参考资料**：
- 参考项目 `/review` 命令注册（`D:\mewcode-java\java\src\main\java\com\mewcode\command\CommandRegistry.java:281-294`）
- spec 能力清单第 25 条

**工作内容**：
- 在 `registerDefaults()` 中注册：
  - `/review`（无别名，type=PROMPT，hidden=false）
    - 处理函数：生成预设提示词 "Please review the current git diff for code changes..."，如果 `ctx.args()` 非空则追加 "Additional focus: ..."
    - 返回完整的提示词文本，由 TerminalUI 的 PROMPT 分支注入对话

---

## Task 7: 重构 TerminalUI——命令分发与补全

**影响文件**：修改 `tui/TerminalUI.java`

**依赖任务**：Task 6（所有内置命令注册完成）

**参考资料**：
- 当前 TerminalUI 硬编码命令（`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tui\TerminalUI.java:150-230`）
- 参考项目 `executeSlashCommand()`（`D:\mewcode-java\java\src\main\java\com\mewcode\tui\MewCodeModel.java:944-1062`）
- 参考项目 `updateSlashMenu()`（同上 `:903-912`）
- 参考项目 `handleChatKey()` 中 slash menu 处理（同上 `:715-754`）
- spec 能力清单第 5-12 条

**工作内容**：
- **构造函数变更**：新增 `setCommandRegistry(CommandRegistry registry)` 方法（或通过构造函数注入）
- **新增 `setCompactCallback(Supplier<String> callback)`**：供 CommandRegistry 在执行 `/compact` 时调用
- **命令分流器**：在 `start()` 方法的输入处理中，将现有的 `if (input.startsWith("/")) { switch(...) }` 替换为：
  1. 解析输入：提取 `/` 后的 commandName（第一个空格前，转小写）和 args（第一个空格后）
  2. `cmdRegistry.find(commandName)` 查找命令
  3. 命中 → 按 `CommandType` 分发：
     - `LOCAL`：`buildCommandContext(args)` → `cmdRegistry.execute(name, ctx)` → 显示返回文本
     - `LOCAL_UI`：进入内部 switch（保留现有 `/clear`、`/plan`、`/do`、`/mode`、`/exit`、`/compact`、`/resume` 的处理逻辑）
     - `PROMPT`：`buildCommandContext(args)` → `cmdRegistry.execute(name, ctx)` → 将返回文本作为用户消息注入对话（如有 InputHandler 回调，调用之）
  4. 未命中 → 显示 "未知命令: /{name} — 输入 /help 查看可用命令"
- **状态栏简化**：将四种权限模式标记（`DEFAULT`/`[接受编辑]`/`[Plan]`/`[YOLO]`）简化为两种——`planMode == true` 时显示 `[PLAN]`，`planMode == false` 时显示 `[DEFAULT]`。修改 `start()` 中 `dynamicPrompt` 的构建逻辑
- **Tab 补全实现**（配合 JLine 的 `Completer` 机制）：
  - 实现一个 `SlashCommandCompleter`（实现 `org.jline.reader.Completer`），当输入缓冲区以 `/` 开头时：提取 `/` 到光标位置的文本作为前缀 → `cmdRegistry.search(prefix)` → 匹配结果 1 条直接补全命令名 + 空格；多条时返回所有候选项让 JLine 弹出菜单
  - 将 completer 注册到 `LineReader`：`lineReader.setCompleter(slashCommandCompleter)`
  - 注意：隐藏命令不参与补全
- **`buildCommandContext(args)` 方法**：从当前 TerminalUI 持有的引用（agentLoop、permissionChecker、memoryManager 等）构造 `CommandContext` 实例

---

## Task 8: 接入 MewCode 主流程

**影响文件**：修改 `MewCode.java`

**依赖任务**：Task 7（TerminalUI 已支持 CommandRegistry 注入）

**参考资料**：
- 当前 `MewCode.main()` 中 UI 初始化部分（`D:\code\mewcode\mewcode\src\main\java\com\mewcode\MewCode.java:78-106`）
- 参考项目 `MewCodeModel` 构造函数中 `this.cmdRegistry = new CommandRegistry()`（`D:\mewcode-java\java\src\main\java\com\mewcode\tui\MewCodeModel.java:215`）

**工作内容**：
- 在 `MewCode.main()` 中，实例化 `CommandRegistry`（在创建 `TerminalUI` 之前）
- 调用 `ui.setCommandRegistry(registry)` 注入
- 如有需要，将 compact 回调绑定到 CommandRegistry（让 `/compact` 能访问 `AgentLoop.forceCompact()`）
- 无需修改 Provider、ConversationManager 等已有组件的初始化逻辑

---

## Task 9: /resume 从 LOCAL_UI 重构为子命令 + 注册中心调度

**影响文件**：修改 `tui/TerminalUI.java`（续 Task 7）

**依赖任务**：Task 7（TerminalUI 重构完成）

**参考资料**：
- 当前 `TerminalUI.handleResume()`（`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tui\TerminalUI.java:255-352`）
- spec 能力清单第 16 条（`/session` 支持 `list` 子命令）

**工作内容**：
- 将现有 `handleResume()` 的实现保留在 TerminalUI 中，但调度改为由 LOCAL_UI 分支触发
- `/session list`（LOCAL 命令）返回会话列表的文本摘要
- `/resume`（LOCAL_UI 命令）打开交互式选择界面（保留现有逻辑：上下键选择、Enter 确认、Esc 取消）
- 两者共享 `SessionManager.listSessions()` 的调用

---

## Task 10: 端到端验证

**影响文件**：无（手动验证 + 自动化检查）

**依赖任务**：Task 1-9 全部完成

**工作内容**：
1. 启动 MewCode，验证启动阶段无别名冲突 panic
2. 依次输入 `/help`、`/h`、`/?`——三者输出一致
3. 输入 `/status`，验证显示工作目录、模型、Token 用量、工具数量、权限模式
4. 输入 `/STATUS`、`/Status`——验证大小写不敏感，输出一致
5. 输入 `/plan`，验证状态栏变为 `[PLAN]`；输入 `/do`，验证状态栏恢复 `[DEFAULT]`
6. 输入 `/mode` 多次，验证权限模式循环切换（DEFAULT→ACCEPT_EDITS→BYPASS→DEFAULT），状态栏始终显示 `[DEFAULT]`
7. 输入 `/compact`（或 `/c`），验证上下文压缩触发并显示结果
8. 输入 `/session list`，验证列出存档会话
9. 输入 `/memory list`，验证列出自动记忆
10. 输入 `/permission info`，验证显示当前权限模式
11. 输入 `/review`，验证提示词被注入对话并触发 LLM 审查
12. 输入 `/clear`，验证对话清空
13. 输入 `/exit`（或 `/q`），验证程序正常退出
14. 输入一个未知命令（如 `/foo`），验证显示"未知命令"提示并引导 `/help`
15. 输入 `/` 后按 Tab，验证弹出命令补全菜单（不包含隐藏命令）
16. 输入普通文本（非 `/` 开头），验证正常走 LLM 对话流程
