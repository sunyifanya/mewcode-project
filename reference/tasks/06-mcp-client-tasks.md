# MewCode MCP 客户端 — 任务清单

## 任务 1：添加 MCP SDK 依赖

**影响文件**：`pom.xml`

**依赖任务**：无

**参考资料**：
- 参考项目：`D:\mewcode-java\java\build.gradle.kts:30` — `implementation("io.modelcontextprotocol.sdk:mcp:1.1.3")`
- 当前 pom.xml 已含 `slf4j-nop`（MCP SDK 依赖 slf4j），无需额外添加

**具体步骤**：
- 在 `<dependencies>` 中新增 `io.modelcontextprotocol.sdk:mcp:1.1.3`，位于 jackson 和 okhttp 之间
- 运行 `mvn dependency:resolve` 确认依赖下载成功

---

## 任务 2：Tool 接口增加 shouldDefer 方法

**影响文件**：`src/main/java/com/mewcode/tool/Tool.java`

**依赖任务**：无

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\tool\Tool.java:23` — `default boolean shouldDefer() { return false; }`
- 当前 Tool 接口：`spec/02-tool-system-spec.md:13` — 接口方法列表

**具体步骤**：
- 在 `category()` 方法之后，添加 `default boolean shouldDefer() { return false; }`
- 内置 6 个工具无需改动（默认 false 行为保持不变）

---

## 任务 3：ToolRegistry 支持延迟工具机制

**影响文件**：`src/main/java/com/mewcode/tool/ToolRegistry.java`

**依赖任务**：任务 2（Tool 接口需先有 shouldDefer）

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\tool\ToolRegistry.java` 全文件
  - `discoveredTools` 字段 (line 15)：`ConcurrentHashMap.newKeySet()`
  - `markDiscovered()` / `isDiscovered()` (line 17-23)
  - `getDeferredToolNames()` (line 25-30)
  - `getDeferredTools()` (line 64-68)
  - `searchDeferred()` (line 70-91)
  - `findDeferredByNames()` (line 94-114)
  - `getAllSchemas()` (line 44-61)：过滤 shouldDefer 且未 discovered 的工具
- 当前 ToolRegistry：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tool\ToolRegistry.java:11-14` — 当前用 `LinkedHashMap`

**具体步骤**：
- 将 `tools` 字段从 `LinkedHashMap` 改为 `ConcurrentHashMap`（线程安全，参照参考项目）
- 新增 `discoveredTools` 字段：`Set<String>`
- 新增 `markDiscovered(String name)` / `isDiscovered(String name)` 方法
- 新增 `getDeferredToolNames()`：返回 shouldDefer 且未 discovered 的工具名列表
- 新增 `getDeferredTools()`：返回 shouldDefer 的工具列表
- 新增 `searchDeferred(String query, int maxResults)`：按名称/描述模糊匹配 shouldDefer 工具
- 新增 `findDeferredByNames(List<String> names)`：按名称精确查找 shouldDefer 工具
- 修改 `toApiFormat()`：跳过 `shouldDefer() && !isDiscovered()` 的工具
- 修改 `getAllTools()`：仍返回全部（内部管理用，不含过滤）
- 修改 `getReadOnlyTools()`：同样需考虑 shouldDefer 逻辑（shouldDefer 工具即使是 READ 也暂不列入只读列表）

---

## 任务 4：创建 McpServerConfig 配置模型

**影响文件**：`src/main/java/com/mewcode/config/McpServerConfig.java`（新建）

**依赖任务**：无

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\config\McpServerConfig.java` 全文件
  - 字段：`name`, `command`, `args`, `url`, `headers`, `env`
  - 注意：参考项目用 list + name 字段；我们用 map，name 由 map key 提供，类内不需要 `name` 字段

**具体步骤**：
- 创建 `McpServerConfig` POJO，字段：
  - `String command` — stdio 命令
  - `List<String> args` — 命令参数
  - `Map<String, String> env` — 环境变量（值支持 `${VAR}`）
  - `String url` — HTTP 端点
  - `Map<String, String> headers` — HTTP 请求头（值支持 `${VAR}`）
- 全部字段提供 getter/setter
- 与参考项目不同：不需要 `name` 字段（name 来自 map key）

---

## 任务 5：AppConfig 增加 MCP 配置段

**影响文件**：
- `src/main/java/com/mewcode/config/AppConfig.java`
- `src/main/java/com/mewcode/config/ConfigLoader.java`
- `src/main/java/com/mewcode/config/ToolConfig.java`（可能参考其格式）

**依赖任务**：任务 4（依赖 McpServerConfig 类）

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\config\AppConfig.java:15` — `List<McpServerConfig> mcpServers`
- 当前 AppConfig：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\config\AppConfig.java:10-59` — 现有字段和 getter/setter 模式
- 当前 ConfigLoader：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\config\ConfigLoader.java:16-137` — Jackson YAML 反序列化 + validate

**具体步骤**：

AppConfig.java：
- 新增字段 `@JsonProperty("mcp") private McpConfigNode mcp;`
- 新增内部类 `McpConfigNode`，包含 `@JsonProperty("servers") private Map<String, McpServerConfig> servers;`
- 提供 getter/setter

ConfigLoader.java：
- `validate()` 方法末尾增加 MCP 段校验：
  - `mcp` 或 `mcp.servers` 为 null/空 → 跳过（向后兼容，不报错）
  - 遍历 `mcp.servers` 每个 entry：
    - key 为 null/空 → 跳过该项，打印告警
    - value 为 null → 跳过该项，打印告警
    - value 的 `command` 和 `url` 均为空 → 跳过该项，打印告警 "Server 'X' 缺少 command 或 url，已跳过"
  - 若最终有效 Server 数为 0 → 无需额外打印（静默兼容无 MCP 的配置）

---

## 任务 6：创建 McpToolWrapper 适配器

**影响文件**：`src/main/java/com/mewcode/mcp/McpToolWrapper.java`（新建，与 McpManager 同包）

**依赖任务**：任务 3（ToolRegistry 需先支持 shouldDefer）

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\mcp\McpManager.java:147-208` — `McpToolWrapper` 内部类
  - `name()` 拼接规则 (line 158-159)：`"mcp__" + sanitize(serverName) + "__" + sanitize(toolName)`
  - `schema()` (line 169-182)：从 `sdkTool.inputSchema()` 构建 map
  - `execute()` (line 184-195)：构造 `CallToolRequest` → `client.callTool()` → 提取文本
  - `extractTextContent()` (line 198-208)：遍历 `result.content()` 找出 `TextContent`
- 当前 Tool 接口：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tool\Tool.java` 全文件
  - 方法签名：`getName()` / `getDescription()` / `getParametersSchema()` / `execute()` / `category()`
  - 注意与参考项目的命名差异（参考用 `name()` / `description()` / `schema()`）

**具体步骤**：
- 创建 `McpToolWrapper` 实现 `Tool`，字段：
  - `String serverName`
  - `McpSchema.Tool sdkTool`
  - `McpSyncClient client`
- `getName()` → `"mcp__" + sanitize(serverName) + "__" + sanitize(sdkTool.name())`
  - `sanitize()`：将非 `[a-zA-Z0-9_]` 字符替换为 `_`
- `getDescription()` → `sdkTool.description()`（null 时返回 `""`）
- `getParametersSchema()` → 从 `sdkTool.inputSchema()` 构建 Map（含 type/properties/required 键）
- `execute(Map<String, Object> params)` →
  - 构造 `McpSchema.CallToolRequest(sdkTool.name(), params)`
  - `client.callTool(request)` 获取 `CallToolResult`
  - 遍历 `result.content()`，拼接所有 `TextContent` 的文本
  - 若 `result.isError()` 为 true → `new ToolResult(false, text, "MCP_ERROR")`
  - 异常捕获 → `new ToolResult(false, "MCP tool call failed: " + e.getMessage(), "MCP_ERROR")`
- `category()` → `ToolCategory.COMMAND`
- `shouldDefer()` → `true`

---

## 任务 7：创建 McpManager 连接管理器

**影响文件**：`src/main/java/com/mewcode/mcp/McpManager.java`（新建）

**依赖任务**：任务 4、任务 5、任务 6（依赖 McpServerConfig、AppConfig 的 mcp 字段、McpToolWrapper）

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\mcp\McpManager.java:25-143`（不含内部类 McpToolWrapper）
  - 构造器 (line 37-41)：接收 `List<McpServerConfig>`，转存为 `Map<String, McpServerConfig> configs`
  - `connectAll()` (line 43-72)：遍历 configs，connect → initialize → listTools → wrap，返回 ConnectResult
  - `registerAllTools()` (line 74-78)：connectAll + register
  - `shutdown()` (line 80-85)：遍历 clients，逐个 closeGracefully
  - `createClient()` (line 87-121)：判断 command vs url，构建对应 transport
  - `windowsSafe()` (line 126-131)：Windows 下为 Node.js 命令加 `.cmd` 后缀
  - `sanitizeName()` (line 133-135)：正则替换非字母数字为 `_`
  - `resolveEnvVars()` (line 137-143)：`${VAR}` 正则替换
- 当前 ToolRegistry：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tool\ToolRegistry.java`

**具体步骤**：
- 创建 `McpManager` 类，字段：
  - `Map<String, McpServerConfig> configs` — 配置缓存
  - `Map<String, McpSyncClient> clients` — 活跃连接
- 构造器：接收 `Map<String, McpServerConfig>`（key 是 Server 名字），过滤 null value
- 内部 record：`ConnectResult(List<Tool> tools, List<String> serverNames, List<String> errors)`
- `connectAll()`：
  - 遍历 `configs` entry
  - 每个 Server 在 try-catch 中处理：
    1. `createClient(cfg)` → `McpSyncClient`
    2. `client.initialize()` — 初始化握手
    3. `client.listTools()` — 拉取工具列表
    4. 遍历 `result.tools()`，`new McpToolWrapper(serverName, sdkTool, client)` → 加入 tools 列表
    5. `clients.put(serverName, client)`
  - catch 中：`errors.add("MCP server '" + name + "': " + e.getMessage())`，继续下一个
  - 返回 `ConnectResult`
- `registerAllTools(ToolRegistry registry)`：
  - 调用 `connectAll()`
  - 将所有 tools 注册到 registry
  - 打印 "MCP: N 个 Server 已连接，M 个工具已注册"
  - 如有 errors，逐条打印告警
  - 返回 errors 列表
- `shutdown()`：遍历 clients，每个 `closeGracefully()`（自身 try-catch），最后 `clients.clear()`
- `createClient(McpServerConfig cfg)`：
  - 若 `command` 非空 → `StdioClientTransport`：
    - `ServerParameters.builder(windowsSafe(command)).args(args).env(resolvedEnv).build()`
    - `env` 中每个值调用 `resolveEnvVars()` 展开 `${VAR}`
  - 否则若 `url` 非空 → `HttpClientStreamableHttpTransport`：
    - `HttpClientStreamableHttpTransport.builder(url).customizeRequest(rb -> ...)` 添加 headers
    - `headers` 中每个值调用 `resolveEnvVars()` 展开 `${VAR}`
  - 否则 → `throw new IllegalArgumentException("Neither command nor url configured")`
  - 构建 `McpClient.sync(transport).clientInfo(new Implementation("mewcode", "0.1.0")).requestTimeout(Duration.ofSeconds(60)).build()`
- `static resolveEnvVars(String value)`：正则 `${...}` 替换为 `System.getenv()`
- `static windowsSafe(String command)`：Windows 下，常见 Node.js 命令追加 `.cmd`
- `static sanitizeName(String name)`：非字母数字替换为 `_`

---

## 任务 8：接入 MewCode 主流程

**影响文件**：`src/main/java/com/mewcode/MewCode.java`

**依赖任务**：任务 7（McpManager 就绪）、任务 5（AppConfig 已有 mcpServers）

**参考资料**：
- 当前 MewCode.java：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\MewCode.java:225-239` — `buildToolRegistry()` 方法
- 当前 main() 末尾：line 121-131 — 清理逻辑（目前只有 cancel + interrupt）

**具体步骤**：
- `buildToolRegistry()` 方法末尾，6 个内置工具注册完成后：
  - 从 `config.getMcp()` 获取 `McpConfigNode`
  - 若 `mcpConfig != null && mcpConfig.getServers() != null && !mcpConfig.getServers().isEmpty()`：
    - `McpManager mcpManager = new McpManager(mcpConfig.getServers())`
    - `mcpManager.registerAllTools(registry)`
  - （注意：McpManager 实例需要能传递到 main 末尾做 shutdown；可用返回值或成员变量）
- `main()` 方法：
  - `buildToolRegistry()` 返回 McpManager 引用（或通过其他方式持有）
  - 现有清理代码（line 121-125）之前，增加 `mcpManager.shutdown()` 调用
  - 启动信息打印中增加 MCP 状态行（已在 McpManager.registerAllTools 中打印）
- `startEventConsumer()` 无需改动——McpToolWrapper.execute() 内部完成调用，事件消费者只看到 ToolResult

---

## 任务 9：端到端验证

**影响文件**：无代码修改（仅验证操作），可能需要临时 mewcode.yaml 配置

**依赖任务**：任务 1-8 全部完成

**具体步骤**：
1. 无 MCP 配置的回归测试：使用现有 mewcode.yaml 启动，确认 6 个内置工具正常工作，无任何 MCP 相关错误日志
2. stdio MCP Server 接入测试：在 mewcode.yaml 中配置一个可用的 stdio MCP Server（如 CodeGraph），启动后确认：
   - 终端打印 "MCP: 1 个 Server 已连接，N 个工具已注册"
   - `toolRegistry.size()` 包含 6 + N
3. 故障 Server 隔离测试：额外配置一个不可达的 Server（错误的 command），确认：
   - 终端打印告警但不影响启动
   - 正常 Server 的工具仍在 toolRegistry 中
4. HTTP MCP Server 测试：配置一个 Streamable HTTP MCP Server，确认工具正常注册和调用
5. MCP 工具调用测试：构造一个 tool_use 请求调用 `mcp__xxx__yyy` 工具，确认返回有效的 ToolResult
6. 关闭清理测试：退出 MewCode 后，确认 stdio 子进程已被终止（通过任务管理器或 `ps` 确认）

---

## 任务依赖关系图

```
任务 1 (SDK 依赖)
  │
任务 2 (shouldDefer) ──→ 任务 3 (ToolRegistry 延迟机制)
  │
任务 4 (McpServerConfig) ──→ 任务 5 (AppConfig + ConfigLoader)
  │                              │
  │                              └──→ 任务 7 (McpManager)
  │                                       │
  └──→ 任务 6 (McpToolWrapper) ──────────┘
                                           │
                                           └──→ 任务 8 (MewCode 主流程)
                                                    │
                                                    └──→ 任务 9 (端到端验证)
```

- 任务 1、2、4 可并行
- 任务 6 依赖于 2（Tool 接口有 shouldDefer），但可与 3 并行
- 任务 7 依赖 4、5、6 全部完成