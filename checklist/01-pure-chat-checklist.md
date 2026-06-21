# MewCode 检查清单

> 每项可勾选、可观测。不写模糊项如「实现完整」「质量良好」。

---

## 编译与结构

- [ ] `mvn clean compile` 零错误、零警告
- [ ] `grep -r "com.mewcode" src/main/java --include="*.java" | wc -l` 返回 ≥ 10（所有类在 com.mewcode 包下）
- [ ] `grep -r "ai.test" src/main/java --include="*.java"` 返回 0（旧包已清理）
- [ ] `grep "maven.compiler.source" pom.xml` 显示 `21`
- [ ] `mvn dependency:tree` 输出包含 `jline`、`jackson-dataformat-yaml`、`okhttp`、`jackson-databind`

---

## 配置文件

- [ ] 启动时不传参数，默认读取当前目录 `./mewcode.yaml`
- [ ] `mewcode.yaml` 不存在 → 输出包含 "配置文件不存在" 或 "config file not found" 字样 + 退出码 1
- [ ] `mewcode.yaml` 缺少 `protocol` 字段 → 输出包含错误信息 + 退出码 1
- [ ] `mewcode.yaml` 缺少 `api_key` 字段 → 输出包含错误信息 + 退出码 1
- [ ] `mewcode.yaml` 中 `protocol: xyz`（非法值）→ 输出包含错误信息 + 退出码 1
- [ ] `mewcode.yaml` 中 `protocol: ANTHROPIC`（大写）→ 正常工作（大小写不敏感）
- [ ] 传参指定配置文件路径 `java -jar mewcode.jar /path/to/config.yaml` → 使用该路径
- [ ] 四个核心字段 `protocol`, `model`, `base_url`, `api_key` 全部可配且被正确读取

---

## 流式显示

- [ ] 发送问题后，回复内容逐 chunk 追加显示到终端，不等全部生成完
- [ ] 使用 Anthropic 后端 + thinking 开启时，思考内容以灰色 ANSI 色（`\033[90m`）+ `[思考] ` 前缀显示
- [ ] 普通回复文本使用终端默认颜色
- [ ] 代码块（三个反引号包裹的内容）原样输出，不额外着色或格式化
- [ ] 每个 chunk 后有 `System.out.flush()` 调用（`grep "flush()" src -r` 确认）

---

## 对话管理

- [ ] 第一轮：「我叫张三」→ 第二轮：「我叫什么？」→ AI 回复中包含 "张三"
- [ ] 第三轮后 `/clear` → 终端显示 "对话已清空" → 第四轮：「我之前叫什么？」→ AI 不知道
- [ ] 空输入（直接回车）→ 不发送请求，回到输入提示符
- [ ] 对话历史中 user 消息 role 为 "user"，assistant 消息 role 为 "assistant"

---

## 基础命令

- [ ] `/exit` → 打印 "Goodbye!" → 程序退出，退出码 0
- [ ] `/clear` → 打印 "对话已清空" → 消息列表清空（只保留 system message）
- [ ] `/xyz`（未知命令）→ 打印 "未知命令: /xyz"
- [ ] Ctrl+C → 程序退出（不打印异常栈）

---

## Provider 切换

- [ ] `protocol: openai` 配置 → 请求发到 OpenAI Chat Completions API → 正常流式回复
- [ ] `protocol: anthropic` 配置 → 请求发到 Anthropic Messages API → 正常流式回复
- [ ] OpenAI 模式下不会出现 `[思考]` 前缀输出（OpenAI 无 extended thinking）

---

## 网络与错误

- [ ] API 返回 401（无效 key）→ 不重试，直接输出错误信息
- [ ] API 返回 429（限流）→ 指数退避重试（1s / 2s / 4s），最多 3 次
- [ ] 网络不可达 → 重试 3 次后输出错误信息
- [ ] API 返回 500 → 指数退避重试最多 3 次
- [ ] `grep "retry\|重试" src -r` 返回 ≥ 2 条匹配（两个 provider 都有重试逻辑）

---

## Anthropic Extended Thinking

- [ ] Anthropic 模式下，请求体 JSON 中包含 `"thinking": {"type": "enabled", "budget_tokens": 16000}`
- [ ] Anthropic 模式下，`system` 字段在请求体的顶层（不在 messages 数组里）
- [ ] `thinking_budget` 未配置时默认使用 16000
- [ ] 流式响应中 `content_block.type == "thinking"` 的 delta 被标记为 THINKING 类型

---

## 上下文窗口

- [ ] `ConversationManager.getEstimatedTokens()` 返回值 = 所有消息字符数 ÷ 3.5
- [ ] 总 token 估算值超过 160000（80% × 200K）时触发关键词提取
- [ ] 关键词提取后，旧消息对被替换为一条 system 消息，格式为 `[Earlier conversation topics: word1, word2, ...]`
- [ ] `grep "stopWords\|停用词" src -r` 确认 KeywordExtractor 有停用词过滤

---

## 端到端验收

- [ ] **完整对话流转**：启动 → 输入 "用 Java 写一个 Hello World" → 看到流式逐 chunk 输出 Java 代码 → 输入 "给上面的代码加上注释" → AI 理解「上面的代码」指刚才生成的 Hello World → 带注释的代码流式输出
- [ ] **跨轮记忆**：启动 → "我的项目叫 MewCode" → 问另一个问题后 → 再问 "我的项目叫什么" → AI 回答 MewCode
- [ ] **清空记忆**：上述对话后 `/clear` → 问 "我的项目叫什么" → AI 表示不知道
- [ ] **切换到 OpenAI**：改 `protocol: openai` + 有效的 OpenAI key → 重启 → 正常流式对话，无 `[思考]` 输出
- [ ] **思考过程可见**：Anthropic 模式下问一个复杂推理题 → 终端中出现灰色 `[思考] xxx` 输出 → 然后出现正常颜色的正式回复
