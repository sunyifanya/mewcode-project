#!/usr/bin/env python3
"""Ollama 本地视觉模型调用脚本

通过 Ollama 的 /api/chat 接口，使用本地视觉语言模型分析图片。
默认使用 minicpm-v:8b，可通过环境变量 OLLAMA_VISION_MODEL 覆盖。

用法:
    python ollama_vision.py /path/to/image.jpg "描述这张图"
    python ollama_vision.py /path/to/image.png
"""

import base64
import json
import os
import sys
import urllib.request
import urllib.error

# ---- 配置（可通过环境变量覆盖） ----
OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434")
OLLAMA_VISION_MODEL = os.environ.get("OLLAMA_VISION_MODEL", "minicpm-v:8b")
TIMEOUT = int(os.environ.get("OLLAMA_VISION_TIMEOUT", "180"))
MAX_FILE_SIZE_MB = 10

ALLOWED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

# 备选视觉模型列表（当默认模型不可用时按顺序尝试）
FALLBACK_MODELS = ["llava:7b", "llava:13b", "bakllava"]


def check_ollama() -> bool:
    """检查 Ollama 服务是否运行"""
    try:
        req = urllib.request.Request(f"{OLLAMA_HOST}/", method="GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            body = resp.read().decode("utf-8")
            return "Ollama" in body
    except Exception:
        return False


def get_available_models() -> list:
    """获取已安装的模型列表"""
    try:
        req = urllib.request.Request(f"{OLLAMA_HOST}/api/tags", method="GET")
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return [m["name"] for m in data.get("models", [])]
    except Exception:
        return []


def find_vision_model() -> str | None:
    """找到一个可用的视觉模型

    优先级：OLLAMA_VISION_MODEL 环境变量 > FALLBACK_MODELS 列表
    返回模型名，或 None 表示没有可用模型。
    """
    available = get_available_models()

    # 先检查用户指定的模型
    if OLLAMA_VISION_MODEL in available:
        return OLLAMA_VISION_MODEL
    # 兼容短名匹配（如 minicpm-v 匹配 minicpm-v:8b）
    for m in available:
        if m.startswith(OLLAMA_VISION_MODEL.split(":")[0]):
            return m

    # 再检查备选列表
    for fm in FALLBACK_MODELS:
        if fm in available:
            return fm
        for m in available:
            if m.startswith(fm.split(":")[0]):
                return m

    # 没有找到任何视觉模型，返回默认模型名供用户拉取
    return None


def encode_image(path: str) -> tuple[str, str]:
    """读取图片文件并返回 (base64编码, 媒体类型)

    Raises:
        FileNotFoundError: 文件不存在
        ValueError: 格式不支持或文件过大
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f"图片不存在: {path}")

    ext = os.path.splitext(path)[1].lower()
    if ext not in ALLOWED_EXTENSIONS:
        raise ValueError(
            f"不支持的图片格式: {ext}，支持: {ALLOWED_EXTENSIONS}"
        )

    size_mb = os.path.getsize(path) / 1048576
    if size_mb > MAX_FILE_SIZE_MB:
        raise ValueError(
            f"图片过大: {size_mb:.1f}MB，超过 {MAX_FILE_SIZE_MB}MB 限制"
        )

    # 确定媒体类型
    media_map = {
        ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
        ".png": "image/png",
        ".webp": "image/webp",
        ".bmp": "image/bmp",
    }

    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8"), media_map[ext]


def try_compress(path: str, max_long_edge: int = 1024) -> str:
    """尝试压缩图片以适应模型限制

    Returns:
        压缩后的 base64 编码，或原始图片（如果不需要/无法压缩）
    """
    try:
        from PIL import Image
        import io

        img = Image.open(path)
        w, h = img.size
        if max(w, h) <= max_long_edge:
            # 不需要压缩，返回原图 base64
            return encode_image(path)[0]

        # 等比缩放
        ratio = max_long_edge / max(w, h)
        new_size = (int(w * ratio), int(h * ratio))
        img = img.resize(new_size, Image.LANCZOS)

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=85)
        return base64.b64encode(buf.getvalue()).decode("utf-8")
    except ImportError:
        return encode_image(path)[0]


def call_ollama(image_path: str, question: str) -> dict:
    """调用 Ollama vision API 分析图片

    Args:
        image_path: 本地图片绝对路径
        question: 用户问题

    Returns:
        {"success": True, "model": "...", "description": "...", "output_file": null}
        或 {"success": False, "error": "..."}
    """
    # 1. 检查 Ollama 状态
    if not check_ollama():
        return {
            "success": False,
            "error": (
                "Ollama 未运行。请先启动: ollama serve\n"
                f"然后拉取模型: ollama pull {OLLAMA_VISION_MODEL}"
            ),
        }

    # 2. 查找可用模型
    model = find_vision_model()
    if model is None:
        return {
            "success": False,
            "error": (
                f"未找到视觉模型。请先拉取:\n"
                f"  ollama pull {OLLAMA_VISION_MODEL}\n"
                f"或备选:\n"
                f"  ollama pull llava:7b"
            ),
        }

    # 3. 编码图片（优先原图，过大则压缩）
    try:
        img_b64, media_type = encode_image(image_path)
    except (FileNotFoundError, ValueError) as e:
        return {"success": False, "error": str(e)}

    # 如果图片过大（base64 > 某一阈值），尝试压缩
    if len(img_b64) > 1_000_000:  # ~750KB 原始图片
        try:
            img_b64 = try_compress(image_path)
            media_type = "image/jpeg"
        except Exception:
            pass  # 压缩失败则用原图

    # 4. 调用 Ollama API
    question_text = question or "请详细描述这张图片中的内容"

    payload = json.dumps({
        "model": model,
        "messages": [{
            "role": "user",
            "content": question_text,
            "images": [img_b64],
        }],
        "stream": False,
    }).encode("utf-8")

    req = urllib.request.Request(
        f"{OLLAMA_HOST}/api/chat",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            description = result["message"]["content"]

            # 始终写入临时文件，避免终端编码问题（GBK/UTF-8 不一致）
            import tempfile
            output_file = os.path.join(
                tempfile.gettempdir(),
                f"ollama_vision_{os.path.basename(image_path)}.txt"
            )
            with open(output_file, "w", encoding="utf-8") as f:
                f.write(description)

            return {
                "success": True,
                "model": model,
                "description": f"分析完成，结果已保存到: {output_file}",
                "output_file": output_file,
                "usage": result.get("usage", {}),
            }

    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return {"success": False, "error": f"Ollama HTTP {e.code}: {body[:300]}"}
    except urllib.error.URLError as e:
        return {"success": False, "error": f"网络错误: {e.reason}"}
    except json.JSONDecodeError:
        return {"success": False, "error": "Ollama 返回格式异常"}
    except Exception as e:
        return {"success": False, "error": str(e)}


def main():
    """命令行入口"""
    if len(sys.argv) < 2:
        print("用法: python ollama_vision.py <图片路径> [问题]")
        print("  python ollama_vision.py /path/to/image.jpg")
        print("  python ollama_vision.py /path/to/image.png '描述这张图'")
        print()
        print("环境变量:")
        print("  OLLAMA_HOST          Ollama 地址 (默认 http://127.0.0.1:11434)")
        print(f"  OLLAMA_VISION_MODEL  视觉模型名 (默认 {OLLAMA_VISION_MODEL})")
        sys.exit(1)

    image_path = sys.argv[1]
    question = sys.argv[2] if len(sys.argv) > 2 else ""

    result = call_ollama(image_path, question)
    print(json.dumps(result, ensure_ascii=False, indent=2))

    if not result.get("success"):
        sys.exit(1)


if __name__ == "__main__":
    main()
