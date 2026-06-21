# tasks.md — Context Restore（上下文恢复与长期记忆）

## 任务总览

共 11 个任务，按依赖关系排序。推荐执行顺序：A 指令文件 → B 会话存档 → C 自动记忆 → D 接入主流程。

---

## Task 1: 项目指令文件加载 + @include 解析

**影响文件**：新建 `com.mewcode.instructions.InstructionsLoader`、`com.mewcode.instructions.IncludeResolver`

**依赖任务**：无

**参考资料**：
- 参考项目 `MemoryManager.loadInstructions()`（`D:\mewcode-java\java\src\main\java\com\mewcode\memory\MemoryManager.java:244`）
- 当前项目三层文件路径约定见 `spec/09-context-restore-spec.md` 能力 1-4

**工作内容**：
- 实现 `IncludeResolver.resolve(baseDir, content, depth, visited)`：逐行扫描，匹配 `^@include\s+(.+)$`，提取相对路径，规范化后校验在 baseDir 子树内，递归解析（depth + 1，visited 加入当前绝对路径），深度 >5 或发现环路时抛自定义异常
- 实现 `InstructionsLoader.load(workDir)`：按优先级顺序读三个 `MEWCODE.md` 文件（项目根 > `.mewcode/` > `~/.mewcode/`），每个文件先调 IncludeResolver 展开 @include，再拼接到结果 String 中，任意文件不存在静默跳过，include 失败时记录 warning 日志并跳过该 include、继续加载其余内容

---

## Task 2: 会话 JSONL 持久化 + ID 生成

**影响文件**：新建 `com.mewcode.session.SessionManager`

**依赖任务**：无

**参考资料**：
- 参考项目 `SessionManager.saveMessage()`（`D:\mewcode-java\java\src\main\java\com\mewcode\session\SessionManager.java:39`）
- 参考项目 `SessionManager.newId()`（同上 `:33`）

**工作内容**：
- 实现 `newId()`：`yyyyMMdd-HHmmss-` + 4 位随机十六进制（`Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000))` 左补 0）
- 实现 `saveMessage(workDir, sessionId, role, content)`：创建 `.mewcode/sessions/` 目录（已在则跳过），打开 `<id>.jsonl` 文件，追加写 `{"role":"...","content":"...","ts":<epoch_second>}\n`
- 每行 JSON 字段：`role`（String）、`content`（String）、`ts`（long，Unix 秒）
- 默认 `sessionsDir` 在 `{workDir}/.mewcode/sessions/`，允许通过构造函数注入自定义路径便于测试

---

## Task 3: 会话列表 + 元数据扫描

**影响文件**：`com.mewcode.session.SessionManager`（续 Task 2）

**依赖任务**：Task 2

**参考资料**：
- 参考项目 `SessionManager.listSessions()`（`D:\mewcode-java\java\src\main\java\com\mewcode\session\SessionManager.java:86`）
- 参考项目 `SessionInfo` record（同上 `:22`）

**工作内容**：
- 定义 `SessionInfo` record：`id`、`firstMessage`（第一条 role=user 的消息内容，截断到 80 字符）、`messageCount`（有效行数）、`modTime`（文件最后修改时间）
- 实现 `listSessions(workDir)`：遍历 `sessionsDir` 下所有 `.jsonl` 文件 → 读第一行获取首条消息 → 统计非空非坏行总数 → 收集到 List → 按 modTime 倒序排列
- 实现 `loadSession(workDir, sessionId)`：逐行读 JSONL，跳过空行和 JSON 解析失败行，返回 `List<SessionMessage>`
- 注意：不维护单独的 meta 文件，所有元数据都从 JSONL 实时扫描得到

---

## Task 4: 会话恢复异常处理

**影响文件**：新建 `com.mewcode.session.SessionRecovery`

**依赖任务**：Task 3

**参考资料**：
- 参考项目 `Agent.agentLoop()` 中的 error recovery（`D:\mewcode-java\java\src\main\java\com\mewcode\agent\Agent.java:262-283`）
- spec 能力清单第 8 条

**工作内容**：
- 实现 `recover(lines, contextWindow)`，返回 `List<SessionMessage>` 或 `RecoveryResult`（含 cleaned messages + warnings）：
  - **(a) 坏行跳过**：JSON 解析失败或缺少 role/content 字段的行静默跳过，计数器 +1
  - **(b) 工具调用不完整截断**：按顺序扫描，发现 role=tool_use 的行，向后查找配对的 role=tool_result（按 toolUseId 关联），找不到配对则丢弃该 tool_use 及之后所有行
  - **(c) Token 超限压缩**：恢复后总消息数 × 平均消息长度估算 token，超过 contextWindow × 0.8 则返回标记 `needsCompaction=true`，由调用方决定是否先压缩再继续
  - **(d) 时间跨度提醒**：最后一条消息的 ts 距当前时间 >24 小时，在消息列表开头插入一条 role=system 的提示消息："⚠️ 本会话最后一次活动已是 N 天 N 小时前（{lastTs}），模型参数/依赖版本可能已有变化"
- 所有异常路径不抛异常——返回 cleaned messages + warning 列表

---

## Task 5: 会话过期清理 + sessionId 传参

**影响文件**：新建 `com.mewcode.session.SessionCleanup`；修改 `SessionManager`

**依赖任务**：Task 3

**参考资料**：
- spec 能力清单第 9 条

**工作内容**：
- 实现 `SessionCleanup.cleanIfNeeded(sessionsDir, maxAgeDays)`：遍历 `sessionsDir` 下所有 `.jsonl`，`Files.getLastModifiedTime()` 距现在超过 maxAgeDays 天的文件执行 `Files.deleteIfExists()`；默认 maxAgeDays = 30；失败的文件跳过并记录 warning
- 确保 `cleanIfNeeded` 只在启动时被调用一次（MewCode.java 内控制，SessionCleanup 本身不维护"是否已清理"状态）

---

## Task 6: 记忆文件 CRUD + 索引管理

**影响文件**：新建 `com.mewcode.memory.MemoryManager`、`com.mewcode.memory.MemoryEntry`、`com.mewcode.memory.MemoryIndex`

**依赖任务**：无

**参考资料**：
- 参考项目 `MemoryManager`（`D:\mewcode-java\java\src\main\java\com\mewcode\memory\MemoryManager.java`）——注意参考项目用单 JSON 文件，本任务改为分文件 Markdown
- spec 能力清单第 10-12 条

**工作内容**：
- 实现 `MemoryEntry`：frontmatter 解析/序列化，字段 `name`（slug）、`description`（一行摘要）、`metadata.type`（user/feedback/project/reference）、`content`（正文 Markdown）
- 实现 `MemoryManager`：
  - 构造函数接受 `workDir`，确定 `projectMemoryDir`（`{workDir}/.mewcode/memory/`）和 `userMemoryDir`（`~/.mewcode/memory/`）
  - `loadIndex(dir)`：读 `MEMORY.md`，解析为 `List<IndexLine>`（每行格式 `- [Title](file.md) — hook`）
  - `writeIndex(dir, lines)`：写回 `MEMORY.md`，校验行数 ≤200、文件大小 ≤25KB；超出时按修改时间裁剪到最新 200 条
  - `upsertEntry(dir, entry)`：检查同名 slug 是否已存在 → 已存在比较内容（简单归一化后字符重叠率 >80% 跳过）→ 否则写新文件
  - `deleteEntry(dir, slug)`：删除对应 `.md` 文件并从索引移除
  - `getAllMemories()`：合并 project + user 两个目录的索引内容，返回拼接后的 String，用于注入上下文
- MemoryIndex 专注于索引文件的读写和大小控制

---

## Task 7: LLM 异步记忆提取

**影响文件**：新建 `com.mewcode.memory.MemoryExtractor`；修改 `MemoryManager`

**依赖任务**：Task 6

**参考资料**：
- 参考项目 `MemoryManager.extract()`（`D:\mewcode-java\java\src\main\java\com\mewcode\memory\MemoryManager.java:119`）
- 参考项目 `MemoryManager.parseTypedSections()`（同上 `:195`）
- spec 能力清单第 13-15 条

**工作内容**：
- 实现 `MemoryExtractor`：
  - 构造函数接受 `LlmClient` 和 `ExecutorService`（独立线程池，核心线程 1，超时 30s）
  - `extract(conv)`：取 `conv.getMessages()` 全量，构造独立 `ConversationManager`（无系统提示、无工具），addUserMessage 为提取 prompt（参考四类分类格式 `### user` / `### feedback` / `### project` / `### reference`；明确指示"不要输出与已有记忆重复的信息，不确定是否值得记住的宁可跳过"）
  - 调用 `client.stream()` → 收集完整响应文本
  - 调 `MemoryManager.parseTypedSections()` 解析为 `Map<type, content>`
  - 每个 section 的内容按 `\n- ` 或 `\n* ` 拆分为单条 → 每条生成 slug（从内容取前 5-8 个单词做 kebab-case）→ 构造 MemoryEntry → 调 `MemoryManager.upsertEntry()`
  - 提取完成更新索引
- 实现 `MemoryManager.shouldExtract()`：turnCount 自增，返回 `turnCount % extractionInterval == 0`，默认 interval = 5
- 整个 extract 方法 try-catch 包裹，失败只记录 warning 不传播异常

---

## Task 8: 记忆索引注入上下文

**影响文件**：修改 `conversation/ConversationManager.java`

**依赖任务**：Task 6

**参考资料**：
- 参考项目 `ConversationManager.injectLongTermMemory()`（`D:\mewcode-java\java\src\main\java\com\mewcode\conversation\ConversationManager.java:44`）
- spec 能力清单第 16 条

**工作内容**：
- 在 `ConversationManager` 中新增方法 `injectInstructions(String instructionsContent)`：用 `<system-reminder>` 包裹指令文本（含 `# mewcodeMd` 标识头），追加到 conversation history 开头
- 新增 `injectMemoryIndex(String memoryIndexContent)`：用 `<system-reminder>` 包裹记忆索引（含 `# autoMemory` 标识头），追加到 conversation history 开头
- 两个方法合并为一个 `injectLongTermContext(instructions, memoryIndex)` 也可——但需保证幂等（只注入一次，用 boolean flag 控制）
- 注入的消息 role 使用 `user`（与参考项目一致），确保模型将其作为上下文而非对话对待

---

## Task 9: 会话恢复 UI

**影响文件**：修改 `tui/TerminalUI.java`

**依赖任务**：Task 4（恢复逻辑）、Task 5（列表和清理）

**参考资料**：
- 参考项目 `MewCodeModel.handleResumeKey()`（`D:\mewcode-java\java\src\main\java\com\mewcode\tui\MewCodeModel.java:1527`）
- spec 能力清单第 7-9 条

**工作内容**：
- 实现 `/resume` 斜杠命令：调 `SessionManager.listSessions()` 获取会话列表，渲染选择界面（会话 ID、首条消息摘要、消息数、相对时间）
- 实现会话选择交互：上下键/`j``k` 移动光标，输入字符过滤（匹配 ID 或首条消息），Enter 确认，Esc 退出
- Enter 确认后：调 `SessionManager.loadSession()` → `SessionRecovery.recover()` 处理异常 → 将恢复的消息加载到当前 `ConversationManager`（addUserMessage/addAssistantMessage 逐条添加）→ 在聊天区显示"已恢复会话 {id}（{N} 条消息）"
- 如果 recovery 返回 `needsCompaction=true`，先触发压缩再进入对话

---

## Task 10: 接入主流程——启动加载 + Loop 触发

**影响文件**：修改 `agent/AgentLoop.java`、`agent/AgentEvent.java`、`agent/AgentEventType.java`、`MewCode.java`

**依赖任务**：Task 1（指令加载）、Task 5（会话清理）、Task 7（记忆提取）、Task 8（上下文注入）

**参考资料**：
- 参考项目 `initializeProvider()` 中指令和记忆的加载时机（`D:\mewcode-java\java\src\main\java\com\mewcode\tui\MewCodeModel.java:437-501`）
- 参考项目 `agentLoop()` 中 `injectLongTermMemory` 调用位置（`D:\mewcode-java\java\src\main\java\com\mewcode\agent\Agent.java:111`）

**工作内容**：
- `MewCode.java` 启动初始化：在实例化 `Agent` 之前 → `InstructionsLoader.load(workDir)` 加载指令 → `SessionCleanup.cleanIfNeeded()` 清理旧会话 → `MemoryManager` 加载记忆索引 → 传给 `Agent` 供 Agent Loop 使用
- `AgentLoop`：首轮迭代开始前调 `ConversationManager.injectLongTermContext()` 注入指令和记忆索引
- `AgentLoop`：每轮结束（`toolCalls.isEmpty()` 时）调 `MemoryManager.shouldExtract()`，满足条件则 `MemoryExtractor.extract()` 异步提交
- 新增 `AgentEvent.MemoryTick` 事件（模型最终回复无工具调用时推送），`TerminalUI` 可以忽略或显示"记忆更新中"提示

---

## Task 11: 端到端验证

**影响文件**：无（手动验证 + 自动化检查）

**依赖任务**：Task 1-10 全部完成

**工作内容**：
1. 创建测试用 MEWCODE.md（三层各一，含 @include 引用），启动 MewCode，验证指令注入正确
2. 进行多轮对话，验证每条消息写入 JSONL，会话列表正确展示
3. 手动制造坏行（编辑 JSONL 插入一行乱码），验证恢复时跳过
4. 模拟 30+ 天旧会话，启动时验证被清理
5. 进行 5 轮以上对话（每轮以模型不调工具结束），验证异步记忆提取触发，检查 `memory/` 目录下生成 .md 文件和索引
6. 重启 MewCode，验证记忆索引注入上下文，新对话中 Agent 引用旧记忆