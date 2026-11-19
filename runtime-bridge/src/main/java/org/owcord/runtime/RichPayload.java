package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, intentionally small Discord legacy-message/UI subset.
 * Accepts structured JSON from OW and re-serializes only approved fields.
 * Tokens, authentication and transport remain exclusively in DiscordRest.
 */
final class RichPayload {
    private RichPayload() {}

    static String message(String source) {
        return Json.write(messageData(source, false));
    }

    static String callback(String source, int type, boolean ephemeral) {
        if (type != 4 && type != 7) throw new IllegalArgumentException("Unsupported message callback");
        return Json.write(Map.of("type", type, "data", messageData(source, ephemeral)));
    }

    static String modal(String source) {
        Map<?, ?> raw = object(source);
        String id = required(raw, "custom_id", 100);
        String title = required(raw, "title", 45);
        List<?> fields = list(raw, "components", 1, 5);
        List<Object> labels = new ArrayList<>();
        for (Object value : fields) {
            Map<?, ?> label = asMap(value);
            numericType(label, 18);
            String text = required(label, "label", 45);
            Map<?, ?> child = asMap(label.get("component"));
            numericType(child, 4);
            String childId = required(child, "custom_id", 100);
            int style = integer(child, "style", 1);
            if (style != 1 && style != 2) throw new IllegalArgumentException("Input style must be short or paragraph");
            String placeholder = optional(child, "placeholder", 100);
            boolean required = booleanValue(child, "required", true);
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("type", 4);
            field.put("custom_id", childId);
            field.put("style", style);
            field.put("required", required);
            if (!placeholder.isEmpty()) field.put("placeholder", placeholder);
            labels.add(Map.of("type", 18, "label", text, "component", field));
        }
        return Json.write(Map.of("type", 9,
            "data", Map.of("custom_id", id, "title", title, "components", labels)));
    }

    private static Map<String, Object> messageData(String source, boolean ephemeral) {
        Map<?, ?> raw = object(source);
        String content = optional(raw, "content", 2000);
        List<?> embeds = list(raw, "embeds", 0, 10);
        List<?> rows = list(raw, "components", 0, 5);
        if (content.isEmpty() && embeds.isEmpty() && rows.isEmpty())
            throw new IllegalArgumentException("Message must have content, embeds or components");
        List<Object> validatedEmbeds = new ArrayList<>();
        for (Object item : embeds) {
            Map<?, ?> embed = asMap(item);
            String title = optional(embed, "title", 256);
            String description = optional(embed, "description", 4096);
            int color = integer(embed, "color", 0);
            if (color < 0 || color > 0xFFFFFF) throw new IllegalArgumentException("Invalid embed color");
            if (title.isEmpty() && description.isEmpty())
                throw new IllegalArgumentException("Embed needs a title or description");
            Map<String, Object> validated = new LinkedHashMap<>();
            if (!title.isEmpty()) validated.put("title", title);
            if (!description.isEmpty()) validated.put("description", description);
            validated.put("color", color);
            validatedEmbeds.add(validated);
        }
        List<Object> validatedRows = new ArrayList<>();
        for (Object item : rows) validatedRows.add(messageRow(item));
        Map<String, Object> safe = new LinkedHashMap<>();
        if (!content.isEmpty()) safe.put("content", content);
        if (!validatedEmbeds.isEmpty()) safe.put("embeds", validatedEmbeds);
        if (!validatedRows.isEmpty()) safe.put("components", validatedRows);
        safe.put("allowed_mentions", Map.of("parse", List.of()));
        if (ephemeral) safe.put("flags", 64);
        return safe;
    }

    private static Map<String, Object> messageRow(Object rawRow) {
        Map<?, ?> row = asMap(rawRow);
        numericType(row, 1);
        List<?> components = list(row, "components", 1, 5);
        List<Object> checked = new ArrayList<>();
        boolean sawSelect = false;
        for (Object item : components) {
            Map<?, ?> part = asMap(item);
            int type = integer(part, "type", -1);
            if (type == 2) {
                if (sawSelect) throw new IllegalArgumentException("Cannot mix buttons and select menus");
                int style = integer(part, "style", 0);
                if (style < 1 || style > 4) throw new IllegalArgumentException("Only interactive button styles 1-4 supported");
                checked.add(Map.of("type", 2, "style", style,
                    "custom_id", required(part, "custom_id", 100),
                    "label", required(part, "label", 80)));
            } else if (type == 3) {
                if (sawSelect || components.size() != 1)
                    throw new IllegalArgumentException("String select must occupy its own action row");
                sawSelect = true;
                String id = required(part, "custom_id", 100);
                String placeholder = optional(part, "placeholder", 150);
                List<?> options = list(part, "options", 1, 25);
                List<Object> parsedOptions = new ArrayList<>();
                for (Object optionValue : options) {
                    Map<?, ?> option = asMap(optionValue);
                    Map<String, Object> parsed = new LinkedHashMap<>();
                    parsed.put("label", required(option, "label", 100));
                    parsed.put("value", required(option, "value", 100));
                    String description = optional(option, "description", 100);
                    if (!description.isEmpty()) parsed.put("description", description);
                    parsedOptions.add(parsed);
                }
                Map<String, Object> menu = new LinkedHashMap<>();
                menu.put("type", 3);
                menu.put("custom_id", id);
                menu.put("options", parsedOptions);
                if (!placeholder.isEmpty()) menu.put("placeholder", placeholder);
                menu.put("min_values", 1);
                menu.put("max_values", 1);
                checked.add(menu);
            } else {
                throw new IllegalArgumentException("Only button and string-select components supported");
            }
        }
        return Map.of("type", 1, "components", checked);
    }

    private static Map<?, ?> object(String source) {
        if (source == null || source.length() > 32_768)
            throw new IllegalArgumentException("Rich payload size limit exceeded");
        return asMap(Json.parse(source));
    }
    private static Map<?, ?> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected JSON object");
        return map;
    }
    private static List<?> list(Map<?, ?> value, String key, int min, int max) {
        Object x = value.get(key);
        List<?> result;
        if (x == null && min == 0) result = List.of();
        else if (x instanceof List<?> list) result = list;
        else throw new IllegalArgumentException("Expected JSON array: " + key);
        if (result.size() < min || result.size() > max)
            throw new IllegalArgumentException("Invalid count: " + key);
        return result;
    }
    private static String required(Map<?, ?> m, String key, int max) {
        String value = optional(m, key, max);
        if (value.isBlank()) throw new IllegalArgumentException("Required field: " + key);
        return value;
    }
    private static String optional(Map<?, ?> m, String key, int max) {
        Object value = m.get(key);
        if (value == null) return "";
        if (!(value instanceof String s) || s.length() > max)
            throw new IllegalArgumentException("Invalid text: " + key);
        return s;
    }
    private static int integer(Map<?, ?> m, String key, int fallback) {
        Object x = m.get(key);
        if (x == null) return fallback;
        if (!(x instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) ||
            n.doubleValue() < Integer.MIN_VALUE || n.doubleValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid integer: " + key);
        return n.intValue();
    }
    private static boolean booleanValue(Map<?, ?> m, String key, boolean fallback) {
        Object x = m.get(key);
        if (x == null) return fallback;
        if (!(x instanceof Boolean value)) throw new IllegalArgumentException("Invalid boolean: " + key);
        return value;
    }
    private static void numericType(Map<?, ?> m, int expected) {
        if (integer(m, "type", -1) != expected) throw new IllegalArgumentException("Wrong Discord component type");
    }
}
