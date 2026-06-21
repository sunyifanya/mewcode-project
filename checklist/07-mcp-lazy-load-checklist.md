# MewCode MCP 延迟加载 — 验收清单

## 基础功能

- [ ] `McpToolWrapper.shouldDefer()` 返回 `true`，`grep -r "shouldDefer" src/main/java/com/mewcode/mcp/McpToolWrapper.java` 能找到 override 且返回 true
- [ ] `ToolSearchTool` 编译通过，`getName()` 返回 `"ToolSearch"`
- [ ] `ToolSearchTool.shouldDefer()` 返回 `false`
- [ ] `ToolSearchTool.category()` 返回 `ToolCategory.READ`
- [ ] 6 个内置工具的 `shouldDefer()` 默认返回 `false`，`grep -r "shouldDefer" src/main/java/com/mewcode/tool/impl/` 不输出任何结果（所有内置工具未 override）

## ToolSearch 查询

- [ ] `ToolSearch.execute({query: "select:mcp__codegraph__explore"})` → 返回匹配 schema，`registry.isDiscovered("mcp__codegraph__explore")` 变为 true
- [ ] `ToolSearch.execute({query: "search"})` → 返回名称或描述中包含 "search" 的工具
- [ ] `ToolSearch.execute({query: "nonexistent_xyz"})` → 返回所有延迟工具名列表
- [ ] `ToolSearch.execute({query: ""})` → 返回 "query is required" 错误
- [ ] `ToolSearch.execute({query: "select:"})` → 空名字列表，无匹配
- [ ] `max_results` 默认 5，指定 `max_results: 3` 返回不超过 3 条
- [ ] `max_results` 超过 20 被截断为 20

## ToolRegistry 延迟机制

- [ ] `getDeferredToolNames()` 返回 shouldDefer 且未 discovered 的工具名列表
- [ ] `searchDeferred("codegraph", 5)` 返回至多 5 个匹配项
- [ ] `findDeferredByNames(["mcp__codegraph__explore"])` 返回精确匹配 schema
- [ ] `toApiFormat()` 跳过未发现的延迟工具
- [ ] `markDiscovered("mcp__codegraph__explore")` 后，`toApiFormat()` 包含该工具

## system-reminder

- [ ] 有未发现延迟工具时，system-reminder 包含 "延迟工具" 字样和 ToolSearch 使用指引，但不列出具体工具名
- [ ] 所有延迟工具被发现后，system-reminder 不出现 "延迟工具" 相关内容
- [ ] 无 MCP 配置时，system-reminder 不出现 "延迟工具" 相关内容
- [ ] system-reminder 中 TOOL_GUIDE_CONTENT 包含 ToolSearch 使用指引

## 端到端验证

- [ ] `mvn compile` 无编译错误
- [ ] 配置 codegraph MCP server 启动后，工具总数 = 7（6 内置 + ToolSearch）+ MCP 工具数
- [ ] 启动日志显示 MCP Server 已连接，包含具体工具数
- [ ] 首轮 LLM 请求的 tools 数组不含 MCP 工具 schema（可通过调试日志确认）
- [ ] 首轮 system-reminder 列出延迟工具名
- [ ] 模型调用 ToolSearch 后发现工具，下一轮 tools 数组包含该工具完整 schema
- [ ] 已发现 MCP 工具被正确调用并返回有效结果
