# MewCode MCP 延迟加载 — 任务清单

## 任务 1：McpToolWrapper 启用延迟标记

**影响文件**：`src/main/java/com/mewcode/mcp/McpToolWrapper.java`

**依赖任务**：无

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\mcp\McpManager.java:167` — `@Override public boolean shouldDefer() { return true; }`
- 当前 McpToolWrapper：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\mcp\McpToolWrapper.java:59` — `shouldDefer()` 使用接口默认值（false）
- 当前 Tool 接口：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tool\Tool.java:58` — 已定义 default shouldDefer()

**具体步骤**：
- 在 `category()` 方法之后，添加 `shouldDefer()` override 返回 `true`

---

## 任务 2：创建 ToolSearchTool

**影响文件**：`src/main/java/com/mewcode/tool/impl/ToolSearchTool.java`（新建）

**依赖任务**：无

**参考资料**：
- 参考项目：`D:\mewcode-java\java\src\main\java\com\mewcode\tool\impl\ToolSearchTool.java` 全文件
  - `name()` → 当前项目用 `getName()`
  - `description()` → 当前项目用 `getDescription()`
  - `schema()` → 当前项目用 `getParametersSchema()`
  - `query.startsWith("select:")` → `findDeferredByNames`
  - 完成后 `registry.markDiscovered(name)`
- 当前 Tool 接口：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tool\Tool.java`
- 当前 ToolRegistry：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\tool\ToolRegistry.java:116-147` — searchDeferred / findDeferredByNames 已实现

**具体步骤**：
- 创建 `ToolSearchTool` 类，实现 `Tool`，持有 `ToolRegistry registry`
- `getName()` → `"ToolSearch"`
- `getDescription()` → 与参考实现一致的描述文本
- `getParametersSchema()` → `query` (string, required) + `max_results` (integer, default 5)
- `execute()`：
  - 若 `query.startsWith("select:")` → 解析名字列表 → 调用 `registry.findDeferredByNames(names)`
  - 否则 → 调用 `registry.searchDeferred(query, maxResults)`
  - 若结果空 → 返回所有延迟工具名列表
  - 若结果非空 → 遍历结果，对每个 name 调用 `registry.markDiscovered(name)` → 返回 JSON 化的 schema 列表
- `category()` → `ToolCategory.READ`
- `shouldDefer()` → `false`（始终可见）

---

## 任务 3：ReminderContext 增加延迟工具名字段

**影响文件**：`src/main/java/com/mewcode/conversation/ReminderContext.java`

**依赖任务**：无

**参考资料**：
- 当前 ReminderContext：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\conversation\ReminderContext.java` 全文件

**具体步骤**：
- 新增字段：`private final List<String> deferredToolNames;`
- 构造器新增参数：`List<String> deferredToolNames`
- 新增 getter：`public List<String> getDeferredToolNames()`

---

## 任务 4：ConversationManager 接入 ToolRegistry

**影响文件**：`src/main/java/com/mewcode/conversation/ConversationManager.java`

**依赖任务**：任务 3（ReminderContext 需先有 deferredToolNames 字段）

**参考资料**：
- 当前 ConversationManager：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\conversation\ConversationManager.java:22-29` — 字段定义
- 当前 ConversationManager 构造器：line 40-50 — `new ConversationManager()` 默认模块，`new ConversationManager(SystemPromptBuilder)`
- `getMessages()` 方法：line 93-114 — 构建 ReminderContext 的位置

**具体步骤**：
- 新增字段：`private ToolRegistry toolRegistry;`
- 新增构造器（或修改现有构造器）：`public ConversationManager(SystemPromptBuilder promptBuilder, ToolRegistry toolRegistry)`
- `getMessages(int iteration, boolean planMode)` 中：
  - 若 toolRegistry 非空 → 调用 `toolRegistry.getDeferredToolNames()` 获取名字列表
  - 传入 `new ReminderContext(iteration, planMode, ..., deferredToolNames)`

---

## 任务 5：SystemPromptBuilder 渲染延迟工具名

**影响文件**：
- `src/main/java/com/mewcode/conversation/SystemPromptBuilder.java`
- `src/main/java/com/mewcode/conversation/ConversationManager.java`（TOOL_GUIDE_CONTENT 常量）

**依赖任务**：任务 3（ReminderContext 需先有 getter）

**参考资料**：
- 当前 SystemPromptBuilder：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\conversation\SystemPromptBuilder.java:56-69` — composeReminder()
- 参考项目 PromptSections：`D:\mewcode-java\java\src\main\java\com\mewcode\prompt\PromptSections.java:148-161` — USING_TOOLS_CONTENT 中关于 ToolSearch 的描述

**具体步骤**：

SystemPromptBuilder.java：
- `composeReminder()` 方法末尾，在 wrapper `</system-reminder>` 之前，若 `ctx.getDeferredToolNames()` 非空列表，追加格式化后的名字列表

ConversationManager.java：
- `TOOL_GUIDE_CONTENT` 常量中增加 ToolSearch 使用指引：
  "- 部分工具（MCP 远端的）被延迟加载。system-reminder 中列出它们的名字，用 ToolSearch search 或 select: 来按需加载完整 schema。"

---

## 任务 6：注册 ToolSearchTool 并接入主流程

**影响文件**：`src/main/java/com/mewcode/MewCode.java`

**依赖任务**：任务 2（ToolSearchTool 就绪）、任务 4（ConversationManager 新构造器）

**参考资料**：
- 当前 MewCode：`D:\code\mewcode\mewcode\src\main\java\com\mewcode\MewCode.java:227-241` — buildToolRegistry()
- 当前 main()：line 73 — `new ConversationManager()`

**具体步骤**：
- `buildToolRegistry()` 中：在 6 个内置工具之前，`registry.register(new ToolSearchTool(registry))`
- `main()` 中：`new ConversationManager()` 改为 `new ConversationManager(new SystemPromptBuilder(modules), toolRegistry)`（或新增一个接收 ToolRegistry 的构造器）

---

## 任务 7：端到端验证

**影响文件**：无代码修改（仅验证操作）

**依赖任务**：任务 1-6 全部完成

**具体步骤**：
1. 编译：`mvn compile` 确认无编译错误
2. 启动测试：配置一个 MCP server（如 codegraph），启动 MewCode
3. 观察启动日志：确认 MCP 工具已注册，但首轮 tools 数组不含它们
4. 观察 system-reminder：确认包含 "延迟工具:" 列表
5. 调用 ToolSearch：手动发送 "使用 ToolSearch 搜索 codegraph" 确认返回完整 schema
6. 后续轮次：确认已发现的工具出现在 tools 数组中

---

## 任务依赖关系图

```
任务 1 (McpToolWrapper shouldDefer)
  │
任务 2 (ToolSearchTool) ──→ 任务 3 (ReminderContext) ──→ 任务 4 (ConversationManager)
  │                                                        │
  │                                                        └──→ 任务 5 (SystemPromptBuilder)
  │                                                              │
  └──────→ 任务 6 (MewCode 主流程) ←──────────────────────────────┘
              │
              └──→ 任务 7 (端到端验证)
```

- 任务 1、2、3 可并行
- 任务 4 依赖 3
- 任务 5 依赖 3
- 任务 6 依赖 2、4
