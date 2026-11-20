package org.owcord.runtime;

import dev.oreo.Json;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Constrained Discord moderation payloads; no silent bulk or message removal. */
final class DiscordModerationPayload {
    private DiscordModerationPayload() { }

    static String ban(int deleteMessageSeconds) {
        if (deleteMessageSeconds < 0 || deleteMessageSeconds > 604800)
            throw new IllegalArgumentException("Ban message deletion range must be 0-604800 seconds");
        return Json.write(Map.of("delete_message_seconds", deleteMessageSeconds));
    }

    static String timeout(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank())
            throw new IllegalArgumentException("Timeout expiry must be a UTC ISO-8601 timestamp");
        final Instant until;
        try { until = Instant.parse(isoTimestamp); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Invalid timeout expiry", e); }
        Instant now = Instant.now();
        if (!until.isAfter(now) || until.isAfter(now.plus(Duration.ofDays(28))))
            throw new IllegalArgumentException("Timeout must expire in the next 28 days");
        return Json.write(Map.of("communication_disabled_until", until.toString()));
    }

    static String clearTimeout() {
        return Json.write(java.util.Collections.singletonMap("communication_disabled_until", null));
    }
}
