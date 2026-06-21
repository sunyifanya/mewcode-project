package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Writes content to a file, creating parent directories as needed.
 * Overwrites the file if it already exists.
 */
public class WriteFileTool implements Tool {

    private final Path workingDir;

    public WriteFileTool(String workingDirectory) {
        this.workingDir = Paths.get(workingDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.WRITE;
    }

    @Override
    public String getDescription() {
        return "将指定内容写入文件。父目录不存在时自动创建。目标已存在则覆盖。返回写入字节数。";
    }

    @Override
    public boolean shouldDefer() {
        return false; // built-in tool, always visible
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.<String, Object>of(
                "type", "object",
                "properties", Map.<String, Object>of(
                        "file_path", Map.<String, Object>of("type", "string", "description", "要写入的文件路径，相对于项目根目录"),
                        "content", Map.<String, Object>of("type", "string", "description", "要写入的文件内容")
                ),
                "required", List.of("file_path", "content")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String filePathStr = (String) params.get("file_path");
        if (filePathStr == null || filePathStr.isBlank()) {
            return new ToolResult(false, "缺少必填参数 file_path", "INVALID_PARAMS");
        }
        String content = (String) params.get("content");
        if (content == null) {
            return new ToolResult(false, "缺少必填参数 content", "INVALID_PARAMS");
        }

        Path filePath = workingDir.resolve(filePathStr).normalize();
        if (!filePath.startsWith(workingDir)) {
            return new ToolResult(false, "路径穿越检测: " + filePathStr, "PATH_TRAVERSAL");
        }

        try {
            // Create parent directories if needed
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(filePath, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long bytes = Files.size(filePath);
            return new ToolResult(true, "已写入 " + bytes + " 字节到 " + filePathStr);
        } catch (IOException e) {
            return new ToolResult(false, "写入文件失败: " + e.getMessage(), "IO_ERROR");
        }
    }
}
