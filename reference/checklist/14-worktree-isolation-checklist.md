# 14-worktree-isolation-checklist.md

## SlugValidator

- [ ] 1. `grep -r "MAX_LENGTH = 64" src/main/java/com/mewcode/worktree/SlugValidator.java` 返回 1 条匹配
- [ ] 2. `grep -r "VALID_SEGMENT" src/main/java/com/mewcode/worktree/SlugValidator.java` 返回 ≥ 2 条匹配（声明 + 使用）
- [ ] 3. `SlugValidator.validate("a/b")` 不抛异常；`SlugValidator.validate("a/../b")` 抛 `IllegalArgumentException` 且消息含 `"must not contain \".\" or \"..\" path segments"`
- [ ] 4. `SlugValidator.validate("a b")` 抛 `IllegalArgumentException` 且消息含 `"letters, digits, dots, underscores, and dashes"`
- [ ] 5. `SlugValidator.validate("")` 抛 `IllegalArgumentException`；`SlugValidator.validate(null)` 抛异常
- [ ] 6. 传入 65 字符的 slug → 抛 `IllegalArgumentException` 且消息含 `"64 characters or fewer"`
- [ ] 7. `SlugValidator.flatten("a/b/c")` 返回 `"a+b+c"`
- [ ] 8. `SlugValidator.branchName("my-agent")` 返回 `"worktree-my-agent"`

## WorktreeChanges

- [ ] 9. `grep -r "record ChangeSummary" src/main/java/com/mewcode/worktree/WorktreeChanges.java` 返回 1 条匹配，含 `changedFiles` 和 `commits` 字段
- [ ] 10. 干净 worktree 目录（无未提交变更、无新 commit）→ `WorktreeChanges.hasChanges(path, head)` 返回 false
- [ ] 11. 有未提交文件时 → `WorktreeChanges.hasChanges(path, head)` 返回 true → `countChanges(path, head).changedFiles()` ≥ 1
- [ ] 12. 有新 commit 时 → `countChanges(path, head).commits()` ≥ 1
- [ ] 13. `originalHeadCommit` 为 null 或空字符串时 → `countChanges()` 返回 null
- [ ] 14. 非 git 目录或 git 命令超时/失败 → `hasChanges` 返回 true（fail-closed）→ `countChanges` 返回 null

## WorktreeSession / WorktreeSessionStore

- [ ] 15. `grep -r "record WorktreeSession" src/main/java/com/mewcode/worktree/WorktreeSession.java` 返回 1 条匹配，含 8 个字段（originalCwd / worktreePath / worktreeName / worktreeBranch / originalBranch / originalHeadCommit / sessionId / creationDurationMs）
- [ ] 16. `grep -r "@JsonIgnoreProperties" src/main/java/com/mewcode/worktree/WorktreeSession.java` 返回 1 条匹配（ignoreUnknown = true）
- [ ] 17. `WorktreeSessionStore.save(repoRoot, session)` 后文件 `.mewcode/worktree_session.json` 存在
- [ ] 18. `WorktreeSessionStore.load(repoRoot)` 返回与保存一致的 session
- [ ] 19. `WorktreeSessionStore.save(repoRoot, null)` 后 `.mewcode/worktree_session.json` 被删除
- [ ] 20. `.mewcode/worktree_session.json` 不存在时 `load()` 返回 null
- [ ] 21. `WorktreeSessionStore.getCurrentSession()` 在 `restoreSession(s)` 后返回 s

## PostCreationSetup

- [ ] 22. `grep -r "copySettingsLocal\|configureHooksPath\|symlinkDirectories\|copyWorktreeIncludeFiles" src/main/java/com/mewcode/worktree/PostCreationSetup.java` 返回 ≥ 5 条匹配
- [ ] 23. 主仓库存在 `.mewcode/settings.local.json` → 创建 worktree 后 worktree 内对应路径存在该文件且内容一致
- [ ] 24. 主仓库存在 `.husky` 目录 → worktree 创建后 `git -C <worktree> config core.hooksPath` 输出指向主仓库 `.husky`
- [ ] 25. `symlinkDirs` 含 `"node_modules"`（目录存在）→ worktree 内 `node_modules` 是符号链接指向主仓库，`Files.isSymbolicLink()` 返回 true
- [ ] 26. `symlinkDirs` 含 `"../etc"` → 被跳过不创建（含 `..` 的拒绝）
- [ ] 27. `.worktreeinclude` 含 `"config.local.yml"` 且该文件被 `.gitignore` 忽略 → worktree 创建后该文件被复制到 worktree 对应路径
- [ ] 28. 主仓库无 `.mewcode/settings.local.json` 无 `.husky` → 创建 worktree 不报错（静默跳过）

## WorktreeManager

- [ ] 29. `grep -r "class WorktreeManager" src/main/java/com/mewcode/worktree/WorktreeManager.java` 返回 1 条匹配
- [ ] 30. `WorktreeManager.create("test-wt", null)` → 目录 `.mewcode/worktrees/test-wt` 存在 → `git -C <path> rev-parse --is-inside-work-tree` 返回 true
- [ ] 31. `create` 返回的 `WorktreeInfo`：`path` 为绝对路径且以 `.mewcode/worktrees/test-wt` 结尾 → `branch` = `"test-wt"` → `createdAt` 在最近 5 秒内
- [ ] 32. `list()` 返回的列表含刚创建的 `test-wt`
- [ ] 33. `get("test-wt")` 返回非空 Optional → `remove("test-wt")` 后目录不存在 → `get("test-wt")` 返回空
- [ ] 34. `remove("nonexistent")` 抛 `IllegalArgumentException` 且消息含 `"worktree not found"`
- [ ] 35. `cleanupStale(0)`（0 小时截止 → 所有都过期）返回 ≥ 1（刚创建的也算）
- [ ] 36. `removeAll()` 后 `list()` 返回空列表

## AgentWorktree

- [ ] 37. `grep -r "class AgentWorktree" src/main/java/com/mewcode/worktree/AgentWorktree.java` 返回 1 条匹配
- [ ] 38. `AgentWorktree.create("my-agent", repoRoot, List.of())` → 目录 `.mewcode/worktrees/my-agent` 存在 → Result.worktreeBranch = `"worktree-my-agent"`
- [ ] 39. 再次调 `AgentWorktree.create("my-agent", ...)` 同一 slug → 快速恢复（目录已存在，不调 git worktree add）→ Result.headCommit 非空
- [ ] 40. `AgentWorktree.create("a/b", ...)` → slug 校验通过 → 目录 = `.mewcode/worktrees/a+b`（`/` 被 flatten 为 `+`）
- [ ] 41. `AgentWorktree.remove(worktreePath, worktreeBranch, repoRoot)` → 目录被删除 → `git branch --list "worktree-my-agent"` 输出为空
- [ ] 42. `AgentWorktree.remove` 对不存在的路径 → 返回 false（best-effort，不抛异常）
- [ ] 43. `AgentWorktree.buildNotice("/home/user/project", "/home/user/project/.mewcode/worktrees/my-agent")` 返回字符串含两条路径

## StaleCleanup

- [ ] 44. `grep -r "EPHEMERAL_PATTERNS" src/main/java/com/mewcode/worktree/StaleCleanup.java` 返回 ≥ 1 条匹配
- [ ] 45. `isEphemeral("agent-a1a2b3c")` 返回 true
- [ ] 46. `isEphemeral("wt-something")` 返回 false（不在临时模式中）
- [ ] 47. `isEphemeral("wf_12345678-abc-42")` 返回 true
- [ ] 48. 创建临时 worktree → touch 设 mtime 为 48 小时前 → `cleanup(repoRoot, 24小时前)` → 返回 ≥ 1 → worktree 目录不存在
- [ ] 49. 临时 worktree 有未提交变更 → `cleanup` 不移除它（Layer 3 保护）
- [ ] 50. 当前 session 的 worktree → `cleanup` 不移除它（Layer 2 保护）
- [ ] 51. `startCleanupLoop(executor, repoRoot, 60, 1)` 正常启动 → 不抛异常

## EnterWorktreeTool

- [ ] 52. `grep -r "class EnterWorktreeTool" src/main/java/com/mewcode/tool/impl/EnterWorktreeTool.java` 返回 1 条匹配
- [ ] 53. `EnterWorktreeTool.shouldDefer()` 返回 true
- [ ] 54. `EnterWorktreeTool.schema()` 返回的 JSON 含 `name` 参数（type string，可选）
- [ ] 55. `execute({name: "test-session"})` 返回 success → 目录 `.mewcode/worktrees/test-session` 存在 → `WorktreeSessionStore.getCurrentSession()` 非 null
- [ ] 56. `execute({})`（不传 name）→ 返回 success → 自动生成随机 `wt-<hex>` 名称
- [ ] 57. `execute({name: "../../etc"})` → 返回 isError，错误含 "must not contain"
- [ ] 58. `execute` 时已有活跃 session → 返回错误含 "Already in a worktree session"

## ExitWorktreeTool

- [ ] 59. `grep -r "class ExitWorktreeTool" src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java` 返回 1 条匹配
- [ ] 60. `ExitWorktreeTool.shouldDefer()` 返回 true
- [ ] 61. `ExitWorktreeTool.schema()` 含 `action` 参数（必填，enum `["keep","remove"]`）和 `discard_changes` 参数（bool，可选）
- [ ] 62. 无活跃 session 时 `execute({action: "keep"})` → 返回错误含 "No-op"
- [ ] 63. 活跃 session 且在 worktree 中无变更 → `execute({action: "remove"})` → success → worktree 目录被删除 → session 为 null
- [ ] 64. 活跃 session 且在 worktree 中有未提交文件 → `execute({action: "remove"})` → 错误含 "uncommitted file(s)" 和变更数量
- [ ] 65. 活跃 session 且 worktree 有未提交变更 → `execute({action: "remove", discard_changes: true})` → success → 目录被删除
- [ ] 66. `execute({action: "keep"})` → success → worktree 保留 → session 为 null

## AgentTool 集成

- [ ] 67. `Agent` 工具的 schema 含 `isolation` 参数，enum 为 `["worktree"]`，描述含 "Isolation mode"
- [ ] 68. `execute({prompt: "创建文件 test.txt", subagent_type: "explore", isolation: "worktree"})` → 子 Agent 在 `.mewcode/worktrees/agent-a<7位hex>` 目录下创建了 test.txt → 主 Agent 的工作目录下无此文件
- [ ] 69. `isolation: "worktree"` 子 Agent 不修改任何文件 → 完成 → 对应 worktree 目录不存在（已自动清理）
- [ ] 70. `isolation: "worktree"` 子 Agent 修改了文件 → tool_result 含 "Worktree kept at" 和 worktree 路径和分支名
- [ ] 71. `isolation: "worktree"` 子 Agent prompt 含父 Agent 的原始路径和 worktree 路径（`buildNotice` 文本已注入）

## 配置

- [ ] 72. `grep -r "worktree" src/main/java/com/mewcode/config/AppConfig.java` 返回 ≥ 3 条匹配（config 类 + enabled + symlink_dirs + stale_cleanup_interval_seconds + stale_cutoff_hours）
- [ ] 73. `mewcode.yaml` 中 `worktree.enabled: false` → EnterWorktree 和 ExitWorktree 工具未注册 → Agent 工具 schema 不含 `isolation` 参数（或不生效）
- [ ] 74. `mewcode.yaml` 中设 `worktree.symlink_dirs: ["node_modules"]` → 创建 worktree 后 node_modules 是符号链接
- [ ] 75. `mewcode.yaml` 中设 `worktree.stale_cutoff_hours: 1` → 1 小时前的临时 worktree 被清理

## 异常安全

- [ ] 76. worktree 创建过程中 git 命令失败 → `AgentWorktree.create` 抛 `IOException` → AgentTool 返回 ToolResult.error（不崩主程序）
- [ ] 77. `StaleCleanup.cleanup` 中任一 git 调用异常 → 该目录被跳过保留 → 清理继续处理下一个目录
- [ ] 78. `PostCreationSetup` 任一步失败 → 不影响后续步骤（best-effort）

## 端到端验收（手动）

- [ ] 79. 启动 MewCode → 输入 "用 EnterWorktree 创建一个隔离工作区" → 主 Agent 调 EnterWorktree → worktree 创建成功 → 在隔离区修改文件 → 确认主目录无影响 → ExitWorktree 退出
- [ ] 80. 输入 "帮我在隔离 worktree 中用 explore 子 Agent 找所有 TODO" → 子 Agent 在隔离 worktree 中执行搜索 → 返回结果 → worktree 自动清理（只读操作）
- [ ] 81. 输入 "开一个子 Agent 在隔离 worktree 中创建一个新文件" → 子 Agent 创建文件 → tool_result 显示 worktree 被保留 → 手动到对应路径验证文件存在
- [ ] 82. 关闭 `worktree.enabled` → 重启 → EnterWorktree 工具不可见 → `isolation` 参数不生效
