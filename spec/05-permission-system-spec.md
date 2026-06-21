# spec.md — 权限系统

## 背景

当前 MewCode 有一套五层权限防御链：黑名单（ExecuteCommandTool 内部正则）→ 路径沙箱 → 规则引擎 → 权限模式 → 人在回路。但在实际使用中暴露出几个不足：

- **缺少安全命令自动放行**：`ls`、`cat`、`git status` 等纯读取命令也需要每次都走规则匹配，规则未覆盖时弹出确认提示，打扰用户。
- **缺少危险命令硬拦截**：当前黑名单只在 ExecuteCommandTool 内部，不属于统一的权限检查链，容易被绕过。没有对 `rm -rf /`、`curl | sh`、fork bomb 等危险模式的硬编码拦截。
- **权限模式粒度不足**：只有 STRICT/DEFAULT/PERMISSIVE 三档，缺少"接受编辑但命令仍需确认"这一常用场景。
- **路径沙箱只允许项目目录**：不允许写到 /tmp 等临时目录，写测试文件等场景不方便。
- **规则格式冗余**：每条规则有 4 个字段（tool/pattern/match_type/action），而 `ToolName(glob)` 一行就能表达。
- **只加载一个配置文件**：仅 `~/.mewcode/permissions.yaml`，不支持项目级共享规则和本地覆盖。
- **Plan Mode 没有例外路径**：Plan Mode 下，即便是写 `.mewcode/plans/` 中的调研计划也需要确认。
- **UI 交互单薄**：确认提示只有 Y/S/N，缺少"总是放行"的快捷选项。

本 spec 重新设计七层防御链并改进交互，参照 Claude Code 的成熟实践。

## 目标用户

使用 MewCode 的开发者。ta 们在终端里让 AI 改代码、跑命令、搜文件，希望在不过度打扰工作流的前提下，对自己机器上发生的事情有掌控感。部分用户可能希望与团队共享一套基础的权限规则，再在本地按需覆盖。

## 能力清单

1. **七层防御链**：在每个工具执行前，按固定顺序过七层检查——Plan 模式例外 → 安全命令 → 危险命令 → 路径沙箱 → 文件规则 → 会话永久放行 → 权限模式。任一层做出"拒绝"裁决即短路返回。任一层"放行"则跳过后续层直接执行。
2. **工具分类体系**：每个工具声明自己的 ToolCategory（READ/WRITE/COMMAND），权限模式以此决定兜底行为，不再依赖 boolean isReadOnly()。
3. **安全命令自动放行（Layer 1）**：维护一个 ~70 条命令的白名单（`ls`、`cat`、`git status`、`grep` 等），当命令不包含 shell 元字符（管道、分号、`&&`、`>`、`$()`、反引号等）且基命令在白名单中时，自动放行。对于 `git`，额外检查子命令是否为安全子集（`status`/`log`/`diff`/`branch` 等，排除 `push`/`commit` 等）。
4. **危险命令硬拦截（Layer 2）**：用正则列表匹配命令字符串，命中 `rm -rf /`、`mkfs.*`、`dd .. of=/dev/`、`chmod 777 /`、fork bomb、`curl | sh` 等 20+ 种高危模式时直接拒绝并给出警告。这一层不可绕过。
5. **路径沙箱（Layer 3）**：对有副作用的文件工具（WRITE 类别），解析目标路径符号链接后判断是否以项目根目录或临时目录为前缀。项目根目录外且不在 /tmp（Windows 上为 `java.io.tmpdir`）内的路径拒绝执行。
6. **可配置规则引擎（Layer 4）**：从三个 YAML 源加载规则堆叠——`~/.mewcode/permissions.yaml`（用户级）、`<project>/.mewcode/permissions.yaml`（项目级，可通过 git 共享）、`<project>/.mewcode/permissions.local.yaml`（本地覆盖，gitignored）。规则格式为 `rule: "ToolName(glob)"` + `effect: allow|deny`。文件规则按加载顺序排列；匹配时逆序遍历，后加载的规则优先级更高（本地 > 项目 > 用户）。
7. **四档权限模式（Layer 5-6 兜底）**：DEFAULT（READ 自动放行，WRITE/COMMAND 需确认）、ACCEPT_EDITS（READ/WRITE 自动放行，COMMAND 需确认）、PLAN（同 DEFAULT + Plan 模式例外）、BYPASS（全部自动放行，"YOLO 模式"）。通过 mewcode.yaml 的 `permission.mode` 配置，运行时可通过 `/mode` 命令或 Shift+Tab 热键切换。
8. **人在回路（Layer 6 ASK 时触发）**：当模式兜底返回 ASK 时，Agent Loop 向事件队列推送 PERMISSION_REQUIRED 事件并阻塞等待。UI 层展示工具名、操作描述（由 describeToolAction() 生成）、选项 `[Y] 本次放行` `[A] 总是放行(本会话)` `[N] 拒绝`。选择 Y 添加 loop 级规则，A 添加 session 级规则并写入 `allowAlwaysRules` 集合。
9. **Plan 模式例外（Layer 0）**：Plan 模式下自动放行 Agent/ToolSearch/AskUserQuestion 等内部工具，以及对 `.mewcode/plans/` 目录的文件写入。
10. **权限拒绝不终止循环**：权限被拒绝时，返回 `ToolResult(success=false, content=拒绝原因, errorCode="PERMISSION_DENIED")`。Agent Loop 将此结果正常回灌给模型，让模型有机会调整策略。
11. **会话临时规则优先级**：loop 规则 > session 规则 > 文件规则。同层内逆序匹配（最后添加的最优先）。

## 非功能要求

- 权限检查必须同步执行 — Agent Loop 在得到结果前不能继续
- 路径沙箱的符号链接解析使用 `Path.toRealPath()`，处理多级跳转
- 安全命令白名单匹配耗时应在微秒级
- 配置文件缺失时使用空规则集启动，不报错
- 人在回路等待期间无超时限制 — 用户可能离开终端
- 权限事件使用现有 `BlockingQueue<AgentEvent>` 推送，不另建通道
- 向后兼容：`mewcode.yaml` 中的 `permission.mode: strict` → DEFAULT（警告），`permissive` → BYPASS（警告）

## 设计骨架

```
permission/                              ← 新文件 + 重写
├── PermissionChecker                    ← 七层编排入口：check(Tool tool, Map<String,Object> args) → PermissionResult
├── PermissionResult                     ← 不可变：Decision(ALLOW/DENY/ASK) + reason + hint + scope
├── PermissionMode                       ← 枚举 DEFAULT/ACCEPT_EDITS/PLAN/BYPASS + 嵌套 Decision + decide(ToolCategory)
├── PermissionResponse                   ← 枚举 ALLOW/ALLOW_ALWAYS/DENY（用户响应）
├── PathSandbox                          ← 项目根目录 + /tmp 前缀判断
├── RuleEngine                           ← 三层规则源（loop > session > config），逆序匹配
├── RuleEntry                            ← 单条规则：toolName + pattern + effect(ALLOW/DENY)，支持 "ToolName(pattern)" 格式
└── PermissionConfig                     ← 从 user/project/local 三个 YAML 源加载规则

tool/
├── ToolCategory                         ← 枚举 READ/WRITE/COMMAND
└── Tool.java                            ← +default category() 方法

修改：
├── agent/AgentLoop.java                 ← respondToPermission() 改为 Y/A/N，新增 allowAlways 逻辑
├── agent/AgentEvent.java                ← +permissionResponse 字段 (PermissionResponse)
├── agent/ToolExecutionStrategy.java     ← 改用 ToolCategory 分类工具
├── config/ConfigLoader.java             ← 更新 permission.mode 合法值校验
├── tui/TerminalUI.java                  ← +/mode 命令，动态权限模式提示符
└── MewCode.java                         ← 简化 PermissionChecker 组装，更新事件消费者渲染 Y/A/N
```

### 权限检查数据流

```
AgentLoop (run loop)
  → for each ToolCall:
      Tool tool = toolRegistry.get(tc.getName())
      PermissionResult r = permissionChecker.check(tool, tc.getInput())
      case ALLOW → 加入待执行列表
      case DENY → ToolResult(false, r.reason)
      case ASK  → 推送 PERMISSION_REQUIRED 事件 → blockingQueue 等待
                  → 用户 Y: 加 loop 规则 + 执行
                  → 用户 A: 加 session 规则 + allowAlways + 执行
                  → 用户 N: ToolResult(DENIED)
```

### 规则 YAML 格式

```yaml
# ~/.mewcode/permissions.yaml（用户级）
rules:
  - rule: "Bash(git *)"
    effect: allow
  - rule: "Bash(npm *)"
    effect: allow

# <project>/.mewcode/permissions.yaml（项目级，可提交 git）
rules:
  - rule: "WriteFile(src/**)"
    effect: allow
  - rule: "Bash(rm *)"
    effect: deny

# <project>/.mewcode/permissions.local.yaml（本地覆盖，gitignored）
rules:
  - rule: "Bash(docker *)"
    effect: deny
```

## Out of Scope

- 网络请求限制（如禁止 curl 访问内网地址）
- 资源配额（CPU、内存、磁盘用量限制）
- 审计日志（谁在何时放行了什么操作）
- 配置文件热重载 — 修改 YAML 后需重启 MewCode
- 人在回路超时处理 — 无限等待
- 只读工具的路径沙箱 — ReadFileTool/GlobTool/GrepTool 不受沙箱限制
- 权限变更的通知/回调
- TUI 中用方向键选择权限选项的对话框（当前使用 Y/A/N 按键）
