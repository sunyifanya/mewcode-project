# spec.md — Context Restore（上下文恢复与长期记忆）

## 背景

当前 MewCode 每次启动都是"一张白纸"——Agent 不记得上次聊了什么、不知道项目技术栈和规范、不了解用户偏好。用户每次新会话都要重新介绍背景。参考项目已实现了基础的三层指令文件加载、JSONL 会话存档和五轮间隔自动记忆提取，但指令文件缺少 @include 机制，会话恢复缺少异常处理，记忆格式是单 JSON 而非分文件 frontmatter Markdown。

本 spec 描述将这三套机制完整迁移到 MewCode 并增强的设计。

## 目标用户

使用 MewCode 在多个项目中频繁切换、处理长周期任务的开发者。典型场景：
- "昨天改了一半的 bug，今天打开继续"——会话恢复后从上次断点接着聊
- "这个项目用的是 Java 17 + Maven，代码规范有特殊约定"——打开项目自动加载项目指令
- "每次我都要强调禁止用 Lombok，能不能记住？"——Agent 记住用户偏好，下次自动遵守

## 能力清单

### A. 项目指令文件

1. **三层指令加载**：启动时按优先级从高到低依次读取三个位置的 `MEWCODE.md` 文件——项目根目录、项目 `.mewcode/` 目录、用户 `~/.mewcode/` 目录；任意一层文件不存在静默跳过，三层都不存在则无指令注入。

2. **优先级覆盖**：高优先级（项目根）内容拼在低优先级前面，模型先读到先遵循；同名配置项以高优先级为准（因为模型先读到高优先级指令，后续低优先级同名指令自然被覆盖）。

3. **@include 引用**：指令文件中支持 `@include path/to/file.md` 语法引用其他文件（行首出现、后跟项目内相对路径）。引用文件内容直接内联到 include 指令所在位置。

4. **@include 安全保障**：嵌套深度上限 5 层；用 visited 集合（已访问文件的绝对路径）检测循环引用，发现环路立即中断并报错提示；路径规范化后校验必须在项目根目录范围内，拒绝 `../` 跳出项目目录的路径。

### B. 会话存档

5. **JSONL 追加持久化**：每个会话一个 JSONL 文件存放在 `.mewcode/sessions/`。每行一条完整 JSON 消息（`{"role":"user","content":"...","ts":1234567890}`），以 `\n` 结尾。文件追加写（`StandardOpenOption.APPEND`），不维护单独 meta 文件。

6. **会话 ID 防撞车**：ID 格式 `yyyyMMdd-HHmmss-xxxx`，xxxx 为 4 位随机十六进制，同秒内同一目录碰撞概率可忽略。

7. **会话列表与元数据**：列出所有会话时不读 meta 文件——直接扫描 JSONL 文件，从文件名提取 ID，读第一条用户消息作为标题，统计总行数（减去空行和坏行）作为消息数，取文件修改时间作为最后活跃时间。

8. **会话恢复异常处理**：加载 JSONL 时依次处理四类异常——(a) 单行 JSON 解析失败则跳过该行继续读后续行；(b) 检测到 tool_use 消息后没有对应的 tool_result 则丢弃该 tool_use 及之后的不完整轮次，截断到最近安全点；(c) 恢复后总消息数超 token 安全阈值（上下文窗口 80%）则先触发一次 Layer 2 压缩再继续；(d) 最后一条消息距今超过 24 小时则在恢复的消息列表开头插入一条时间跨度提醒（"⚠️ 本会话最后一次活动已是 N 天前"）。

9. **过期会话清理**：启动时检查 `.mewcode/sessions/`，删除最后修改时间超过 30 天的 JSONL 文件。清理只在启动时执行一次，运行期间不重复清理。

### C. 自动记忆

10. **四类记忆**：每条记忆标记为四种类型之一——`user`（用户偏好和背景，跨项目共享）、`feedback`（用户纠正和确认的做法，跨项目共享）、`project`（当前项目技术栈/约定/截止日期，跟项目走）、`reference`（当前项目的外部资源链接，跟项目走）。user/feedback 存 `~/.mewcode/memory/`，project/reference 存 `{project}/.mewcode/memory/`。

11. **每条记忆一个 Markdown 文件**：文件名即记忆 slug（短横线连接），文件带 YAML frontmatter（name、description、metadata.type、metadata.tags），正文为记忆内容。正文中可用 `[[other-slug]]` 链接到其他记忆。

12. **记忆索引文件**：每个 memory 目录下一份 `MEMORY.md`，每行一条 `- [Title](file.md) — hook`。索引在构建请求上下文时读入，相当于 Agent 已经"读过"所有记忆的大纲。索引严格控制在 200 行 / 25KB 以内；超出时按最近修改时间保留最新 200 条。

13. **LLM 异步提取**：Agent Loop 每轮结束后检查 (a) 是否有工具调用（有则不提取——只有"模型最终回复无工具调用"才算一轮完整结束）、(b) 距上次提取是否已满 5 轮。满足条件则异步提交提取任务（扔到线程池 fire-and-forget），不阻塞主循环。

14. **提取 Prompt 设计**：从当前 ConversationManager 取全量消息，构造独立 ConversationManager（无系统提示、无工具），让 LLM 提取值得跨会话记住的事实，按四类分类输出（`### user` / `### feedback` / `### project` / `### reference`），明确指示"不输出任何已有记忆中的重复信息"。

15. **去重策略**：提取结果拿到后，先按 slug（从内容生成或 LLM 提供）去重——同名已存在则比较内容，内容相似（编辑距离或简单文本重叠率 >80%）则跳过；否则更新。不做语义向量检索去重（那属于 RAG 范畴，本次不做）。

16. **记忆注入**：每次处理用户新请求前，将索引文件内容作为 `<system-reminder>` 注入上下文最前面（在所有 conversation 消息之前），确保模型先"回忆"再回答。

## 非功能要求

- 指令文件加载在启动时完成，典型耗时 <50ms（三个文件 + 少量 @include）
- JSONL 写入单行 <1ms（本地文件系统追加写）
- 会话列表扫描：100 个会话文件、每个 100 行以内 <500ms
- 异步记忆提取不阻塞 Agent Loop（独立线程池，超时 30s）
- @include 解析失败不阻断启动——记录警告并跳过该 include
- 所有文件 IO 使用 try-catch best-effort 模式，磁盘错误不导致 Agent 崩溃
- 30 天清理阈值和 5 轮提取间隔可通过 mewcode.yaml 配置

## 设计骨架

```
com.mewcode.instructions/                       ← 新包
├── InstructionsLoader                           ← 公共入口: load(workDir) → String
└── IncludeResolver                              ← @include 解析: resolve(baseDir, content, depth, visited) → String

com.mewcode.session/                             ← 新包（当前项目中不存在）
├── SessionManager                               ← JSONL 持久化 + sessionId 生成 + 列表/搜索
├── SessionRecovery                              ← 恢复异常处理: recover(lines, contextWindow) → List<Message>
└── SessionCleanup                               ← 过期清理: cleanIfNeeded(sessionsDir, maxAgeDays)

com.mewcode.memory/                              ← 新包（当前项目中不存在）
├── MemoryManager                                ← 入口: 加载索引、CRUD 记忆文件、注入上下文
├── MemoryExtractor                              ← 异步提取: extract(client, conv) → 提交线程池
├── MemoryEntry                                  ← Frontmatter 数据类（name, description, type, content）
└── MemoryIndex                                  ← 索引读写: read/update/validate size cap

修改:
├── agent/AgentLoop.java                         ← 每轮结束判断 shouldExtract()，推送 MemoryTick 事件
├── agent/AgentEvent.java                        ← +MemoryTick 事件子类型
├── agent/AgentEventType.java                    ← +MEMORY_TICK 枚举
├── conversation/ConversationManager.java         ← +injectInstructions() +injectMemoryIndex() 方法
├── conversation/Message.java                     ← 支持 system-reminder role（如果没有的话）
├── config/AppConfig.java                         ← +maxSessionAgeDays +extractionInterval 字段
├── config/ConfigLoader.java                      ← 新增字段默认值校验
├── tui/TerminalUI.java                           ← 会话恢复选择界面 + /memory 斜杠命令
└── MewCode.java                                  ← 启动时初始化 InstructionsLoader + MemoryManager + SessionCleanup
```

### 关键数据流

```
MewCode 启动:
  1. InstructionsLoader.load(workDir) → 拼接三层 MEWCODE.md（含 @include 解析）
  2. MemoryManager 加载 project + user 记忆索引（MEMORY.md）
  3. SessionCleanup.cleanIfNeeded() → 删除 30+ 天旧会话

新会话开始（initializeProvider）:
  4. ConversationManager.injectInstructions(instructionsContent)
     → 注入 <system-reminder> 包裹的指令
  5. ConversationManager.injectMemoryIndex(memoryIndexContent)
     → 注入 <system-reminder> 包裹的记忆索引

Agent Loop 每轮结束:
  6. 判断 toolCalls.isEmpty() && shouldExtract()
     → 是：提交 MemoryExtractor.extract(client, conv) 到线程池
     → 否：跳过

用户请求会话恢复:
  7. TerminalUI 调用 SessionManager 列出会话
  8. 用户选择 → SessionRecovery.recover() 处理异常
  9. 恢复的消息加载到 ConversationManager，进入正常对话循环
```

## Out of Scope

- 向量数据库和 RAG 语义检索（记忆去重用简单文本比较，不做 embedding）
- 团队记忆同步（多用户共享记忆）
- 跨设备记忆同步（iCloud / S3 / 自建服务）
- 记忆的版本历史与回滚（Git 级别的 history）
- 指令文件中条件/环境变量替换（`@if` 等高级语法）
- 会话导出/导入（与其他工具互操作）
- 会话搜索全文索引（仅做标题 + ID 简单匹配）
- 记忆提取的质量机器学习优化（Prompt 调优留到后续）
- 记忆文件的并发写入锁（单进程场景无需分布式锁）