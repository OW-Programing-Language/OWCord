package org.owcord.runtime;

import dev.oreo.Json;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Instance-scoped Discord rate limit tracker. Uses relative monotonic deadlines
 * (Reset-After / Retry-After), never host wall-clock timestamps.
 *
 * All DiscordRest requests are serialized by DiscordRest.request, so checks
 * and observations never race. This is intentionally not a distributed limiter.
 */
final class DiscordRateLimiter {
    private final Map<String, String> bucketByRoute = new HashMap<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();
    private long globalUntil;

    /** Hide interaction tokens and interaction snowflakes from in-memory route keys. */
    static String routeKey(String method, String path) {
        // Do not retain message IDs or pagination cursors as unique bucket keys.
        String clean = path.split("\\?", 2)[0];
        if (clean.matches("channels/[0-9]+/messages"))
            return method + " channels/*/messages";
        if (clean.matches("channels/[0-9]+/messages/[0-9]+"))
            return method + " channels/*/messages/*";
        if (clean.matches("channels/[0-9]+/messages/[0-9]+/reactions/[^/]+(?:/@me)?")) {
            return method + " channels/*/messages/*/reactions/*" +
                (clean.endsWith("/@me") ? "/@me" : "");
        }
        String cleanGuild = path.split("\\?", 2)[0];
        if (cleanGuild.matches("guilds/[0-9]+"))
            return method + " guilds/*";
        if (cleanGuild.matches("guilds/[0-9]+/(?:channels|roles|members)"))
            return method + " " + cleanGuild.replaceFirst("^guilds/[0-9]+", "guilds/*");
        if (cleanGuild.matches("guilds/[0-9]+/roles/[0-9]+"))
            return method + " guilds/*/roles/*";
        if (cleanGuild.matches("guilds/[0-9]+/members/[0-9]+"))
            return method + " guilds/*/members/*";
        if (cleanGuild.matches("guilds/[0-9]+/members/[0-9]+/roles/[0-9]+"))
            return method + " guilds/*/members/*/roles/*";
        if (cleanGuild.matches("channels/[0-9]+"))
            return method + " channels/*";
        if (path.matches("interactions/[0-9]+/[^/]+/callback"))
            return method + " interactions/*/callback";
        if (path.matches("webhooks/[0-9]+/[^/]+/messages/(?:@original|[0-9]+)"))
            return method + " webhooks/*/messages/*";
        if (path.matches("webhooks/[0-9]+/[^/]+"))
            return method + " webhooks/*";
        // Unknown future endpoints must not retain raw webhook/interaction
        // tokens, snowflakes, queries or attachment references in telemetry.
        if(clean.equals("gateway/bot")) return method+" gateway/bot";
        return method+" other/*";
    }

    /** Major Discord resource identifiers are not included in X-RateLimit-Bucket. */
    static String majorResource(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if (segments[i].equals("channels") || segments[i].equals("guilds"))
                return segments[i] + "/" + segments[i + 1];
            if (segments[i].equals("webhooks") && i + 2 < segments.length)
                return "webhook/" + segments[i + 1] + ":" + segments[i + 2].hashCode();
        }
        return "none";
    }

    synchronized long waitNanos(String route, String major, boolean authorized, long now) {
        long global = authorized ? globalUntil : 0;
        long byRoute = blockedUntil.getOrDefault("route:" + route + ":" + major, 0L);
        String sharedBucket = bucketByRoute.get(route);
        if (sharedBucket != null) {
            byRoute = Math.max(byRoute, blockedUntil.getOrDefault("bucket:" + sharedBucket + ":" + major, 0L));
        }
        return Math.max(0, Math.max(global, byRoute) - now);
    }

    synchronized void observe(String route, String major, boolean authorized, HttpResponse<String> response, long now) {
        String bucket = response.headers().firstValue("X-RateLimit-Bucket").orElse("");
        if (!bucket.isBlank() && bucket.length() <= 100) bucketByRoute.put(route, bucket);

        String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("");
        String resetAfter = response.headers().firstValue("X-RateLimit-Reset-After").orElse("");
        if ("0".equals(remaining)) {
            double reset = seconds(resetAfter, 0);
            if (reset > 0) block(route, major, now, reset);
        }

        if (response.statusCode() != 429) return;
        double retry = seconds(response.headers().firstValue("Retry-After").orElse(""), -1);
        if (retry < 0) retry = retryAfterBody(response.body());
        if (retry < 0) retry = 1;
        // A 429 always blocks the relevant scope, including the shared scope.
        block(route, major, now, retry);
        boolean global = authorized && (
            response.headers().firstValue("X-RateLimit-Global").orElse("").equalsIgnoreCase("true") ||
            response.headers().firstValue("X-RateLimit-Scope").orElse("").equalsIgnoreCase("global") ||
            isGlobalResponse(response.body()));
        if (global) globalUntil = Math.max(globalUntil, deadline(now, retry));
    }

    private void block(String route, String major, long now, double seconds) {
        long until = deadline(now, seconds);
        String shared = bucketByRoute.get(route);
        String key = shared == null ? "route:" + route : "bucket:" + shared + ":" + major;
        blockedUntil.merge(key, until, Math::max);
        // Preserve route-scoped waits when Discord reveals/changes bucket IDs later.
        blockedUntil.merge("route:" + route + ":" + major, until, Math::max);
    }

    private static long deadline(long now, double seconds) {
        return now + Math.max(1L, (long) Math.ceil(seconds * 1_000_000_000d));
    }

    private static double seconds(String raw, double fallback) {
        try {
            double parsed = Double.parseDouble(raw);
            return Double.isFinite(parsed) && parsed >= 0 && parsed <= 86400 ? parsed : fallback;
        } catch (RuntimeException e) { return fallback; }
    }

    private static double retryAfterBody(String body) {
        try {
            Object obj = Json.parse(body);
            if (obj instanceof Map<?, ?> data && data.get("retry_after") instanceof Number n) {
                return seconds(String.valueOf(n), -1);
            }
        } catch (RuntimeException ignored) { /* malformed response is not logged */ }
        return -1;
    }
    private static boolean isGlobalResponse(String body) {
        try {
            Object obj = Json.parse(body);
            return obj instanceof Map<?, ?> data && Boolean.TRUE.equals(data.get("global"));
        } catch (RuntimeException ignored) { return false; }
    }
}
