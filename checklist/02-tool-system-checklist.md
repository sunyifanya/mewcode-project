# MewCode 工具系统 — 检查清单

> 每项可勾选、可观测。不写模糊项如「实现完整」「质量良好」。

---

## 编译与结构

- [ ] `mvn clean compile` 零错误、零警告
- [ ] `find src/main/java -name "*.java" | wc -l` 返回 ≥ 18（新增 tool 包 8 个类 + 原有 10 个）
- [ ] `grep -r "implements Tool" src/main/java/com/mewcode/tool/impl --include="*.java" -l | wc -l` 返回 6（6 个工具实现）
- [ ] `mvn dependency:tree` 输出不包含新增依赖（全部用标准库 + 已有 Jackson/OkHttp）

---

## 配置扩展

- [ ] `mewcode.yaml` 中包含 `tool.timeout_seconds` 和 `tool.working_directory` 配置项
- [ ] 不配置 `tool.timeout_seconds` 时默认取 30 秒
- [ ] 不配置 `tool.working_directory` 时默认取 `"."`
- [ ] `tool.timeout_seconds: 0` → 程序将其重置为默认 30（`grep "timeoutSeconds.*30" src -r` 确认）

---

## Tool 接口 + 注册中心

- [ ] `Tool` 接口包含 4 个方法：`getName()`、`getDescription()`、`getParametersSchema()`、`execute(Map)`
- [ ] `ToolResult` 三个字段：`success`（boolean）、`content`（String）、`errorCode`（String，可为 null）
- [ ] `ToolRegistry.register(Tool)` 注册后，`ToolRegistry.get("read_file")` 返回非 null 实例
- [ ] `ToolRegistry.get("nonexistent")` 返回 null
- [ ] `ToolRegistry.toApiFormat()` 返回的 List 长度 = 已注册工具数
- [ ] `ToolRegistry.toApiFormat()` 返回的每个元素包含 key：`name`、`description`、`input_schema`

---

## ChunkType + Message 扩展

- [ ] `ChunkType` 枚举包含 4 个值：`THINKING`、`TEXT`、`ERROR`、`TOOL_CALL`
- [ ] `Message` 类有 `getToolCalls()` 和 `getToolResult()` 方法
- [ ] 普通文本 Message 的 `getToolCalls()` 返回 null（向后兼容）
- [ ] `ConversationManager.addToolCallMessage(...)` 调用后，`getMessages()` 包含该消息
- [ ] `ConversationManager.addToolResultMessage(...)` 调用后，`getMessages()` 包含该消息

---

## 读文件工具 (read_file)

- [ ] 读取 `pom.xml` → 返回带行号的文本内容，格式为 `     1\t<project ...`
- [ ] 读取不存在的文件 → `ToolResult(success=false, errorCode="FILE_NOT_FOUND", content 包含路径)`
- [ ] 读取超过 2000 行的文件 → 截断，末尾包含 `"... (截断，文件共 N 行)"`
- [ ] `file_path` 参数用 `/` 分隔，Windows 下也能正确解析

---

## 写文件工具 (write_file)

- [ ] 写入 `test-output/hello.txt` 内容为 `"Hello World"` → 文件被创建，父目录自动创建，返回写入字节数
- [ ] 覆盖已存在文件 → 旧内容被替换，返回新写入字节数
- [ ] 写入到只读目录 → `ToolResult(success=false, errorCode="IO_ERROR")`
- [ ] 写完后手动 `cat test-output/hello.txt` 看到 `Hello World`

---

## 改文件工具 (edit_file)

- [ ] `old_string` 在文件中唯一出现 → 替换为 `new_string`，返回 `success=true` + 替换行号
- [ ] `old_string` 在文件中零次出现 → `success=false`，`errorCode="MATCH_NOT_FOUND"`，content 中包含文件内容片段（前后各 5 行）
- [ ] `old_string` 在文件中出现 ≥2 次 → `success=false`，`errorCode="MULTIPLE_MATCHES"`，content 中包含所有匹配位置的行号和片段
- [ ] `old_string` = `"a"`，`new_string` = `"b"` → 只替换一次（精确匹配，非 replaceAll）
- [ ] 缩进/空白不一致导致匹配失败 → 返回 `MATCH_NOT_FOUND`
- [ ] 替换含特殊字符（如 `{`、`\`、`"`）的字符串 → 正常工作（不做正则转义）

---

## 按模式找文件工具 (glob)

- [ ] `pattern: "**/*.java"` → 返回项目下所有 .java 文件路径列表
- [ ] `pattern: "src/**/*.java"` → 只返回 src/ 下的 .java 文件
- [ ] `pattern: "*.md"` → 返回根目录下的 .md 文件
- [ ] 结果按文件修改时间降序排列（最近修改的在前）
- [ ] 无匹配文件 → `ToolResult(success=true, content="未找到匹配文件" 或空列表)`
- [ ] 命中数超过 500 → 截断并注明

---

## 搜代码内容工具 (grep)

- [ ] `pattern: "main"`, `path: "src/"` → 返回所有包含 main 的行及其文件路径和行号
- [ ] `pattern: "class\s+\w+"`, `glob: "*.java"` → 只搜索 .java 文件
- [ ] 输出格式为 `{path}:{line}: {content}`，如 `src/main/java/com/mewcode/MewCode.java:18: public static void main`
- [ ] 无效正则（如 `[unclosed`）→ `ToolResult(success=false, errorCode="INVALID_PARAMS"）`
- [ ] 无匹配 → `ToolResult(success=true, content="无匹配" 或空)`
- [ ] 结果超过 250 条 → 截断，末尾注明 `"... (截断，共 N 条匹配)"`

---

## 执行命令工具 (execute_command)

- [ ] `command: "echo hello"` → 返回 `stdout 含 "hello"`，退出码 0
- [ ] `command: "ls src/"`（Unix）或 `"dir src\"`（Windows）→ 返回目录列表
- [ ] 命令超时（如 `sleep 60` + 配置超时 2 秒）→ `ToolResult(success=false, errorCode="TIMEOUT"）`
- [ ] 黑名单命中 `rm -rf /` → `success=false`，`errorCode="BLACKLISTED"`，content 描述被拒绝
- [ ] 黑名单命中 `shutdown` → 同上拒绝
- [ ] 黑名单命中 `dd if=/dev/zero of=/dev/sda` → 同上拒绝
- [ ] 黑名单命中 `mkfs.ext4 /dev/sda1` → 同上拒绝
- [ ] 黑名单命中 `:(){ :|:& };:`（fork 炸弹）→ 同上拒绝
- [ ] `command: "invalid_command_xyz"` → `success=false` 或退出码非零，stderr 有错误信息
- [ ] Windows 下命令通过 `cmd /c` 执行，Unix 下通过 `/bin/sh -c` 执行

---

## AnthropicProvider 工具调用

- [ ] 请求体 JSON 中包含 `"tools": [...]` 数组，内容来自 `ToolRegistry.toApiFormat()`
- [ ] 普通文本消息的 `content` 仍为字符串（不因 tools 存在而改变格式）
- [ ] 含 `tool_use` 的消息 `content` 序列化为数组，包含 `{"type": "text", ...}` 和 `{"type": "tool_use", ...}` 块
- [ ] 含 `tool_result` 的消息 `content` 序列化为数组，包含 `{"type": "tool_result", "tool_use_id": "...", "content": "..."}` 块
- [ ] SSE 流中 `content_block_start(type=tool_use)` → 开始累积 JSON 分片
- [ ] SSE 流中 `content_block_delta(type=input_json_delta)` → 拼接 `partial_json`
- [ ] SSE 流中 `content_block_stop`（tool_use 块）→ `callback.onChunk(完整JSON, TOOL_CALL)` 被调用
- [ ] 多个 content block 交替到达（text 和 tool_use 交叉）→ 正确区分各自内容，文本仍走 TEXT 回调

---

## 工具执行与回灌

- [ ] 用户输入触发 tool_use → 终端显示 "正在执行工具 {tool_name}..."
- [ ] 工具执行结果被追加到对话历史（role=user, content 含 tool_result 块）
- [ ] 工具执行后自动发起第二次 API 请求 → 模型看到工具结果并给出文本回复
- [ ] 第二次回复中若又含 tool_use → **不执行**，忽略，流程结束
- [ ] 工具执行异常（如 NPE）→ 被捕获为 `ToolResult(false, ..., "EXECUTION_ERROR")`，不回灌崩溃

---

## 端到端验收

- [ ] **读文件流转**：启动 → 输入 "帮我读一下 pom.xml 的内容，告诉我有哪些依赖" → 工具被执行 → 模型列出 pom.xml 中的依赖
- [ ] **搜索代码流转**：输入 "在 src/ 下搜索所有出现 main 方法的地方" → 工具被执行 → 模型列出匹配文件
- [ ] **找文件流转**：输入 "列出项目里所有 .yaml 文件" → glob 被执行 → 模型列出 `mewcode.yaml` 等
- [ ] **创建文件流转**：输入 "创建一个文件 test-output/example.txt 内容是 Hello from MewCode" → 文件被创建 → 模型回复确认
- [ ] **修改文件流转**：先用 write 创建一个文件，再输入 "把 example.txt 里的 Hello 改成 Hi" → edit_file 执行成功 → 再次 read 确认修改
- [ ] **误匹配恢复**：修改文件时故意给不准确的 old_string → 模型收到 MATCH_NOT_FOUND 错误（含文件片段）→ 用户在下一轮对话中要求修正（手动验证错误信息是否清晰）
- [ ] **危险命令拒绝**：输入 "执行命令 rm -rf /" → 工具拒绝执行 → 模型回复 "命令被安全策略拒绝"
- [ ] **纯文本降级**：不涉及工具的对话（如"你好"）→ 正常流式回复，没有工具调用发生
- [ ] **跨轮记忆保持**：先做一次工具调用 → 下一轮问"刚才你读了什么文件？"→ 模型记得并回答
