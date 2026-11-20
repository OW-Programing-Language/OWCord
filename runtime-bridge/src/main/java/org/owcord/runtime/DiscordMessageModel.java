package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable minimal message/user DTOs across the Java-to-OW JSON boundary.
 * Snowflakes are always strings; arbitrary Discord payload fields are omitted.
 */
final class DiscordMessageModel {
    private DiscordMessageModel() {}

    static String message(String json) {
        return Json.write(normalizeMessage(Json.parse(json)));
    }

    static String messages(String json) {
        Object raw = Json.parse(json);
        if (!(raw instanceof List<?> items) || items.size() > 100)
            throw new IllegalStateException("Discord returned an invalid message page");
        List<Object> normalized = new ArrayList<>();
        for (Object item : items) normalized.add(normalizeMessage(item));
        return Json.write(normalized);
    }

    static String users(String json) {
        Object raw = Json.parse(json);
        if (!(raw instanceof List<?> items) || items.size() > 100)
            throw new IllegalStateException("Discord returned an invalid reaction page");
        List<Object> ids = new ArrayList<>();
        for (Object entry : items) ids.add(requiredId(asMap(entry), "id"));
        return Json.write(ids);
    }

    static String messageId(String json) {
        return requiredId(asMap(Json.parse(json)), "id");
    }

    private static Map<String, Object> normalizeMessage(Object entry) {
        Map<?, ?> source = asMap(entry);
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("id", requiredId(source, "id"));
        clean.put("channel_id", requiredId(source, "channel_id"));
        clean.put("content", optionalString(source, "content"));
        Object author = source.get("author");
        String authorId = "";
        if (author instanceof Map<?, ?> user && user.get("id") instanceof String id)
            authorId = DiscordRest.snowflake(id);
        clean.put("author_id", authorId);
        clean.put("timestamp", optionalString(source, "timestamp"));
        clean.put("edited_timestamp", optionalString(source, "edited_timestamp"));
        clean.put("pinned", Boolean.TRUE.equals(source.get("pinned")));
        return clean;
    }

    private static Map<?, ?> asMap(Object obj) {
        if (!(obj instanceof Map<?, ?> map))
            throw new IllegalStateException("Discord returned an invalid JSON object");
        return map;
    }
    private static String requiredId(Map<?, ?> item, String key) {
        Object value = item.get(key);
        if (!(value instanceof String id))
            throw new IllegalStateException("Discord response missing string " + key);
        return DiscordRest.snowflake(id);
    }
    private static String optionalString(Map<?, ?> item, String key) {
        Object value = item.get(key);
        if (value == null) return "";
        if (!(value instanceof String s))
            throw new IllegalStateException("Discord response has invalid " + key);
        return s;
    }
}
