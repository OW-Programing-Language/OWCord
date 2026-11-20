package org.owcord.runtime;

import dev.oreo.Json;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, snapshot-based Discord permission evaluation.
 *
 * Server-side Discord checks remain authoritative. Incomplete snapshots fail
 * closed rather than guessing permission grants or role precedence.
 */
final class DiscordAuthorization {
    private static final BigInteger ALL = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger ADMIN = BigInteger.valueOf(8);
    private static final BigInteger VIEW_CHANNEL = BigInteger.valueOf(1L << 10);
    private static final BigInteger READ_HISTORY = BigInteger.valueOf(1L << 16);
    private static final BigInteger KICK = BigInteger.valueOf(2);
    private static final BigInteger BAN = BigInteger.valueOf(4);
    private static final BigInteger TIMEOUT = BigInteger.ONE.shiftLeft(40);

    private DiscordAuthorization() {}

    static String effective(String guildJson, String memberJson, String channelJson, String rolesJson) {
        Map<?, ?> guild = object(Json.parse(guildJson));
        Map<?, ?> member = object(Json.parse(memberJson));
        Map<?, ?> channel = object(Json.parse(channelJson));
        List<?> roles = array(Json.parse(rolesJson), 1000);
        String guildId = id(guild, "id");
        String memberId = id(member, "user_id");
        if (!guildId.equals(id(channel, "guild_id")))
            throw new IllegalArgumentException("Channel not in requested guild");

        BigInteger mask = baseMask(guild, member, roles);
        boolean privileged = mask.equals(ALL);
        if (!privileged) {
            if (!Boolean.TRUE.equals(channel.get("overwrites_complete")))
                throw new IllegalStateException("Channel overwrites incomplete; refusing permission calculation");
            List<?> overwrites = array(channel.get("permission_overwrites"), 1000);
            Set<String> roleIds = memberRoleIds(member);
            BigInteger everyoneAllow = BigInteger.ZERO, everyoneDeny = BigInteger.ZERO;
            BigInteger rolesAllow = BigInteger.ZERO, rolesDeny = BigInteger.ZERO;
            BigInteger memberAllow = BigInteger.ZERO, memberDeny = BigInteger.ZERO;
            Set<String> seen = new HashSet<>();
            for (Object value : overwrites) {
                Map<?, ?> overwrite = object(value);
                String id = id(overwrite, "id");
                int type = number(overwrite.get("type"));
                if (type != 0 && type != 1) throw new IllegalArgumentException("Invalid overwrite type");
                if (!seen.add(type + ":" + id))
                    throw new IllegalArgumentException("Duplicate channel overwrite");
                BigInteger allow = permissions(overwrite, "allow");
                BigInteger deny = permissions(overwrite, "deny");
                if (type == 0 && id.equals(guildId)) {
                    everyoneAllow = allow; everyoneDeny = deny;
                } else if (type == 0 && roleIds.contains(id)) {
                    rolesAllow = rolesAllow.or(allow); rolesDeny = rolesDeny.or(deny);
                } else if (type == 1 && id.equals(memberId)) {
                    memberAllow = allow; memberDeny = deny;
                }
            }
            mask = mask.andNot(everyoneDeny).or(everyoneAllow);
            mask = mask.andNot(rolesDeny).or(rolesAllow);
            mask = mask.andNot(memberDeny).or(memberAllow);
        }

        if (!privileged && isCurrentlyTimedOut(member)) {
            // Discord temporarily limits timed-out members to these two flags.
            mask = mask.and(VIEW_CHANNEL.or(READ_HISTORY));
        }
        return mask.toString();
    }

    static boolean canModerate(String guildJson, String actorJson, String targetJson,
                               String rolesJson, String action) {
        Map<?, ?> guild = object(Json.parse(guildJson));
        Map<?, ?> actor = object(Json.parse(actorJson));
        Map<?, ?> target = object(Json.parse(targetJson));
        List<?> roles = array(Json.parse(rolesJson), 1000);
        String owner = id(guild, "owner_id");
        String actorId = id(actor, "user_id"), targetId = id(target, "user_id");
        if (actorId.equals(targetId) || targetId.equals(owner)) return false;
        BigInteger needed = switch (action) {
            case "kick" -> KICK;
            case "ban", "unban" -> BAN;
            case "timeout", "untimeout" -> TIMEOUT;
            default -> throw new IllegalArgumentException("Unsupported moderation action");
        };
        BigInteger granted = baseMask(guild, actor, roles);
        if (!granted.equals(ALL) && !granted.and(needed).equals(needed)) return false;
        // An unbanned user is not a guild member, so no target role hierarchy
        // exists. Only the BAN_MEMBERS permission is relevant here.
        if ("unban".equals(action)) return true;
        if (actorId.equals(owner)) return true;
        if ("timeout".equals(action) || "untimeout".equals(action)) {
            if (baseMask(guild, target, roles).equals(ALL)) return false;
        }
        Map<String, Integer> positions = rolePositions(roles);
        int actorPosition = topPosition(actor, positions);
        int targetPosition = topPosition(target, positions);
        // Equal positions are denied conservatively, including same-position
        // snowflake tie breaks; Discord's server remains the final authority.
        return actorPosition > targetPosition;
    }

    static boolean roleManageable(String guildJson, String actorJson,
                                  String roleId, String rolesJson) {
        Map<?, ?> guild = object(Json.parse(guildJson));
        Map<?, ?> actor = object(Json.parse(actorJson));
        List<?> roles = array(Json.parse(rolesJson), 1000);
        String guildId = id(guild, "id"), actorId = id(actor, "user_id");
        String wanted = DiscordRest.snowflake(roleId);
        if (guildId.equals(wanted)) return false;
        BigInteger granted = baseMask(guild, actor, roles);
        if (!granted.equals(ALL) && !granted.and(BigInteger.ONE.shiftLeft(28))
                                           .equals(BigInteger.ONE.shiftLeft(28)))
            return false;
        if (actorId.equals(id(guild, "owner_id"))) return true;
        Map<String, Integer> positions = rolePositions(roles);
        Integer targetPosition = positions.get(wanted);
        if (targetPosition == null) throw new IllegalArgumentException("Unknown target role");
        for (Object entry : roles) {
            Map<?, ?> role = object(entry);
            if (wanted.equals(id(role, "id")) && Boolean.TRUE.equals(role.get("managed")))
                return false; // managed integration roles are never assignable
        }
        return topPosition(actor, positions) > targetPosition;
    }

    private static BigInteger baseMask(Map<?, ?> guild, Map<?, ?> member, List<?> roles) {
        String guildId = id(guild, "id");
        String owner = id(guild, "owner_id");
        String memberId = id(member, "user_id");
        if (memberId.equals(owner)) return ALL;
        Set<String> memberRoles = memberRoleIds(member);
        Map<String, BigInteger> all = new HashMap<>();
        for (Object entry : roles) {
            Map<?, ?> role = object(entry);
            String id = id(role, "id");
            if (all.putIfAbsent(id, permissions(role, "permissions")) != null)
                throw new IllegalArgumentException("Duplicate guild role ID");
        }
        BigInteger everyone = all.get(guildId);
        if (everyone == null) throw new IllegalStateException("Missing @everyone role; refusing permission calculation");
        BigInteger mask = everyone;
        for (String id : memberRoles) {
            BigInteger permission = all.get(id);
            if (permission == null)
                throw new IllegalStateException("Incomplete role snapshot: " + id);
            mask = mask.or(permission);
        }
        return mask.and(ADMIN).signum() != 0 ? ALL : mask;
    }

    private static Map<String, Integer> rolePositions(List<?> roles) {
        Map<String, Integer> result = new HashMap<>();
        for (Object item : roles) {
            Map<?, ?> role = object(item);
            String id = id(role, "id");
            if (result.putIfAbsent(id, number(role.get("position"))) != null)
                throw new IllegalArgumentException("Duplicate guild role");
        }
        return result;
    }

    private static int topPosition(Map<?, ?> member, Map<String, Integer> positions) {
        int best = 0;
        for (String role : memberRoleIds(member)) {
            Integer position = positions.get(role);
            if (position == null) throw new IllegalStateException("Missing hierarchy role");
            best = Math.max(best, position);
        }
        return best;
    }

    private static boolean isCurrentlyTimedOut(Map<?, ?> member) {
        Object raw = member.get("communication_disabled_until");
        if (raw == null || "".equals(raw)) return false;
        if (!(raw instanceof String str)) throw new IllegalArgumentException("Invalid timeout timestamp");
        try { return Instant.parse(str).isAfter(Instant.now()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Invalid timeout timestamp", e); }
    }

    private static BigInteger permissions(Map<?, ?> item, String key) {
        Object raw = item.get(key);
        if (!(raw instanceof String str)) throw new IllegalStateException("Missing permission bitfield: " + key);
        return DiscordPermissions.parse(str);
    }
    private static Set<String> memberRoleIds(Map<?, ?> member) {
        List<?> raw = array(member.get("roles"), 300);
        Set<String> result = new HashSet<>();
        for (Object id : raw) {
            if (!(id instanceof String str)) throw new IllegalArgumentException("Malformed member role");
            if (!result.add(DiscordRest.snowflake(str)))
                throw new IllegalArgumentException("Duplicate member role");
        }
        return result;
    }
    private static String id(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s)) throw new IllegalArgumentException("Missing identifier: " + key);
        return DiscordRest.snowflake(s);
    }
    private static Map<?, ?> object(Object raw) {
        if (!(raw instanceof Map<?, ?> obj)) throw new IllegalArgumentException("Expected JSON object");
        return obj;
    }
    private static List<?> array(Object raw, int max) {
        if (!(raw instanceof List<?> list) || list.size() > max)
            throw new IllegalArgumentException("Expected bounded JSON array");
        return list;
    }
    private static int number(Object raw) {
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
            || n.doubleValue() < 0 || n.doubleValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid role or overwrite number");
        return n.intValue();
    }
}
