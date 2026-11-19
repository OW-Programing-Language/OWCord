package org.owcord.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayReconnectPolicyTest {
    @Test void boundedExponentialDelaysAndResetAreStable() {
        GatewayReconnectPolicy policy = new GatewayReconnectPolicy();
        assertBetween(policy.nextDelayMillis(0), 1000, 1200);
        assertBetween(policy.nextDelayMillis(0), 2000, 2400);
        assertBetween(policy.nextDelayMillis(0), 4000, 4800);
        for (int i = 0; i < 80; i++) {
            assertBetween(policy.nextDelayMillis(0), 1000, 60000);
        }
        assertEquals(83, policy.consecutiveFailures());
        policy.reset();
        assertEquals(0, policy.consecutiveFailures());
        assertBetween(policy.nextDelayMillis(0), 1000, 1200);
    }

    @Test void rateLimitedCloseCodeNeverReconnectsFasterThanTenSeconds() {
        GatewayReconnectPolicy policy = new GatewayReconnectPolicy();
        assertBetween(policy.nextDelayMillis(4008), 10000, 12000);
        assertBetween(policy.nextDelayMillis(4008), 10000, 12000);
    }

    private static void assertBetween(long value, long low, long high) {
        assertTrue(value >= low && value <= high, "Delay " + value + " not in [" + low + "," + high + "]");
    }
}
