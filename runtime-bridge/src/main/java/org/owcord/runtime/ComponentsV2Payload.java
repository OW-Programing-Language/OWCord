package org.owcord.runtime;

import dev.oreo.Json;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict Discord Components V2 message subset. The V2 flag is mandatory:
 * classic content and embeds must NOT accompany V2 components.
 *
 * Supported: action rows, sections, text displays, thumbnails (section accessory
 * only), media galleries, separators and containers. File attachments and other
 * v2 component kinds are intentionally not supported here.
 */
final class ComponentsV2Payload {
    static final int V2 = 1 << 15;
    static final int EPHEMERAL = 1 << 6;
    private ComponentsV2Payload() {}

    static String message(String json) { return Json.write(data(json, false)); }

    static String callback(String json, boolean ephemeral, boolean update) {
        if (update && ephemeral)
            throw new IllegalArgumentException("Cannot change ephemeral visibility on component update");
        return Json.write(Map.of("type", update ? 7 : 4, "data", data(json, ephemeral)));
    }

    private static Map<String, Object> data(String json, boolean ephemeral) {
        if (json == null || json.length() > 65536)
            throw new IllegalArgumentException("Components V2 payload exceeds 64 KiB");
        Object parsed = Json.parse(json);
        Map<?, ?> raw = object(parsed);
        if (raw.containsKey("content") || raw.containsKey("embeds") ||
            raw.containsKey("poll") || raw.containsKey("stickers"))
            throw new IllegalArgumentException("Components V2 cannot include legacy message content or embeds");
        Object flags = raw.get("flags");
        if (flags != null && number(flags, "flags") != V2)
            throw new IllegalArgumentException("Components V2 must use the exact IS_COMPONENTS_V2 flag");
        List<?> roots = array(raw.get("components"), 1, 10);
        Counter count = new Counter();
        List<Object> components = new ArrayList<>();
        for (Object comp : roots) components.add(component(comp, false, count));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("flags", V2 | (ephemeral ? EPHEMERAL : 0));
        payload.put("components", components);
        payload.put("allowed_mentions", Map.of("parse", List.of()));
        return payload;
    }

    private static Object component(Object input, boolean insideContainer, Counter count) {
        Map<?, ?> raw = object(input);
        int type = number(raw.get("type"), "type");
        count.next();
        return switch (type) {
            case 1 -> {
                // Reuse the already validated action-row subset (buttons and string selects).
                Map<String, Object> row = RichPayload.messageRow(raw);
                for (Object button : (List<?>) row.get("components")) {
                    count.next();
                    Map<?, ?> item = object(button);
                    if (item.get("custom_id") instanceof String id && !count.customIds.add(id))
                        throw new IllegalArgumentException("Duplicate custom_id in V2 message");
                }
                yield row;
            }
            case 9 -> {
                List<?> items = array(raw.get("components"), 1, 3);
                List<Object> text = new ArrayList<>();
                for (Object child : items) {
                    if (number(object(child).get("type"), "section child") != 10)
                        throw new IllegalArgumentException("Section children must be Text Displays");
                    text.add(component(child, false, count));
                }
                Map<?, ?> accessory = object(raw.get("accessory"));
                int accessoryType = number(accessory.get("type"), "accessory type");
                count.next();
                Object checkedAccessory;
                if (accessoryType == 11) {
                    checkedAccessory = Map.of("type", 11, "media",
                        Map.of("url", url(object(accessory.get("media")).get("url"))));
                } else if (accessoryType == 2) {
                    Map<String,Object> wrapper = RichPayload.messageRow(
                        Map.of("type", 1, "components", List.of(accessory)));
                    Map<?, ?> button = object(((List<?>) wrapper.get("components")).get(0));
                    String id = (String) button.get("custom_id");
                    if (!count.customIds.add(id))
                        throw new IllegalArgumentException("Duplicate custom_id in V2 message");
                    checkedAccessory = button;
                } else throw new IllegalArgumentException("Section accessory must be button or thumbnail");
                yield Map.of("type", 9, "components", text, "accessory", checkedAccessory);
            }
            case 10 -> {
                String text = string(raw.get("content"), "content", 1, 4000);
                yield Map.of("type", 10, "content", text);
            }
            case 12 -> {
                List<?> items = array(raw.get("items"), 1, 10);
                List<Object> gallery = new ArrayList<>();
                for (Object item : items) {
                    Map<?, ?> entry = object(item);
                    Map<?, ?> media = object(entry.get("media"));
                    Map<String, Object> checked = new LinkedHashMap<>();
                    checked.put("media", Map.of("url", url(media.get("url"))));
                    if (entry.containsKey("description"))
                        checked.put("description", string(entry.get("description"), "description", 0, 1024));
                    if (entry.containsKey("spoiler"))
                        checked.put("spoiler", bool(entry.get("spoiler"), "spoiler"));
                    gallery.add(checked);
                }
                yield Map.of("type", 12, "items", gallery);
            }
            case 14 -> {
                boolean divider = raw.containsKey("divider") ? bool(raw.get("divider"), "divider") : true;
                int spacing = raw.containsKey("spacing") ? number(raw.get("spacing"), "spacing") : 1;
                if (spacing != 1 && spacing != 2)
                    throw new IllegalArgumentException("Separator spacing must be 1 or 2");
                yield Map.of("type", 14, "divider", divider, "spacing", spacing);
            }
            case 17 -> {
                if (insideContainer) throw new IllegalArgumentException("Nested V2 containers unsupported");
                List<?> children = array(raw.get("components"), 1, 10);
                List<Object> accepted = new ArrayList<>();
                for (Object child : children) {
                    int childType = number(object(child).get("type"), "container child type");
                    if (childType != 1 && childType != 9 && childType != 10 &&
                        childType != 12 && childType != 14)
                        throw new IllegalArgumentException("Invalid V2 container child");
                    accepted.add(component(child, true, count));
                }
                Map<String, Object> container = new LinkedHashMap<>();
                container.put("type", 17);
                container.put("components", accepted);
                if (raw.containsKey("accent_color")) {
                    int color = number(raw.get("accent_color"), "accent_color");
                    if (color < 0 || color > 0xFFFFFF)
                        throw new IllegalArgumentException("Invalid accent color");
                    container.put("accent_color", color);
                }
                if (raw.containsKey("spoiler"))
                    container.put("spoiler", bool(raw.get("spoiler"), "spoiler"));
                yield container;
            }
            default -> throw new IllegalArgumentException("Unsupported V2 component type: " + type);
        };
    }

    private static String url(Object raw) {
        String str = string(raw, "media.url", 8, 2048);
        URI uri;
        try { uri = URI.create(str); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid HTTPS media URL", e); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
            uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Only public HTTPS media URLs supported");
        return str;
    }

    private static final class Counter {
        int seen;
        final Set<String> customIds = new HashSet<>();
        void next() {
            if (++seen > 40) throw new IllegalArgumentException("Discord V2 messages permit at most 40 components");
        }
    }
    private static Map<?, ?> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected V2 JSON object");
        return map;
    }
    private static List<?> array(Object raw, int min, int max) {
        if (!(raw instanceof List<?> list) || list.size() < min || list.size() > max)
            throw new IllegalArgumentException("Invalid V2 component array size");
        return list;
    }
    private static String string(Object raw, String field, int min, int max) {
        if (!(raw instanceof String value) || value.length() < min || value.length() > max)
            throw new IllegalArgumentException("Invalid V2 " + field);
        return value;
    }
    private static int number(Object raw, String field) {
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
            || n.doubleValue() < Integer.MIN_VALUE || n.doubleValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid V2 number: " + field);
        return n.intValue();
    }
    private static boolean bool(Object raw, String field) {
        if (!(raw instanceof Boolean value))
            throw new IllegalArgumentException("Invalid V2 boolean: " + field);
        return value;
    }
}
