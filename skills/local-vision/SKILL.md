---
name: local-vision
description: 使用本地 Ollama 部署的视觉模型（默认 minicpm-v:8b）识别图片内容、提取文字、分析图表。当用户要求"用本地模型识别/查看/读取/分析/描述图片"、提到"离线识图"、"Ollama 识图"、或者主 vision API 不可用需要降级时使用。不处理纯文本或音频文件。
allowed-tools: [Bash, Read, Write]
mode: inline
---

# 本地视觉识图 Skill

## ⛔ 禁止 `python -c` 内联代码

**无论任何情况下，都不要使用 `python -c "..."` 的多行内联写法来调用 Ollama API。**

Windows 上通过 `cmd /c` 执行多行双引号字符串时，换行处的引号会被 shell 错误解析，Python 收到的代码被截断或为空，导致输出空白且退出码为 0，无法通过错误信息排查。

**正确做法：始终使用本 skill 自带的 `ollama_vision.py` 脚本文件**，它已经处理好了编码、压缩、结果文件写入等问题。

```bash
# ✅ 正确
python ${SKILL_DIR}/scripts/ollama_vision.py "/path/to/image.png" "问题"

# ❌ 错误 — Windows shell 会吞掉多行代码
python -c "
import ...
...
"
```

## 角色与能力

你使用本地 Ollama 部署的视觉语言模型来分析图片。适用于：
- 云 API 代理不可用时的降级方案
- 对隐私敏感、不希望图片离开本机的场景
- 开发者本地快速验证截图内容

## 前置条件

- **Ollama 已安装并运行**（默认 `http://127.0.0.1:11434`）
- **已拉取视觉模型**（默认 `minicpm-v:8b`，可通过环境变量覆盖）
- Python 3.10+（base64 编码）
- 支持的图片格式：`.jpg` `.jpeg` `.png` `.webp` `.bmp`

### 环境变量（可选）

```bash
OLLAMA_HOST=http://127.0.0.1:11434   # Ollama 地址，默认 127.0.0.1:11434
OLLAMA_VISION_MODEL=minicpm-v:8b    # 视觉模型名称
```

> 💡 其他可选视觉模型：`llava:7b`、`llava:13b`、`bakllava`。`minicpm-v:8b` 在体积和精度之间平衡较好（约 5.4GB）。

## 核心工作流

```
用户提供图片路径 → 检查 Ollama 可用性 → 确保视觉模型已拉取 → 调用 ollama_vision.py → 展示结果
```

### 第一步：检查 Ollama 是否运行

```bash
curl -s http://127.0.0.1:11434/
# 应返回 "Ollama is running"
```

如果未运行，提示用户启动 Ollama（`ollama serve`）。

### 第二步：确保视觉模型可用

```bash
# 检查已有模型
curl -s http://127.0.0.1:11434/api/tags | python -c "import sys,json; [print(m['name']) for m in json.load(sys.stdin).get('models',[])]"
```

如果默认模型 `minicpm-v:8b` 不存在，询问用户是否拉取：
```bash
ollama pull minicpm-v:8b
```

拉取需要下载约 5.4GB，请提前告知用户。

### 第三步：调用脚本分析图片

```bash
python ${SKILL_DIR}/scripts/ollama_vision.py "/path/to/image.png" "请详细描述这张图片的内容"
```

脚本参数：
| 参数 | 必需 | 说明 |
|------|------|------|
| `image_path` | ✅ | 本地图片绝对路径 |
| `question` | ❌ | 默认 "请详细描述这张图片中的内容" |

### 第四步：读取结果文件

脚本始终将完整分析结果写入临时文件（UTF-8 编码），返回 JSON 中包含文件路径：

```json
{
  "success": true,
  "model": "minicpm-v:8b",
  "description": "分析完成，结果已保存到: /tmp/ollama_vision_test-screenshot.png.txt",
  "output_file": "/tmp/ollama_vision_test-screenshot.png.txt"
}
```

**务必用 Read 工具读取 `output_file` 指向的文件**，将内容展示给用户。不要在终端直接输出中文——终端编码可能不一致导致乱码。

## 错误处理

| 现象 | 处理方式 |
|------|----------|
| Ollama 未运行 | 提示 `ollama serve` 启动 |
| 模型未拉取 | 询问用户是否执行 `ollama pull minicpm-v:8b` |
| 图片格式不支持 | 提示支持的格式 `.jpg` `.jpeg` `.png` `.webp` `.bmp` |
| 请求超时 | 图片可能过大或模型推理慢，等待后重试 |
| 终端乱码 | 读取 `output_file` 指向的 UTF-8 文件 |
| 图片过大 | 尝试用 Pillow 压缩后再发送 |

## 与云 API vision skill 的关系

本 skill 是**独立 skill**，不依赖云 API。当两者都可用时，优先使用云 API（速度更快、精度更高）；当云 API 不可用（HTTP 502/503、代理故障）或用户明确要求本地处理时，使用本 skill。

## 输出格式

分析结果直接展示给用户。如果截图包含代码，完整提取；如果是 UI 截图，描述布局、文字、组件；如果是图表，解读数据趋势。
