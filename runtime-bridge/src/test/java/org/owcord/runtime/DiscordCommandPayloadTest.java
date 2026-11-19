package org.owcord.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.oreo.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordCommandPayloadTest {
    private static String option(int type, String name, boolean required, boolean autocomplete) {
        return Json.write(Map.of("type", type, "name", name, "description", "A parameter",
            "required", required, "autocomplete", autocomplete));
    }
    private static String opts(Object... values) { return Json.write(List.of(values)); }

    @Test void validatesTypedCommandOptionsAndPermissions() {
        String schema = opts(
            Map.of("type", 3, "name", "query", "description", "Search term",
                "required", true, "autocomplete", true),
            Map.of("type", 5, "name", "private", "description", "Private result",
                "required", false));
        Map<?, ?> result = (Map<?, ?>) Json.parse(DiscordCommandPayload.command(
            "search", "Search", "8192", schema));
        assertEquals("search", result.get("name"));
        assertEquals("8192", result.get("default_member_permissions"));
        List<?> options = (List<?>) result.get("options");
        assertEquals(2, options.size());
        assertEquals(true, ((Map<?, ?>) options.get(0)).get("autocomplete"));
        assertEquals(5, ((Map<?, ?>) options.get(1)).get("type"));
        assertEquals(3, ((Map<?, ?>) options.get(0)).get("type"));
    }

    @Test void validatesSubcommandsAndNestedGroups() {
        String schema = opts(
            Map.of("type", 2, "name", "user", "description", "User actions", "options",
                List.of(Map.of("type", 1, "name", "ban", "description", "Ban user", "options",
                    List.of(Map.of("type", 6, "name", "target", "description", "The user",
                        "required", true))))),
            Map.of("type", 1, "name", "info", "description", "Information", "options",
                List.of(Map.of("type", 3, "name", "target", "description", "The target"))));
        Map<?, ?> cmd = (Map<?, ?>) Json.parse(
            DiscordCommandPayload.command("admin", "Moderation", null, schema));
        assertEquals(2, ((List<?>) cmd.get("options")).size());
        assertEquals(2, ((Map<?, ?>) ((List<?>) cmd.get("options")).get(0)).get("type"));
    }

    @Test void rejectsInvalidOptionSchemasFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null,
                opts(Map.of("type", 3, "name", "Bad Name", "description", "Invalid"))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null,
                opts(Map.of("type", 3, "name", "query", "description", "First"),
                     Map.of("type", 5, "name", "query", "description", "Duplicate"))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null,
                opts(Map.of("type", 3, "name", "optional", "description", "Optional"),
                     Map.of("type", 3, "name", "required", "description", "Required",
                         "required", true))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null,
                opts(Map.of("type", 5, "name", "flag", "description", "Invalid autocomplete",
                    "autocomplete", true))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null,
                opts(Map.of("type", 3, "name", "query", "description", "Conflicting options",
                    "autocomplete", true, "choices", List.of(Map.of("name", "A", "value", "a"))))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null,
                opts(Map.of("type", 1, "name", "nested", "description", "Bad nested",
                    "options", List.of(Map.of("type", 1, "name", "nested", "description", "Bad"))))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null,
                opts(Map.of("type", 3, "name", "query", "description", "Top-level argument"),
                     Map.of("type", 1, "name", "nested", "description", "Bad mixed",
                         "options", List.of(Map.of("type", 5, "name", "flag",
                             "description", "Flag"))))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.command("search", "Search", null, "{}"));
    }

    @Test void validatesAutocompleteChoicesAndCallbackTypeEight() {
        String choices = opts(Map.of("name", "Alpha", "value", "alpha"),
            Map.of("name", "One", "value", 1));
        Map<?, ?> callback = (Map<?, ?>) Json.parse(DiscordCommandPayload.autocomplete(choices));
        assertEquals(8, callback.get("type"));
        Map<?, ?> data = (Map<?, ?>) callback.get("data");
        assertEquals(2, ((List<?>) data.get("choices")).size());
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.autocomplete(opts(Map.of("name", "Broken", "value", true))));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.autocomplete(opts(Map.of("name", "", "value", "x"))));
        List<Object> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 26; i++) tooMany.add(Map.of("name", "v" + i, "value", "v" + i));
        assertThrows(IllegalArgumentException.class, () ->
            DiscordCommandPayload.autocomplete(Json.write(tooMany)));
    }

    @Test void transportsCommandSchemaAndAutocompleteWithoutBotAuthOnCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = path.get().startsWith("/interactions/") ? 204 : 201;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "test-token");
            String schema = opts(Map.of("type", 3, "name", "query",
                "description", "Search query", "required", true, "autocomplete", true));
            assertEquals(201, rest.registerGuildCommandOptions("123", "456", "search",
                "Search records", schema, ""));
            assertEquals("/applications/123/guilds/456/commands", path.get());
            assertEquals("Bot test-token", auth.get());
            Map<?, ?> result = (Map<?, ?>) Json.parse(body.get());
            assertEquals(1, ((List<?>) result.get("options")).size());

            assertEquals(204, rest.replyAutocomplete("111", "interaction-token",
                opts(Map.of("name", "Alpha", "value", "alpha"))));
            assertEquals("/interactions/111/interaction-token/callback", path.get());
            assertNull(auth.get(), "Interaction callbacks use the token, not bot authorization");
            assertEquals(8, ((Map<?, ?>) Json.parse(body.get())).get("type"));
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
}
