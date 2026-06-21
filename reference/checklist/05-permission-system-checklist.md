# checklist.md — 权限系统

每项必须可勾选、可观测。

## 1. 工具分类（ToolCategory）

- [ ] `grep -r "implements Tool" src/` 返回 6 个文件
- [ ] `grep -r "ToolCategory.READ" src/` 返回 ≥ 3 条（ReadFileTool、GlobTool、GrepTool）
- [ ] `grep -r "ToolCategory.WRITE" src/` 返回 ≥ 2 条（WriteFileTool、EditFileTool）
- [ ] `grep -r "ToolCategory.COMMAND" src/` 返回 ≥ 1 条（ExecuteCommandTool）
- [ ] Tool 接口中 `category()` 默认返回 `ToolCategory.COMMAND`

## 2. 权限模式（PermissionMode）

- [ ] `grep "DEFAULT"`、`grep "ACCEPT_EDITS"`、`grep "PLAN"`、`grep "BYPASS"` 在 PermissionMode.java 中各出现 ≥ 1 次
- [ ] `grep "strict" PermissionMode.java` 返回 ≥ 1 条（fromString 中的向后兼容映射）
- [ ] `grep "permissive" PermissionMode.java` 返回 ≥ 1 条（fromString 中的向后兼容映射）
- [ ] Decision.java 文件不存在
- [ ] Action.java 文件不存在
- [ ] MatchType.java 文件不存在

## 3. 用户响应（PermissionResponse）

- [ ] `grep "ALLOW_ALWAYS" PermissionResponse.java` 返回 ≥ 1 条
- [ ] `grep "ALLOW" PermissionResponse.java` 返回 ≥ 2 条（ALLOW 和 ALLOW_ALWAYS）

## 4. 安全命令（Layer 1）

- [ ] `grep "SAFE_COMMANDS" PermissionChecker.java` 返回 ≥ 1 条
- [ ] `grep -c "ls\|cat\|pwd\|whoami\|git\|grep\|find\|echo\|head\|tail\|df\|ps\|ping\|diff\|sort\|wc\|file\|stat\|uname\|hostname" PermissionChecker.java` 显示 ≥ 15
- [ ] `grep "SAFE_GIT_SUBCOMMANDS" PermissionChecker.java` 返回 ≥ 1 条
- [ ] `grep "SHELL_META" PermissionChecker.java` 返回 ≥ 1 条（命令中包含 `|`、`;`、`&&`、`>`、`$()`、反引号时拒绝）

## 5. 危险命令（Layer 2）

- [ ] `grep "DANGEROUS_PATTERNS" PermissionChecker.java` 返回 ≥ 1 条
- [ ] `grep "rm.*-rf" PermissionChecker.java` 返回 ≥ 1 条
- [ ] `grep "mkfs" PermissionChecker.java` 返回 ≥ 1 条
- [ ] `grep "curl.*sh\|wget.*sh" PermissionChecker.java` 返回 ≥ 1 条
- [ ] `grep "fork" PermissionChecker.java` 返回 ≥ 1 条

## 6. 路径沙箱（Layer 3）

- [ ] `grep "java.io.tmpdir" PathSandbox.java` 返回 ≥ 1 条
- [ ] `grep "toRealPath" PathSandbox.java` 返回 ≥ 2 条（正常路径 + 父目录回退）

## 7. 规则引擎（Layer 4）

- [ ] `grep "parseRuleString" RuleEntry.java` 返回 ≥ 1 条
- [ ] `grep "ToolName(pattern)" RuleEntry.java` 或附近注释出现格式说明
- [ ] `grep "reverse" RuleEngine.java` 或在 match 逻辑中看到逆序遍历
- [ ] `grep "permissions.yaml" PermissionConfig.java` 返回 ≥ 2 条（用户级 + 项目级）
- [ ] `grep "permissions.local.yaml" PermissionConfig.java` 返回 ≥ 1 条

## 8. Plan 模式例外（Layer 0）

- [ ] `grep "PLAN_MODE_ALWAYS_ALLOW" PermissionChecker.java` 返回 ≥ 1 条
- [ ] `grep "planDir\|plans" PermissionChecker.java` 返回 ≥ 1 条

## 9. 权限检查入口

- [ ] `PermissionChecker.check(Tool tool, Map)` 方法签名存在
- [ ] `PermissionChecker.describeToolAction()` 方法存在
- [ ] `PermissionChecker.extractContent()` 方法存在（原 extractKeyParam）
- [ ] `describeToolAction` 输出包含 "Execute: " / "Read: " / "Write: " / "Edit: " / "Glob search: " / "Grep search: " 中的 ≥ 4 种

## 10. AgentLoop 人在回路

- [ ] `respondToPermission("Y")` 返回 ALLOW
- [ ] `respondToPermission("A")` 返回 ALLOW_ALWAYS
- [ ] `respondToPermission("N")` 返回 DENY
- [ ] `addAllowAlways` 被调用当用户选择 A

## 11. UI 交互

- [ ] 权限提示框显示 `[Y] 本次放行  [A] 总是放行(本会话)  [N] 拒绝`
- [ ] `/mode` 命令在 TerminalUI 中定义
- [ ] 启动时打印当前权限模式
- [ ] `/plan` 命令将 checker 模式设为 PLAN
- [ ] `/do` 命令将 checker 模式恢复为 DEFAULT

## 12. 配置文件

- [ ] `mewcode.yaml` 中 `permission.mode: "default"`、`"accept-edits"`、`"bypass"`、`"yolo"` 四值之一可启动
- [ ] `permission.mode: "strict"` 启动时 stderr 出现向后兼容警告
- [ ] `permission.mode: "permissive"` 启动时 stderr 出现向后兼容警告

## 13. 端到端验收

- [ ] 启动 MewCode，输入 "帮我列出当前目录文件" → Agent Loop 调用 execute_command → `ls` 自动放行（安全命令白名单命中）
- [ ] 输入 "删除文件 /etc/hosts" → Agent Loop 调用 execute_command → `rm /etc/hosts` 弹出 Y/A/N 确认提示
- [ ] 输入 Y → 工具执行；输入 N → 工具拒绝，模型收到 PERMISSION_DENIED 结果并尝试调整策略
- [ ] 使用 `/mode` 切换到 BYPASS（提示符显示红色 YOLO）→ 再次发出危险操作 → 自动放行
- [ ] 使用 `/mode` 切换到 ACCEPT_EDITS → 文件写入自动放行，shell 命令仍需确认
- [ ] 输入 "rm -rf /" → 被危险命令层硬拦截，返回 "危险命令被拦截"
- [ ] 输入 "写文件到 /etc/test" → 被路径沙箱拦截
- [ ] 输入 "写文件到 /tmp/test.txt" → 路径沙箱放行
- [ ] 在 `~/.mewcode/permissions.yaml` 中添加 `rule: "Bash(git push)"` + `effect: deny` → 重启后 `git push` 被规则拦截
- [ ] 权限拒绝后 Agent Loop 继续运行（不是终止），模型能根据拒绝信息调整操作
- [ ] `mvn compile` 无错误
