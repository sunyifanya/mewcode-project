package com.mewcode.hook;

/**
 * Executes a {@code prompt} hook action by rendering the template message.
 *
 * <p>The rendered message is returned as {@link HookResult#output()} and can
 * be injected into the conversation by the caller (e.g. added as a system-reminder).
 */
public final class PromptExecutor {

    private PromptExecutor() {}

    /**
     * Render a prompt hook's message template.
     *
     * @param config the hook configuration
     * @param ctx    the runtime context (for template rendering)
     * @return the result containing the rendered message
     */
    public static HookResult execute(HookConfig config, HookContext ctx) {
        String rendered = TemplateEngine.render(config.action().message(), ctx);
        return new HookResult(config.id(), rendered, true, config.reject());
    }
}
