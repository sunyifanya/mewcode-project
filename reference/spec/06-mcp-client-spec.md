# MewCode MCP 客户端 — 需求规格说明

## 背景

当前 MewCode 的工具全部是内置的（ReadFile、WriteFile、EditFile、Glob、Grep、ExecuteCommand），能力在编译时固定。MCP（Model Context Protocol）是 Anthropic 发布的标准化协议，让 AI 应用接入外部工具服务器——用户只需要在配置文件里声明 Server，MewCode 就能自动发现并注册远端工具，Agent 使用时完全无感。

参考项目 mewcode-java 已使用 `io.modelcontextprotocol.sdk:mcp:1.1.3` 完成了相同的 MCP 客户端实现，本步骤复用该 SDK 并适配到当前 MewCode 的 Tool 体系。

## 目标用户

需要为 MewCode 接入外部工具源的开发者。典型场景：接入 CodeGraph MCP Server 做代码智能分析、接入企业内部 API Server、接入第三方数据查询 Server。

## 能力清单

1. **MCP Server 配置声明**：在 mewcode.yaml 中用 `mcp.servers` map 声明 Server 列表，每个 key 是 Server 名字（用于工具命名前缀）。stdio 类型填 `command`、`args`、`env`；HTTP 类型填 `url`、`headers`。`env` 的值和 `headers` 的值均支持 `${VAR}` 环境变量展开；未匹配的变量原样保留不替换
2. **双传输支持**：通过 MCP SDK 支持本地子进程 stdio（`StdioClientTransport`）和远程 Streamable HTTP（`HttpClientStreamableHttpTransport`）两种传输。配置中 `command` 非空则走 stdio，否则 `url` 非空走 HTTP；两者都填时 `command` 优先；两者都未填则跳过该 Server 并打印告警
3. **启动时自动发现**：MewCode 启动时依次连接所有配置的 MCP Server，对每个 Server 先 `initialize` 握手，再 `listTools` 拉取工具列表。发现到的远端工具用适配层包装后注册进 ToolRegistry。单个 Server 连接失败打印告警并跳过，不影响其他 Server 和 MewCode 正常启动
4. **工具适配层（McpToolWrapper）**：每个远端工具包装为 MewCode 的 `Tool` 接口实现。工具名为 `mcp__<server>__<tool>` 三段式（非字母数字字符替换为 `_` 防冲突）。`execute()` 委托给 SDK 的 `client.callTool()`，返回标准 `ToolResult`
5. **延迟可见**：MCP 工具启动时已注册到 ToolRegistry，但其 schema 默认不加入发送给模型的 `tools` 数组（`shouldDefer() = true`），需要后续 ToolSearchTool 搜索后才对模型可见。这是参考项目的既有做法——避免上下文膨胀，模型按需发现
6. **权限分类**：所有 MCP 工具统一归类为 `ToolCategory.COMMAND`（最高风险等级），经过 PermissionChecker 的同一路径检查
7. **Server 隔离**：多个 MCP Server 连接相互独立，一个 Server 挂了（进程崩溃、网络断开）不影响其他 Server 的工具调用
8. **优雅关闭**：MewCode 退出时遍历所有活跃的 MCP 客户端连接，调用 `closeGracefully()` 释放子进程和网络资源
9. **Windows 兼容**：在 Windows 下，常见 Node.js 命令（`npx`、`npm`、`node`、`uvx`、`uv`、`pnpm`、`yarn`、`bunx`）自动追加 `.cmd` 后缀，保证子进程能正确启动

## 非功能要求

- **错误弹性**：MCP 连接、初始化、listTools、callTool 全链路的任何异常必须被捕获——连接失败打印告警跳过该 Server，工具调用失败返回 `ToolResult(success=false, content=错误描述)`，绝不能以未捕获异常的形式崩掉主进程
- **超时控制**：每次 MCP 请求（initialize / listTools / callTool）的超时时间设为 60 秒，由 SDK 的 `requestTimeout` 统一控制
- **启动不阻塞**：多 Server 的连接和 listTools 在启动阶段依次完成，总超时不超过 `Server数量 × 60s`。启动慢于预期的 Server 不会无限期挂起启动流程
- **向后兼容**：未配置 `mcp.servers` 的 mewcode.yaml 保持原有行为不变（无 MCP 工具，零额外开销）

## 设计骨架

```
com.mewcode.mcp                    ← 新包
  ├── McpManager                   ← 连接生命周期、工具发现、关闭
  └── McpToolWrapper               ← Tool 接口适配，委托给 McpSyncClient.callTool()

com.mewcode.config
  ├── McpServerConfig              ← 新：单个 Server 配置（command/args/env/url/headers）
  ├── AppConfig                    ← 改：新增 mcpServers 字段 Map<String, McpServerConfig>
  └── ConfigLoader                 ← 改：新增 mcp.servers 解析与校验

com.mewcode.tool
  ├── Tool                         ← 改：新增 default boolean shouldDefer() { return false; }
  └── ToolRegistry                 ← 改：新增延迟工具相关方法
                                     (markDiscovered / isDiscovered / getDeferredToolNames /
                                      getDeferredTools / searchDeferred / findDeferredByNames)
                                     toApiFormat() 跳过 shouldDefer 且未 discovered 的工具

com.mewcode
  └── MewCode                      ← 改：buildToolRegistry() 末尾调用 McpManager.registerAllTools()
                                     启动信息中打印 MCP 连接状态
                                     main() 末尾调用 McpManager.shutdown()
```

### 关键数据流

```
MewCode 启动
  → ConfigLoader 解析 mewcode.yaml → AppConfig.mcpServers (Map<String, McpServerConfig>)
  → buildToolRegistry() 注册 6 个内置工具
  → new McpManager(config.getMcpServers())
  → mcpManager.registerAllTools(toolRegistry)
       → 遍历每个 Server 配置:
           → createClient(cfg): 判断 command/url → 选择 StdioClientTransport 或 HttpClientStreamableHttpTransport
           → client.initialize()
           → client.listTools()
           → 遍历返回的 tools: new McpToolWrapper(serverName, sdkTool, client)
           → registry.register(wrapper)
           → 失败: 收集错误信息，continue 下一个
       → 返回 errors 列表
  → 打印 MCP 连接状态 ("N 个 MCP Server 已连接, M 个工具已注册")
  → 如有错误，打印告警

模型请求工具
  → ToolRegistry.toApiFormat()
       → 遍历所有工具，跳过 shouldDefer() && !isDiscovered() 的工具
       → MCP 工具默认 shouldDefer=true, discovered=false → 不出现在 tools 数组
  → (未来) ToolSearchTool 搜索匹配 → markDiscovered → 后续请求中该工具出现

MCP 工具调用
  → model 发起 tool_use: name="mcp__codegraph__codegraph_search"
  → ToolRegistry.get("mcp__codegraph__codegraph_search") → McpToolWrapper
  → wrapper.execute(params)
       → 构造 McpSchema.CallToolRequest
       → client.callTool(request)
       → 提取 TextContent，拼接为字符串
       → 返回 ToolResult(success=true/false, content=...)

MewCode 退出
  → mcpManager.shutdown()
       → 遍历所有 McpSyncClient: client.closeGracefully()
```

### 配置格式

```yaml
# mewcode.yaml (新增 mcp 段)
mcp:
  servers:
    codegraph:                              # Server 名字，用作工具前缀
      command: npx                          # stdio 传输
      args: ["-y", "@codegraph/mcp-server"]
      env:
        NODE_ENV: production
        TOKEN: ${MY_TOKEN}                  # ${VAR} 展开为环境变量值

    remote-docs:                            # HTTP 传输
      url: https://mcp.example.com/api
      headers:
        Authorization: "Bearer ${GITHUB_TOKEN}"
        X-Client: mewcode
```

## Out of Scope

- **MCP Resources / Prompts / Sampling**：仅实现工具相关能力（`listTools` / `callTool`）
- **Server 健康检查和自动重连**：启动时连接一次，运行中不监控 Server 存活状态，不断线重连
- **ToolSearchTool**：延迟工具的搜索与发现入口在后续步骤实现，本步骤只提供基础 shouldDefer 机制
- **运行时动态增减 Server**：Server 列表仅在启动时从配置文件读取一次，运行中不可变
- **OAuth / 复杂认证**：HTTP headers 只支持静态值 + `${VAR}` 展开，不做 OAuth 流程
- **工具 schema 转换 / 校验**：MCP SDK 返回的 inputSchema 原样传给 ToolRegistry，不做格式校验
- **并发工具调用优化**：模型同时发起多个 MCP tool_use 时按顺序逐个执行