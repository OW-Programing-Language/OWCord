package org.owcord.runtime;

import java.math.BigInteger;
import java.util.Map;

/**
 * Discord permissions are unsigned decimal-string bitsets, not OW integers.
 * BigInteger preserves flags beyond JS number/32-bit and handles Administrator.
 */
final class DiscordPermissions {
    private static final BigInteger MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger ADMINISTRATOR = BigInteger.valueOf(8);
    private DiscordPermissions() { }

    static BigInteger parse(String raw) {
        if (raw == null || !raw.matches("(0|[1-9][0-9]{0,19})"))
            throw new IllegalArgumentException("Discord permissions must be canonical unsigned decimal strings");
        BigInteger n = new BigInteger(raw);
        if (n.compareTo(MAX) > 0)
            throw new IllegalArgumentException("Discord permissions must fit an unsigned 64-bit field");
        return n;
    }

    static boolean has(String granted, String required) {
        BigInteger needed = parse(required);
        BigInteger available = parse(granted);
        return available.and(ADMINISTRATOR).signum() != 0 ||
            available.and(needed).equals(needed);
    }

    static String defaultPermissions(String value) {
        return parse(value).toString();
    }

    // Fail closed on DMs and incomplete Gateway interaction payloads.
    static boolean memberCan(String granted, String required) {
        if (granted == null || granted.isBlank()) return false;
        return has(granted, required);
    }
}
