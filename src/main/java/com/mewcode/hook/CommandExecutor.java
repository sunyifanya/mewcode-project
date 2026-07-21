package com.mewcode.hook;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Executes a {@code command} hook action via {@link ProcessBuilder}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Template rendering on the command string before execution</li>
 *   <li>Environment variables ({@code MEWCODE_EVENT}, {@code MEWCODE_TOOL})</li>
 *   <li>{@code timeout} — in seconds; kills the process if exceeded</li>
 *   <li>{@code background} — runs asynchronously (daemon thread), returns immediately</li>
 * </ul>
 */
public final class CommandExecutor {

    private CommandExecutor() {}

    /**
     * Execute a command hook action.
     *
     * @param config the hook configuration
     * @param hookContext    the runtime context (for template rendering and env vars)
     * @return the result of execution
     */
    public static HookResult execute(HookConfig config, HookContext hookContext) {
        HookAction hookAction = config.action();

        // Render template variables in the command string
        String renderedCommand = TemplateEngine.render(hookAction.command(), hookContext);

        int timeout = hookAction.timeout() > 0 ? hookAction.timeout() : 30;

        if (hookAction.background()) {
            runInBackground(config.id(), renderedCommand, hookContext);
            return new HookResult(config.id(), "", true, config.reject());
        }

        return runForeground(config.id(), renderedCommand, hookContext, timeout, config.reject());
    }

    private static HookResult runForeground(String hookId, String command, HookContext ctx,
                                            int timeoutSec, boolean reject) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
            injectEnv(processBuilder, ctx);

            Process process = processBuilder.start();
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);

            String stdout = new String(process.getInputStream().readAllBytes()).strip();
            String stderr = new String(process.getErrorStream().readAllBytes()).strip();

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                String output = merge(stdout, stderr);
                output = output.isEmpty()
                        ? "[Hook timed out after " + timeoutSec + "s]"
                        : output + "\n[Hook timed out after " + timeoutSec + "s]";
                return new HookResult(hookId, output, false, reject);
            }

            int exitCode = process.exitValue();
            String output = merge(stdout, stderr);
            return new HookResult(hookId, output, exitCode == 0, reject);
        } catch (IOException e) {
            return new HookResult(hookId, "Failed to execute hook command: " + e.getMessage(), false, reject);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult(hookId, "Hook command interrupted", false, reject);
        }
    }

    private static void runInBackground(String hookId, String command, HookContext ctx) {
        Thread bg = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                injectEnv(pb, ctx);
                Process proc = pb.start();
                proc.waitFor();
            } catch (Exception ignored) {
                // Background failures are silent — logged only if error logger is wired
            }
        }, "hook-bg-" + hookId);
        bg.setDaemon(true);
        bg.start();
    }

    private static void injectEnv(ProcessBuilder processBuilder, HookContext ctx) {
        var environment = processBuilder.environment();
        environment.put("MEWCODE_EVENT", ctx.event() != null ? ctx.event().value() : "");
        environment.put("MEWCODE_TOOL", ctx.toolName() != null ? ctx.toolName() : "");
    }

    private static String merge(String stdout, String stderr) {
        if (stdout.isEmpty() && stderr.isEmpty()) return "";
        if (stdout.isEmpty()) return stderr;
        if (stderr.isEmpty()) return stdout;
        return stdout + "\n" + stderr;
    }
}
