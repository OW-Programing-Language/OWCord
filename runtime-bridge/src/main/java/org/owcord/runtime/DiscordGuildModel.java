package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deliberately small, stable OW-facing representations of Discord guild objects.
 * IDs and permission bitfields remain decimal strings, never floating-point.
 */
final class DiscordGuildModel {
    private DiscordGuildModel() {}

    static String guild(String json) { return Json.write(guildObject(Json.parse(json))); }
    static String channel(String json) { return Json.write(channelObject(Json.parse(json))); }
    static String role(String json) { return Json.write(roleObject(Json.parse(json))); }
    static String member(String json) { return Json.write(memberObject(Json.parse(json))); }
    static String channels(String json) { return Json.write(array(json, 1000, "channels", "channel")); }
    static String roles(String json) { return Json.write(array(json, 1000, "roles", "role")); }
    static String members(String json) { return Json.write(array(json, 1000, "members", "member")); }

    private static List<Object> array(String json, int max, String label, String kind) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof List<?> list) || list.size() > max)
            throw new IllegalStateException("Unexpected Discord " + label + " response");
        List<Object> result = new ArrayList<>();
        for (Object entry : list) {
            switch (kind) {
                case "channel" -> result.add(channelObject(entry));
                case "role" -> result.add(roleObject(entry));
                case "member" -> result.add(memberObject(entry));
                default -> throw new IllegalStateException("Unexpected model type");
            }
        }
        return result;
    }

    private static Map<String, Object> guildObject(Object value) {
        Map<?, ?> obj = object(value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(obj, "id"));
        out.put("name", string(obj, "name"));
        out.put("owner_id", optionalId(obj, "owner_id"));
        out.put("description", string(obj, "description"));
        out.put("member_count", optionalNumber(obj, "approximate_member_count"));
        return out;
    }

    private static Map<String, Object> channelObject(Object value) {
        Map<?, ?> obj = object(value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(obj, "id"));
        out.put("guild_id", optionalId(obj, "guild_id"));
        out.put("name", string(obj, "name"));
        out.put("topic", string(obj, "topic"));
        out.put("parent_id", optionalId(obj, "parent_id"));
        out.put("type", optionalNumber(obj, "type"));
        out.put("position", optionalNumber(obj, "position"));
        out.put("nsfw", Boolean.TRUE.equals(obj.get("nsfw")));
        return out;
    }

    private static Map<String, Object> roleObject(Object value) {
        Map<?, ?> obj = object(value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(obj, "id"));
        out.put("name", string(obj, "name"));
        out.put("permissions", DiscordPermissions.parse(string(obj, "permissions")).toString());
        out.put("position", optionalNumber(obj, "position"));
        out.put("managed", Boolean.TRUE.equals(obj.get("managed")));
        out.put("mentionable", Boolean.TRUE.equals(obj.get("mentionable")));
        return out;
    }

    private static Map<String, Object> memberObject(Object value) {
        Map<?, ?> obj = object(value);
        Map<?, ?> user = object(obj.get("user"));
        Object rawRoles = obj.get("roles");
        if (!(rawRoles instanceof List<?> roles) || roles.size() > 300)
            throw new IllegalStateException("Unexpected Discord member role list");
        List<String> roleIds = new ArrayList<>();
        for (Object role : roles) {
            if (!(role instanceof String str)) throw new IllegalStateException("Unexpected role ID");
            roleIds.add(DiscordRest.snowflake(str));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", id(user, "id"));
        out.put("nick", string(obj, "nick"));
        out.put("joined_at", string(obj, "joined_at"));
        out.put("roles", roleIds);
        out.put("pending", Boolean.TRUE.equals(obj.get("pending")));
        return out;
    }

    private static Map<?, ?> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map))
            throw new IllegalStateException("Unexpected Discord object");
        return map;
    }
    private static String id(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (!(v instanceof String s)) throw new IllegalStateException("Missing Discord ID: " + key);
        return DiscordRest.snowflake(s);
    }
    private static String optionalId(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : id(map, key);
    }
    private static String string(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return "";
        if (!(v instanceof String s)) throw new IllegalStateException("Invalid Discord " + key);
        return s;
    }
    private static int optionalNumber(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) return 0;
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() > Integer.MAX_VALUE
                || n.doubleValue() < Integer.MIN_VALUE)
            throw new IllegalStateException("Invalid Discord number: " + key);
        return n.intValue();
    }
}
