package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Optional, TTL-bound, LRU cache for *partial Gateway metadata only*.
 * No message content, interaction tokens, email addresses or raw user objects.
 * Entry lookups never assert that a snapshot is complete or authoritative.
 */
final class GatewayEventCache {
    enum Mode { OFF, METADATA, MEMBERS }
    record Config(Mode mode, int maxEntries, int ttlSeconds) {
        Config {
            if (mode == null || maxEntries < 1 || maxEntries > 4096
                || ttlSeconds < 1 || ttlSeconds > 3600)
                throw new IllegalArgumentException("Invalid Gateway cache configuration");
        }

        static Config parse(String modeRaw, String maxRaw, String ttlRaw) {
            if (modeRaw == null) modeRaw = "off";
            Mode mode = switch (modeRaw.toLowerCase(java.util.Locale.ROOT).strip()) {
                case "off" -> Mode.OFF;
                case "metadata" -> Mode.METADATA;
                case "members" -> Mode.MEMBERS;
                default -> throw new IllegalArgumentException(
                    "OWCORD_CACHE_MODE must be off, metadata or members");
            };
            try {
                return new Config(mode, Integer.parseInt(maxRaw), Integer.parseInt(ttlRaw));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid cache max entries or TTL", e);
            }
        }

        static Config fromEnvironment() {
            return parse(System.getenv().getOrDefault("OWCORD_CACHE_MODE", "off"),
                System.getenv().getOrDefault("OWCORD_CACHE_MAX_ENTRIES", "256"),
                System.getenv().getOrDefault("OWCORD_CACHE_TTL_SECONDS", "300"));
        }
    }

    private record Entry(Map<String,Object> value, long expiresAt) {}
    private final Config config;
    // One cache instance per gateway; access order provides bounded LRU eviction.
    private final LinkedHashMap<String, Entry> entries =
        new LinkedHashMap<>(32, 0.75f, true);
    private long evictions;
    private long invalidations;
    private long hits;
    private long misses;

    GatewayEventCache(Config config) { this.config = config; }

    synchronized void apply(String eventName, Object data) {
        if (config.mode() == Mode.OFF || !(data instanceof Map<?,?> raw)) return;
        switch (eventName) {
            case "READY" -> clear();
            case "GUILD_CREATE", "GUILD_UPDATE" -> {
                String guild = id(raw.get("id"));
                if (guild.isEmpty()) return;
                Map<String,Object> fields = new LinkedHashMap<>();
                fields.put("id", guild);
                putIfString(raw, fields, "name");
                upsert("guild:" + guild, fields);
            }
            case "GUILD_DELETE" -> {
                String guild = id(raw.get("id"));
                if (!guild.isEmpty()) invalidateGuild(guild);
            }
            case "CHANNEL_CREATE", "CHANNEL_UPDATE", "THREAD_CREATE", "THREAD_UPDATE" -> {
                String channel = id(raw.get("id"));
                String guild = id(raw.get("guild_id"));
                if (channel.isEmpty() || guild.isEmpty()) return;
                Map<String,Object> fields = new LinkedHashMap<>();
                fields.put("id", channel);
                fields.put("guild_id", guild);
                putIfString(raw, fields, "name");
                putIfString(raw, fields, "topic");
                putIfId(raw, fields, "parent_id");
                if (raw.get("type") instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                    && n.intValue() >= 0) fields.put("type", n.intValue());
                upsert("channel:" + channel, fields);
            }
            case "CHANNEL_DELETE", "THREAD_DELETE" -> {
                String channel = id(raw.get("id"));
                if (!channel.isEmpty()) remove("channel:" + channel);
            }
            case "GUILD_MEMBER_ADD", "GUILD_MEMBER_UPDATE" -> {
                if (config.mode() != Mode.MEMBERS) return;
                String guild = id(raw.get("guild_id"));
                Object user = raw.get("user");
                String userId = user instanceof Map<?,?> m ? id(m.get("id")) : "";
                if (guild.isEmpty() || userId.isEmpty()) return;
                Map<String,Object> fields = new LinkedHashMap<>();
                fields.put("guild_id", guild);
                fields.put("user_id", userId);
                // Role IDs only. Never cache user profile, nick or activity data.
                if (raw.get("roles") instanceof List<?> roles && roles.size() <= 300) {
                    List<String> parsed = new ArrayList<>();
                    for (Object role : roles) {
                        String roleId = id(role);
                        if (roleId.isEmpty()) return;
                        parsed.add(roleId);
                    }
                    fields.put("roles", parsed);
                }
                upsert("member:" + guild + ":" + userId, fields);
            }
            case "GUILD_MEMBER_REMOVE" -> {
                String guild = id(raw.get("guild_id"));
                Object user = raw.get("user");
                String member = user instanceof Map<?,?> m ? id(m.get("id")) : "";
                if (!guild.isEmpty() && !member.isEmpty())
                    remove("member:" + guild + ":" + member);
            }
            case "GUILD_ROLE_DELETE", "GUILD_ROLE_UPDATE" -> {
                // Role snapshots can be incomplete. Drop any cached member role
                // associations rather than trusting potentially stale data.
                String guild = id(raw.get("guild_id"));
                if (!guild.isEmpty()) invalidateMembers(guild);
            }
            default -> { }
        }
    }

    synchronized String getGuild(String id) {
        return get("guild:" + DiscordRest.snowflake(id));
    }
    synchronized String getChannel(String id) {
        return get("channel:" + DiscordRest.snowflake(id));
    }
    synchronized String getMember(String guild, String user) {
        return get("member:" + DiscordRest.snowflake(guild) + ":" + DiscordRest.snowflake(user));
    }
    private String get(String key) {
        if (config.mode() == Mode.OFF) return "{\"found\":false}";
        Entry cached = entries.get(key);
        if (cached == null) { misses++; return "{\"found\":false}"; }
        if (System.nanoTime() - cached.expiresAt() >= 0) {
            entries.remove(key);
            misses++;
            return "{\"found\":false}";
        }
        hits++;
        return Json.write(Map.of("found", true, "partial", true, "data", cached.value()));
    }
    synchronized String stats() {
        purgeExpired();
        return Json.write(Map.of("mode", config.mode().name().toLowerCase(java.util.Locale.ROOT),
            "max_entries", config.maxEntries(), "ttl_seconds", config.ttlSeconds(),
            "entries", entries.size(), "hits", hits, "misses", misses,
            "evictions", evictions, "invalidations", invalidations));
    }
    synchronized int clear() {
        invalidations += entries.size();
        entries.clear();
        return 1;
    }

    private void putIfString(Map<?,?> src, Map<String,Object> out, String key) {
        if (src.get(key) instanceof String s) out.put(key, s);
    }
    private void putIfId(Map<?,?> src, Map<String,Object> out, String key) {
        String value = id(src.get(key));
        if (!value.isEmpty()) out.put(key, value);
    }
    private String id(Object raw) {
        if (!(raw instanceof String s) || !s.matches("[0-9]{1,22}")) return "";
        return s;
    }
    private void upsert(String key, Map<String,Object> update) {
        purgeExpired();
        Entry old = entries.get(key);
        Map<String,Object> result = old == null ? new LinkedHashMap<>() : new LinkedHashMap<>(old.value());
        result.putAll(update);
        entries.put(key, new Entry(Map.copyOf(result),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(config.ttlSeconds())));
        while (entries.size() > config.maxEntries()) {
            entries.remove(entries.keySet().iterator().next());
            evictions++;
        }
    }
    private void remove(String key) {
        if (entries.remove(key) != null) invalidations++;
    }
    private void invalidateGuild(String id) {
        remove("guild:" + id);
        entries.entrySet().removeIf(e -> {
            boolean purge = id.equals(e.getValue().value().get("guild_id"));
            if (purge) invalidations++;
            return purge;
        });
    }
    private void invalidateMembers(String guild) {
        String prefix = "member:" + guild + ":";
        entries.keySet().removeIf(key -> {
            boolean purge = key.startsWith(prefix);
            if (purge) invalidations++;
            return purge;
        });
    }
    private void purgeExpired() {
        long now = System.nanoTime();
        entries.entrySet().removeIf(e -> now - e.getValue().expiresAt() >= 0);
    }
}
