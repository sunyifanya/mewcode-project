我正在构建一个终端 AI 编程助手（类似 Claude Code），项目名叫 MewCode，使用的编程语言是java。

每次我会提出一个初步的想法，需要你通过向我提问，帮助我澄清需求、挖掘边缘场景。澄清清楚后共创三份文档，按编号递增分别放入：

- `reference/spec/NN-feature-name-spec.md`
- `reference/tasks/NN-feature-name-tasks.md`
- `reference/checklist/NN-feature-name-checklist.md`

三个目录均在 `reference/` 下：`reference/spec/`、`reference/tasks/`、`reference/checklist/`。文件名带后缀（`-spec`/`-tasks`/`-checklist`），避免跨目录搜索时出现同名文件无法区分。

例如 `reference/spec/03-agent-loop-spec.md`、`reference/tasks/03-agent-loop-tasks.md`、`reference/checklist/03-agent-loop-checklist.md`。编号规则：查看各文件夹内已有的最大编号 +1。

# 三份文档的角色与边界

## spec.md
回答：要解决什么问题、做哪些能力、不做哪些、什么算完成。
写：背景、目标用户、能力清单（一句话一条）、非功能要求、设计骨架、Out of Scope
不写：具体函数名 / 参数名 / 默认值 / 错误文本 / 行号 / SDK 类型名
（这些是实现细节，spec 改一次就过期，维护爆炸）

## tasks.md
回答：按什么顺序做、每步动什么文件。
- 5~15 个任务，每个能在一次专注会话内完成
- 每个任务标注：影响文件、依赖任务、参考资料定位（精确到函数/行号都可以）
- 最后一定有「接入主流程」+「端到端验证」两个任务

## checklist.md
每一项必须可勾选、可观测，不许写「实现完整」「质量良好」。
- 把 spec 里被砍掉的具体值（错误文本、默认值、阈值）放进来作为验收项
- 写法举例：「`grep -r X` 返回 ≥3 条」「输入 Y 看到输出 Z」
- 至少一条端到端验收