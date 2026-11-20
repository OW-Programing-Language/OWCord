package org.owcord.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.oreo.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordMessageRestTest {
    private static final String CHANNEL = "123456789012345678";
    private static final String ID = "123456789012345679";
    private static final String USER = "123456789012345680";

    private static String message(String content) {
        return Json.write(Map.of("id", ID, "channel_id", CHANNEL, "author",
            Map.of("id", USER), "content", content, "pinned", false));
    }

    @Test void supportsFullMessageAndReactionLifecycleViaLocalHttp() throws Exception {
        AtomicReference<String> path = new AtomicReference<>("");
        AtomicReference<String> query = new AtomicReference<>("");
        AtomicReference<String> method = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>("");
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String p = exchange.getRequestURI().getRawPath();
            String q = exchange.getRequestURI().getRawQuery();
            String m = exchange.getRequestMethod();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            path.set(p); query.set(q == null ? "" : q); method.set(m);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(reqBody);
            requests.add(m + " " + p);
            int code;
            String reply;
            if (p.contains("/reactions/")) {
                if (m.equals("GET")) { code = 200; reply = Json.write(List.of(Map.of("id", USER))); }
                else { code = 204; reply = ""; }
            } else if (m.equals("DELETE")) {
                code = 204; reply = "";
            } else if (m.equals("GET") && q != null) {
                code = 200; reply = Json.write(List.of(Json.parse(message("created"))));
            } else if (m.equals("PATCH")) {
                code = 200; reply = message("edited");
            } else if (m.equals("POST")) {
                code = 201; reply = message("created");
            } else {
                code = 200; reply = message("created");
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length != 0) {
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            }
            exchange.close();
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "safe-token");
            assertEquals(ID, rest.createMessage(CHANNEL, "created"));
            Map<?, ?> createBody = (Map<?, ?>) Json.parse(requestBody.get());
            assertEquals("created", createBody.get("content"));
            assertEquals(List.of(), ((Map<?, ?>) createBody.get("allowed_mentions")).get("parse"));
            assertEquals("Bot safe-token", authorization.get());

            Map<?, ?> fetched = (Map<?, ?>) Json.parse(rest.getMessage(CHANNEL, ID));
            assertEquals(ID, fetched.get("id"));
            assertEquals(CHANNEL, fetched.get("channel_id"));
            assertEquals(USER, fetched.get("author_id"));
            assertEquals("created", fetched.get("content"));
            assertEquals("", fetched.get("edited_timestamp"));

            var firstPage = (List<?>) Json.parse(rest.listMessages(CHANNEL, "", "before", 20));
            assertEquals(1, firstPage.size());
            assertEquals("limit=20", query.get());
            var secondPage = (List<?>) Json.parse(rest.listMessages(CHANNEL, ID, "after", 5));
            assertEquals(1, secondPage.size());
            assertEquals("limit=5&after=" + ID, query.get());

            Map<?, ?> edited = (Map<?, ?>) Json.parse(rest.editMessage(CHANNEL, ID, "edited"));
            assertEquals("edited", edited.get("content"));
            assertEquals("PATCH", method.get());
            assertEquals("edited", ((Map<?, ?>) Json.parse(requestBody.get())).get("content"));

            assertEquals(204, rest.addReaction(CHANNEL, ID, "👍"));
            assertEquals("PUT", method.get());
            assertTrue(path.get().endsWith("/reactions/%F0%9F%91%8D/@me"));
            var users = (List<?>) Json.parse(rest.reactionUsers(CHANNEL, ID, "👍", "", 10));
            assertEquals(List.of(USER), users);
            assertEquals("limit=10", query.get());
            assertEquals(204, rest.deleteOwnReaction(CHANNEL, ID, "👍"));
            assertEquals("DELETE", method.get());
            assertEquals(204, rest.deleteMessage(CHANNEL, ID));
            assertEquals("DELETE", method.get());
            assertEquals("/channels/" + CHANNEL + "/messages/" + ID, path.get());
            assertEquals("Bot safe-token", authorization.get());
            assertEquals(9, requests.size());
        } finally {
            server.stop(0);
        }
    }

    @Test void rejectsInvalidIdsPaginationAndEmojiBeforeIssuingRequests() {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/"), "safe");
        assertThrows(IllegalArgumentException.class, () -> rest.getMessage("..", ID));
        assertThrows(IllegalArgumentException.class, () -> rest.getMessage(CHANNEL, "../evil"));
        assertThrows(IllegalArgumentException.class, () -> rest.listMessages(CHANNEL, "", "before", 0));
        assertThrows(IllegalArgumentException.class, () -> rest.listMessages(CHANNEL, "", "before", 101));
        assertThrows(IllegalArgumentException.class, () -> rest.listMessages(CHANNEL, "", "around", 5));
        assertThrows(IllegalArgumentException.class, () -> rest.listMessages(CHANNEL, "../bad", "after", 5));
        assertThrows(IllegalArgumentException.class, () -> rest.reactionUsers(CHANNEL, ID, "👍", "", 101));
        assertThrows(IllegalArgumentException.class, () -> rest.addReaction(CHANNEL, ID, ""));
        assertThrows(IllegalArgumentException.class, () -> rest.addReaction(CHANNEL, "wrong", "👍"));
        assertEquals("channels/" + CHANNEL + "/messages/" + ID + "/reactions/%2F%3F%23",
            DiscordRest.reactionPath(CHANNEL, ID, "/?#"));
        assertEquals("channels/" + CHANNEL + "/messages/" + ID + "/reactions/custom%3A123",
            DiscordRest.reactionPath(CHANNEL, ID, "custom:123"));
        assertThrows(IllegalArgumentException.class, () -> rest.editMessage(CHANNEL, ID, "x".repeat(2001)));
    }

    @Test void rejectsMalformedAndOverlargeMessageResponsePages() {
        assertThrows(IllegalStateException.class, () -> DiscordMessageModel.message("{}"));
        assertThrows(IllegalStateException.class, () -> DiscordMessageModel.message("[]"));
        assertThrows(IllegalStateException.class, () -> DiscordMessageModel.messages("{}"));
        assertThrows(IllegalStateException.class, () -> DiscordMessageModel.users("[{}]"));
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) tooMany.add(Json.parse(message("hello")));
        assertThrows(IllegalStateException.class, () ->
            DiscordMessageModel.messages(Json.write(tooMany)));
        Map<?, ?> record = (Map<?, ?>) Json.parse(DiscordMessageModel.message(message("test")));
        assertEquals(ID, record.get("id"));
        assertEquals("", record.get("timestamp"));
        assertEquals(false, record.get("pinned"));
    }

    @Test void rateLimitRoutesGroupPagesWithoutLeakingMessageIdentifiers() {
        assertEquals("GET channels/*/messages",
            DiscordRateLimiter.routeKey("GET", "channels/" + CHANNEL + "/messages?limit=5&before=" + ID));
        assertEquals("GET channels/*/messages/*",
            DiscordRateLimiter.routeKey("GET", "channels/" + CHANNEL + "/messages/" + ID));
        assertEquals("PUT channels/*/messages/*/reactions/*/@me",
            DiscordRateLimiter.routeKey("PUT", "channels/" + CHANNEL + "/messages/" + ID + "/reactions/%F0%9F%91%8D/@me"));
    }
}
