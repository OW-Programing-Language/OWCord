package org.owcord.runtime;

import dev.oreo.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordCommandContextsTest {
    @Test void createsGlobalCommandWithExplicitInstallAndInteractionScopes() {
        var value = (Map<?,?>)Json.parse(DiscordCommandContexts.command(
            "profile", "Show profile", "[0,1,2]", "[0,1]"));
        assertEquals("profile", value.get("name"));
        assertEquals(1, value.get("type"));
        assertEquals(List.of(0,1,2), value.get("contexts"));
        assertEquals(List.of(0,1), value.get("integration_types"));
    }
    @Test void rejectsMalformedOrUnsupportedScopes() {
        for (String contexts : List.of("[]", "[0,0]", "[0,3]", "[1.5]", "\"\"","{}")) {
            assertThrows(IllegalArgumentException.class,
                () -> DiscordCommandContexts.command("profile","Profile",contexts,"[0,1]"),
                contexts);
        }
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandContexts.command("profile","Profile","[2]","[0]"));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandContexts.command("profile","Profile","[0]","[0,0]"));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandContexts.command("INVALID NAME","Profile","[0]","[0]"));
    }
}
