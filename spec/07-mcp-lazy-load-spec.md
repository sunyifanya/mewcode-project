# MewCode MCP 延迟加载 — 需求规格说明

## 背景

06-mcp-client 完成了 MCP 工具的基础接入——启动时连接所有 MCP Server，发现远端工具，包装注册进 ToolRegistry。但当前所有 MCP 工具 schema 在每轮 LLM 请求中都被塞进 tools 数组，当 MCP Server 工具数量多（如 codegraph 有 18+ 个工具），schema 消耗大量 context window tokens。

参考项目 mewcode-java 已实现了延迟加载机制：MCP 工具默认不进入 tools 数组，模型通过 ToolSearch 工具按需发现完整定义，发现后该工具才出现在后续请求中。

## 目标用户

使用 MewCode 接入了多个 MCP Server 的开发者。典型场景：配置了 codegraph（18 个工具）+ 企业内部 API（10 个工具），不希望所有 28 个工具 schema 消耗每轮的 context window。

## 能力清单

1. **MCP 工具默认延迟**：McpToolWrapper 注册后 `shouldDefer()` 返回 `true`，其完整 schema 默认不进入发送给模型的 tools 数组
2. **ToolSearch 发现工具**：提供 `ToolSearch` 内置工具（始终可见），模型调用它以关键词搜索或精确名称匹配查找延迟工具。支持两种查询模式：`select:Name1,Name2` 前缀走精确名称匹配，普通字符串走关键词搜索（匹配工具名和描述）
3. **发现后自动可见**：ToolSearch 返回匹配工具的完整 schema 后自动将其标记为"已发现"，从下一轮开始这些工具出现在正常 tools 数组中
4. **system-reminder 静态提示**：当存在未发现的延迟工具时，system-reminder 中追加静态提示，告知模型"部分工具未直接加载，请先调用 ToolSearch"。不列出具体工具名——避免部分模型（DeepSeek 等）看到名字后跳过 ToolSearch 直接脑补参数调用
5. **搜索无结果时列出全部**：当 ToolSearch 没有匹配结果时，返回所有延迟工具的名字列表，帮助模型调整搜索词

## 非功能要求

- **不增加启动时间**：延迟加载不改变 MCP 连接和工具注册流程，仅在每轮构建 tools 数组时过滤
- **不破坏已有工具**：内置 6 个工具（ReadFile、WriteFile、EditFile、Glob、Grep、ExecuteCommand）保持 `shouldDefer() == false`，始终可见
- **ToolSearch 始终可用**：ToolSearch 本身 `shouldDefer()` 返回 `false`，永远出现在 tools 数组中
- **向前兼容**：未配置 MCP Server 时 system-reminder 中不出现延迟工具信息（`getDeferredToolNames()` 返回空列表）

## 设计骨架

```
com.mewcode.tool.impl
  └── ToolSearchTool                    ← 新建：延迟工具搜索入口

com.mewcode.mcp
  └── McpToolWrapper                    ← 改：新增 shouldDefer() { return true; }

com.mewcode.conversation
  ├── ReminderContext                   ← 改：新增 deferredToolNames 字段
  ├── SystemPromptBuilder               ← 改：reminder 中渲染延迟工具名
  └── ConversationManager               ← 改：注入 ToolRegistry → deferredToolNames

com.mewcode
  └── MewCode                           ← 改：注册 ToolSearchTool，传递 ToolRegistry 给 ConversationManager
```

### 关键数据流

```
启动阶段（不变）
  → MCP connect → McpToolWrapper(shouldDefer=true) → ToolRegistry.register()
  → ToolSearchTool(shouldDefer=false) → ToolRegistry.register()

Agent Loop 每轮：
  1. ConversationManager.getMessages()
     → 从 ToolRegistry 取 getDeferredToolNames()
     → ReminderContext.deferredToolNames = [未发现的延迟工具名列表]
     → system-reminder 末尾加入：
       "延迟工具: mcp__codegraph__codegraph_search, mcp__codegraph__codegraph_explore, ... (用 ToolSearch 按需加载)"

  2. AnthropicProvider.buildRequestBody()
     → toolRegistry.toApiFormat(toolSubset)
     → 跳过 shouldDefer() && !isDiscovered() 的工具
     → tools 数组仅含 6 个内置工具 + ToolSearch + 已发现的 MCP 工具

  3. 模型看到名字 → 调用 ToolSearch {query: "select:mcp__codegraph__codegraph_search"}

  4. ToolSearch.execute()
     → query.startsWith("select:") → findDeferredByNames(names)
     → registry.markDiscovered(每个匹配工具名)
     → 返回完整 schema JSON + "These tools are now loaded"

  5. 下一轮：已发现工具出现在 tools 数组中，模型可直接调用
```

## Out of Scope

- **运行时取消已发现状态**：工具一旦被 ToolSearch 发现，在本次会话中持续可见，不支持回退
- **批量自动发现**：不会在模型调用失败后自动发现——必须模型主动调用 ToolSearch
- **非 MCP 工具延迟**：仅 MCP 工具延迟加载，内置工具永远立即可用
- **发现优先级 / 排序**：ToolSearch 返回结果按注册顺序排列，不做相关性排序
- **工具卸载**：已发现的工具不能卸载或重新隐藏
