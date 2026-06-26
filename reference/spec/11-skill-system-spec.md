# spec.md — Skill 系统（可复用 AI 操作封装与按需加载）

## 背景

当前 MewCode 中，用户要重复使用同一套提示词或工作流，只能手动粘贴、或用 `/review` 这类写死处理函数的斜杠命令。前者低效，后者每加一种操作就要改 CommandRegistry 并重新编译。

参考项目（mewcode-java）已实现一套基础的 SkillCatalog / SkillExecutor / SkillHost 骨架，支持从 `.mewcode/skills/` 目录加载 Skill 文件并注册为 PROMPT 斜杠命令。但缺少：Skill 工具（让模型按需主动加载）、fork 模式实际接入、MCP 工具校验、内置样板 Skill。

本 spec 描述将 Skill 系统完整迁移到 MewCode 并增强的设计。

## 目标用户

使用 MewCode 的开发者，希望将常用 AI 操作（审查代码、生成 commit message、运行测试、前端设计等）封装成可复用的 Skill 文件，通过两种路径激活：
- 主动打 `/skillname` 斜杠命令
- 对话中描述需求，模型自行判断并调用 Skill 工具加载

典型场景：
- 输入 `/review` 激活代码审查 Skill，工具白名单收窄为只读，给出审查结果
- 用户说"帮我写个 commit message"，模型调用 Skill 工具加载 commit Skill，生成规范提交信息
- 用户说"帮我把这个页面改得更好看"，模型加载 frontend-design Skill，获得设计指南后动手
- 用户 fork 模式下运行 test Skill，独立子 agent 跑完测试后摘要回流主对话

## 能力清单

### A. Skill 定义格式与解析

1. **单文件 Skill**：一个 `.md` 文件，以 YAML frontmatter（`---` 包裹）开头放元信息，其后为 Markdown 正文（SOP 指令）。正文支持 `$ARGUMENTS` 占位符，激活时替换为用户传入的参数。

2. **目录型 Skill**：一个目录，内含入口 SKILL.md 和可选的 `scripts/`（可执行脚本）、`references/`（参考文档，按需读取）、`assets/`（模板/图标等资产）。目录作为一个整体能力包分发。

3. **YAML frontmatter 字段**：
   - `name` — 唯一标识（小写，不含空格，不含 `/` 前缀），用于斜杠命令名和 Skill 工具的参数。缺失时取目录名（空格换 `-`，转小写）
   - `description` — 一句话说明，出现在 `available_skills` 列表和 `/help` 命令输出，是触发 Skill 的主要信号。缺失时取正文第一个非空非标题行
   - `allowed_tools` — 可见工具白名单（字符串数组）。激活时收窄当前可用工具列表为此集合。省略或为空表示不限制
   - `mode` — 执行模式，`inline`（共享当前对话）或 `fork`（开独立对话，摘要回流）。省略时默认 `inline`
   - `fork_context` — fork 模式下带多少父历史：`none`（空历史）、`recent`（最近 5 条消息）、`full`（全部历史）。省略时默认 `none`。仅在 `mode: fork` 时生效
   - `model` — 可选的模型指定。fork 模式下子 agent 使用此模型；当前 provider 不支持时回退到默认模型并输出 system 警告

4. **解析容错**：单个 Skill 文件 YAML 解析失败 → 跳过该 Skill（记录日志），不阻断其他 Skill 加载。目录存在但无 SKILL.md → 视为无效 Skill 目录，跳过。

### B. 三级存放与同名覆盖

5. **三级路径**：builtin（随 JAR 发布的内置 Skill，从 classpath 加载）→ user global（`~/.mewcode/skills/`）→ project（`<workspace>/.mewcode/skills/`）。加载顺序由低到高，后加载的同名 Skill 完全替换先加载的（不是字段合并）。

6. **覆盖粒度**：仅按 `name` 字段判断同名。project 的同名 Skill 覆盖 user 的同名 Skill；user 的同名 Skill 覆盖 builtin。修改一个 Skill 不影响其他。

7. **部分失败容忍**：三级路径中任一目录不存在或不可读 → 跳过该级，不影响其他。单目录内单个 Skill 解析失败 → 跳过该 Skill，其他正常加载。低优先级 tier 加载成功的 Skill 被高优先级 tier 成功覆盖时生效覆盖；高优先级 tier 加载失败（解析错/目录为空）则低优先级 tier 的版本保留。

### C. 两阶段加载

8. **Phase 1（启动期）**：只解析 frontmatter，提取 `name` 和 `description`。将（name, description）列表注入 system prompt 的 `available_skills` 区域。同时每条 Skill 自动注册为 PROMPT 型斜杠命令（命令名 = skill name，描述附 `[skill]` 标记）。Phase 1 不加载正文，不校验 allowed_tools。

9. **Phase 2（激活期）**：两种触发路径——①用户输入 `/skillname` 斜杠命令（PROMPT 处理器），②模型调用内置 `Skill` 工具。触发后重新从磁盘读取完整 SKILL.md（热更新），解析正文和完整 frontmatter，执行 allowed_tools 校验（全部通过才激活），渲染 SOP 正文（替换参数），注入 system prompt 的 `## Active Skills` 区域。

10. **热更新**：每次 Phase 2 触发时从磁盘重读文件，修改 SKILL.md 后下次激活立即生效，无需重启。读写失败时保留上次缓存的正文。

### D. Skill 工具（内置工具，系统级）

11. **Skill 工具定义**：一个内置 Tool，name=`Skill`，category=SYSTEM，不受 allowed_tools 白名单约束（始终可用）。参数：`skill`（Skill 名称，必填）和 `args`（传给 Skill 的参数，选填）。

12. **Skill 工具行为**：接收 skill 名称和参数 → 执行 Phase 2 加载 → 校验 allowed_tools → 按 mode 分发到 SkillExecutor。返回激活确认文本（inline 模式返回 SOP 渲染后的前 200 字符摘要；fork 模式返回子 agent 的最终输出）。

### E. 工具白名单

13. **白名单元数据**：Skill 的 `allowed_tools` 字段声明激活后模型可见的工具名列表（精确匹配，大小写敏感）。白名单为空或未声明 → 不限制，维持当前工具过滤器。

14. **启动期校验**：Skill 激活时对白名单中每个工具名调用 `ToolRegistry.get(name)`；任一不存在则抛出异常，Skill 激活失败。由于 MCP 先于 Skill 加载完成（见初始化顺序），MCP 工具在此时已注册，校验可以正常通过。

15. **白名单叠加**：多个 Skill 同时激活时，工具过滤器为最后激活的 Skill 的白名单（后激活覆盖前激活）。Skill 退出时恢复上一个 Skill 的过滤器，或清空过滤器（如果无其他激活 Skill）。

16. **目录型 Skill 专属工具**：目录型 Skill 下 `scripts/` 目录中的脚本不作为独立 Tool 注册进全局 ToolRegistry，而是通过 Bash 工具执行。Skill 正文中应给出脚本的用途和调用方式。

### F. 两种执行模式

17. **Inline 模式**：
    - Skill SOP 渲染后注入 system prompt 的 `## Active Skills` 区域（priority 90，仅次于 Identity/System/DoingTasks/ExecutingActions/UsingTools/ToneStyle/Environment）
    - 应用 allowed_tools 白名单收窄工具
    - 后续对话轮次中模型始终可见此 SOP，直到 Skill 被停用或对话清空
    - 模型产生的所有输出留在主对话历史中

18. **Fork 模式**：
    - 独立创建子 agent，种子历史由 `fork_context` 决定（none: 空；recent: 最近 5 条消息；full: 完整复制）
    - 子 agent 使用 Skill 指定的模型（不支持时回退到默认模型 + system 警告）
    - 子 agent 只看到 allowed_tools 白名单中的工具
    - 子 agent 跑完后，其最终输出文本作为 fork 摘要回流主对话
    - 子 agent 有独立的迭代上限（等于主 agent 配置，或 Skill 可配置）

19. **多 Skill 同时激活**：允许多个 Skill 同时 inline 激活。它们的 SOP 按 Skill 名称自然排序拼接在 `## Active Skills` 区域。最后一个激活的 Skill 的工具白名单生效。

### G. 斜杠命令自动注册

20. **命令注册**：Phase 1 加载后，每条 Skill 自动在 CommandRegistry 注册为 PROMPT 命令，命令名 = skill.name，描述附 `[skill]` 后缀。注册前检查冲突——若已有同名命令（内置命令如 `/help`、`/status`），Skill 不覆盖内置命令，记录警告日志。

21. **PROMPT 执行**：用户输入 `/skillname [args]` → CommandRegistry 查找命令 → PROMPT 处理器触发 Phase 2 加载 → 渲染后的 SOP 作为用户消息注入对话 → LLM 按 SOP 执行。参数 `args` 替换 SOP 中的 `$ARGUMENTS` 或追加到末尾。

22. **Tab 补全**：Skill 命令参与 `/` 开头的 Tab 补全，与内置命令一起出现在补全菜单。

### H. 生命周期与清理

23. **清空对话清理**：`/clear` 命令清空 ConversationManager 时，同时清空已激活的 Skill 列表和工具白名单过滤器，`## Active Skills` 区域变为空。Phase 1 的 `available_skills` 列表不受影响（下次激活仍可用）。

24. **去激活**：尚无显式 `/deactivate` 命令（留待后续）。Skill 激活后在整个对话生命周期中保持，除非 `/clear` 清空或程序退出。

25. **会话恢复**：会话恢复后，Phase 1 的 `available_skills` 列表自动重建。但已激活的 Skill（Phase 2 状态）不自动恢复——用户需重新 `/skillname` 或等待模型调用 Skill 工具。

### I. 内置样板 Skill

26. **commit Skill**（`mode: inline`）：引导模型根据当前 git diff 生成规范的 commit message。遵循 Conventional Commits 格式。工具限制：`Bash`（仅 `git diff`/`git status`/`git log` 类只读 git 命令）、`Read`、`Grep`。

27. **review Skill**（`mode: inline`）：引导模型审查当前代码变更。关注逻辑错误、安全问题、性能问题、代码风格。工具限制：`Read`、`Grep`、`Glob`、`Bash`（只读命令）。

28. **test Skill**（`mode: fork`）：在独立子 agent 中分析代码并生成/运行测试。不带父历史（`fork_context: none`）。工具限制：`Read`、`Grep`、`Glob`、`Write`、`Edit`、`Bash`。

## 非功能要求

- Phase 1 加载（三级遍历 + frontmatter 解析）典型耗时 <100ms（10 个 Skill 目录场景）
- Phase 2 加载（重读文件 + 校验 + 渲染）典型耗时 <50ms
- Skill 工具从接受到激活完成的端到端延迟 <100ms（不含 fork 模式的子 agent 运行时间）
- 单 Skill 定义文件建议 <500 行 Markdown（超大 Skill 应拆分为 references）
- 所有 Skill 加载/激活错误不导致程序崩溃——以文本或系统消息形式反馈给用户
- Skill 目录和文件的文件系统监听不做（留给后续）——当前仅靠 Phase 2 每次重读实现热更新

## 设计骨架

```
com.mewcode.skill/                                  ← 新包
├── SkillCatalog                                    ← Skill 数据记录、三级加载、Phase1/2、热更新、ActiveContext 构建
├── SkillExecutor                                   ← inline/fork 执行、参数替换、种子历史构建
├── SkillHost (interface)                           ← Agent 状态切片（activateSkill、setToolFilter、toolRegistry）
├── SkillForkHost (interface, extends SkillHost)    ← fork 专用（runSubAgent、snapshotParentMessages）
└── SkillTool (implements Tool)                     ← 内置工具（Skill 按需加载），category=SYSTEM

修改:
├── agent/AgentLoop.java                            ← implements SkillHost + SkillForkHost，新增 activeSkills 管理、工具过滤器、子 agent 启动
├── prompt/PromptBuilder.java                       ← 新增 ## Active Skills section (priority 90)
├── command/CommandRegistry.java                    ← 新增 registerSkillCommands() 从 SkillCatalog 自动注册
├── command/CommandContext.java                      ← 新增 skillList: Supplier<List<String>>
├── MewCode.java                                    ← 调整 init 顺序（MCP 先连 → Skill 后加载）
├── tool/ToolCategory.java                           ← 新增 SYSTEM category（如不存在）
└── .mewcode/skills/ (resources/builtin-skills/)    ← 三条内置 Skill
    ├── commit/SKILL.md
    ├── review/SKILL.md
    └── test/SKILL.md
```

### 关键数据流

```
Phase 1 — 启动加载:
  MewCode.start() → MCP 连接完成 → SkillCatalog.loadCatalog(workDir)
  → 三级遍历 builtin/user/project
  → 每个 Skill 只解析 frontmatter（name + description）
  → 注入 system prompt 的 available_skills 区域
  → CommandRegistry.registerSkillCommands()（PROMPT 型，带冲突检测）
  → Agent Loop 正常启动

Phase 2 — Skill 激活（斜杠命令路径）:
  用户输入 /review
  → CommandRegistry.find("review") → PROMPT 处理器
  → SkillCatalog.getFull("review") → 重新读盘
  → SkillExecutor.executeInline(skill, args, agentLoop)
  → a) 渲染 SOP（替换 $ARGUMENTS）
  → b) 校验 allowed_tools（assertAllowedToolsExist）
  → c) agentLoop.activateSkill(name, body)
  → d) agentLoop.setToolFilter(allowed::contains)
  → 渲染后的 SOP 作为用户消息注入 conversation
  → LLM 按 SOP 执行

Phase 2 — Skill 激活（Skill 工具路径）:
  LLM 在对话中调用 Skill 工具（skill="review", args="focus on security"）
  → SkillTool.execute() → SkillExecutor 同上流程
  → 返回 "Skill 'review' activated (inline mode)" 给 LLM
  → LLM 在下一轮中参照 System Prompt 中的 Active Skills SOP 执行

Fork 模式:
  SkillTool 或斜杠命令触发 mode=fork 的 Skill
  → SkillExecutor.executeFork(skill, args, agentLoop)
  → a) 渲染 SOP
  → b) assertAllowedToolsExist
  → c) buildForkSeed(fork_context, parentMessages)
  → d) agentLoop.runSubAgent(body, seed, allowedTools, model)
  → 子 agent 独立运行（独立 ConversationManager、独立 AgentLoop）
  → 子 agent 完成后文本输出回流主对话

清空对话:
  /clear → ConversationManager 重置 + AgentLoop 清空 activeSkills
  → toolFilter 重置为 null（无白名单限制）
  → 下次 system prompt 重建时 ## Active Skills 区域为空
```

## Out of Scope

- Skill 的市场分发和版本管理（留给后续章节）
- 显式的 `/deactivate` 或 `/skill-off` 命令
- 文件系统监听自动热更新（当前靠 Phase 2 每次读盘）
- Skill 的依赖声明（一个 Skill 依赖另一个 Skill）
- Skill 打包/导出为 `.skill` 文件（留给分发章节）
- Skill 的模型级命中率优化（description trigger accuracy tuning）
- 会话恢复时自动恢复已激活 Skill 的 SOP 正文（恢复后仅 Phase 1 状态，需重新激活）
- fork 模式子 agent 的迭代上限独立配置（当前复用主 agent 配置）
- Skill 间的冲突检测（两个 Skill 声明矛盾的指令时的自动裁决）
