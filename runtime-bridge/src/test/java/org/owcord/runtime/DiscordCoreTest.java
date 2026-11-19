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

    @Test void reserializedDiscordEventWithLargeIntegerMetadataIsValidOwJson() {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), URI.create("http://localhost/"), "test");
        try (DiscordGateway gateway = new DiscordGateway(rest, 1)) {
            // OW's JSON parser converts values above 32 bits to doubles; the
            // bridge must avoid writing those doubles using exponent notation.
            gateway.receive("{\"op\":0,\"s\":11,\"t\":\"MESSAGE_CREATE\",\"d\":"
                + "{\"content\":\"OWCord live smoke\",\"id\":\"123456789\","
                + "\"channel_id\":\"1459838511485157481\",\"numeric_metadata\":1321509685031075911}}", null);
            Map<?, ?> event = (Map<?, ?>) Json.parse(gateway.poll(0));
            assertEquals("MESSAGE_CREATE", event.get("type"));
            Map<?, ?> data = (Map<?, ?>) event.get("data");
            assertEquals("OWCord live smoke", data.get("content"));
            assertTrue(data.get("numeric_metadata") instanceof String,
                "Oversized numeric metadata is preserved as a decimal string");
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
