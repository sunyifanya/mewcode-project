# tasks.md — Skill 系统（可复用 AI 操作封装与按需加载）

## 任务总览

共 13 个任务，按依赖关系排序。推荐执行顺序：A 数据模型 → B 加载与存储 → C 宿主接口 → D 执行引擎 → E Skill 工具 → F 主流程集成 → G 内置 Skill → H 验证。

---

## Task 1: 创建 SkillCatalog 数据模型

**影响文件**：新建 `com.mewcode.skill.SkillCatalog`

**依赖任务**：无

**参考资料**：
- 参考项目 `SkillCatalog.java:29-44`（SkillMeta/Skill 记录类）
- 参考项目 `SkillCatalog.java:270-319`（metaFromMap 字段映射）
- spec 能力清单第 1-4 条（Skill 定义格式与解析）

**工作内容**：
- 定义 `SkillMeta` 记录类，字段：`name`（String）、`description`（String）、`allowedTools`（List\<String\>）、`mode`（String，`"inline"`/`"fork"`）、`forkContext`（String，`"none"`/`"recent"`/`"full"`）、`model`（String，可为空）、`sourceDir`（Path，可为空）
- 定义 `Skill` 记录类，字段：`meta`（SkillMeta）、`promptBody`（String）、`bodyLoaded`（boolean）。提供 `withBody(String)` 方法返回新的 Skill 实例
- 内部维护 `Map<String, Skill> skills`（LinkedHashMap 保持插入序）和 `Map<String, String> sources`（记录来来源 tier）
- 实现 `register(Skill, String source)`、`get(String name)`、`list()`（返回 meta 列表，不返回 body）、`source(String name)` 方法
- `getFull(String name)` 方法：sourceDir 非空则从磁盘重读（热更新），sourceDir 为空则直接返回缓存的 Skill
- 暂不实现三级加载和解析逻辑（Task 2）

---

## Task 2: 实现 SkillCatalog 三级加载与解析

**影响文件**：修改 `com.mewcode.skill.SkillCatalog`（续 Task 1）

**依赖任务**：Task 1

**参考资料**：
- 参考项目 `SkillCatalog.java:113-164`（loadCatalog / loadTier）
- 参考项目 `SkillCatalog.java:190-268`（loadSkill / parseSkillMD / metaFromMap）
- spec 能力清单第 5-7 条（三级存放与覆盖）、第 3-4 条（解析容错）

**工作内容**：
- 引入 SnakeYAML 依赖（`org.yaml:snakeyaml`）用于解析 YAML frontmatter
- 实现 `parseSkillMD(Path dir, String content)` 静态方法：
  - 提取 `---` 包裹的 YAML frontmatter → `metaFromMap(map, dir)`
  - 提取 `---` 后的 Markdown 正文 → promptBody
  - YAML 解析异常 → 记录日志，返回 null（单文件跳过）
  - name 缺失时取目录名（空格换 `-`，转小写）
  - description 缺失时取正文第一个非空非标题行
  - `mode` 默认 `"inline"`，`forkContext` 默认 `"none"`
- 实现 `loadSkill(Path dir)` 静态方法：
  - 策略 1：`skill.yaml` + `prompt.md` 分离格式
  - 策略 2：单个 `SKILL.md`（YAML frontmatter + Markdown 正文）
  - 两种策略都不满足 → 返回 null（跳过）
- 实现 `loadTier(Path dir, String source)` 私有方法：
  - 列出 `dir` 下所有子目录 → 每个子目录 `loadSkill()` → 成功则 `register()`
  - 目录不存在或不可读 → 静默跳过
  - 单个 Skill 异常（IOException）→ 记录日志，跳过
- 实现 `loadCatalog(String workDir)` 静态工厂方法：
  - Tier 1: builtin（从 classpath `builtin-skills/` 加载）→ `loadTier()`
  - Tier 2: `~/.mewcode/skills/` → `loadTier()`
  - Tier 3: `<workDir>/.mewcode/skills/` → `loadTier()`
  - 返回合并后的 SkillCatalog
- 实现 `reload(String workDir)`：重建 catalog 替换内部状态

---

## Task 3: 实现 SkillCatalog 上下文构建

**影响文件**：修改 `com.mewcode.skill.SkillCatalog`（续 Task 2）

**依赖任务**：Task 2

**参考资料**：
- 参考项目 `SkillCatalog.java:168-186`（buildActiveContext）
- spec 能力清单第 8 条（可用 Skill 列表）、第 19 条（多 Skill 同时激活）

**工作内容**：
- 实现 `buildAvailableSkillsList()`：遍历所有 Skill，输出格式如 `- <name>: <description>`，每个 Skill 一行。供 system prompt 的 available_skills section 使用
- 实现 `buildActiveContext(Set<String> activeSkillNames)`：
  - 遍历 activeSkillNames，按名称自然排序
  - 每条 Skill 输出 `### <name>\n<promptBody>\n\n`
  - activeSkillNames 为空或 null → 返回空字符串
  - activeSkillNames 中某个 name 在 catalog 中不存在 → 跳过，不报错
- 两者区别：`buildAvailableSkillsList` 用于 Phase 1（只含 name+description），`buildActiveContext` 用于 Phase 2（含完整 SOP 正文）

---

## Task 4: 创建 SkillHost 和 SkillForkHost 接口

**影响文件**：新建 `com.mewcode.skill.SkillHost`、新建 `com.mewcode.skill.SkillForkHost`

**依赖任务**：Task 1（需要 SkillMeta 等类型）

**参考资料**：
- 参考项目 `SkillHost.java:18-32`（接口定义）
- 参考项目 `SkillForkHost.java:18-24`（接口定义）
- spec 能力清单第 17-18 条（inline/fork 执行模式）

**工作内容**：
- `SkillHost` 接口定义三个方法：
  - `void activateSkill(String name, String body)` — Agent 记录激活的 Skill SOP
  - `void setToolFilter(Predicate<String> filter)` — 设置工具名过滤器
  - `ToolRegistry toolRegistry()` — 返回当前 ToolRegistry 引用
  - `default void recordSkillInvocation(String name, String body) {}` — 记录调用，供 compact 恢复用（默认空实现）
- `SkillForkHost` 接口（extends SkillHost）新增两个方法：
  - `String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model)` — 启动独立子 agent，返回最终文本
  - `List<Message> snapshotParentMessages()` — 快照当前对话消息列表
- 两个接口均不 import agent 包，避免循环依赖

---

## Task 5: 创建 SkillExecutor 执行引擎

**影响文件**：新建 `com.mewcode.skill.SkillExecutor`

**依赖任务**：Task 4（SkillHost/SkillForkHost 接口）、Task 1（Skill/SkillMeta 类型）

**参考资料**：
- 参考项目 `SkillExecutor.java:19-95`（完整实现）
- spec 能力清单第 13-14 条（工具白名单校验）、第 17-18 条（inline/fork 执行）

**工作内容**：
- 实现 `static String executeInline(Skill skill, String args, SkillHost host)`：
  - `assertAllowedToolsExist(skill, host.toolRegistry())` → 白名单校验（失败抛 IllegalStateException）
  - `substituteArguments(skill.promptBody(), args)` → 参数替换
  - `host.activateSkill(skill.meta().name(), body)` → 记录激活
  - `host.recordSkillInvocation(skill.meta().name(), body)` → compact 恢复用
  - allowedTools 非空 → `host.setToolFilter(allowed::contains)`
  - allowedTools 为空 → `host.setToolFilter(null)`
  - 返回渲染后的 body
- 实现 `static String executeFork(Skill skill, String args, SkillForkHost host)`：
  - `assertAllowedToolsExist(skill, host.toolRegistry())`
  - `substituteArguments(skill.promptBody(), args)`
  - `host.recordSkillInvocation(skill.meta().name(), skill.promptBody())`
  - `buildForkSeed(mode, parentMessages)` → 构建种子历史
  - `host.runSubAgent(body, seed, skill.meta().allowedTools(), skill.meta().model())` → 启动子 agent
  - 模型回退与警告由 host 的 `runSubAgent()` 实现方处理
  - 返回子 agent 的最终文本
- 实现私有方法 `substituteArguments(String body, String args)`：
  - body 含 `$ARGUMENTS` → 全部替换
  - body 不含 → 追加 `\n\n## User Request\n\n` + args
  - args 为 null/blank → 不替换，直接返回 body
- 实现私有方法 `buildForkSeed(String mode, List<Message> parent)`：
  - `"none"` → 空列表
  - `"recent"` → 最近 5 条消息（不足 5 条则全部）
  - `"full"` → 完整复制父历史
- 实现私有方法 `assertAllowedToolsExist(Skill skill, ToolRegistry registry)`：
  - 遍历 allowedTools，每个工具名调用 `registry.get(name)`
  - 任一不存在 → 抛出 IllegalStateException（含 skill 名和缺失工具名）

---

## Task 6: 创建 SkillTool 内置工具

**影响文件**：新建 `com.mewcode.skill.SkillTool`

**依赖任务**：Task 5（SkillExecutor）、Task 2（SkillCatalog）

**参考资料**：
- 参考项目 `Tool.java:11-61`（当前项目的 Tool 接口）
- spec 能力清单第 11-12 条（Skill 工具定义和行为）

**工作内容**：
- 实现 `Tool` 接口的 `SkillTool` 类：
  - `getName()` → `"Skill"`
  - `getDescription()` → `"Activate a skill to get specialized instructions and tools. Use when the user asks you to do something that matches a skill's description. Skills available: ..."`
  - `category()` → `ToolCategory.COMMAND`（确保不受权限限制）
  - `shouldDefer()` → `false`（始终可见，不延迟加载）
  - `isReadOnly()` → `false`（inline 模式只读但 fork 模式会写）
- `getParametersSchema()` 返回：
  ```json
  {
    "name": "Skill",
    "description": "...",
    "input_schema": {
      "type": "object",
      "properties": {
        "skill": {"type": "string", "description": "The skill name to activate"},
        "args": {"type": "string", "description": "Optional arguments for the skill"}
      },
      "required": ["skill"]
    }
  }
  ```
- `execute(Map<String, Object> params)`：
  - 提取 `skill` 参数（必填，否则返回错误）
  - 提取 `args` 参数（选填）
  - 调用 `catalog.getFull(skill)` → 不存在返回错误文本
  - 判断 mode → `"fork"` → `SkillExecutor.executeFork(skill, args, forkHost)`
  - 否则 → `SkillExecutor.executeInline(skill, args, host)`
  - 返回 ToolResult.success/failure
- 构造函数接收：`SkillCatalog catalog`、`SkillHost host`、`SkillForkHost forkHost`
- 注意：Skill 工具不受当前工具白名单限制——在 AgentLoop 的 toolFilter 路径中特殊处理 Skill 工具名（始终允许通过）

---

## Task 7: 创建 PromptSections 的 Skills 区域

**影响文件**：新建/修改 `com.mewcode.prompt.PromptSections`（或独立方法在 SkillCatalog 中）

**依赖任务**：Task 3（SkillCatalog.buildActiveContext）

**参考资料**：
- 参考项目 `PromptBuilder.java:122-125`（skillSection 注入方式）
- spec 能力清单第 8 条（两阶段加载）、第 17 条（Active Skills 区域位置）

**工作内容**：
- 在 `PromptBuilder` 中新增 `availableSkills` 字段
- 新增 `setAvailableSkills(String skillsList)` 方法
- 在 `build()` 方法中，将 availableSkills section 放在 priority 85（介于 UsingTools (40) 和 Environment (100) 之间）
- 新增 `setActiveSkills(String activeContext)` 方法，inject 到 priority 90 的 `## Active Skills` section
- 在 `buildSystemPrompt()` 便利方法中支持传入 skills sections
- Phase 1 注入内容格式：
  ```
  ## Available Skills
  
  The following skills are available. Use the Skill tool to load one when you need specialized instructions.
  
  - review: 代码审查——检查当前变动的逻辑错误、安全问题、性能问题
  - commit: 生成规范的 Conventional Commits 消息
  - test: 分析代码并运行/生成测试
  ```
- Phase 2 注入内容格式：
  ```
  ## Active Skills
  
  ### review
  <完整的 SKILL.md 正文>
  
  ### test
  <完整的 SKILL.md 正文>
  ```

---

## Task 8: 在 AgentLoop 中实现 SkillHost/SkillForkHost

**影响文件**：修改 `com.mewcode.agent.AgentLoop`

**依赖任务**：Task 4（接口定义）、Task 5（SkillExecutor）

**参考资料**：
- 当前项目 `AgentLoop.java:46-80`（构造函数和状态字段）
- 参考项目 `Agent.java:93-95`（setToolNameFilter + predicates）
- 参考项目 `MewCodeModel.java:1021-1058`（PROMPT 命令执行逻辑，含 skill 识别）
- spec 能力清单第 14-18 条（白名单、inline、fork）

**工作内容**：
- `AgentLoop` 声明 `implements SkillHost, SkillForkHost`
- 新增成员：
  - `Map<String, String> activeSkills = new LinkedHashMap<>()` — name → body
  - `Predicate<String> skillToolFilter` — 当前有效的工具名过滤器（null = 不限）
- 实现 `activateSkill(String name, String body)`：`activeSkills.put(name, body)`
- 实现 `setToolFilter(Predicate<String> filter)`：`this.skillToolFilter = filter`
- 实现 `toolRegistry()`：return `this.toolRegistry`
- 实现 `recordSkillInvocation(String name, String body)`：存入 `recoveryState`（供 compact 恢复）
- 实现 `snapshotParentMessages()`：返回 `conversation.getMessages()` 的不可变快照
- 实现 `runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model)`：
  - 创建新的 ConversationManager（seed 非空则预填）
  - 添加 system prompt（含 Skill SOP）
  - 创建新的 AgentLoop 实例（独立迭代）
  - 设置 allowedTools → toolFilter（缩小工具范围）
  - 处理 model 指定 → 支持则用，不支持回退默认模型并打印 system warning
  - 运行子 agent loop 直到完成
  - 返回子 agent 的最终助理消息文本
- 修改 `agentLoop()` 方法中的工具过滤逻辑（`iterToolSchemas` 计算时）：
  - 计算 `effectiveFilter`：`toolNameFilter` AND `skillToolFilter`（两者都需通过）
  - **例外**：`"Skill"` 工具名始终通过 skillToolFilter（Skill 工具本身不受白名单限制）
- 新增 `getActiveSkills()` getter 供 PromptBuilder 使用
- 新增 `clearActiveSkills()` 方法（`/clear` 调用时触发）：清空 activeSkills + 重置 skillToolFilter

---

## Task 9: Skill → 斜杠命令自动注册

**影响文件**：修改 `com.mewcode.command.CommandRegistry`

**依赖任务**：Task 2（SkillCatalog 可用）

**参考资料**：
- 参考项目 `MewCodeModel.java:589-611`（wireSkillsToAgent / registerSkillCommand 完整逻辑）
- 当前项目 `CommandRegistry.java`（register 方法签名）
- 当前项目 `CommandContext.java:15-26`（现有字段）
- spec 能力清单第 20-22 条（斜杠命令自动注册）

**工作内容**：
- 在 `CommandContext` 中新增字段 `skillList: Supplier<List<String>>`
- 在 `CommandRegistry` 中新增方法 `registerSkillCommands(SkillCatalog catalog, SkillHost skillHost)`：
  - 遍历 `catalog.list()`（即所有 Skill 的 meta）
  - 对每个 Skill：检查 `cmdRegistry.find(name).isPresent()` — 已存在则跳过（内置命令优先），记录日志
  - 不存在则 `register(new Command(name, description + " [skill]", new String[]{}, CommandType.PROMPT, false), handler)`
  - handler 函数：执行 Phase 2 加载 + SkillExecutor.executeInline 或 executeFork → 返回渲染后的 SOP 文本（或 fork 摘要）
- 注册时使用 `catalog.getFull(name)` 确保热更新生效
- fork 模式的 Skill 在 PROMPT 处理器中也走 fork 路径（需要 SkillForkHost）

---

## Task 10: 调整 MewCode 初始化顺序（MCP 先 → Skill 后）

**影响文件**：修改 `com.mewcode.MewCode`

**依赖任务**：Task 8（AgentLoop 实现 SkillHost）、Task 9（CommandRegistry 支持 Skill 命令）

**参考资料**：
- 参考项目 `MewCodeModel.java:525-578`（MCP 异步连接 + Skill 同步加载的当前顺序）
- spec 能力清单第 14 条（MCP 工具校验时机）

**工作内容**：
- 修改初始化顺序：当前为 MCP 异步连接 → Skill 同步加载 → 改为 **MCP 同步完成连接 → Skill 加载**
- 具体变更：
  1. 构建 ToolRegistry.createDefault()
  2. 连接 MCP 服务器（等待 `connectAll()` 完成）
  3. 注册 MCP 工具到 ToolRegistry
  4. `SkillCatalog.loadCatalog(workDir)` → Phase 1 加载
  5. 构建 PromptBuilder → 注入 `availableSkills` section
  6. `CommandRegistry.registerSkillCommands(catalog, agentLoop)`
  7. 启动 AgentLoop
- MCP 连接如果失败（网络超时等），Skill 加载继续进行——只是 MCP 工具不会在 ToolRegistry 中。Skill 的 allowed_tools 若引用了未连接的 MCP 工具 → 激活时报错（行为正确）
- `AgentLoop` 构造时传入 SkillCatalog 引用（或通过 setter）

---

## Task 11: 创建三条内置 Skill 文件

**影响文件**：新建 `resources/builtin-skills/commit/SKILL.md`、`resources/builtin-skills/review/SKILL.md`、`resources/builtin-skills/test/SKILL.md`

**依赖任务**：Task 10（主流程集成完成）

**参考资料**：
- 参考项目 `frontend-design/SKILL.md`（SKILL.md 格式样例）
- spec 能力清单第 26-28 条（三个内置 Skill 的行为定义）

**工作内容**：
- **commit/SKILL.md**：
  ```yaml
  name: commit
  description: Generate a Conventional Commits message from the current git diff. Use when asked to create a commit message, commit changes, or write a commit.
  allowed_tools: [Bash, Read, Grep]
  mode: inline
  ```
  正文：引导模型先 `git diff` / `git diff --staged` 查看变更 → 分析变更类型（feat/fix/docs/chore 等）→ 生成 Conventional Commits 格式消息 → 可选 `git add` + `git commit`

- **review/SKILL.md**：
  ```yaml
  name: review
  description: Review current code changes for bugs, security issues, and code quality. Use when asked to review code, audit changes, or check a PR.
  allowed_tools: [Read, Grep, Glob, Bash]
  mode: inline
  ```
  正文：引导模型审查 git diff → 关注逻辑错误、安全漏洞、性能问题、代码风格 → 按严重程度输出审查结果

- **test/SKILL.md**：
  ```yaml
  name: test
  description: Analyze code and generate or run tests. Use when asked to write tests, run tests, check test coverage, or verify code works correctly.
  allowed_tools: [Read, Grep, Glob, Write, Edit, Bash]
  mode: fork
  fork_context: none
  ```
  正文：引导模型分析源码 → 确定测试策略 → 生成测试代码 → 运行测试 → 报告结果

- 确保 builtin skills 被打包进 JAR（配置 gradle 的 `sourceSets` 或 `processResources`）

---

## Task 12: 在 AgentLoop 中接入 activeSkills 到 system prompt 重建

**影响文件**：修改 `com.mewcode.agent.AgentLoop`

**依赖任务**：Task 7（PromptSections skills 区域）、Task 8（AgentLoop 实现 SkillHost）

**参考资料**：
- spec 能力清单第 17 条（每轮重建环境上下文时 Active Skills 始终可见）

**工作内容**：
- 在 `agentLoop()` 方法的每次迭代开始前（`iterToolSchemas` 计算之后），将 activeSkills 注入 conversation：
  - 遍历 `activeSkills` → 拼接 `## Active Skills\n\n### <name>\n<body>\n\n`
  - 以 `conv.addSystemReminder(skillsSection)` 注入
- 系统提醒在每轮自动过期（LLM 只看到当轮的），但 `activeSkills` Map 持久存在，所以每轮都会重新注入
- 确保 `updateSystemPrompt()` 或等效方法也包含 activeSkills（如果 system prompt 每次重建而非仅首次构建）

---

## Task 13: 端到端验证

**影响文件**：无（手动验证 + 自动化检查）

**依赖任务**：Task 1-12 全部完成

**工作内容**：
1. 启动 MewCode，检查启动日志——Phase 1 加载无报错，available_skills 列表正确
2. 输入 `/skills`（如已注册），验证三个内置 Skill 出现在列表中
3. 输入 `/review`，验证 Skill SOP 出现在 system prompt 的 Active Skills 区域，工具限制为 `[Read, Grep, Glob, Bash]`
4. 在对话中说"帮我写个 commit message"，验证模型调用 Skill 工具加载 commit Skill
5. 输入 `/test`，验证 fork 模式启动独立子 agent，子 agent 输出回流主对话
6. 输入 `/clear`，验证 Active Skills 区域清空，工具过滤器恢复全部
7. 在 `~/.mewcode/skills/` 和 `.mewcode/skills/` 各放一个同名 Skill，验证 project 覆盖 user
8. 故意写一个 YAML 语法错误的 SKILL.md，验证启动不崩溃、日志中报告解析失败
9. 修改 SKILL.md 正文后再次 `/skillname`，验证热更新生效（新正文出现）
10. Skill 的 `allowed_tools` 中声明不存在的工具名 → 激活时报错但不崩溃
11. fork 模式指定不支持的 model → 回退到默认模型 + 输出 system 警告
12. 输入 `/help` 验证三个 Skill 命令出现在命令列表中（带 `[skill]` 标记）
13. Tab 补全 `/r` 验证同时出现 `/review` 和 `/resume`
