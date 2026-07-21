package com.mewcode.hook;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ${var}} placeholders in hook prompt messages and HTTP bodies.
 * Uses the same variable resolution as {@link ConditionEvaluator#resolveVar}.
 */
public final class TemplateEngine {

    /** Matches ${varname} — varname may not contain '}' */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    private TemplateEngine() {}

    /**
     * Render a template by replacing all {@code ${var}} placeholders
     * with their resolved values from the context.
     */
    public static String render(String template, HookContext hookContext) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder stringBuilder = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = ConditionEvaluator.resolveVar(varName, hookContext);
            matcher.appendReplacement(stringBuilder, Matcher.quoteReplacement(value != null ? value : ""));
        }
        matcher.appendTail(stringBuilder);

        return stringBuilder.toString();
    }
}
