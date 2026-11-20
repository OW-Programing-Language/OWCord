package org.owcord.runtime;

import dev.oreo.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayIntentsTest {
    @Test void namedAndNumericProfilesProduceSameBitmask() {
        int expected = 1 | 512 | 1024;
        assertEquals(expected, GatewayIntents.parse("1537"));
        assertEquals(expected, GatewayIntents.parse("GUILDS, GUILD_MESSAGES, GUILD_MESSAGE_REACTIONS"));
        assertEquals(expected, GatewayIntents.parse(" guilds | guild_messages | guild_message_reactions "));
        Map<?,?> settings = (Map<?,?>) Json.parse(GatewayIntents.describe(expected));
        assertEquals(expected, settings.get("mask"));
        assertEquals(List.of("GUILDS", "GUILD_MESSAGES", "GUILD_MESSAGE_REACTIONS"),
            settings.get("names"));
        assertEquals(List.of(), settings.get("privileged"));
        assertEquals(false, settings.get("portal_configuration_required"));
    }

    @Test void privilegedIntentsAreReportedButNotSilentlyGranted() {
        int mask = GatewayIntents.parse("GUILDS,GUILD_MEMBERS,GUILD_PRESENCES,MESSAGE_CONTENT");
        Map<?,?> settings = (Map<?,?>) Json.parse(GatewayIntents.describe(mask));
        assertEquals(List.of("GUILD_MEMBERS", "GUILD_PRESENCES", "MESSAGE_CONTENT"),
            settings.get("privileged"));
        assertEquals(true, settings.get("portal_configuration_required"));
    }

    @Test void refusesUnknownFlagsDuplicatesAndOverflow() {
        for(String value: List.of("", " ", "GUILDS,BOGUS", "GUILDS, GUILDS",
            "GUILDS,", "1073741824", "2147483648", "-1", "0x200")) {
            assertThrows(IllegalArgumentException.class, () -> GatewayIntents.parse(value), value);
        }
        assertThrows(IllegalArgumentException.class, () -> GatewayIntents.parse(null));
        assertEquals(0, GatewayIntents.parse("0"));
    }
}
