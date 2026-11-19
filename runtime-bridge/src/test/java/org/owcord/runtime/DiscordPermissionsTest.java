package org.owcord.runtime;

import dev.oreo.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordPermissionsTest {
    @Test void permissionBitfieldsRemainExactBeyondFloatingPointRange() {
        String high = "1152921504606846976"; // 2^60, > JavaScript Number precision
        assertEquals(high, DiscordPermissions.parse(high).toString());
        assertTrue(DiscordPermissions.has(high, high));
        assertFalse(DiscordPermissions.has("0", high));
        assertTrue(DiscordPermissions.has("18446744073709551615", high));
        assertTrue(DiscordPermissions.has("8", high), "Administrator overrides individual flags");
        assertFalse(DiscordPermissions.memberCan("", high), "Missing permissions must fail closed");
        assertTrue(DiscordPermissions.has("0", "0"));
    }

    @Test void rejectsNegativeOverwideNonCanonicalOrMalformedMasks() {
        for (String invalid : List.of("", "-1", "+1", "01", "1.0", "1e4", "hello", "18446744073709551616")) {
            assertThrows(IllegalArgumentException.class, () -> DiscordPermissions.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> DiscordPermissions.parse(null));
    }

    @Test void writesDefaultMemberPermissionsAsDecimalString() {
        String body = DiscordRest.commandPayload("moderate", "Manage messages", "8192");
        Map<?, ?> data = (Map<?, ?>) Json.parse(body);
        assertEquals("8192", data.get("default_member_permissions"));
        assertEquals(1, data.get("type"));
        assertFalse(((Map<?, ?>) Json.parse(DiscordRest.commandPayload("ping", "Public"))).containsKey(
            "default_member_permissions"));
        assertEquals("0", ((Map<?, ?>) Json.parse(
            DiscordRest.commandPayload("admin", "Disabled by default", "0")))
            .get("default_member_permissions"));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordRest.commandPayload("moderate", "Manage messages", "2.5"));
    }

    @Test void restrictedRegistrationPostsValidatedPayloadWithBotAuthorization() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "test-token");
            assertEquals(201, rest.registerGuildCommand("111", "222", "moderate", "Manage messages", "8192"));
            assertEquals("/applications/111/guilds/222/commands", path.get());
            assertEquals("Bot test-token", auth.get());
            assertEquals("8192", ((Map<?, ?>) Json.parse(body.get())).get("default_member_permissions"));
        } finally { server.stop(0); }
    }

    @Test void allowlistedExtensionContainsPermissionOperations() {
        try (OWCordExtension extension = new OWCordExtension()) {
            assertTrue(extension.supports("owcord.rest.dev_command_permissions"));
            assertTrue(extension.supports("owcord.permissions.has"));
            assertEquals(1, extension.invoke("owcord.permissions.has", List.of("8192", "8192")));
            assertEquals(0, extension.invoke("owcord.permissions.has", List.of("", "8192")));
            assertEquals(1, extension.invoke("owcord.permissions.has", List.of("8", "8192")));
            assertThrows(IllegalArgumentException.class,
                () -> extension.invoke("owcord.permissions.has", List.of("garbage", "8192")));
        }
    }
}
