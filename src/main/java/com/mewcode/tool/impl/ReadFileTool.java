package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Reads a file and returns its contents with line numbers (cat -n format).
 * Maximum 2000 lines; truncated with a note if exceeded.
 */
public class ReadFileTool implements Tool {

    private static final int MAX_LINES = 2000;

    private final Path workingDir;

    public ReadFileTool(String workingDirectory) {
        this.workingDir = Paths.get(workingDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "读取指定路径的文件全部内容，输出带行号，上限2000行。超出时截断并在末尾注明。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.<String, Object>of(
                        "file_path", Map.<String, Object>of("type", "string", "description", "要读取的文件路径，相对于项目根目录"),
                        "offset", Map.<String, Object>of("type", "integer", "description", "从第几行开始读取（0-based）", "default", 0),
                        "limit", Map.<String, Object>of("type", "integer", "description", "最多读取行数", "default", 2000)
                ),
                "required", List.of("file_path")
        );
    }

    @Override
    public boolean shouldDefer() {
        return false; // built-in tool, always visible
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String filePathStr = (String) params.get("file_path");
        if (filePathStr == null || filePathStr.isBlank()) {
            return new ToolResult(false, "缺少必填参数 file_path", "INVALID_PARAMS");
        }

        Path filePath = workingDir.resolve(filePathStr).normalize();
        if (!filePath.startsWith(workingDir)) {
            return new ToolResult(false, "路径穿越检测: " + filePathStr, "PATH_TRAVERSAL");
        }

        if (!Files.exists(filePath)) {
            return new ToolResult(false, "文件不存在: " + filePathStr, "FILE_NOT_FOUND");
        }
        if (!Files.isRegularFile(filePath)) {
            return new ToolResult(false, "路径不是文件: " + filePathStr, "IO_ERROR");
        }
        if (!Files.isReadable(filePath)) {
            return new ToolResult(false, "文件不可读: " + filePathStr, "IO_ERROR");
        }

        try {
            List<String> allLines = Files.readAllLines(filePath);
            int totalLines = allLines.size();
            boolean truncated = totalLines > MAX_LINES;
            int linesToRead = Math.min(totalLines, MAX_LINES);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < linesToRead; i++) {
                sb.append(String.format("%6d\t%s%n", i + 1, allLines.get(i)));
            }
            if (truncated) {
                sb.append(String.format("... (截断，文件共 %d 行)%n", totalLines));
            }

            return new ToolResult(true, sb.toString());
        } catch (IOException e) {
            return new ToolResult(false, "读取文件失败: " + e.getMessage(), "IO_ERROR");
        }
    }
}
