package com.mewcode.hook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Executes an {@code http} hook action using {@link java.net.http.HttpClient}.
 *
 * <p>Supports template rendering on {@code url}, {@code headers}, and {@code body}.
 * Timeout is fixed at 10 seconds to avoid blocking the agent.
 */
public final class HttpExecutor {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpExecutor() {}

    /**
     * Execute an HTTP hook action.
     *
     * @param config the hook configuration
     * @param ctx    the runtime context (for template rendering)
     * @return the result containing the response body
     */
    public static HookResult execute(HookConfig config, HookContext ctx) {
        HookAction action = config.action();

        String renderedUrl = TemplateEngine.render(action.url(), ctx);
        String method = action.method() != null && !action.method().isBlank()
                ? action.method().toUpperCase() : "GET";
        String renderedBody = TemplateEngine.render(action.body(), ctx);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(renderedUrl))
                    .timeout(Duration.ofSeconds(10));

            // Render headers
            if (action.headers() != null) {
                for (Map.Entry<String, String> entry : action.headers().entrySet()) {
                    String renderedValue = TemplateEngine.render(entry.getValue(), ctx);
                    builder.header(entry.getKey(), renderedValue);
                }
            }

            // Body only for methods that support it
            if (("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))
                    && renderedBody != null && !renderedBody.isEmpty()) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(renderedBody));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 400;
            return new HookResult(config.id(), response.body(), success, config.reject());

        } catch (IOException e) {
            return new HookResult(config.id(),
                    "HTTP hook failed (connect/timeout): " + e.getMessage(), false, config.reject());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult(config.id(), "HTTP hook interrupted", false, config.reject());
        } catch (IllegalArgumentException e) {
            return new HookResult(config.id(),
                    "HTTP hook invalid URL: " + e.getMessage(), false, config.reject());
        }
    }
}
