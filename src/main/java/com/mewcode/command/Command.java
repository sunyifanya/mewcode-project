package com.mewcode.command;

import java.util.Locale;

/**
 * A slash command metadata record.
 *
 * <p>Holds the canonical name (without leading slash), description, aliases,
 * usage example, type, optional parameter hint, and visibility flag.
 * Handler functions are managed separately in {@link CommandRegistry}.
 *
 * @param name        canonical name without the leading slash (e.g. "help")
 * @param description one-line description shown in /help output
 * @param aliases     alternative names (e.g. {"h", "?"} for help)
 * @param usage       short usage example (e.g. "/help <command>")
 * @param type        how the command is dispatched
 * @param paramHint   optional hint for parameters shown in /help (e.g. "<command>")
 * @param hidden      if true, omitted from /help listings and Tab completion
 */
public record Command(
        String name,
        String description,
        String[] aliases,
        String usage,
        CommandType type,
        String paramHint,
        boolean hidden
) {

    /**
     * Returns {@code true} when {@code input} matches the canonical name
     * or any alias (case-insensitive comparison).
     */
    public boolean matches(String input) {
        if (name.equalsIgnoreCase(input)) {
            return true;
        }
        for (var alias : aliases) {
            if (alias.equalsIgnoreCase(input)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the name in lowercase for case-insensitive keying.
     */
    public String lowerName() {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Convenience constructor without paramHint and usage.
     */
    public Command(String name, String description, String[] aliases,
                   CommandType type, boolean hidden) {
        this(name, description, aliases, "/" + name, type, "", hidden);
    }
}
