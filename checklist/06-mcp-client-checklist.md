# MewCode MCP 客户端 — 验收检查清单

> 每一项必须可勾选、可观测。按 spec 中砍掉的具体值逐条验收。

---

## 一、依赖与编译

- [ ] `grep "io.modelcontextprotocol.sdk" pom.xml` 返回 1 条，版本为 `1.1.3`
- [ ] `mvn compile` 零错误通过（含 MCP SDK 依赖的所有新 import）

---

## 二、Tool 接口与 ToolRegistry

- [ ] `grep "shouldDefer" src/main/java/com/mewcode/tool/Tool.java` 返回 ≥1 条
- [ ] `Tool.shouldDefer()` 默认返回 `false`（不传参调用返回 false）
- [ ] `ToolRegistry.markDiscovered("test-tool")` 调用后 `isDiscovered("test-tool")` 返回 `true`
- [ ] 未 markDiscovered 的工具名调用 `isDiscovered()` 返回 `false`
- [ ] `getDeferredToolNames()` 返回的列表中每个 Tool 均满足 `shouldDefer() == true && !isDiscovered()`
- [ ] `toApiFormat()` 返回的列表中不含任何 `shouldDefer() && !isDiscovered()` 的工具
- [ ] `getDeferredTools()` 返回的列表中每个 Tool 均满足 `shouldDefer() == true`
- [ ] `searchDeferred("xxx", 10)` 返回结果数 ≤ 10，每条结果均匹配搜索词（名称或描述包含）
- [ ] `searchDeferred` 忽略 `shouldDefer() == false` 的工具
- [ ] `findDeferredByNames(["name1", "name2"])` 返回结果仅包含名称精确匹配的 shouldDefer 工具

---

## 三、配置模型

- [ ] `McpServerConfig` 类存在，含 `getCommand()` / `getArgs()` / `getEnv()` / `getUrl()` / `getHeaders()` 五个 getter
- [ ] `McpServerConfig` 类中**不含** `name` 字段（name 由 map key 提供）
- [ ] `AppConfig` 类中存在 `getMcp()` 方法，返回含 `getServers()` 的节点对象
- [ ] `mewcode.yaml` 中完全不写 `mcp:` 段时，`AppConfig.getMcp()` 返回 null 或 servers 为空，MewCode 正常启动不报错

---

## 四、环境变量展开

- [ ] 设置环境变量 `MY_VAR=hello123`：配置 `env.TEST: ${MY_VAR}` → 进程实际环境变量 `TEST=hello123`
- [ ] 未设置的环境变量 `UNSET_VAR`：配置 `env.TEST: ${UNSET_VAR}` → 进程实际环境变量 `TEST=${UNSET_VAR}`（原样保留）
- [ ] headers 值中的 `${VAR}` 同样展开（行为与 env 一致）
- [ ] 多变量混合：`"a${X}b${Y}c"` 正确替换已知变量，未知原样

---

## 五、工具命名

- [ ] `mcp__codegraph__codegraph_search` 格式：三段用双下划线分隔
- [ ] Server 名含特殊字符（如 `my-server`）→ 工具名中对应段为 `my_server`（`-` 替换为 `_`）
- [ ] Server 名含中文 → 工具名中中文被替换为 `_`
- [ ] SDK 工具名含 `.`（如 `tool.name`）→ 工具名中 `.` 被替换为 `_`

---

## 六、McpToolWrapper 行为

- [ ] `getName()` 返回 `mcp__<sanitizedServer>__<sanitizedTool>` 格式
- [ ] `getDescription()` 返回 SDK 工具的描述文本（非 null，空时返回 `""`）
- [ ] `getParametersSchema()` 返回的 Map 包含 `type`、`properties`、`required` 键（至少含 `type: "object"`）
- [ ] `category()` 返回 `ToolCategory.COMMAND`
- [ ] `shouldDefer()` 返回 `true`
- [ ] `execute(params)` 成功时返回 `ToolResult(success=true, content=文本输出)`
- [ ] `execute(params)` 调用失败（如 SDK 抛异常）返回 `ToolResult(success=false, errorCode="MCP_ERROR")`
- [ ] `execute(params)` 在 `CallToolResult.isError() == true` 时返回 `ToolResult(success=false)`
- [ ] `execute()` 返回的 `content` 包含所有 `TextContent` 的拼接结果（多个 content 用换行分隔）
- [ ] `execute()` 在 `result.content()` 为空时返回 `"(no output)"`

---

## 七、McpManager 连接管理

- [ ] stdio 配置（command + args + env）→ 使用 `StdioClientTransport`
- [ ] HTTP 配置（url + headers）→ 使用 `HttpClientStreamableHttpTransport`
- [ ] command 和 url 同时非空 → 走 stdio（command 优先）
- [ ] command 和 url 均为空 → 打印告警 "缺少 command 或 url"，跳过该 Server
- [ ] 构造 `McpClient` 时 `clientInfo` 为 `Implementation("mewcode", "0.1.0")`
- [ ] 构造 `McpClient` 时 `requestTimeout` 为 `60` 秒
- [ ] Windows 下 `npx` / `npm` / `node` / `uvx` / `uv` / `pnpm` / `yarn` / `bunx` 命令自动追加 `.cmd` 后缀
- [ ] 非 Windows 系统不追加 `.cmd` 后缀

---

## 八、启动行为

- [ ] 无 MCP 配置（mcp 段缺失或 servers 为空 map）：启动日志无 MCP 相关信息
- [ ] 1 个正常 stdio Server：启动日志含 `MCP: 1 个 Server 已连接，N 个工具已注册`
- [ ] 2 个正常 Server：启动日志含 `MCP: 2 个 Server 已连接，M 个工具已注册`（M ≥ 各 Server 工具数之和）
- [ ] 1 个正常 + 1 个故障 Server：正常 Server 的工具已注册；故障 Server 打印告警但不阻塞启动
- [ ] 故障 Server 打印格式：`MCP server '<name>': <error message>`
- [ ] 故障原因为"command not found"时，错误信息包含原始命令名
- [ ] 故障原因为"URL unreachable"时，错误信息包含 URL
- [ ] `toolRegistry.size()` = 6（内置）+ 所有正常 Server 的工具数

---

## 九、工具调用

- [ ] `toolRegistry.get("mcp__<server>__<tool>")` 返回非 null 的 McpToolWrapper
- [ ] 通过 McpToolWrapper.execute() 调用成功 → 返回 `ToolResult.success=true`
- [ ] MCP 工具返回 error（`isError() == true`）→ `ToolResult.success=false`，模型可见错误文本
- [ ] MCP Server 进程已死但仍尝试调用其工具 → 返回 `ToolResult.success=false`，不崩主进程

---

## 十、关闭清理

- [ ] `mcpManager.shutdown()` 调用后所有 `McpSyncClient` 的 `closeGracefully()` 被调用
- [ ] shutdown 过程中某个 client.closeGracefully() 抛异常 → 不影响其他 client 关闭
- [ ] 程序退出后 stdio 方式启动的子进程已终止（确认无残留进程）

---

## 十一、端到端验收

- [ ] **零配置回归**：使用不含 `mcp:` 段的 mewcode.yaml 启动 MewCode，输入 `搜索 main 方法` 能正确调用 GrepTool 并返回结果
- [ ] **stdio 接入**：配置 CodeGraph MCP Server（`command: npx, args: ["-y", "@codegraph/mcp-server"]`），启动后 `grep -r "mcp__codegraph__"` 在 toolRegistry 中命中 ≥3 个工具
- [ ] **stdio 工具调用**：通过 toolCall 调用 `mcp__codegraph__codegraph_search`（参数 `query: "Tool"`），返回有效搜索结果文本
- [ ] **故障隔离**：额外配置一个指向不存在命令的 Server（如 `command: nonexistent-cmd-12345`），MewCode 启动正常，内置工具 + CodeGraph 工具仍可用
- [ ] **HTTP 接入**：配置一个可用的 Streamable HTTP MCP Server，启动后工具正常注册，调用返回有效结果
- [ ] **工具延迟可见**：MCP 工具在 `toApiFormat()` 结果中不存在（shouldDefer + 未 discovered），但仍在 `getAllTools()` 列表中