package org.owcord.runtime;

import dev.oreo.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Minimal typed Discord REST transport. Never log credentials or response bodies. */
final class DiscordRest {
    static final URI API = URI.create("https://discord.com/api/v10/");
    private final String token;
    private final URI base;
    private final HttpClient http;

    DiscordRest(String token) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), API, token);
    }
    DiscordRest(HttpClient client, URI base, String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Missing bot token");
        this.token = token;
        this.base = base;
        this.http = client;
    }
    HttpClient http() { return http; }

    String gatewayUrl() {
        HttpResponse<String> response = request("GET", "gateway/bot", "", true);
        check(response);
        Object json = Json.parse(response.body());
        if (!(json instanceof Map<?, ?> map) || !(map.get("url") instanceof String url))
            throw new IllegalStateException("Gateway URL missing");
        URI ws = URI.create(url);
        String host = ws.getHost();
        if (!"wss".equals(ws.getScheme()) || host == null ||
            !(host.equals("discord.gg") || host.endsWith(".discord.gg")
              || host.equals("discord.com") || host.endsWith(".discord.com")))
            throw new IllegalStateException("Invalid Gateway URL");
        return url;
    }

    int registerGuildCommand(String application, String guild, String name, String description) {
        String path = "applications/" + snowflake(application) + "/guilds/" + snowflake(guild) + "/commands";
        HttpResponse<String> r = request("POST", path, commandPayload(name, description), true);
        check(r);
        return r.statusCode();
    }
    int replyInteraction(String id, String interactionToken, String content) {
        if (interactionToken == null || !interactionToken.matches("[A-Za-z0-9_.-]{1,512}"))
            throw new IllegalArgumentException("Invalid interaction token");
        HttpResponse<String> r = request("POST", "interactions/" + snowflake(id) + "/"
            + interactionToken + "/callback", replyPayload(content), false);
        check(r);
        return r.statusCode();
    }
    int sendMessage(String channel, String content) {
        HttpResponse<String> r = request("POST", "channels/" + snowflake(channel)
            + "/messages", messagePayload(content), true);
        check(r);
        return r.statusCode();
    }

    static String snowflake(String id) {
        if (id == null || !id.matches("[0-9]{1,22}"))
            throw new IllegalArgumentException("Invalid Discord snowflake");
        return id;
    }
    static String commandPayload(String name, String desc) {
        if (name == null || !name.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid slash command name");
        if (desc == null || desc.isBlank() || desc.length() > 100)
            throw new IllegalArgumentException("Description must have 1-100 characters");
        return Json.write(Map.of("type", 1, "name", name, "description", desc));
    }
    static String messagePayload(String content) {
        return Json.write(Map.of("content", content(content),
            "allowed_mentions", Map.of("parse", List.of())));
    }
    static String replyPayload(String content) {
        return Json.write(Map.of("type", 4, "data",
            Map.of("content", content(content), "allowed_mentions", Map.of("parse", List.of()))));
    }
    private static String content(String content) {
        if (content == null || content.length() > 2000)
            throw new IllegalArgumentException("Message content must be at most 2000 characters");
        return content;
    }

    private HttpResponse<String> request(String method, String path, String json, boolean auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json")
            .header("User-Agent", "OWCord/0.1 (https://github.com/OW-Programing-Language/OWCord)");
        if (auth) b.header("Authorization", "Bot " + token);
        if ("POST".equals(method)) b.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        else b.GET();
        HttpRequest r = b.build();
        try {
            HttpResponse<String> out = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (out.statusCode() == 429) {
                double seconds = out.headers().firstValue("Retry-After").map(DiscordRest::seconds).orElse(1d);
                if (seconds > 3) throw new IllegalStateException("Discord rate limit exceeds 3 seconds");
                Thread.sleep((long) Math.ceil(Math.max(0.1, seconds) * 1000));
                out = http.send(r, HttpResponse.BodyHandlers.ofString());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discord request interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("Discord request failed", e);
        }
    }
    private static double seconds(String s) {
        try {
            double x = Double.parseDouble(s);
            return Double.isFinite(x) && x >= 0 ? x : 1;
        } catch (NumberFormatException e) { return 1; }
    }
    private static void check(HttpResponse<String> r) {
        if (r.statusCode() < 200 || r.statusCode() >= 300)
            throw new IllegalStateException("Discord HTTP status " + r.statusCode());
    }
}
