package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse Gateway v10 intent settings before Identify. Privileged intents still
 * require Developer Portal approval: local validation cannot grant them.
 */
final class GatewayIntents {
    private static final Map<String,Integer> BITS = new LinkedHashMap<>();
    static {
        BITS.put("GUILDS", 1 << 0);
        BITS.put("GUILD_MEMBERS", 1 << 1);
        BITS.put("GUILD_MODERATION", 1 << 2);
        BITS.put("GUILD_EXPRESSIONS", 1 << 3);
        BITS.put("GUILD_INTEGRATIONS", 1 << 4);
        BITS.put("GUILD_WEBHOOKS", 1 << 5);
        BITS.put("GUILD_INVITES", 1 << 6);
        BITS.put("GUILD_VOICE_STATES", 1 << 7);
        BITS.put("GUILD_PRESENCES", 1 << 8);
        BITS.put("GUILD_MESSAGES", 1 << 9);
        BITS.put("GUILD_MESSAGE_REACTIONS", 1 << 10);
        BITS.put("GUILD_MESSAGE_TYPING", 1 << 11);
        BITS.put("DIRECT_MESSAGES", 1 << 12);
        BITS.put("DIRECT_MESSAGE_REACTIONS", 1 << 13);
        BITS.put("DIRECT_MESSAGE_TYPING", 1 << 14);
        BITS.put("MESSAGE_CONTENT", 1 << 15);
        BITS.put("GUILD_SCHEDULED_EVENTS", 1 << 16);
        BITS.put("AUTO_MODERATION_CONFIGURATION", 1 << 20);
        BITS.put("AUTO_MODERATION_EXECUTION", 1 << 21);
        BITS.put("GUILD_MESSAGE_POLLS", 1 << 24);
        BITS.put("DIRECT_MESSAGE_POLLS", 1 << 25);
    }
    private static final int KNOWN_MASK = BITS.values().stream().reduce(0, (a,b) -> a|b);
    private static final int PRIVILEGED = BITS.get("GUILD_MEMBERS") |
        BITS.get("GUILD_PRESENCES") | BITS.get("MESSAGE_CONTENT");

    private GatewayIntents() {}

    static int parse(String configured) {
        if (configured == null || configured.isBlank())
            throw new IllegalArgumentException("OWCORD_GATEWAY_INTENTS is blank");
        String raw = configured.strip();
        int value;
        if (raw.matches("[0-9]+")) {
            try { value = Integer.parseInt(raw); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Intent bitmask too large", e);
            }
        } else {
            value = 0;
            for (String entry : raw.split("[,|]", -1)) {
                String name = entry.strip().toUpperCase(java.util.Locale.ROOT);
                Integer flag = BITS.get(name);
                if (flag == null) throw new IllegalArgumentException("Unknown Gateway intent: " + name);
                if ((value & flag) != 0)
                    throw new IllegalArgumentException("Duplicate Gateway intent: " + name);
                value |= flag;
            }
        }
        if ((value & ~KNOWN_MASK) != 0)
            throw new IllegalArgumentException("Unknown Gateway intent bit(s): " + (value & ~KNOWN_MASK));
        return value;
    }

    static String describe(int mask) {
        int validated = parse(Integer.toString(mask));
        List<String> enabled = new ArrayList<>();
        List<String> privileged = new ArrayList<>();
        for (var item : BITS.entrySet()) {
            if ((validated & item.getValue()) == 0) continue;
            enabled.add(item.getKey());
            if ((item.getValue() & PRIVILEGED) != 0) privileged.add(item.getKey());
        }
        return Json.write(Map.of("mask", validated, "names", enabled,
            "privileged", privileged, "portal_configuration_required", !privileged.isEmpty()));
    }
}
