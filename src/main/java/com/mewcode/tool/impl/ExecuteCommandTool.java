package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Executes a shell command with timeout control and a safety blacklist.
 *
 * Windows: runs via cmd /c
 * Unix:    runs via /bin/sh -c
 */
public class ExecuteCommandTool implements Tool {

    private final Path workingDir;
    private final int timeoutSeconds;

    private static final List<Pattern> BLACKLIST = buildBlacklist();

    public ExecuteCommandTool(String workingDirectory, int timeoutSeconds) {
        this.workingDir = Paths.get(workingDirectory).toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String getName() {
        return "execute_command";
    }

    @Override
    public String getDescription() {
        return "在系统 Shell 中执行命令字符串，返回 stdout、stderr 和退出码。" +
               "带超时控制（默认 " + timeoutSeconds + " 秒）。危险命令会被安全策略拒绝。";
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
                        "command", Map.<String, Object>of("type", "string", "description", "要执行的命令字符串"),
                        "timeout_seconds", Map.<String, Object>of("type", "integer", "description", "超时时间（秒），覆盖默认值 " + timeoutSeconds + " 秒")
                ),
                "required", List.of("command")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String command = (String) params.get("command");
        if (command == null || command.isBlank()) {
            return new ToolResult(false, "缺少必填参数 command", "INVALID_PARAMS");
        }

        // --- blacklist check ---
        for (Pattern p : BLACKLIST) {
            if (p.matcher(command).find()) {
                return new ToolResult(false,
                        "命令被安全策略拒绝（匹配规则: " + p.pattern() + "）",
                        "BLACKLISTED");
            }
        }

        // Determine effective timeout
        int effectiveTimeout = timeoutSeconds;
        Object timeoutParam = params.get("timeout_seconds");
        if (timeoutParam instanceof Number num && num.intValue() > 0) {
            effectiveTimeout = num.intValue();
        }

        // Build shell command
        List<String> cmdList = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            cmdList.add("cmd");
            cmdList.add("/c");
        } else {
            cmdList.add("/bin/sh");
            cmdList.add("-c");
        }
        cmdList.add(command);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(false); // keep stdout/stderr separate

            Process process = pb.start();

            // Read stdout and stderr in separate threads to avoid deadlock
            ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();

            Thread stdoutThread = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(stdoutBuf);
                } catch (IOException ignored) {}
            }, "cmd-stdout");
            stdoutThread.start();

            Thread stderrThread = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(stderrBuf);
                } catch (IOException ignored) {}
            }, "cmd-stderr");
            stderrThread.start();

            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                // Give threads a moment to collect partial output
                stdoutThread.join(1000);
                stderrThread.join(1000);
                return new ToolResult(false,
                        "命令超时（" + effectiveTimeout + " 秒）\n" +
                        "stdout:\n" + stdoutBuf + "\n" +
                        "stderr:\n" + stderrBuf,
                        "TIMEOUT");
            }

            // Wait for reader threads to finish
            stdoutThread.join(5000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();
            String stdout = stdoutBuf.toString();
            String stderr = stderrBuf.toString();

            StringBuilder output = new StringBuilder();
            if (!stdout.isEmpty()) {
                output.append(stdout);
            }
            if (!stderr.isEmpty()) {
                if (!output.isEmpty()) output.append("\n");
                output.append(stderr);
            }
            output.append("\n退出码: ").append(exitCode);

            return new ToolResult(true, output.toString().trim());
        } catch (IOException e) {
            return new ToolResult(false, "命令执行失败: " + e.getMessage(), "IO_ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, "命令执行被中断", "TIMEOUT");
        }
    }

    // --- blacklist patterns ---

    private static List<Pattern> buildBlacklist() {
        List<Pattern> list = new ArrayList<>();
        // Recursive root deletion (Unix)
        list.add(Pattern.compile("rm\\s+-r[f]?\\s+/", Pattern.CASE_INSENSITIVE));
        // Recursive root deletion (Windows)
        list.add(Pattern.compile("(del|erase)\\s+/[fsq]\\s+[a-z]:\\\\", Pattern.CASE_INSENSITIVE));
        list.add(Pattern.compile("rmdir\\s+/s\\s+[a-z]:\\\\", Pattern.CASE_INSENSITIVE));
        // Shutdown / reboot
        list.add(Pattern.compile("(shutdown|reboot|halt|poweroff)\\b", Pattern.CASE_INSENSITIVE));
        list.add(Pattern.compile("init\\s+[06]\\b"));
        // Disk formatting
        list.add(Pattern.compile("mkfs\\.\\w+", Pattern.CASE_INSENSITIVE));
        list.add(Pattern.compile("format\\s+[a-z]:", Pattern.CASE_INSENSITIVE));
        // Block device overwrite
        list.add(Pattern.compile("dd\\s+.*of=/dev/", Pattern.CASE_INSENSITIVE));
        list.add(Pattern.compile(">[\\s]*/dev/sd[a-z]"));
        // Fork bomb
        list.add(Pattern.compile(":\\(\\).*\\{\\s*:\\|:"));
        list.add(Pattern.compile("fork\\s*bomb", Pattern.CASE_INSENSITIVE));
        return list;
    }
}
