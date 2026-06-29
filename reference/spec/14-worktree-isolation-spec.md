# 14-worktree-isolation-spec.md

## 背景

ch13 实现了子 Agent 的分发与执行，但主 Agent 和子 Agent 仍在同一个工作目录下操作文件。当多个 Agent 并行读写同一个仓库时，会出现文件互相覆盖、git 状态冲突的问题。

Git 原生支持 `git worktree` 机制——同一仓库可以挂多个工作目录，各自一个分支，共享 `.git` 版本库。本章利用这一机制，为每个需要隔离的 Agent 创建独立的 worktree，让并行操作在各自的文件系统空间里进行。

## 目标用户

使用 MewCode 处理需要并行文件操作的复杂任务的开发者。典型场景：
- 主 Agent 正在改一个文件，同时派 3 个 Explore 子 Agent 并行搜索不同模块——子 Agent 可能也要改文件，不能互相覆盖
- 主 Agent 想在一个隔离环境里做个实验性重构，做完决定是否保留，保留就合并，不保留就扔掉

---

## 目标

- **G1**：提供 `EnterWorktree` / `ExitWorktree` 两个工具，让主 Agent 能把当前 session 切换到隔离的 git worktree 中工作；进入后所有路径相关资源（记忆、session、plan、compact spill、task list、file history）按 worktree 目录隔离
- **G2**：子 Agent 通过 Agent 工具的 `isolation: "worktree"` 参数自动获得独立 worktree；Agent 工具在启动子 Agent 前创建目录、注入路径说明、完成后按变更情况决定保留或清理
- **G3**：目录名走严格安全校验——限字符集 `[a-zA-Z0-9._-]`、限长度 64 字符、拒绝 `.` 和 `..` 段、允许 `/` 做逻辑分层但在文件系统上扁平化（`/` → `+`），防 LLM 输入触发路径遍历
- **G4**：完整生命周期——创建（含快速恢复：目录已存在时只 bump mtime 不调 git）、进入（记录原始路径，不调 chdir）、退出（恢复原始路径）、删除（含分支清理）
- **G5**：创建后自动环境初始化——复制 `.mewcode/settings.local.json`、配置 `core.hooksPath` 指向主仓库的 hooks 目录、软链配置的大型依赖目录、按 `.worktreeinclude` 规则补上被 ignore 但运行需要的文件
- **G6**：退出时变更保护——有未提交修改或有相对于原始 HEAD 的新 commit 时，默认拒绝删除并列出变更数量；用户可显式传 `discard_changes: true` 强制删除
- **G7**：后台定期清理过期的临时 worktree，三层过滤保证安全——① 只清理匹配临时模式（如 `agent-a<hex>`、`wf_*`）的目录 ② 检查最后修改时间超过截止阈值 ③ `git status --porcelain -uno` 和 `rev-list HEAD --not --remotes` 双重确认无变更无未推送；任何一步不确定就保留（fail-closed）
- **G8**：Agent 工具新增 `isolation` 参数（enum: `["worktree"]`，可选），独立于 `SubAgentSpec`——隔离是运行时行为不是角色属性；子 Agent 通过 `Agent.setWorkDir()` 注入工作目录，所有文件相关基础设施（路径缓存、compact spill、plan、session）用绝对路径按目录隔离，不需要在切换时清缓存
- **G9**：子 Agent 完成时自动判断——无变更无新 commit 自动删除 worktree；有变更则保留并通知主 Agent worktree 路径

---

## 功能需求

### 目录名校验

- **F1**：`SlugValidator.validate(slug)` 做四级校验：
  - 非空
  - 总长 ≤ 64 字符
  - 按 `/` 分段后每段不能是 `.` 或 `..`
  - 每段必须匹配 `^[a-zA-Z0-9._-]+$`（字母、数字、点、下划线、连字符）
- **F2**：`SlugValidator.flatten(slug)` 把 `/` 替换为 `+`，产出实际文件系统目录名；`SlugValidator.branchName(slug)` 返回 `"worktree-" + flatten(slug)`

### WorktreeManager

- **F3**：`WorktreeManager` 类管理所有 worktree 生命周期：
  - 构造参数：`projectRoot`（仓库根目录）、`symlinkDirs`（要软链的目录名列表）、`staleCutoffHours`（过期清理默认阈值，默认 24）
  - 内部维护 `Map<String, WorktreeInfo>`（key 为分支名）
  - `WorktreeInfo` 是 record：`path`、`branch`、`createdAt`
- **F4**：`create(branch, targetDir)` 创建 worktree：
  - 默认目录：`.mewcode/worktrees/<branch>`
  - 调 `git worktree add -B <branch> <path>`（大写 B 重置孤儿分支）
  - 创建后调 `PostCreationSetup.perform()` 做环境初始化
  - 记录到内存 Map
- **F5**：`remove(branch)` 删除 worktree：调 `git worktree remove <path> --force`，从 Map 移除
- **F6**：`list()` 返回 worktree 列表，优先解析 `git worktree list --porcelain` 输出（含仓库中所有 worktree），失败或为空时回退到内存 Map
- **F7**：`cleanupStale(cutoffHours)`：遍历内存 Map，删除 `createdAt` 超过阈值的 worktree；`removeAll()` 清空所有

### AgentWorktree（子 Agent 轻量 API）

- **F8**：`AgentWorktree.create(slug, repoRoot, symlinkDirs) -> Result`：
  - 先调 `SlugValidator.validate(slug)`
  - 目录 = `.mewcode/worktrees/<flatten(slug)>`，分支名 = `"worktree-" + flatten(slug)`
  - **快速恢复**：目录已存在 → 只 bump 最后修改时间（防过期清理误删），读 HEAD commit，直接返回 Result；不调 git
  - 目录不存在 → 调 `git worktree add -B <branch> <path> HEAD`（从当前 HEAD 分出），超时 60s
  - 创建后调 `PostCreationSetup.perform()`
  - Result 含：`worktreePath`、`worktreeBranch`、`headCommit`、`gitRoot`
- **F9**：`AgentWorktree.remove(worktreePath, worktreeBranch, gitRoot) -> boolean`：
  - 调 `git worktree remove --force <path>`，超时 30s
  - 再调 `git branch -D <branch>` 清理分支（中间 sleep 100ms 等 git lockfile 释放）
  - 异常时返回 false（best-effort）
- **F10**：`AgentWorktree.buildNotice(parentCwd, worktreeCwd) -> String`：生成子 Agent 的路径说明文本，告知它继承自父 Agent 的上下文路径需要转换到自己的工作目录

### 环境初始化

- **F11**：`PostCreationSetup.perform(repoRoot, worktreePath, symlinkDirs)` 串行执行四步，每一步异常不阻断后续：
  1. **复制配置**：从主仓库 `.mewcode/settings.local.json` 复制到 worktree 对应位置（文件不存在则跳过）
  2. **配置 hooks**：检测主仓库 `.husky` 或 `.git/hooks` 目录，存在则在新 worktree 内设 `git config core.hooksPath <path>`
  3. **软链依赖目录**：遍历 `symlinkDirs`，对每个目录在 worktree 内创建符号链接指向主仓库对应目录（跳过含 `..` 的、源不存在的、目标已存在的）
  4. **补上被忽略文件**：读取主仓库 `.worktreeinclude`（每行一个 pattern），通过 `git ls-files --others --ignored --exclude-standard --directory` 发现被忽略文件，按 pattern 匹配（支持精确路径匹配、文件名匹配、目录前缀匹配），匹配到的文件复制到 worktree 对应位置
- **F12**：`.worktreeinclude` 语法：每行一个 pattern，`#` 开头为注释，空行跳过；pattern 可以是 `path/to/file`（精确路径）、`filename.ext`（文件名）、`dirname/`（目录前缀）

### 变更检测

- **F13**：`WorktreeChanges.countChanges(worktreePath, originalHeadCommit) -> ChangeSummary | null`：
  - `ChangeSummary` record：`changedFiles: int`、`commits: int`
  - 调 `git status --porcelain` 统计未提交变更文件数
  - 调 `git rev-list --count <head>..HEAD` 统计新 commit 数
  - 任一 git 调用失败返回 null（fail-closed：调用方必须视为不安全）
- **F14**：`WorktreeChanges.hasChanges(worktreePath, headCommit) -> boolean`：
  - 有未提交变更或新 commit → true
  - git 调用失败 → true（fail-closed）

### 定期清理

- **F15**：`StaleCleanup` 管理后台清理：
  - `EPHEMERAL_PATTERNS` 定义临时目录的命名模式：`agent-a<7位hex>`、`wf_<8位hex>-<3位hex>-<数字>`、`wf-<数字>`、`bridge-<字母数字下划线段>`、`job-<1~55字符>-<8位hex>`
  - `isEphemeral(slug)` 判断目录名是否匹配任一临时模式
  - `cleanup(repoRoot, cutoff)` 扫描 `.mewcode/worktrees/`，三层过滤：
    1. 非临时模式 → 跳过
    2. 是当前 session 的 worktree → 跳过
    3. 最后修改时间晚于 cutoff → 跳过
    4. `git status --porcelain -uno` 非空 → 跳过（有未提交变更）
    5. `git rev-list --max-count=1 HEAD --not --remotes` 非空 → 跳过（有未推送 commit）
    6. 通过全部检查 → 调 `AgentWorktree.remove()` 删除
  - 删除后调 `git worktree prune` 清理裸引用
- **F16**：`StaleCleanup.startCleanupLoop(executor, repoRoot, intervalSeconds, cutoffHours)`：用 `ScheduledExecutorService` 按固定频率调度清理任务

### EnterWorktree 工具

- **F17**：`EnterWorktree` 工具实现 `Tool` 接口，`shouldDefer()` 返回 true（默认不加载 schema，需 ToolSearch 按需加载）
  - 参数：`name`（string，可选；不传则随机生成 `wt-<hex>`）
  - 已有活跃 session 时返回错误 `"Already in a worktree session"`
  - 校验 name → 调 `WorktreeManager.create()` → 创建 `WorktreeSession` 记录原始 cwd、worktree 路径、分支、sessionId → 持久化到 `.mewcode/worktree_session.json`
  - 返回成功信息含 worktree 路径和分支名
- **F18**：进入 worktree 后不调 `System.setProperty("user.dir", ...)`（不 chdir）；通过 `WorktreeSessionStore.getCurrentSession()` 让各组件按需获取当前 worktree 路径

### ExitWorktree 工具

- **F19**：`ExitWorktree` 工具，`shouldDefer()` 返回 true：
  - 参数：`action`（必填，`"keep"` | `"remove"`）、`discard_changes`（bool，可选）
  - 无活跃 session → 返回 `"No-op: there is no active EnterWorktree session to exit"`
  - `action="remove"` 且 `discard_changes != true` → 调 `WorktreeChanges.countChanges()`：
    - 返回 null（无法确定状态）→ 错误，提示用 `discard_changes: true` 或 `action: "keep"`
    - 有变更（changedFiles > 0 或 commits > 0）→ 错误，列出具体数量和类型，提示用 `discard_changes: true` 或 `action: "keep"`
    - 无变更 → 继续删除
  - 清除 `WorktreeSessionStore` 的当前 session 和持久化文件
  - `action="remove"` → 调 `worktreeManager.remove()` 物理删除
  - 返回结果含原路径和当前路径

### Agent 工具集成

- **F20**：Agent 工具 schema 新增 `isolation` 参数：类型 string，enum `["worktree"]`，可选
- **F21**：`isolation: "worktree"` 且 `worktreeManager` 不为 null 时：
  - 生成 slug：`"agent-a" + 7位随机hex`
  - 调 `AgentWorktree.create(slug, projectRoot, symlinkDirs)` 创建/恢复 worktree
  - 调 `subAgent.setWorkDir(wtResult.worktreePath)` 注入工作目录
  - 把 `AgentWorktree.buildNotice()` 的输出拼到子 Agent prompt 前面
  - 创建失败返回错误（不启动子 Agent）
- **F22**：子 Agent 完成后：
  - 调 `WorktreeChanges.hasChanges(worktreePath, headCommit)` 检查
  - 无变更 → 自动调 `AgentWorktree.remove()` 清理
  - 有变更 → 保留 worktree，在主 Agent 看到的 tool_result 末尾追加 worktree 保留信息（路径和分支名）
- **F23**：`isolation` 参数独立于 `SubAgentSpec`——隔离是运行时行为，不由角色定义决定；Teammate 路径（`TeamManager` 分发）也支持 `isolation` 参数，走相同逻辑

---

## 非功能需求

- **N1**：fail-closed——所有 git 调用异常时默认假定有不安全状态（有变更、不可删除），宁可保留目录也不误删
- **N2**：不 chdir——整个进程的 `user.dir` 不变，所有路径相关组件通过 `workDir` 参数显式传递绝对路径做隔离，天然按目录分 key，不需要在切换时清缓存
- **N3**：worktree 目录放在 `.mewcode/worktrees/`（仓库内不被追踪的位置，被 `.gitignore` 覆盖）
- **N4**：与现有 ch13 SubAgent、ch12 Hook、ch08 权限、ch03 Agent Loop 协同，不破坏既有测试
- **N5**：符号链接创建失败不阻断整体流程（best-effort）
- **N6**：`StaleCleanup.startCleanupLoop` 的异常被静默捕获（`log.fine`），不影响主程序
- **N7**：`EnterWorktree`/`ExitWorktree` 均为 defer 工具（`shouldDefer() = true`），schema 不随主工具列表加载，防止 prompt cache 抖动

---

## Out of Scope

- Worktree 之间的合并策略（交给上层用 `git merge` 决定）
- 跨 worktree 代码同步
- 多 Agent 并行编排（留给团队协作章节）
- Worktree 会话的跨进程恢复（`WorktreeSessionStore` 持久化但不跨进程激活）
- 非 git 项目的隔离机制
- Worktree 目录的跨文件系统支持

---

## 验收标准

- **AC1**：`EnterWorktree` 工具调用后，session 切换到 worktree；`ExitWorktree action:keep` 后回到原始目录
- **AC2**：`EnterWorktree` 传入非法 name（含 `..`、超长、含非法字符）→ 返回结构化错误
- **AC3**：子 Agent 调 `isolation: "worktree"` → 在独立 worktree 中执行，文件修改不影响主 Agent 工作目录
- **AC4**：子 Agent 无文件变更时 → worktree 自动被清理；有变更时 → 保留并通知路径
- **AC5**：`ExitWorktree action:remove` 检测到未提交变更 → 拒绝删除并列出变更数量
- **AC6**：过期临时 worktree 被后台清理移除
- **AC7**：`EnterWorktree` 已存在同名 worktree 时 → 快速恢复（目录已存在，不重建）
- **AC8**：worktree 创建后 `.mewcode/settings.local.json` 被复制、`core.hooksPath` 被配置、symlink 目录被链接
- **AC9**：Agent 工具 schema 中 `isolation` 参数存在，enum 为 `["worktree"]`
