package com.familyguard.agent;

import java.time.Duration;

/** Reintento acotado sin bucles rápidos cuando la red o el backend no están disponibles. */
final class RetryBackoff {
    private final long maximumSeconds;
    RetryBackoff() { this(300); }
    RetryBackoff(long maximumSeconds) { this.maximumSeconds = maximumSeconds; }
    Duration delay(int consecutiveFailures) {
        int exponent = Math.max(0, Math.min(20, consecutiveFailures));
        return Duration.ofSeconds(Math.min(maximumSeconds, 1L << exponent));
    }
}
