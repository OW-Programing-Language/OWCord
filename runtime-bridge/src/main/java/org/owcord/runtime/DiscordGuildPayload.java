package org.owcord.runtime;

import dev.oreo.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Narrow allowlist for changes: text channels and non-privileged roles only.
 * The SDK deliberately does not expose bulk destructive operations.
 */
final class DiscordGuildPayload {
    private DiscordGuildPayload() {}

    static String newTextChannel(String name, String topic) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", 0); // guild text channel, never category/voice/forum
        out.put("name", channelName(name));
        if (topic != null && !topic.isEmpty()) out.put("topic", topic(topic));
        return Json.write(out);
    }

    static String updateTextChannel(String name, String topic) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", channelName(name));
        out.put("topic", topic(topic));
        return Json.write(out);
    }

    static String newSafeRole(String name) {
        return Json.write(Map.of("name", roleName(name), "permissions", "0",
            "hoist", false, "mentionable", false));
    }

    static String renameRole(String name) {
        return Json.write(Map.of("name", roleName(name)));
    }

    static String auditReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 160
            || reason.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("An audit reason of 1-160 printable characters is required");
        String encoded = URLEncoder.encode(reason, StandardCharsets.UTF_8).replace("+", "%20");
        if (encoded.length() > 512)
            throw new IllegalArgumentException("Audit reason exceeds Discord header limit");
        return encoded;
    }

    private static String channelName(String name) {
        if (name == null || name.isBlank() || name.length() > 100
            || name.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("Invalid channel name");
        return name;
    }

    private static String roleName(String name) {
        if (name == null || name.isBlank() || name.length() > 100
            || name.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("Invalid role name");
        return name;
    }

    private static String topic(String value) {
        if (value == null || value.length() > 1024
            || value.chars().anyMatch(c -> c == 0))
            throw new IllegalArgumentException("Invalid channel topic");
        return value;
    }
}
