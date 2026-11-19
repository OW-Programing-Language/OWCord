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

    @Test void supportsPrivateAndDeferredInteractionResponses() {
        Map<?, ?> privateCallback = (Map<?, ?>) Json.parse(DiscordRest.replyPayload("only you", true));
        assertEquals(4, privateCallback.get("type"));
        Map<?, ?> privateData = (Map<?, ?>) privateCallback.get("data");
        assertEquals(64, privateData.get("flags"));
        assertEquals(List.of(), ((Map<?, ?>) privateData.get("allowed_mentions")).get("parse"));

        Map<?, ?> publicDefer = (Map<?, ?>) Json.parse(DiscordRest.deferPayload(false));
        assertEquals(5, publicDefer.get("type"));
        assertFalse(publicDefer.containsKey("data"));
        Map<?, ?> privateDefer = (Map<?, ?>) Json.parse(DiscordRest.deferPayload(true));
        assertEquals(64, ((Map<?, ?>) privateDefer.get("data")).get("flags"));

        Map<?, ?> followup = (Map<?, ?>) Json.parse(DiscordRest.followupPayload("done", true));
        assertEquals(64, followup.get("flags"));
        assertEquals("done", followup.get("content"));
        assertEquals(List.of(), ((Map<?, ?>) followup.get("allowed_mentions")).get("parse"));
        assertThrows(IllegalArgumentException.class, () -> DiscordRest.interactionToken("../bad"));
    }
    @Test void followsUpViaWebhookWithoutBotAuthorizationHeader() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> path = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> authorization = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> posted = new java.util.concurrent.atomic.AtomicReference<>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            posted.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "unused-bot-token");
            assertEquals(200, rest.followupInteraction("12345", "callback-token", "hello", true));
            assertEquals("/webhooks/12345/callback-token", path.get());
            assertNull(authorization.get());
            assertEquals(64, ((Map<?, ?>) Json.parse(posted.get())).get("flags"));
        } finally { server.stop(0); }
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
