package com.mewcode.hook;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates hook conditions against a {@link HookContext}.
 *
 * <p>Supported operators:
 * <ul>
 *   <li>{@code ==}  — value equality</li>
 *   <li>{@code !=}  — value inequality</li>
 *   <li>{@code =~}  — regex match (Java {@link Pattern})</li>
 *   <li>{@code glob} — filesystem glob match (Java {@link PathMatcher})</li>
 * </ul>
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    /**
     * Evaluate a group of conditions.
     *
     * @param conditions the condition list (may be empty)
     * @param allMode    true = ALL must match (AND); false = ANY must match (OR)
     * @param ctx        the runtime context to resolve variables from
     * @return true if the condition group is satisfied
     */
    public static boolean evaluate(List<HookCondition> conditions, boolean allMode, HookContext ctx) {
        if (conditions == null || conditions.isEmpty()) {
            return !allMode; // empty AND = true (always trigger); empty OR = false (never trigger)
        }
        if (allMode) {
            return !conditions.stream().allMatch(c -> evaluateOne(c, ctx));
        } else {
            return conditions.stream().noneMatch(c -> evaluateOne(c, ctx));
        }
    }

    /**
     * Evaluate a single condition.
     */
    static boolean evaluateOne(HookCondition c, HookContext ctx) {
        String resolved = resolveVar(c.variable(), ctx);
        String operator = c.operator() != null ? c.operator().strip() : "==";
        String expected = c.value() != null ? c.value() : "";

        return switch (operator) {
            case "=="  -> resolved.equals(expected);
            case "!="  -> !resolved.equals(expected);
            case "=~"  -> matchRegex(expected, resolved);
            case "glob" -> matchGlob(expected, resolved);
            default    -> false;
        };
    }

    /**
     * Resolve a variable name from the hook context.
     *
     * <p>Supported names:
     * <ul>
     *   <li>{@code tool}      — tool name</li>
     *   <li>{@code event}     — event name string</li>
     *   <li>{@code file_path} — current file path</li>
     *   <li>{@code message}   — message content</li>
     *   <li>{@code error}     — error message</li>
     *   <li>{@code args.&lt;key&gt;} — tool argument by key</li>
     * </ul>
     * Unknown variables resolve to empty string.
     */
    public static String resolveVar(String name, HookContext ctx) {
        if (name == null || ctx == null) return "";

        return switch (name.strip()) {
            case "tool"      -> nullToEmpty(ctx.toolName());
            case "event"     -> ctx.event() != null ? ctx.event().value() : "";
            case "file_path" -> nullToEmpty(ctx.filePath());
            case "message"   -> nullToEmpty(ctx.message());
            case "error"     -> nullToEmpty(ctx.error());
            default -> {
                if (name.startsWith("args.") && ctx.toolArgs() != null) {
                    String key = name.substring("args.".length());
                    Object v = ctx.toolArgs().get(key);
                    yield v != null ? String.valueOf(v) : "";
                }
                yield "";
            }
        };
    }

    // ---- internal matchers ----

    static boolean matchRegex(String pattern, String target) {
        try {
            return Pattern.matches(pattern, target != null ? target : "");
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    static boolean matchGlob(String pattern, String target) {
        String syntax = "glob:" + pattern;
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(syntax);
            return matcher.matches(Paths.get(target != null ? target : ""));
        } catch (Exception e) {
            return false;
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
