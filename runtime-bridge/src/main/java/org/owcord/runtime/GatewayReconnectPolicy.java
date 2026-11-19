package org.owcord.runtime;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded per-session reconnect policy. Full jitter prevents reconnect storms.
 * Fresh sessions and resumable sessions use the same attempt counter until READY.
 */
final class GatewayReconnectPolicy {
    private int failures;

    synchronized long nextDelayMillis(int closeCode) {
        int exponent = Math.min(failures, 6);
        failures = Math.min(failures + 1, 100);
        long base = Math.min(60_000L, 1000L << exponent);
        if (closeCode == 4008) base = Math.max(base, 10_000L);
        // Minimum 1 second; jitter up to 20%, bounded to 60 seconds.
        long spread = Math.min(60_000L - base, Math.max(1L, base / 5));
        return Math.min(60_000L, base + ThreadLocalRandom.current().nextLong(spread + 1));
    }

    synchronized int consecutiveFailures() { return failures; }

    synchronized void reset() { failures = 0; }
}
