package org.owcord.runtime;

import dev.oreo.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordCoreTest {
    @Test void commandAndMessagePayloadsAreSafe() {
        Map<?, ?> data = (Map<?, ?>) Json.parse(DiscordRest.replyPayload("hello \" @everyone"));
        assertEquals(4, data.get("type"));
        Map<?, ?> message = (Map<?, ?>) data.get("data");
        assertEquals("hello \" @everyone", message.get("content"));
        assertEquals(0, ((List<?>) ((Map<?, ?>) message.get("allowed_mentions")).get("parse")).size());
        assertThrows(IllegalArgumentException.class, () -> DiscordRest.commandPayload("Upper Case", "bad"));
        assertThrows(IllegalArgumentException.class, () -> DiscordRest.snowflake("../evil"));
        assertThrows(IllegalArgumentException.class, () -> DiscordRest.messagePayload("x".repeat(2001)));
    }
    @Test void gatewayDispatchIsConsumableWithoutNetwork() {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), URI.create("http://localhost/"), "test");
        try (DiscordGateway gateway = new DiscordGateway(rest, 1)) {
            gateway.receive("{\"op\":0,\"s\":3,\"t\":\"INTERACTION_CREATE\",\"d\":{\"id\":\"123\",\"data\":{\"name\":\"ping\"}}}", null);
            Map<?, ?> event = (Map<?, ?>) Json.parse(gateway.poll(0));
            assertEquals("INTERACTION_CREATE", event.get("type"));
            assertEquals("NONE", ((Map<?, ?>) Json.parse(gateway.poll(0))).get("type"));
            assertThrows(IllegalArgumentException.class, () -> gateway.poll(-1));
        }
    }
    @Test void operationDispatchIsAllowlisted() {
        try (OWCordExtension extension = new OWCordExtension()) {
            assertTrue(extension.supports("owcord.rest.reply"));
            assertFalse(extension.supports("owcord.rest.delete_guild"));
            assertThrows(IllegalArgumentException.class,
                () -> extension.invoke("owcord.rest.reply", List.of("one")));
        }
    }
}
