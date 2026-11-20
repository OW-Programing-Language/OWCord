package org.owcord.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.oreo.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordModerationRestTest {
    @Test void validatesModerationPayloadsAndAuditReasons() {
        Map<?, ?> ban = (Map<?, ?>) Json.parse(DiscordModerationPayload.ban(0));
        assertEquals(0, ban.get("delete_message_seconds"));
        assertThrows(IllegalArgumentException.class, () -> DiscordModerationPayload.ban(-1));
        assertThrows(IllegalArgumentException.class, () -> DiscordModerationPayload.ban(604801));
        assertEquals(null, ((Map<?, ?>) Json.parse(DiscordModerationPayload.clearTimeout()))
            .get("communication_disabled_until"));
        assertThrows(IllegalArgumentException.class, () -> DiscordModerationPayload.timeout("bad"));
        assertThrows(IllegalArgumentException.class, () -> DiscordModerationPayload.timeout("2020-01-01T00:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> DiscordModerationPayload.timeout(
            Instant.now().plusSeconds(60L * 60 * 24 * 29).toString()));
        Map<?, ?> payload = (Map<?, ?>) Json.parse(DiscordModerationPayload.timeout(
            Instant.now().plusSeconds(3600).toString()));
        assertTrue(payload.get("communication_disabled_until") instanceof String);
    }

    @Test void sendsCorrectAuditHeaderMethodsAndBodyWithoutModifyingLiveDiscord() throws Exception {
        List<Map<String,Object>> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var req = Map.<String,Object>of(
                "method", exchange.getRequestMethod(),
                "path", exchange.getRequestURI().getRawPath(),
                "audit", String.valueOf(exchange.getRequestHeaders().getFirst("X-Audit-Log-Reason")),
                "auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(req);
            boolean patch = exchange.getRequestMethod().equals("PATCH");
            String body = patch ?
                "{\"user\":{\"id\":\"222\"},\"roles\":[],\"communication_disabled_until\":null}" : "";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(patch ? 200 : 204, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) try (var out = exchange.getResponseBody()) { out.write(bytes); }
            exchange.close();
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "local-test");
            String reason = "OWCord audit & test";
            String until = Instant.now().plusSeconds(3600).toString();
            assertEquals(204, rest.kickMember("111","222",reason));
            assertEquals("DELETE", requests.get(0).get("method"));
            assertEquals("/guilds/111/members/222", requests.get(0).get("path"));

            assertEquals(204, rest.banMember("111","222",0,reason));
            assertEquals("PUT", requests.get(1).get("method"));
            assertEquals("/guilds/111/bans/222", requests.get(1).get("path"));
            assertEquals(0, ((Map<?,?>)Json.parse((String)requests.get(1).get("body")))
                .get("delete_message_seconds"));

            assertEquals(204, rest.unbanMember("111","222",reason));
            assertEquals("DELETE", requests.get(2).get("method"));

            Map<?,?> timed = (Map<?,?>)Json.parse(rest.timeoutMember("111","222",until,reason));
            assertEquals("222", timed.get("user_id"));
            Map<?,?> timeoutBody = (Map<?,?>)Json.parse((String)requests.get(3).get("body"));
            assertEquals(until, timeoutBody.get("communication_disabled_until"));
            assertEquals("PATCH", requests.get(3).get("method"));

            Map<?,?> untimed = (Map<?,?>)Json.parse(rest.clearMemberTimeout("111","222",reason));
            assertEquals("222", untimed.get("user_id"));
            assertNull(((Map<?,?>)Json.parse((String)requests.get(4).get("body")))
                .get("communication_disabled_until"));

            assertEquals(5, requests.size());
            for (Map<String,Object> request : requests) {
                assertEquals("Bot local-test", request.get("auth"));
                assertEquals("OWCord%20audit%20%26%20test", request.get("audit"));
            }
            assertThrows(IllegalArgumentException.class, () ->
                rest.kickMember("111","222","bad\nreason"));
            assertThrows(IllegalArgumentException.class, () ->
                rest.banMember("111","222",604801,reason));
            assertThrows(IllegalArgumentException.class, () ->
                rest.timeoutMember("111","222","nonsense",reason));
            assertThrows(IllegalArgumentException.class, () ->
                rest.unbanMember("111","bad/id",reason));
            assertEquals(5, requests.size(), "Invalid moderation input must not touch network");
        } finally { server.stop(0); }
    }

    @Test void extensionOpsAreAllowlistedAndArityChecked() {
        try (OWCordExtension ext = new OWCordExtension()) {
            for (String op : List.of("owcord.permissions.effective",
                "owcord.permissions.can_moderate", "owcord.permissions.role_manageable",
                "owcord.moderation.kick", "owcord.moderation.ban",
                "owcord.moderation.unban", "owcord.moderation.timeout",
                "owcord.moderation.clear_timeout"))
                assertTrue(ext.supports(op), "Missing extension op " + op);
            assertThrows(IllegalArgumentException.class,
                () -> ext.invoke("owcord.moderation.kick", List.of("111", "222")));
            // Exact operation arities are validated before the bridge accesses
            // secrets. Body and integer validation is checked at REST layer.
        }
    }
}
