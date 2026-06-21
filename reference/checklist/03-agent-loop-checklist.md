# checklist.md — Agent Loop 验收清单

## 代码结构

- [ ] `grep -r "isReadOnly" src/main/java/com/mewcode/tool/` 返回 ≥8 条结果
- [ ] `grep -r "getReadOnlyTools\|toApiFormat.*List.*Tool" src/main/java/com/mewcode/tool/` 返回 ≥2 条
- [ ] `ls src/main/java/com/mewcode/agent/` 输出 6 个文件（AgentLoop, AgentEvent, AgentEventType, StreamingCollector, ToolExecutionStrategy, StopReason）
- [ ] `grep "BlockingQueue<AgentEvent>" src/main/java/com/mewcode/MewCode.java` 有匹配
- [ ] `grep "onComplete.*stopReason\|onComplete.*String" src/main/java/com/mewcode/provider/` 返回 ≥2 条
- [ ] `grep "stop_reason" src/main/java/com/mewcode/provider/AnthropicProvider.java` 有匹配
- [ ] `grep "maxIterations\|max_iterations" src/main/java/com/mewcode/config/AppConfig.java` 有匹配
- [ ] `grep "maxIterations\|max_iterations" src/main/java/com/mewcode/config/ConfigLoader.java` 有匹配

## 具体值验收

- [ ] AgentLoop 中迭代上限常量为 25（或从 ConfigLoader 读取默认值 25）
- [ ] `grep "LinkedBlockingQueue.*1000\|newLinkedBlockingQueue.*1000" src/main/java/` 有匹配
- [ ] `grep "availableProcessors\|newFixedThreadPool" src/main/java/com/mewcode/agent/ToolExecutionStrategy.java` 有匹配，且线程数公式含 `Math.min(..., 10)`
- [ ] `grep "END_TURN\|TOOL_USE\|MAX_TOKENS\|STOP_SEQUENCE" src/main/java/com/mewcode/agent/StopReason.java` 每个值都有匹配
- [ ] `grep "TEXT_DELTA\|TOOL_CALL_START\|TOOL_CALL_RESULT\|TOKEN_USAGE\|LOOP_STARTED\|LOOP_FINISHED\|ERROR\|CANCELLED\|UNKNOWN_TOOL" src/main/java/com/mewcode/agent/AgentEventType.java` 每个值都有匹配 (≥9)

## 功能验证

- [ ] 启动后输入"读一下 pom.xml 分析项目用了哪些依赖"，终端输出中能看到 `⚙ 正在执行工具 read_file...` 和后续分析文本
- [ ] 输入一个需要 ≥2 轮工具调用的任务，观察终端自动完成多轮循环（用户无需手动输入任何催促）
- [ ] 临时在 mewcode.yaml 中设 `max_iterations: 2`，输入需要 ≥3 轮工具的任务，确认第 2 轮后循环终止
- [ ] 输入 `/plan`，终端回显"已进入 Plan Mode，仅可调研"（或类似中文确认）
- [ ] Plan Mode 下输入"删除项目根目录的 pom.xml"，确认终端**未**出现 write_file 或 execute_command 工具的调用
- [ ] 输入 `/do`，终端回显"已退出 Plan Mode，可执行修改"
- [ ] 修改代码临时注释掉 `buildToolRegistry` 中 `globTool` 的注册 → 输入"全局搜索 TODO" → 模型尝试 glob → 确认 Agent 因 UNKNOWN_TOOL 停止
- [ ] 流式响应中文本和 thinking 实时逐段出现（非请求完成后一次性打印）
- [ ] Ctrl+C 后 Agent 输出终止信息而非 JVM 直接退出（有 shutdown hook 清理）

## 端到端验收

- [ ] **主场景**：启动 MewCode → 输入"在 src/main/java/com/mewcode 下创建一个 Hello.java，内容是打印 Hello MewCode 的 main 类，然后编译它并运行确认输出正确" → Agent 自动完成：`glob` 确认目录存在 → `write_file` 创建文件 → `execute_command` 编译 → `execute_command` 运行 → 检查输出 → 给出完成总结。全程用户零催促，且流式输出持续可见。
