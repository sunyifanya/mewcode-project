package com.mewcode.provider;

/**
 * Immutable snapshot of prompt-cache metrics from the last API request.
 *
 * Both values can be zero (no cache activity) but never negative.
 * Use {@link #isEmpty()} to check whether the API returned any cache data.
 */
public final class CacheMetrics {

    private final long cacheCreationTokens;
    private final long cacheReadTokens;

    public CacheMetrics(long cacheCreationTokens, long cacheReadTokens) {
        this.cacheCreationTokens = cacheCreationTokens;
        this.cacheReadTokens = cacheReadTokens;
    }

    /** Tokens written into the prompt cache on this request. */
    public long getCacheCreationTokens() { return cacheCreationTokens; }

    /** Tokens read from a pre-existing cache entry on this request. */
    public long getCacheReadTokens() { return cacheReadTokens; }

    /** True when neither creation nor read activity was reported. */
    public boolean isEmpty() {
        return cacheCreationTokens == 0 && cacheReadTokens == 0;
    }

    @Override
    public String toString() {
        return "CacheMetrics{created=" + cacheCreationTokens + ", read=" + cacheReadTokens + "}";
    }
}
