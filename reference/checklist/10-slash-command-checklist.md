# checklist.md — Slash Command System（斜杠命令注册与分发）

## A. 命令注册中心

- [ ] **A1** 启动 MewCode 后 `grep -c "CommandRegistry"` 日志中无 "conflict" 或 "duplicate" 字样（别名无冲突）
- [ ] **A2** 在代码中手动注册两条 name 相同的命令，启动时应立即 `System.exit(1)` 并在 stderr 中输出冲突详情（含冲突名称和涉及的两条命令名）
- [ ] **A3** 在代码中手动注册一条命令的 alias 与另一条命令的 name 相同，启动时同样 `System.exit(1)`（别名与主名冲突被检测）
- [ ] **A4** `grep -r "registerDefaults" src/` 返回的注册调用数量 = 13（13 条内置命令全部注册）
- [ ] **A5** 在代码中将 `/help` 的 `hidden` 设为 `true`，输入 `/help` 仍能执行（精确匹配不受隐藏影响），但输入 `/` + Tab 补全列表中不出现 `help`

## B. 命令解析

- [ ] **B1** 输入 `/help` 显示可用命令列表（含名称、别名、描述），输出行数 ≥13（13 条可见命令）
- [ ] **B2** 输入 `/HELP`（全大写）和 `/Help`（混合大小写），输出与 `/help` 完全一致
- [ ] **B3** 输入 `/h`（别名）和 `/?`（别名），输出与 `/help` 完全一致
- [ ] **B4** 输入 `/help status` 显示 `/status` 命令的详细用法（含名称、描述、别名、用法示例），而非全部命令列表
- [ ] **B5** 输入 `/help nonexistent` 显示 "Unknown command: nonexistent"（或等效未命中提示）
- [ ] **B6** 输入单独的 `/` 后直接回车（空命令），不执行任何操作、不报错、不退出
- [ ] **B7** 输入 `/   `（斜杠后只有空格）后回车，视为空命令，不执行操作
- [ ] **B8** 输入 `/unknowncommand`，显示含 "未知命令" 和 "/help" 字样的提示
- [ ] **B9** 输入 `/status extra args here`，命令正常执行（`extra args here` 作为参数忽略或显示，命令不报错）

## C. 三种执行模式

### LOCAL

- [ ] **C1** 输入 `/status`（或 `/s`），终端输出含：工作目录绝对路径、模型名、权限模式、Token 用量（`input` 和 `output` 字段）、工具数量
- [ ] **C2** 输入 `/status` 后，对话历史中不新增用户消息（`grep -c "/status"` 在会话 JSONL 中为 0）
- [ ] **C3** 输入 `/status` 不消耗 LLM Token（Token 计数在命令前后不变）
- [ ] **C4** 输入 `/compact`（或 `/c`），终端显示压缩结果文本，含已压缩的消息数量或 Token 节省量
- [ ] **C5** 输入 `/session list`，输出会话列表（如无存档会话则显示"无会话"类提示），格式含会话 ID 和消息数
- [ ] **C6** 输入 `/memory list`，输出记忆列表（如无记忆则显示"暂无记忆"类提示）
- [ ] **C7** 输入 `/memory clear`，所有记忆被清除，再次 `/memory list` 返回空列表
- [ ] **C8** 输入 `/permission info`，输出当前权限模式名称（`default` / `acceptEdits` / `plan` / `bypassPermissions` 之一）
- [ ] **C9** 输入 `/permission mode acceptEdits`，权限模式切换到 ACCEPT_EDITS，再次 `/permission info` 显示 `acceptEdits`

### LOCAL_UI

- [ ] **C10** 输入 `/clear`，界面消息列表清空，对话历史重置（`conversation.getMessages().isEmpty() == true`）
- [ ] **C11** 输入 `/plan`（或 `/p`），状态栏标记变为 `[PLAN]`，Agent 只能使用只读工具（尝试调用 Write 工具应被拒绝）
- [ ] **C12** 输入 `/do`，状态栏标记恢复 `[DEFAULT]`，Agent 恢复全部工具权限
- [ ] **C13** 在 DEFAULT 模式下输入 `/mode` 一次，权限模式切换为 ACCEPT_EDITS；输入 `/mode` 第二次，切换为 BYPASS；输入 `/mode` 第三次，回到 DEFAULT。三次切换后状态栏始终显示 `[DEFAULT]`（不被 /mode 改变）
- [ ] **C14** 在 PLAN 模式下输入 `/mode`，应退出 PLAN 并切换到 DEFAULT（PLAN 不参与 /mode 循环）
- [ ] **C15** 输入 `/exit`（或 `/q`），程序正常退出（exit code = 0），不弹异常栈
- [ ] **C16** 输入 `/resume`（或 `/r`），显示会话选择界面（列表格式，含编号/ID/摘要），选择有效编号后成功恢复历史对话

### PROMPT

- [ ] **C17** 输入 `/review`，对话中新增一条用户消息（内容含 "review the current git diff" 等关键词），随后 LLM 开始审查代码变更
- [ ] **C18** 输入 `/review focus on security issues`，新增的用户消息中同时包含 "review the current git diff" 和 "focus on security issues"
- [ ] **C19** `/review` 触发的对话消耗 LLM Token（Token 计数在命令后增加）

## D. Tab 补全

- [ ] **D1** 输入 `/` 后按 Tab，弹出命令补全菜单，列表包含所有非隐藏命令，按名称排序
- [ ] **D2** 补全菜单中不出现标记为 `hidden=true` 的命令
- [ ] **D3** 输入 `/s` 后按 Tab——由于 `/status` 和 `/session` 均以 s 开头，弹出多选菜单（至少含 `status` 和 `session`）
- [ ] **D4** 输入 `/sta` 后按 Tab——仅 `/status` 匹配，直接补全为 `/status `（自动追加尾随空格）
- [ ] **D5** 输入 `/Q`（大写）后按 Tab——如果 `/q` 是 `/exit` 的唯一匹配别名，直接补全为 `/exit `（大小写不敏感补全）
- [ ] **D6** 补全菜单打开时按 Esc 键，菜单关闭，输入缓冲区恢复原样

## E. 状态栏

- [ ] **E1** 启动时状态栏显示 `[DEFAULT]`（默认执行模式）
- [ ] **E2** 输入 `/plan` 后状态栏立即切换为 `[PLAN]`
- [ ] **E3** 输入 `/do` 后状态栏恢复 `[DEFAULT]`
- [ ] **E4** 输入 `/mode` 循环权限模式时，状态栏始终显示 `[DEFAULT]`（不被 /mode 改变）
- [ ] **E5** 状态栏中不再出现 `[接受编辑]` 或 `[YOLO]` 标记（已简化为两种标记）

## F. 分流器

- [ ] **F1** 输入 `/help`，响应时间 <100ms（不经过 LLM，纯本地返回）
- [ ] **F2** 输入普通文本 "hello"，正常进入 LLM 对话流程（不触发命令分发）
- [ ] **F3** 输入以 `/` 开头但不是命令的文本（如 `/notacommand`），显示错误提示，不将文本发送给 LLM
- [ ] **F4** 输入空行，不触发任何处理，直接显示新提示符

## G. 端到端验收

- [ ] **G1** 完整流程：启动 MewCode → 验证 `[DEFAULT]` 状态栏 → `/plan` → 验证 `[PLAN]` 状态栏 → `/do` → 验证 `[DEFAULT]` 状态栏 → `/status` → 验证状态输出 → `/mode` 三次 → 验证权限循环 → `/help` → 验证命令列表 → `/compact` → 验证压缩 → `/clear` → 验证清空 → `/exit` → 验证退出
- [ ] **G2** 别名流程：`/h` = `/help`、`/?` = `/help`、`/s` = `/status`、`/c` = `/compact`、`/p` = `/plan`、`/perm` = `/permission`、`/q` = `/exit`、`/r` = `/resume`——共 8 个别名全部可正常执行
- [ ] **G3** 大小写流程：`/HELP`、`/Help`、`/help` 三者行为完全一致
