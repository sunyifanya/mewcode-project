# MewCode

MewCode 是一个用 Java 编写的终端 AI 编程助手，目标是提供类似 Claude Code 的交互式开发体验：在终端里和模型对话，让它读取、搜索、编辑代码，执行命令，并通过权限系统、MCP、Skill、SubAgent、Worktree 和 Team 协作机制完成更复杂的软件工程任务。

> 当前项目仍处于快速迭代阶段，`reference/` 目录下按编号保存了各功能的 spec、tasks 和 checklist，可作为设计与实现进度的参考。

## 功能特性

- **终端交互式 TUI**：基于 JLine 的命令行输入、Tab 补全和流式输出。
- **多 Provider 配置**：通过 `mewcode.yaml` 配置 `anthropic` 或 `openai` 兼容协议、模型、Base URL 和 API Key。
- **内置代码工具**：支持读取文件、写入文件、精确编辑、Glob 搜索、Grep 搜索和命令执行。
- **权限模式**：支持 `default`、`accept-edits`、`plan`、`bypass` 等模式，在可控范围内决定工具调用是否需要确认。
- **Slash Commands**：提供 `/help`、`/status`、`/compact`、`/session`、`/memory`、`/permission`、`/review`、`/skill`、`/plan`、`/do`、`/resume` 等命令。
- **上下文压缩与会话恢复**：支持压缩长对话上下文，并从历史 session 中恢复工作。
- **自动记忆**：可从对话中抽取长期记忆，并通过 `/memory` 管理。
- **MCP 客户端**：可在配置文件中声明 MCP Server，并把外部工具接入 Tool Registry。
- **Skill 系统**：支持从 `skills/` 加载可复用工作流，并自动注册为 slash command。
- **Hook 系统**：支持从 `.mewcode/hooks/` 加载事件钩子，在特定事件前后执行命令。
- **SubAgent**：支持派生子 Agent 处理独立任务，可配置后台运行和最大轮次。
- **Worktree 隔离**：支持为 Agent 创建独立 Git worktree，避免并行修改互相污染。
- **Team 协作**：支持创建 Agent 团队、向 teammate 发送消息、停止成员，并预览/合并 teammate 的 worktree 变更。

## 技术栈

- Java 21
- Maven
- JLine 3
- Jackson / Jackson YAML
- OkHttp
- Model Context Protocol Java SDK

## 项目结构

```text
.
├── src/main/java/com/mewcode/       # 主程序源码
│   ├── agent/                       # Agent 循环、事件与工具执行策略
│   ├── command/                     # Slash command 注册与分发
│   ├── config/                      # mewcode.yaml 配置加载
│   ├── hook/                        # Hook 加载与执行
│   ├── mcp/                         # MCP 客户端与工具包装
│   ├── memory/                      # 长期记忆
│   ├── permission/                  # 权限模式与检查
│   ├── provider/                    # LLM Provider 适配
│   ├── session/                     # 会话保存、清理和恢复
│   ├── skill/                       # Skill 加载与执行
│   ├── subagent/                    # SubAgent 定义与调度
│   ├── task/                        # 任务列表工具
│   ├── teams/                       # 多 Agent 团队协作
│   ├── tool/                        # Tool 接口与内置工具
│   ├── tui/                         # 终端 UI
│   └── worktree/                    # Git worktree 隔离
├── src/main/resources/              # 默认配置与内置 subagent 定义
├── skills/                          # 项目级 Skill
├── reference/                       # 功能 spec / tasks / checklist
├── pom.xml                          # Maven 构建配置
└── MEWCODE.md                       # 项目身份说明
```

## 环境要求

1. 安装 JDK 21 或更高版本。
2. 安装 Maven。
3. 准备一个可用的 LLM API Key。
4. 如需 MCP 示例配置中的 Node/Python Server，确保本机可用 `npx` 和 `python`。
5. 如需 worktree 或 team 合并能力，当前目录需要是 Git 仓库。

## 快速开始

### 1. 构建项目

```bash
mvn package
```

构建完成后，主程序 JAR 通常位于：

```text
target/mewcode-1.0-SNAPSHOT.jar
```

### 2. 准备配置文件

在项目根目录创建 `mewcode.yaml`。程序启动时会按以下优先级加载配置：

1. 命令行第一个参数指定的配置文件路径；
2. 当前目录下的 `./mewcode.yaml`；
3. 打包在 classpath 中的默认 `mewcode.yaml`。

示例配置：

```yaml
protocol: anthropic             # anthropic 或 openai
model: your-model-name
base_url: https://api.example.com/anthropic
api_key: ${DEEPSEEK_API_KEY}
thinking_budget: 16000
max_iterations: 25
stream_timeout_seconds: 300
max_session_age_days: 5
extraction_interval: 5

subagent:
  background:
    enabled: true
  max_turns: 25

tool:
  timeout_seconds: 30
  working_directory: .

permission:
  mode: default                  # default / accept-edits / plan / bypass

worktree:
  enabled: true
  symlink_dirs: []
  stale_cleanup_interval_seconds: 3600
  stale_cutoff_hours: 24

team:
  coordinator:
    enabled: false

mcp:
  servers:
    context7:
      command: npx
      args: ["-y", "@upstash/context7-mcp"]
```

> 注意：不要把真实 API Key 提交到 Git 仓库。建议使用本地未跟踪配置文件或环境变量注入方案。

### 3. 启动 MewCode

使用当前目录的 `mewcode.yaml`：

```bash
java -jar target/mewcode-1.0-SNAPSHOT.jar
```

指定配置文件：

```bash
java -jar target/mewcode-1.0-SNAPSHOT.jar /path/to/mewcode.yaml
```

启动后，你会看到已加载的 provider、model、tool 数量、skill 数量、权限模式等信息，然后可以直接输入自然语言任务。

## 常用 Slash Commands

| 命令 | 说明 |
| --- | --- |
| `/help` | 显示可用命令列表或某个命令的详情 |
| `/status` | 显示当前运行状态 |
| `/compact` | 压缩对话上下文以节省 Token |
| `/session` | 查看当前会话信息或列出会话 |
| `/resume` | 恢复之前的会话 |
| `/memory` | 查看或清理自动记忆 |
| `/permission` | 查看或切换权限模式 |
| `/review` | 审查当前代码变更 |
| `/skill` | 列出可用 Skill |
| `/plan` | 进入只读计划模式 |
| `/do` | 退出计划模式，恢复执行模式 |
| `/mode` | 循环切换权限模式 |
| `/clear` | 清空当前对话历史 |
| `/exit` | 退出 MewCode |

也可以输入 `/help <command>` 查看单个命令的用法。

## 权限模式

| 模式 | 行为 |
| --- | --- |
| `default` | 读操作默认允许；写入和命令执行需要确认 |
| `accept-edits` | 读写操作默认允许；命令执行需要确认 |
| `plan` | 面向只读调研和计划阶段 |
| `bypass` / `yolo` | 默认允许所有工具调用 |

建议日常开发使用 `default` 或 `accept-edits`，只在明确可信的场景下使用 `bypass`。

## MCP 配置

MewCode 可以通过 `mewcode.yaml` 中的 `mcp.servers` 接入外部 MCP Server：

```yaml
mcp:
  servers:
    context7:
      command: npx
      args: ["-y", "@upstash/context7-mcp"]
    time:
      command: python
      args: ["-m", "mcp_server_time"]
```

启动时 MewCode 会连接这些 Server，并将可用工具注册到工具系统中。

## Skill

项目级 Skill 放在 `skills/` 目录下。启动时 MewCode 会加载 Skill Catalog，并将 Skill 注册为 slash command。你可以通过：

```text
/skill
```

查看当前可用 Skill。

## Hook

Hook 配置放在 `.mewcode/hooks/` 目录下。MewCode 启动时会加载有效 Hook，并在对应事件触发时执行配置的命令。Hook 命令执行时会注入部分环境变量，例如：

- `MEWCODE_EVENT`
- `MEWCODE_TOOL`

Hook 错误会写入 `.mewcode/hooks-errors.log`。

## SubAgent 与 Team

MewCode 支持用 Agent 工具派生子 Agent 来完成独立任务。子 Agent 可以后台运行，也可以在独立 worktree 中工作。

Team 能力在此基础上提供多 Agent 协作：

1. 创建团队；
2. 启动带 `team_name` 和 `name` 的 teammate；
3. 通过 `SendMessage` 在 lead 与 teammate 之间通信；
4. teammate 完成任务后回报 lead；
5. lead 预览并合并 teammate 的 worktree 变更。

相关工具包括：

- `Agent`
- `SendMessage`
- `TeamCreate`
- `TeamDelete`
- `TeamStopMember`
- `TeamMerge`
- `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate`

## Worktree 隔离

启用 `worktree.enabled: true` 后，SubAgent 可以在独立 Git worktree 中运行。这样可以让多个 Agent 并行修改代码，主工作区保持可控。过期 worktree 会按配置定期清理。

```yaml
worktree:
  enabled: true
  stale_cleanup_interval_seconds: 3600
  stale_cutoff_hours: 24
```

## 开发与验证

常用开发命令：

```bash
mvn compile
mvn package
```

如果你修改了功能设计，请同步更新 `reference/` 下对应编号的文档：

- `reference/spec/NN-feature-name-spec.md`
- `reference/tasks/NN-feature-name-tasks.md`
- `reference/checklist/NN-feature-name-checklist.md`

## 安全提示

- 不要提交真实 API Key、访问令牌或私有配置。
- 在 `bypass` 权限模式下，模型可直接执行写入和命令操作，请谨慎使用。
- Hook 会执行本地命令，添加或修改 Hook 前请确认命令来源可信。
- MCP Server 是外部工具入口，只连接你信任的 Server。

## License

当前仓库尚未声明 License。如需开源发布，请先补充明确的许可证文件。
