# tasks.md — 权限系统

## 依赖关系

```
Task 1 (ToolCategory)
──→ Task 6 (Tool 接口 + 6 个实现)

Task 2 (PermissionMode)
──→ Task 7 (ConfigLoader)
──→ Task 5 (PermissionChecker)

Task 3 (PermissionResponse)
──→ Task 5 (PermissionChecker)
──→ Task 8 (AgentLoop/Event)

Task 4 (Rule 系统)
──→ Task 5 (PermissionChecker)

Task 5 (PermissionChecker)
──→ Task 8 (AgentLoop/Event/Strategy)
──→ Task 9 (MewCode)

Task 6 + 7 + 8
──→ Task 9 (MewCode)
──→ Task 10 (TerminalUI)

Task 9 + 10
──→ Task 12 (端到端验证)
```

## 任务列表

### Task 1 — 创建 ToolCategory 枚举
- **影响文件**：`src/main/java/com/mewcode/tool/ToolCategory.java`（新建）
- **说明**：创建 READ/WRITE/COMMAND 三值枚举，作为工具分类的标准
- **依赖**：无
- **参考**：`tool/Tool.java`（现有接口）

### Task 2 — 重写 PermissionMode 枚举
- **影响文件**：
  - `src/main/java/com/mewcode/permission/PermissionMode.java`（重写）
  - `src/main/java/com/mewcode/permission/Decision.java`（删除）
- **说明**：将 STRICT/DEFAULT/PERMISSIVE 替换为 DEFAULT/ACCEPT_EDITS/PLAN/BYPASS；添加嵌套 Decision 枚举（ALLOW/DENY/ASK）；添加 `decide(ToolCategory)` 方法；更新 `fromString()` 支持新值并向后兼容
- **依赖**：Task 1（ToolCategory）
- **参考**：`D:\mewcode-java\java\src\main\java\com\mewcode\permission\PermissionMode.java`

### Task 3 — 创建 PermissionResponse 枚举
- **影响文件**：`src/main/java/com/mewcode/permission/PermissionResponse.java`（新建）
- **说明**：创建 ALLOW/ALLOW_ALWAYS/DENY 三值枚举，表示用户对权限提示的响应
- **依赖**：无
- **参考**：`D:\mewcode-java\java\src\main\java\com\mewcode\permission\PermissionResponse.java`

### Task 4 — 更新规则系统
- **影响文件**：
  - `permission/RuleEntry.java`（重写）— 支持 `rule: "ToolName(pattern)"` 格式解析
  - `permission/RuleEngine.java`（重写）— 逆序遍历规则列表（last-match-wins），移除 regex 支持仅保留 glob
  - `permission/PermissionConfig.java`（重写）— 从 user/project/local 三个 YAML 源加载
  - `permission/PathSandbox.java`（修改）— 添加 /tmp 路径允许
  - `permission/Action.java`（删除）
  - `permission/MatchType.java`（删除）
- **依赖**：无
- **参考**：`D:\mewcode-java\java\src\main\java\com\mewcode\permission\PermissionChecker.java`（规则加载逻辑）

### Task 5 — 重写 PermissionChecker
- **影响文件**：
  - `permission/PermissionChecker.java`（重写）
  - `permission/PermissionResult.java`（修改 Decision 引用为 PermissionMode.Decision）
- **说明**：实现七层检查链——Plan 例外 → 安全命令 → 危险命令 → 路径沙箱 → 文件规则 → 会话永放 → 权限模式；添加安全命令白名单（~70 条）、危险命令正则（~20 条）、describeToolAction()、addAllowAlways()；check() 签名改为 `check(Tool tool, Map<String,Object> args)`
- **依赖**：Task 2（PermissionMode）、Task 3（PermissionResponse）、Task 4（规则系统）
- **参考**：`D:\mewcode-java\java\src\main\java\com\mewcode\permission\PermissionChecker.java`（350 行，完整参考）

### Task 6 — 更新 Tool 接口和实现
- **影响文件**：
  - `tool/Tool.java`（修改）— 添加 `default ToolCategory category()` 方法，默认返回 COMMAND
  - `tool/impl/ReadFileTool.java`（修改）— 覆盖 category() 返回 READ
  - `tool/impl/WriteFileTool.java`（修改）— 覆盖 category() 返回 WRITE
  - `tool/impl/EditFileTool.java`（修改）— 覆盖 category() 返回 WRITE
  - `tool/impl/GlobTool.java`（修改）— 覆盖 category() 返回 READ
  - `tool/impl/GrepTool.java`（修改）— 覆盖 category() 返回 READ
  - `tool/impl/ExecuteCommandTool.java`（修改）— 覆盖 category() 返回 COMMAND
- **依赖**：Task 1（ToolCategory 枚举）
- **参考**：各工具文件的 `isReadOnly()` 方法位置

### Task 7 — 更新 ConfigLoader
- **影响文件**：
  - `config/ConfigLoader.java`（修改）— validate() 中更新 permission.mode 合法值校验
- **说明**：接受 default/accept-edits/accept_edits/plan/bypass/yolo；strict 映射为 default（警告）；permissive 映射为 bypass（警告）
- **依赖**：Task 2（PermissionMode）
- **参考**：`config/ConfigLoader.java` 第 99-114 行

### Task 8 — 更新 AgentLoop/AgentEvent/ToolExecutionStrategy
- **影响文件**：
  - `agent/AgentLoop.java`（重写）— respondToPermission() 改为 Y/A/N，wire allowAlways；permission pre-check 使用 Tool.category()
  - `agent/AgentEvent.java`（修改）— 添加 permissionResponse 字段 (PermissionResponse 类型)
  - `agent/ToolExecutionStrategy.java`（修改）— 使用 Tool.category() 替代 isReadOnly() 进行工具分类
- **依赖**：Task 3（PermissionResponse）、Task 5（PermissionChecker）、Task 6（Tool 接口）
- **参考**：现有 `agent/AgentLoop.java` 第 111-127 行 respondToPermission()，第 226-263 行 permission pre-check

### Task 9 — 更新 MewCode.java 主程序
- **影响文件**：`MewCode.java`（重写）
- **说明**：简化 PermissionChecker 构造（移除 PathSandbox/PermissionConfig 手动创建）；更新事件消费者中 PERMISSION_REQUIRED 渲染为 Y/A/N 格式；更新输入处理器（Y/A/N 替代 Y/S/N）
- **依赖**：Task 5、Task 7、Task 8
- **参考**：现有 `MewCode.java` 第 70-80 行（权限构造），第 91-103 行（输入处理），第 203-218 行（权限渲染）

### Task 10 — 更新 TerminalUI
- **影响文件**：`tui/TerminalUI.java`（重写）
- **说明**：添加 `/mode` 命令轮换权限模式；添加 `setPermissionChecker()` 方法；Plan Mode 入口设置 checker.setPlanMode(true)；动态提示符显示当前权限模式；`/plan` 进入时设置 mode=PLAN，`/do` 恢复 mode=DEFAULT
- **依赖**：Task 5（PermissionChecker）、Task 9（MewCode）
- **参考**：现有 `tui/TerminalUI.java` 第 92-119 行（slash 命令处理）

### Task 11 — 编写文档
- **影响文件**：
  - `spec/05-permission-system.md`（重写）
  - `tasks/05-permission-system.md`（重写）
  - `checklist/05-permission-system.md`（重写）
- **依赖**：所有设计决策定稿后
- **参考**：现有三份文档 + 参考项目 D:\mewcode-java\java 的权限实现

### Task 12 — 端到端验证
- **说明**：
  1. `mvn compile` 编译通过
  2. 启动 MewCode，验证权限模式显示在启动信息和提示符中
  3. 测试 DEFAULT 模式：`ls`/`cat file` 自动放行，`rm file` 弹出 Y/A/N 提示
  4. 测试 ACCEPT_EDITS 模式：文件写入自动放行，shell 命令弹出提示
  5. 测试 BYPASS 模式：所有操作自动放行
  6. 测试安全命令：`git status` 自动放行，`git push` 弹出提示
  7. 测试危险命令：`rm -rf /` 被硬拦截
  8. 测试路径沙箱：写到项目外被拦截，写到 /tmp 被放行
  9. 测试 YAML 规则加载：`~/.mewcode/permissions.yaml` 规则生效
  10. 测试 /mode 命令：循环切换四档模式
  11. 测试 /plan 命令：进入 Plan 模式
- **依赖**：所有实现任务完成后
