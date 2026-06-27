# 12-hook-system-checklist.md

## 数据模型

- [ ] 1. `grep -r "EventName" src/main/java/com/mewcode/hook/` 返回 9 个枚举值：`SESSION_START`、`SESSION_END`、`TURN_START`、`TURN_END`、`PRE_SEND`、`POST_RECEIVE`、`PRE_TOOL_USE`、`POST_TOOL_USE`、`SHUTDOWN`
- [ ] 2. `grep -r "ActionType" src/main/java/com/mewcode/hook/` 返回 4 个枚举值：`COMMAND`、`PROMPT`、`HTTP`、`SUB_AGENT`
- [ ] 3. `grep -r "timeout" src/main/java/com/mewcode/hook/HookAction.java` 或 HookConfig 相关文件，确认 timeout 默认值 = 30
- [ ] 4. `grep -r "background" src/main/java/com/mewcode/hook/` 确认 HookAction 有 background 字段且默认 `false`

## 条件求值

- [ ] 5. 创建 YAML 测试条件 `tool == Bash` → `pre_tool_use` 事件，工具名为 Bash 时触发，工具名为 WriteFile 时不触发
- [ ] 6. 创建 YAML 测试条件 `tool =~ Bash|WriteFile` → 工具名为 Bash 和 WriteFile 都触发，ReadFile 不触发
- [ ] 7. 创建 YAML 测试条件 `mode: all` 两条 `tool==Bash` + `args.command=~git.*` → Bash 且命令以 git 开头触发，Bash 但命令是 ls 不触发

## 模板替换

- [ ] 8. prompt 中包含 `${tool}` `${args.file}`，调用工具 WriteFile(/tmp/test.txt) 时渲染结果包含 `WriteFile` 和 `/tmp/test.txt`；`${unknown_var}` 在渲染后变为空串不崩溃

## Command 执行器

- [ ] 9. post_tool_use hook 中执行 `echo "hello"` → HookResult.output 包含 `hello`，success=true
- [ ] 10. post_tool_use hook 中执行 `sleep 60`，timeout=2 → 2 秒后返回 success=false，output 包含 `timed out`
- [ ] 11. background=true 的 command hook 执行 `sleep 5` → runHooks 立即返回（不阻塞），success=true

## Prompt 执行器

- [ ] 12. post_tool_use hook 中 action type=prompt, message="Tool ${tool} done" → HookResult.output = "Tool WriteFile done"

## HTTP 执行器

- [ ] 13. 对本地 echo server 发 POST 请求，body 为 `{"tool":"${tool}"}` → server 收到替换后的 JSON
- [ ] 14. 对不可达 URL 发请求 → HookResult.success=false，输出包含连接错误信息

## HookEngine 核心

- [ ] 15. 注册 3 条 hook（匹配不同 event），触发 `POST_TOOL_USE` 事件 → 只有 event 匹配的那条执行
- [ ] 16. `runOnce=true` 的 hook 在同一进程中第二次触发同 event → 不重复执行
- [ ] 17. `reject=true` 的 pre_tool_use hook → `runPreToolHooks()` 返回 `PreToolResult(true, output)`，未 reject 返回 `PreToolResult(false, "")`

## YAML 加载

- [ ] 18. `.mewcode/hooks/` 下有 3 个合法 YAML + 1 个非法 YAML（event 写错） → `HookLoader.load()` 返回 3 条 rules + >=1 条 warning
- [ ] 19. `.mewcode/hooks/` 目录不存在 → 返回空列表 + 0 warning，不抛异常
- [ ] 20. YAML 文件缺少必填字段 `id` → warning 中报告 "id is required"，该条跳过
- [ ] 21. `reject=true` 且 `background=true` → warning 提示 "reject and background are mutually exclusive"

## 错误日志

- [ ] 22. Hook 执行中 command 返回非零退出码 → `.mewcode/hooks-errors.log` 追加一行带时间戳和 hook-id 的错误记录
- [ ] 23. `.mewcode/hooks-errors.log` 所在目录只读（模拟磁盘满） → 不崩溃，`System.err` 有 fallback 输出

## 生命周期集成

- [ ] 24. `grep -n "hookEngine.runHooks" src/main/java/com/mewcode/agent/AgentLoop.java` 返回 ≥ 7 处调用（session_start、turn_start、turn_end、session_end、pre_send、post_receive、post_tool_use）
- [ ] 25. 启动 MewCode → 终端显示加载的 hook 数量和 warning（如果有）

## pre_tool_use 拦截

- [ ] 26. 配置 pre_tool_use hook `tool==Bash` + `args.command=~rm -rf /` + reject=true → 模型发出 `rm -rf /` → 工具不执行，模型收到 `HOOK_REJECTED` 错误和拒绝原因
- [ ] 27. `grep -r "HOOK_REJECTED" src/main/java/com/mewcode/agent/AgentLoop.java` 返回 ≥ 1 处

## 端到端

- [ ] 28. 创建 post_tool_use hook（command: `echo "hook fired: ${tool}" >> /tmp/mewcode-hook-test.log`）→ 执行任意工具后 `/tmp/mewcode-hook-test.log` 新增一行
- [ ] 29. 创建 pre_tool_use reject hook（拦截 Bash 的特定危险命令）→ 对话中让模型执行该命令 → 工具被拦截，模型给出调整后的回复
- [ ] 30. 删除 `.mewcode/hooks/` 目录 → MewCode 正常启动，无 hooks 加载，功能不受影响
