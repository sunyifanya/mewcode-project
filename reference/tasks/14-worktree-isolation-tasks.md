# 14-worktree-isolation-tasks.md

## 任务概览

| # | 任务 | 依赖 | 影响文件 |
|---|------|------|---------|
| 1 | 数据模型：SlugValidator + WorktreeChanges + WorktreeSession | 无 | 新建 `worktree/` 包下 3 个文件 |
| 2 | WorktreeSessionStore：持久化 + 全局单例 | 1 | `worktree/WorktreeSessionStore.java` |
| 3 | PostCreationSetup：环境初始化 | 无 | `worktree/PostCreationSetup.java` |
| 4 | WorktreeManager：Worktree 生命周期管理 | 1, 3 | `worktree/WorktreeManager.java` |
| 5 | AgentWorktree：子 Agent 轻量 API | 1, 3 | `worktree/AgentWorktree.java` |
| 6 | StaleCleanup：后台过期清理 | 1, 5 | `worktree/StaleCleanup.java` |
| 7 | EnterWorktreeTool | 1, 4 | `tool/impl/EnterWorktreeTool.java` |
| 8 | ExitWorktreeTool | 1, 2, 4 | `tool/impl/ExitWorktreeTool.java` |
| 9 | AgentTool 集成：isolation 参数 + Worktree 自动创建/清理 | 5 | `subagent/AgentTool.java` |
| 10 | TeamManager 集成：teammate 隔离 | 5, 9 | `teams/TeamManager.java`, `teams/SpawnDispatcher.java` |
| 11 | 配置 + 主流程接入 | 6, 7, 8, 9 | `config/AppConfig.java`, `tui/MewCodeModel.java` |
| 12 | 端到端验证 | 11 | 无新建文件 |

---

## 任务 1：数据模型

**影响文件**：新建 `src/main/java/com/mewcode/worktree/SlugValidator.java`、`WorktreeChanges.java`、`WorktreeSession.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/SlugValidator.java`（完整实现，51 行）
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/WorktreeChanges.java`（完整实现，92 行）
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/WorktreeSession.java`（完整 record，27 行）
- 现有 record 模式见 `src/main/java/com/mewcode/subagent/SubAgentSpec.java`

**工作内容**：

1. 创建 `SlugValidator` 类：
   - 常量 `MAX_LENGTH = 64`
   - 静态 Pattern `VALID_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$")`
   - `validate(String slug)`：四级校验——非空、≤64 字符、按 `/` 分段后每段不能是 `.`/`..`、每段匹配 `VALID_SEGMENT`；任一失败抛 `IllegalArgumentException`
   - `flatten(String slug)`：`slug.replace('/', '+')`
   - `branchName(String slug)`：`"worktree-" + flatten(slug)`

2. 创建 `WorktreeChanges` 类：
   - `record ChangeSummary(int changedFiles, int commits)`
   - `hasChanges(String worktreePath, String headCommit) -> boolean`：调 `git status --porcelain` 和 `git rev-list --count <head>..HEAD`，有变更或 git 失败 → true（fail-closed）
   - `countChanges(String worktreePath, String originalHeadCommit) -> ChangeSummary | null`：详细统计，`originalHeadCommit` 为空或 git 失败 → null
   - 私有 `runGit(String cwd, String... args)`：调 git 命令，30s 超时，exitCode != 0 返回 null

3. 创建 `WorktreeSession` record（Jackson 注解）：
   - 字段：`originalCwd`、`worktreePath`、`worktreeName`、`worktreeBranch`、`originalBranch`、`originalHeadCommit`、`sessionId`、`creationDurationMs`
   - `@JsonIgnoreProperties(ignoreUnknown = true)` + `@JsonProperty` 标注每个字段

**验收**：编译通过；`SlugValidator.validate("a/b")` 不抛异常；`SlugValidator.flatten("a/b")` 返回 `"a+b"`；`WorktreeChanges.hasChanges` 对干净目录返回 false

---

## 任务 2：WorktreeSessionStore

**依赖**：任务 1

**影响文件**：新建 `src/main/java/com/mewcode/worktree/WorktreeSessionStore.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/WorktreeSessionStore.java`（完整实现，61 行）
- 现有 JSON 序列化模式见 `src/main/java/com/mewcode/config/ConfigLoader.java`

**工作内容**：

1. 创建 `WorktreeSessionStore` 类：
   - `static volatile WorktreeSession currentSession`（全局单例）
   - `getCurrentSession()` / `restoreSession(WorktreeSession)` / `clearForTesting()`
   - `save(String repoRoot, WorktreeSession session)`：序列化到 `.mewcode/worktree_session.json`（session 为 null 时删除文件）
   - `load(String repoRoot) -> WorktreeSession`：从文件反序列化，文件不存在或解析失败返回 null
   - `sessionPath(String repoRoot) -> Path`：返回 `.mewcode/worktree_session.json` 路径

**验收**：`save` 后 `load` 返回相同 session；`save(null)` 后文件被删除

---

## 任务 3：PostCreationSetup

**依赖**：无

**影响文件**：新建 `src/main/java/com/mewcode/worktree/PostCreationSetup.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/PostCreationSetup.java`（完整实现，121 行）
- 现有资源复制模式见 `src/main/java/com/mewcode/config/ConfigLoader.java`

**工作内容**：

1. 创建 `PostCreationSetup` 工具类（私有构造器，静态方法）：
   - `perform(String repoRoot, String worktreePath, List<String> symlinkDirs)`：串行调四个步骤

2. `copySettingsLocal(repoRoot, worktreePath)`：
   - 源：`<repoRoot>/.mewcode/settings.local.json`
   - 目标：`<worktreePath>/.mewcode/settings.local.json`
   - 源不存在则跳过；创建目标父目录后 `Files.copy`

3. `configureHooksPath(repoRoot, worktreePath)`：
   - 检测 `<repoRoot>/.husky` 或 `<repoRoot>/.git/hooks` 目录存在
   - 在 worktreePath 下执行 `git config core.hooksPath <hooksPath>`

4. `symlinkDirectories(repoRoot, worktreePath, symlinkDirs)`：
   - 遍历 `symlinkDirs`，每个目录：
     - 跳过含 `..` 的、源不存在的、目标已存在的
     - `Files.createSymbolicLink(dst, src)`（Windows 需权限，best-effort）

5. `copyWorktreeIncludeFiles(repoRoot, worktreePath)`：
   - 读 `<repoRoot>/.worktreeinclude`，不存在则跳过
   - 解析 pattern（跳过 `#` 注释行和空行）
   - 调 `git ls-files --others --ignored --exclude-standard --directory` 获取被忽略文件
   - `matchesAnyPattern(path, patterns)`：精确路径匹配、文件名匹配、目录前缀匹配
   - 匹配到的文件复制到 worktree 对应位置（先 `createDirectories`）

**验收**：有 `.mewcode/settings.local.json` 时 worktree 创建后该文件被复制；`.husky` 存在时 `core.hooksPath` 被配置

---

## 任务 4：WorktreeManager

**依赖**：任务 1、3

**影响文件**：新建 `src/main/java/com/mewcode/worktree/WorktreeManager.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/WorktreeManager.java`（完整实现，247 行）
- 现有 git 操作模式见 `src/main/java/com/mewcode/hook/HookEngine.java`（`ProcessBuilder`）

**工作内容**：

1. `record WorktreeInfo(String path, String branch, Instant createdAt)`

2. 构造器：`projectRoot`、`symlinkDirs`（默认空 List）、`staleCutoffHours`（默认 24）

3. `create(String branch, Path targetDir) -> WorktreeInfo`（synchronized）：
   - 默认目录 `.mewcode/worktrees/<branch>`
   - `git worktree add -B <branch> <wtDir>`（大写 B 重置孤儿分支），超时 60s
   - `PostCreationSetup.perform(projectRoot, wtDir, symlinkDirs)`
   - 记录到 Map，返回 WorktreeInfo

4. `remove(String branch)`（synchronized）：查 Map 得 info → `git worktree remove <path> --force` → 从 Map 移除

5. `list() -> List<WorktreeInfo>`（synchronized）：解析 `git worktree list --porcelain` 输出（`parsePorcelain` 私有方法），回退到 Map

6. `get(String branch) -> Optional<WorktreeInfo>`（synchronized）

7. `cleanupStale(int cutoffHours) -> int`（synchronized）：删除过期 worktree

8. `removeAll()`（synchronized）：清空所有

9. `detectChanges(String worktreePath) -> String`（static）：`git diff --stat`

**验收**：`create("test-branch", null)` 在 `.mewcode/worktrees/test-branch` 创建 worktree；`list()` 返回含该项的列表；`remove("test-branch")` 后目录被清理

---

## 任务 5：AgentWorktree

**依赖**：任务 1、3

**影响文件**：新建 `src/main/java/com/mewcode/worktree/AgentWorktree.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/AgentWorktree.java`（完整实现，122 行）
- 现有 Agent 的 `setWorkDir` 方法见 `src/main/java/com/mewcode/agent/Agent.java` 第 90 行

**工作内容**：

1. `record Result(String worktreePath, String worktreeBranch, String headCommit, String gitRoot)`

2. `create(String slug, String repoRoot, List<String> symlinkDirs) -> Result`：
   - `SlugValidator.validate(slug)`
   - 目录 = `<repoRoot>/.mewcode/worktrees/<flatten(slug)>`
   - 分支名 = `"worktree-" + flatten(slug)`
   - **快速恢复**：`Files.isDirectory(wtPath)` → bump mtime（`Files.setLastModifiedTime`）→ `readHead()` → 返回 Result（不调 git）
   - 否则：`Files.createDirectories(wtPath.getParent())` → `git worktree add -B <branch> <wtPath> HEAD`（超时 60s，环境变量 `GIT_TERMINAL_PROMPT=0`、`GIT_ASKPASS=""`）
   - `PostCreationSetup.perform(repoRoot, wtPath, symlinkDirs)`
   - `readHead()` 返回 HEAD commit hash

3. `remove(String worktreePath, String worktreeBranch, String gitRoot) -> boolean`：
   - `git worktree remove --force <worktreePath>`（超时 30s）
   - `Thread.sleep(100)` → `git branch -D <worktreeBranch>`（超时 30s）
   - 异常返回 false

4. `buildNotice(String parentCwd, String worktreeCwd) -> String`：生成路径说明文本

5. `readHead(String worktreePath) -> String | null`：`git rev-parse HEAD`，超时 10s

**验收**：首次调 `create` 创建 worktree；再次调用同一 slug → 快速恢复不调 `git worktree add`；`remove` 后目录和分支均被清理

---

## 任务 6：StaleCleanup

**依赖**：任务 1、5

**影响文件**：新建 `src/main/java/com/mewcode/worktree/StaleCleanup.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/worktree/StaleCleanup.java`（完整实现，139 行）
- 现有调度模式见 `src/main/java/com/mewcode/session/SessionManager.java`

**工作内容**：

1. 定义临时目录模式列表 `EPHEMERAL_PATTERNS`：
   - `agent-a[0-9a-f]{7}`
   - `wf_[0-9a-f]{8}-[0-9a-f]{3}-\d+`
   - `wf-\d+`
   - `bridge-[A-Za-z0-9_]+(-[A-Za-z0-9_]+)*`
   - `job-[a-zA-Z0-9._-]{1,55}-[0-9a-f]{8}`

2. `isEphemeral(String slug) -> boolean`：匹配任一 pattern

3. `cleanup(String repoRoot, Instant cutoff) -> int`：
   - 扫描 `.mewcode/worktrees/` 目录
   - Layer 1：非临时模式 → 跳过
   - Layer 2：是当前 session 的 worktree → 跳过；最后修改时间晚于 cutoff → 跳过
   - Layer 3：`git status --porcelain -uno` 非空 → 跳过；`git rev-list --max-count=1 HEAD --not --remotes` 非空 → 跳过
   - 通过全部检查 → `AgentWorktree.remove()` → removed 计数 +1
   - 有删除则调 `git worktree prune`

4. `startCleanupLoop(ScheduledExecutorService executor, String repoRoot, int intervalSeconds, int cutoffHours)`：
   - `intervalSeconds <= 0` 则不启动
   - `executor.scheduleAtFixedRate(...)`：计算 cutoff、调 `cleanup`、异常静默捕获

5. `runGitQuiet(String cwd, String... args) -> String | null`：私有辅助，30s 超时，异常返回 null

**验收**：临时 worktree 过期后 `cleanup` 移除它；非临时 worktree 不被移除；当前 session 的 worktree 不被移除；有未提交变更的不被移除

---

## 任务 7：EnterWorktreeTool

**依赖**：任务 1、4

**影响文件**：新建 `src/main/java/com/mewcode/tool/impl/EnterWorktreeTool.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/tool/impl/EnterWorktreeTool.java`（完整实现，96 行）
- 现有 Tool 实现模式见 `src/main/java/com/mewcode/tool/impl/ToolSearchTool.java`
- 现有 Tool 接口见 `src/main/java/com/mewcode/tool/Tool.java`

**工作内容**：

1. 构造器注入 `WorktreeManager` 和 `sessionId`

2. `name()` 返回 `"EnterWorktree"`；`category()` 返回 `COMMAND`；`shouldDefer()` 返回 `true`

3. `schema()`：参数 `name`（string，可选），描述含 "Max 64 chars"

4. `execute(Map<String, Object> args)`：
   - 已有活跃 session → 返回错误 `"Already in a worktree session"`
   - `slug = args.get("name")`；null/blank 时随机生成 `"wt-" + Integer.toHexString(secureRandom.nextInt())`
   - `SlugValidator.validate(slug)` → 失败返回错误
   - `worktreeManager.create(slug, null)` → 失败返回错误
   - 构造 `WorktreeSession`：`originalCwd = System.getProperty("user.dir")`、`worktreePath = info.path()`、`worktreeName = slug`、`worktreeBranch = info.branch()`、`originalBranch = ""`、`originalHeadCommit = ""`、`sessionId`、`creationDurationMs = 0`
   - `WorktreeSessionStore.restoreSession(session)` + `save()`
   - 返回成功信息含路径和分支

**验收**：工具注册后 schema 正确；调用成功返回 worktree 路径；非法 name 返回错误；已有 session 时返回错误

---

## 任务 8：ExitWorktreeTool

**依赖**：任务 1、2、4

**影响文件**：新建 `src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/tool/impl/ExitWorktreeTool.java`（完整实现，126 行）
- 现有 ToolResult 模式见 `src/main/java/com/mewcode/tool/ToolResult.java`

**工作内容**：

1. 构造器注入 `WorktreeManager`

2. `name()` 返回 `"ExitWorktree"`；`category()` 返回 `COMMAND`；`shouldDefer()` 返回 `true`

3. `schema()`：参数 `action`（必填，enum `["keep", "remove"]`）、`discard_changes`（bool，可选）

4. `execute(Map<String, Object> args)`：
   - 无活跃 session → 返回错误 `"No-op: there is no active EnterWorktree session to exit"`
   - `action="remove"` 且 `discard_changes != true`：
     - `WorktreeChanges.countChanges(worktreePath, originalHeadCommit)`
     - 返回 null → 错误 `"Could not verify worktree state"`
     - `changedFiles > 0 || commits > 0` → 错误列变更详情 `"Worktree has X uncommitted file(s) and Y commit(s)"`
   - 清除 session：`restoreSession(null)` + `save(null)`（异常忽略）
   - `action="remove"` → `worktreeManager.remove(worktreeName)` → 返回 `"Exited and removed..."`
   - `action="keep"` → 返回 `"Exited worktree. Your work is preserved at..."`

**验收**：无变更时 `action: remove` 成功删除；有未提交变更时拒绝删除并列出数量；`action: keep` 保留 worktree；无 session 时返回 No-op

---

## 任务 9：AgentTool 集成

**依赖**：任务 5

**影响文件**：修改 `src/main/java/com/mewcode/subagent/AgentTool.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/subagent/AgentTool.java`：
  - 第 55-56 行（`worktreeManager` 字段声明）
  - 第 117-118 行（`setWorktreeManager` setter）
  - 第 195-198 行（schema 中 `isolation` 参数）
  - 第 236 行（参数提取）
  - 第 244 行（teammate 路径传递）
  - 第 267 行（同步路径传递）
  - 第 340-365 行（`runSync` 中 worktree 创建逻辑）
  - 第 418-428 行（worktree 完成后的变更检查与清理）
  - 第 486-500 行（teammate 路径的 worktree 逻辑）
- 现有 `AgentTool` 实现见 `src/main/java/com/mewcode/subagent/AgentTool.java`

**工作内容**：

1. 在 `AgentTool` 中新增字段：`private WorktreeManager worktreeManager` + `setWorktreeManager()` setter

2. `schema()` 中新增 `isolation` 参数：type string，enum `["worktree"]`，描述含 "Isolation mode. 'worktree' creates a temporary git worktree."

3. `execute()` 中提取 `isolation` 参数，传递到 `runSync()` 和 `runAsTeammate()`

4. `runSync()` 中新增 worktree 逻辑（`isolation="worktree"` 且 `worktreeManager != null` 时）：
   - 生成 slug：`"agent-a" + 7 位随机 hex（SecureRandom + HexFormat）`
   - `AgentWorktree.create(slug, projectRoot, symlinkDirs)` → 错误时返回 ToolResult.error
   - `subAgent.setWorkDir(wtResult.worktreePath())`
   - `AgentWorktree.buildNotice(parentCwd, worktreeCwd)` 拼到 prompt 前面
   - LoopComplete 后：`WorktreeChanges.hasChanges(wtPath, headCommit)` → 有变更保留 worktree 并在 tool_result 末尾追加保留信息（路径和分支）→ 无变更调 `AgentWorktree.remove()`

5. `runAsTeammate()` 中新增相同的 worktree 逻辑（teammate 路径也支持隔离）

**验收**：Agent 工具 schema 含 `isolation` 参数；`isolation: "worktree"` 子 Agent 在独立 worktree 执行；无文件变更时 worktree 自动清理；有变更时保留并通知

---

## 任务 10：TeamManager 集成

**依赖**：任务 5、9

**影响文件**：修改 `src/main/java/com/mewcode/teams/TeamManager.java`、`src/main/java/com/mewcode/teams/SpawnDispatcher.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/teams/SpawnDispatcher.java` 第 46 行（`setWorkDir` 调用处）
- 现有 TeamManager 见 `src/main/java/com/mewcode/teams/TeamManager.java`

**工作内容**：

1. TeamManager 接受 `WorktreeManager` 引用（可选注入）

2. `SpawnDispatcher` 在 spawn teammate 时，如果配置了 `isolation: "worktree"` 且 `worktreeManager` 可用，则调用 `AgentWorktree.create()` 并 `member.agent.setWorkDir()`

3. 异常处理：worktree 创建失败时返回错误（不启动 teammate）

**验收**：teammate 的 `isolation: "worktree"` 参数生效，teammate 在隔离目录运行

---

## 任务 11：配置 + 主流程接入

**依赖**：任务 6、7、8、9

**影响文件**：修改 `src/main/java/com/mewcode/config/AppConfig.java`、`src/main/java/com/mewcode/tui/MewCodeModel.java`

**参考资料**：
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/tui/MewCodeModel.java`：
  - 第 439-470 行（worktree 相关初始化）
  - 第 501 行（`agent.setWorkDir(workDir)`）
  - 第 467-468 行（EnterWorktreeTool / ExitWorktreeTool 注册）
- 参考代码 `D:/mewcode-java/java/src/main/java/com/mewcode/config/AppConfig.java`（`@JsonProperty` 注解模式）

**工作内容**：

1. 在 `AppConfig` 中新增 `worktree` 配置节点：
   ```java
   @JsonProperty("worktree")
   private WorktreeConfig worktree = new WorktreeConfig();

   public static class WorktreeConfig {
       @JsonProperty("enabled")
       private boolean enabled = true;
       @JsonProperty("symlink_dirs")
       private List<String> symlinkDirs = List.of();
       @JsonProperty("stale_cleanup_interval_seconds")
       private int staleCleanupIntervalSeconds = 3600;
       @JsonProperty("stale_cutoff_hours")
       private int staleCutoffHours = 24;
   }
   ```

2. 在 `MewCodeModel` 初始化中：
   - 读取 `worktreeConfig`，创建 `WorktreeManager(projectRoot, symlinkDirs, staleCutoffHours)`
   - 注册 `EnterWorktreeTool` 和 `ExitWorktreeTool`
   - 如果从 `WorktreeSessionStore.load()` 恢复了 session，设置 `agent.setWorkDir(session.worktreePath())`
   - `agentTool.setWorktreeManager(worktreeManager)`
   - `teamManager.setWorktreeManager(worktreeManager)`（如果 TeamManager 存在）
   - 启动 `StaleCleanup.startCleanupLoop(scheduler, projectRoot, intervalSeconds, cutoffHours)`（在一个后台 ScheduledExecutorService 上）

3. `worktree.enabled: false` 时，不注册 EnterWorktree/ExitWorktree 工具，`isolation` 参数无效

**验收**：启动后 EnterWorktree/ExitWorktree 工具注册；`agentTool` 的 `worktreeManager` 非 null；后台清理 loop 启动

---

## 任务 12：端到端验证

**依赖**：任务 11

**影响文件**：无新建文件（可能新增 `src/test/` 下的集成测试）

**工作内容**：

1. **EnterWorktree**：启动 MewCode → 调 EnterWorktree（name="test-wt"）→ 成功返回 worktree 路径 → 在 worktree 中新建文件 → ExitWorktree action:keep → 文件在 worktree 路径下可见

2. **EnterWorktree 安全校验**：传 name="../../etc" → 错误含 "must not contain . or .."；传 name 超 64 字符 → 错误含 "64 characters"

3. **ExitWorktree 变更保护**：在 worktree 中修改文件 → ExitWorktree action:remove → 错误含 "uncommitted file(s)" → action:remove + discard_changes:true → 成功删除

4. **子 Agent worktree 隔离**：调 Agent 工具 `isolation: "worktree"` → 子 Agent 在独立 worktree 运行 → 创建测试文件 → 主 Agent 工作目录下无此文件（文件在 worktree 中）

5. **子 Agent 自动清理**：子 Agent 不修改文件 → 完成 → worktree 目录已被删除

6. **子 Agent 变更保留**：子 Agent 修改文件 → 完成 → tool_result 含 "Worktree kept at..." 和路径

7. **快速恢复**：创建 worktree → ExitWorktree（keep）→ 再次 EnterWorktree 同名 → 快速恢复不重建

8. **后台清理**：创建临时 worktree 并 touch 设旧 mtime → 等待或手动触发 cleanup → worktree 被移除

9. **Agent 工具 schema**：`Agent` 工具的 schema 含 `isolation` 参数，enum 为 `["worktree"]`

10. **配置开关**：`worktree.enabled: false` → EnterWorktree 工具不存在 → Agent 工具 `isolation` 参数不生效

**验收**：以上 10 项全部通过
