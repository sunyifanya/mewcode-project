# checklist.md — Skill 系统（可复用 AI 操作封装与按需加载）

## A. Skill 文件解析

- [ ] **A1** 在 `.mewcode/skills/example/` 下放置一个合法的 SKILL.md（含完整 YAML frontmatter），启动后 `grep -r "example"` 日志能搜到 "Loaded skill: example" 或等效信息
- [ ] **A2** YAML frontmatter 中 `name` 缺失时，skill 名称自动取目录名（空格换 `-`、转小写），`grep` 日志确认名称正确
- [ ] **A3** YAML frontmatter 中 `description` 缺失时，自动取正文第一个非空非标题行，`grep` 日志确认 description 不为空
- [ ] **A4** `mode` 字段不写时默认 `"inline"`，Skill 激活后走 inline 路径（不启动子 agent）
- [ ] **A5** `fork_context` 字段不写时默认 `"none"`，fork 模式不携带父对话历史
- [ ] **A6** 故意写一个 YAML 语法错误的 SKILL.md（如 `---` 内不是合法 YAML），启动不崩溃、日志中可搜到 "skip" 或 "failed to parse" 类字样
- [ ] **A7** 一个只有 SKILL.md 无 frontmatter（第一行非 `---`）的 skill 目录，整篇正文当 body、name 取目录名、description 取首行

## B. 三级加载与覆盖

- [ ] **B1** `~/.mewcode/skills/` 目录不存在时启动不报错、不崩溃
- [ ] **B2** `.mewcode/skills/` 目录不存在时启动不报错、不崩溃
- [ ] **B3** `~/.mewcode/skills/foo/` 和 `.mewcode/skills/foo/` 各放一个同名 Skill（description 不同），启动后 `grep` 验证 project 的 description 生效（覆盖 user）
- [ ] **B4** 三级都为空时（无任何 Skill 目录），`grep "Available Skills"` 在 system prompt 中返回空列表或 "No skills" 提示
- [ ] **B5** 单个 Skill 目录存在但无 SKILL.md 且无 skill.yaml → 跳过不加载，其他 Skill 正常
- [ ] **B6** project 的 skill 解析失败时，不覆盖 user 的同名 skill（user 版本保留），日志中有两个事件的痕迹

## C. 两阶段加载

- [ ] **C1** 启动后 `grep "Available Skills"` system prompt 区域存在，且每个 Skill 只显示 name + description，不含完整 SOP 正文
- [ ] **C2** Phase 1 加载后模型的工具列表不变化（Skill 未激活时 allowed_tools 不生效）
- [ ] **C3** 用户输入 `/skillname` 后，`grep "Active Skills"` system prompt 出现完整 SOP 正文，含 skill 名称标题
- [ ] **C4** Phase 2 加载后 `grep` 工具列表确认只有 allowed_tools 中的工具（如果声明了白名单）
- [ ] **C5** 修改磁盘上的 SKILL.md 正文后再次 `/skillname`，`grep` 确认新正文出现（热更新生效）
- [ ] **C6** 磁盘上 SKILL.md 被删除后再次 `/skillname`，显示错误提示但不崩溃，Phase 1 状态不变

## D. Skill 工具（模型主动调用）

- [ ] **D1** 模型的工具列表中始终包含 `Skill` 工具（不受任何 allowed_tools 白名单影响）
- [ ] **D2** 在对话中说 "帮我审查代码"，模型调用 Skill 工具（skill="review"），工具返回 "Skill 'review' activated" 后 system prompt 出现 Active Skills 区域
- [ ] **D3** 模型调用 Skill 工具时传 `args` 参数（如 `args="focus on SQL injection"`），SOP 正文中 `$ARGUMENTS` 被替换为 args
- [ ] **D4** 模型调用 Skill 工具传入不存在的 skill 名称，工具返回错误文本，system prompt 不出现 Active Skills 变化
- [ ] **D5** Skill 工具的描述文本中列出了所有 Phase 1 可用的 skill 名称和说明

## E. 工具白名单

- [ ] **E1** `allowed_tools: [Read, Grep]` 的 Skill 激活后，模型只能看到 Read 和 Grep 工具（`grep` 工具列表不含 Write、Edit、Bash 等）
- [ ] **E2** `allowed_tools: [NonExistentTool]` 的 Skill 激活时抛出异常（含 "NonExistentTool" 和 skill 名），程序不崩溃
- [ ] **E3** Skill 的 `allowed_tools` 为空数组 `[]` 或不写 `allowed_tools` 字段时，工具过滤器重置为 null（不限工具）
- [ ] **E4** MCP 连接完成后加载的 Skill 带 `mcp__` 前缀的白名单工具 → 校验通过，不报 "not registered"
- [ ] **E5** 多个 Skill 先后激活，最后激活的 Skill 的白名单生效（非并集），`grep` 工具列表确认只有最后一个 Skill 声明的工具
- [ ] **E6** `Skill` 工具名始终在工具列表中，即使当前激活的 Skill 的白名单不包含 `Skill`

## F. Inline 执行模式

- [ ] **F1** `mode: inline` 的 Skill 激活后，用户的后续对话中模型遵循 SOP 指令（如 commit Skill → 模型生成 Conventional Commits 格式消息）
- [ ] **F2** Inline Skill 激活后的所有对话轮次保留在主历史中（`grep` 会话 JSONL 确认 Skill 相关对话在主文件中）
- [ ] **F3** Inline Skill 的 SOP 正文在每轮 system prompt 的 `## Active Skills` 区域中始终可见（≥2 轮对话后仍存在）
- [ ] **F4** Inline Skill 激活后不产生新的子 agent 进程（`ps` 或资源管理器确认进程数不变）

## G. Fork 执行模式

- [ ] **G1** `mode: fork` 的 Skill 激活后，启动独立子 agent（`grep` 日志确认 "fork" 或 "sub-agent" 字样）
- [ ] **G2** `fork_context: none` 时子 agent 的对话历史为空，`grep` 子 agent 的 conversation 确认无父历史消息
- [ ] **G3** `fork_context: recent` 时子 agent 携带最近 5 条父对话消息，`grep` 确认消息数 ≤5
- [ ] **G4** `fork_context: full` 时子 agent 携带全部父对话消息，`grep` 确认消息逐条相同
- [ ] **G5** Fork 子 agent 完成后，其最终输出文本回流主对话，作为一条 assistant 消息显示
- [ ] **G6** Skill 指定了 `model` 且当前 provider 支持该模型 → 子 agent 使用指定模型
- [ ] **G7** Skill 指定了 `model` 但当前 provider 不支持 → 子 agent 回退到默认模型，输出 "Model 'X' not available, falling back to 'Y'" system 警告

## H. 斜杠命令注册

- [ ] **H1** 启动后 `/help` 输出中包含三个内置 Skill（commit/review/test），每条后面标注 `[skill]`
- [ ] **H2** 输入 `/review` 激活 review Skill，效果与模型调用 Skill 工具一致（SOP 出现在 Active Skills 区域）
- [ ] **H3** 输入 `/review focus on XSS` → SOP 中 `$ARGUMENTS` 被替换为 `focus on XSS`
- [ ] **H4** Tab 补全 `/c` 同时出现 `/commit`（skill 命令）和 `/compact`（内置命令）
- [ ] **H5** 内置命令（如 `/help`、`/status`）与 skill 同名时，内置命令优先，skill 版本不覆盖，日志中有警告

## I. 生命周期

- [ ] **I1** 输入 `/clear` 后 `grep` system prompt 确认 `## Active Skills` 区域为空
- [ ] **I2** `/clear` 后工具过滤器恢复为全部工具可用（`grep` 工具列表确认 Write、Edit、Bash 等均可见）
- [ ] **I3** `/clear` 后 Phase 1 的 `## Available Skills` 列表仍存在（不需重启即可再次激活）
- [ ] **I4** 程序退出后重启，之前激活的 Skill 的 Phase 2 状态不保留（Active Skills 区域恢复为空）、仅 Phase 1 状态重建

## J. 目录型 Skill

- [ ] **J1** 目录型 Skill（如 `skill-creator/` 含 SKILL.md + references/ + agents/ 子文件）可正常加载，SKILL.md 作为入口文件被解析
- [ ] **J2** Skill 正文中引用 `references/schemas.md`，模型读取该文件的行为可用
- [ ] **J3** 目录下 `scripts/` 中的脚本可通过 Bash 工具执行（Skill 正文给出调用路径）

## K. 错误容错

- [ ] **K1** 三个 tier 目录都不可读（权限错误）→ 启动不崩溃，`grep` Phase 1 输出为 "No skills installed" 或等效
- [ ] **K2** `SKILL.md` 文件存在但为 0 字节 → 跳过该 Skill，日志记录 "empty file" 或等效
- [ ] **K3** `SKILL.md` 含 frontmatter `---` 但无结束 `---`（未闭合）→ 全文视为正文（无元信息），name 取目录名、description 取首行
- [ ] **K4** `allowed_tools` 字段写的是非字符串列表（如 `allowed_tools: "Read"` 写成字符串而非数组）→ 解析为单元素列表或跳过，不抛异常崩溃

## L. 端到端验收

- [ ] **L1** 完整流程：启动 MewCode → 验证启动日志中 Skill Phase 1 加载成功 → 输入 `/help` 确认 commit/review/test 出现在命令列表 → 输入 `/review` → 验证 `## Active Skills` 出现 review SOP 正文 → 验证工具列表缩小到 `[Read, Grep, Glob, Bash]` → 输入 `/clear` → 验证 Active Skills 清空 → 验证工具列表恢复 → 对话中说 "帮我写个 commit message" → 模型自动调用 Skill 工具加载 commit → 验证 commit SOP 出现在 Active Skills → 确认模型生成了 Conventional Commits 格式消息
- [ ] **L2** Fork 流程：输入 `/test` → 验证启动子 agent → 验证子 agent 无父历史（fork_context: none）→ 验证子 agent 输出回流主对话 → 验证主对话后续轮次可引用子 agent 输出
- [ ] **L3** 覆盖流程：在 project 和 user 目录各放一个同名 Skill（不同 description）→ 启动 → 验证 project 的 description 生效 → 删除 project 的 Skill 目录 → 重启 → 验证 user 的 description 生效（fallback）
- [ ] **L4** 热更新流程：激活 review Skill → 记下当前 SOP 正文 → 不退出、修改 SKILL.md → 输入 `/review` → 验证新 SOP 正文出现
- [ ] **L5** 白名单流程：激活 commit Skill（allowed_tools=[Bash,Read,Grep]）→ 模型尝试调用 Write → 工具调用被拦截/Write 不在可用工具列表中 → 模型收到 "tool not available" 错误
