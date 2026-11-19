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

class RichPayloadTest {
    private static String rich() {
        return Json.write(Map.of(
            "content", "Click the button",
            "embeds", List.of(Map.of("title", "OWCord", "description", "A test", "color", 5793266)),
            "components", List.of(
                Map.of("type", 1, "components", List.of(
                    Map.of("type", 2, "custom_id", "test_button", "label", "Click", "style", 1))),
                Map.of("type", 1, "components", List.of(
                    Map.of("type", 3, "custom_id", "test_select", "placeholder", "Choose",
                        "options", List.of(Map.of("label", "Blue", "value", "blue"))))))));
    }

    @Test void serializesSafeEmbedAndInteractiveComponents() {
        Map<?, ?> result = (Map<?, ?>) Json.parse(RichPayload.message(rich()));
        assertEquals("Click the button", result.get("content"));
        assertEquals(List.of(), ((Map<?, ?>) result.get("allowed_mentions")).get("parse"));
        assertEquals("OWCord", ((Map<?, ?>) ((List<?>) result.get("embeds")).get(0)).get("title"));
        var components = (List<?>) result.get("components");
        assertEquals(2, components.size());
        var row = (Map<?, ?>) components.get(0);
        assertEquals("test_button", ((Map<?, ?>) ((List<?>) row.get("components")).get(0)).get("custom_id"));
        var selectRow = (Map<?, ?>) components.get(1);
        var select = (Map<?, ?>) ((List<?>) selectRow.get("components")).get(0);
        assertEquals(3, select.get("type"));
        assertEquals(1, select.get("max_values"));
        Map<?, ?> callback = (Map<?, ?>) Json.parse(RichPayload.callback(rich(), 4, true));
        assertEquals(4, callback.get("type"));
        assertEquals(64, ((Map<?, ?>) callback.get("data")).get("flags"));
        Map<?, ?> update = (Map<?, ?>) Json.parse(RichPayload.callback(rich(), 7, false));
        assertEquals(7, update.get("type"));
    }

    @Test void createsCurrentModalLabelAndRejectsMalformedPayloads() {
        String modal = Json.write(Map.of("custom_id", "feedback_form", "title", "Feedback",
            "components", List.of(Map.of("type", 18, "label", "Your feedback",
                "component", Map.of("type", 4, "custom_id", "feedback", "style", 2,
                    "required", true, "placeholder", "Write here")))));
        Map<?, ?> callback = (Map<?, ?>) Json.parse(RichPayload.modal(modal));
        assertEquals(9, callback.get("type"));
        var data = (Map<?, ?>) callback.get("data");
        var label = (Map<?, ?>) ((List<?>) data.get("components")).get(0);
        assertEquals(18, label.get("type"));
        assertEquals("feedback", ((Map<?, ?>) label.get("component")).get("custom_id"));
        assertThrows(IllegalArgumentException.class, () -> RichPayload.message("{}"));
        assertThrows(IllegalArgumentException.class, () -> RichPayload.message(Json.write(Map.of(
            "components", List.of(Map.of("type", 1, "components", List.of(
                Map.of("type", 2, "custom_id", "bad", "label", "X", "style", 5))))))));
        assertThrows(IllegalArgumentException.class, () -> RichPayload.modal(Json.write(Map.of(
            "custom_id", "test", "title", "Test", "components",
            List.of(Map.of("type", 18, "label", "Field", "component",
                Map.of("type", 4, "style", 3, "custom_id", "field")))))));
        assertThrows(IllegalArgumentException.class, () -> RichPayload.message(Json.write(Map.of(
            "content", "x".repeat(2001)))));
        assertThrows(IllegalArgumentException.class, () -> RichPayload.callback(rich(), 9, false));
    }

    @Test void sendsRealTransportShapesAgainstLocalHttpServer() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int code = exchange.getRequestURI().getPath().startsWith("/channels/") ? 201 : 204;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "example-token");
            assertEquals(201, rest.sendRichMessage("1234", rich()));
            assertEquals("/channels/1234/messages", path.get());
            assertEquals("Bot example-token", auth.get());
            assertEquals(2, ((List<?>) ((Map<?, ?>) Json.parse(body.get())).get("components")).size());

            assertEquals(204, rest.replyRichInteraction("1234", "opaque", rich(), true));
            assertEquals("/interactions/1234/opaque/callback", path.get());
            assertNull(auth.get(), "Discord interaction callback never sends bot authorization");
            assertEquals(4, ((Map<?, ?>) Json.parse(body.get())).get("type"));

            assertEquals(204, rest.updateComponentMessage("1234", "opaque", rich()));
            assertEquals(7, ((Map<?, ?>) Json.parse(body.get())).get("type"));

            String modal = Json.write(Map.of("custom_id", "form", "title", "Feedback", "components",
                List.of(Map.of("type", 18, "label", "Your feedback", "component",
                    Map.of("type", 4, "custom_id", "feedback", "style", 1)))));
            assertEquals(204, rest.openModal("1234", "opaque", modal));
            assertEquals(9, ((Map<?, ?>) Json.parse(body.get())).get("type"));
            assertNull(auth.get());
        } finally {
            server.stop(0);
        }
    }
}
