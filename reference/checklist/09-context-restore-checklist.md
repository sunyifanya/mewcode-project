# checklist.md — Context Restore（上下文恢复与长期记忆）

## A. 项目指令文件

- [ ] **A1** 项目根 `MEWCODE.md` 存在时 `grep -c "MEWCODE.md"` 日志输出 ≥1 条，内容被注入到 system reminder
- [ ] **A2** 三层文件都存在时，最终注入文本中项目根内容出现在最前面、`~/.mewcode/MEWCODE.md` 出现在最后面（`grep` 检查拼接顺序）
- [ ] **A3** 任意一层文件不存在时启动不报错，日志中有"跳过"或 DEBUG 级别信息
- [ ] **A4** `@include` 引用存在的项目内文件，展开后 include 指令所在位置被替换为引用文件内容（原始 `@include` 行消失）
- [ ] **A5** `@include` 嵌套 5 层以内正常展开，第 6 层触发报错：日志含"max depth exceeded"
- [ ] **A6** A 引用 B、B 引用 A（或更长的环），日志含"circular include detected"且不导致无限循环或 StackOverflow
- [ ] **A7** `@include ../../etc/passwd` 类跳出项目目录的路径被拒绝，日志含"path outside project root"
- [ ] **A8** `@include` 指向不存在的文件时，日志含 warning 级别提示、不阻断启动、该 include 被跳过

## B. 会话存档

- [ ] **B1** 新会话启动后 `%PROJECT%/.mewcode/sessions/` 目录下有且仅有一个新增 `.jsonl` 文件
- [ ] **B2** JSONL 文件每行是合法 JSON：`{"role":"user","content":"...","ts":<数字>}`（用 `jq .` 或 JSON 解析器逐行验证）
- [ ] **B3** 会话文件名格式匹配正则 `^\d{8}-\d{6}-[0-9a-f]{4}\.jsonl$`
- [ ] **B4** 发送 3 条消息后，JSONL 文件有 3 行（`wc -l` 返回 3）
- [ ] **B5** 手动在 JSONL 文件末尾插入一行乱码 `{this is not json}`，重启后 `listSessions()` 仍能列出该会话、`loadSession()` 跳过坏行、有效消息数 = 原始正确行数
- [ ] **B6** JSONL 末行被截断（删除最后半行），`loadSession()` 跳过末行、不抛异常
- [ ] **B7** 会话中存在 tool_use 消息但无对应 tool_result，恢复后该 tool_use 及之后的不完整轮次被截断（检查恢复后的消息列表中不存在孤立 tool_use）
- [ ] **B8** 会话最后一条消息 ts 距今 >24h，恢复后在消息开头有一条 role=system、内容含"⚠️"和具体天数的提醒消息
- [ ] **B9** 修改一个 `.jsonl` 文件的最后修改时间为 31 天前，重启后该文件被删除（`ls` 不显示该文件）
- [ ] **B10** 修改一个 `.jsonl` 文件的最后修改时间为 29 天前，重启后该文件保留

## C. 自动记忆

- [ ] **C1** `~/.mewcode/memory/` 目录下存在 `MEMORY.md` 索引文件，`{project}/.mewcode/memory/` 目录下也存在 `MEMORY.md` 索引文件
- [ ] **C2** 每条记忆文件是合法 Markdown：开头为 `---\nname: xxx\ndescription: xxx\nmetadata:\n  type: xxx\n---\n\n正文内容`
- [ ] **C3** 记忆 slug 仅含小写字母、数字、短横线（正则 `^[a-z0-9-]+$`）
- [ ] **C4** `MEMORY.md` 行数 ≤200（`wc -l MEMORY.md`），文件大小 ≤25KB（`ls -l` 或 `stat`）
- [ ] **C5** 创建 5 轮完整对话（每轮以模型不调工具结束），检查日志：第 5 轮结束后出现"memory extraction triggered"日志
- [ ] **C6** 连续 3 轮一问一答（模型直接回复无工具），日志显示只在第 5 轮提取一次（不会每轮都提取）
- [ ] **C7** 提取完成后 `memory/` 目录下至少新增 1 个 `.md` 文件，`MEMORY.md` 索引同步更新
- [ ] **C8** `user` 类型的记忆写入 `~/.mewcode/memory/`，`project` 类型的记忆写入 `{project}/.mewcode/memory/`
- [ ] **C9** 同内容（归一化后文本重叠率 >80%）的记忆再次提取时不产生重复文件
- [ ] **C10** 重启 MewCode 后，新对话的 system reminder 中包含 `MEMORY.md` 索引中的记忆标题（`grep -c "记忆标题"` ≥1）

## D. 端到端验收

- [ ] **D1** 完整流程：创建项目层 `MEWCODE.md`（含 `@include` 引用一个子文件）→ 启动 MewCode → 验证指令注入（检查日志或 system prompt）→ 进行 5 轮对话 → 检查 `.mewcode/sessions/` 下 JSONL 文件完整 → 检查 `.mewcode/memory/` 下有记忆文件和索引 → 重启 MewCode → 验证记忆索引注入 → 新对话中 Agent 的行为体现之前记住的项目知识
- [ ] **D2** 会话恢复流程：进行一次多轮对话 → 记录会话 ID → 退出 MewCode → 重新启动 → 执行 `/resume` → 选择刚才的会话 → 验证历史消息正确显示 → 发送新消息验证可以继续对话
