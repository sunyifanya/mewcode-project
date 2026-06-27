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
     *
     * @param template the template string (may be null)
     * @param ctx      the hook context for variable resolution
     * @return the rendered string with placeholders replaced; null → ""
     */
    public static String render(String template, HookContext ctx) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();

        while (m.find()) {
            String varName = m.group(1);
            String value = ConditionEvaluator.resolveVar(varName, ctx);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : ""));
        }
        m.appendTail(sb);

        return sb.toString();
    }
}
