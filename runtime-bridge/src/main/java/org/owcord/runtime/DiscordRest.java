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
    private final DiscordRateLimiter limits = new DiscordRateLimiter();

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
        return registerGuildCommand(application, guild, name, description, null);
    }

    int registerGuildCommand(String application, String guild, String name, String description,
                             String defaultPermissions) {
        String path = "applications/" + snowflake(application) + "/guilds/" + snowflake(guild) + "/commands";
        HttpResponse<String> r = request("POST", path, commandPayload(name, description, defaultPermissions), true);
        check(r);
        return r.statusCode();
    }
    int registerGuildCommandOptions(String application, String guild, String name,
                                    String description, String optionsJson, String permissions) {
        String path = "applications/" + snowflake(application) + "/guilds/" + snowflake(guild) + "/commands";
        HttpResponse<String> result = request("POST", path,
            DiscordCommandPayload.command(name, description,
                permissions.isEmpty() ? null : permissions, optionsJson), true);
        check(result);
        return result.statusCode();
    }

    int replyAutocomplete(String interactionId, String token, String choicesJson) {
        HttpResponse<String> result = request("POST",
            "interactions/" + snowflake(interactionId) + "/" + interactionToken(token) + "/callback",
            DiscordCommandPayload.autocomplete(choicesJson), false);
        check(result);
        return result.statusCode();
    }

    int replyInteraction(String id, String interactionToken, String content) {
        return replyInteraction(id, interactionToken, content, false);
    }
    int replyInteraction(String id, String interactionToken, String content, boolean ephemeral) {
        HttpResponse<String> r = request("POST", "interactions/" + snowflake(id) + "/"
            + interactionToken(interactionToken) + "/callback", replyPayload(content, ephemeral), false);
        check(r);
        return r.statusCode();
    }
    int deferInteraction(String id, String token, boolean ephemeral) {
        HttpResponse<String> r = request("POST", "interactions/" + snowflake(id) + "/"
            + interactionToken(token) + "/callback", deferPayload(ephemeral), false);
        check(r);
        return r.statusCode();
    }
    int followupInteraction(String token, String content, boolean ephemeral) {
        return followupInteraction(OWCordExtension.requiredEnv("DISCORD_APPLICATION_ID"), token, content, ephemeral);
    }
    int followupInteraction(String applicationId, String token, String content, boolean ephemeral) {
        HttpResponse<String> r = request("POST", "webhooks/" + snowflake(applicationId) + "/"
            + interactionToken(token), followupPayload(content, ephemeral), false);
        check(r);
        return r.statusCode();
    }
    static String interactionToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_.-]{1,512}"))
            throw new IllegalArgumentException("Invalid interaction token");
        return token;
    }
    int sendMessage(String channel, String content) {
        HttpResponse<String> r = request("POST", "channels/" + snowflake(channel)
            + "/messages", messagePayload(content), true);
        check(r);
        return r.statusCode();
    }

    int sendRichMessage(String channel, String payloadJson) {
        HttpResponse<String> r = request("POST", "channels/" + snowflake(channel) + "/messages",
            RichPayload.message(payloadJson), true);
        check(r);
        return r.statusCode();
    }
    /** Create a plain-text message and return the exact Discord snowflake. */
    String createMessage(String channel, String content) {
        HttpResponse<String> r = request("POST", "channels/" + snowflake(channel) + "/messages",
            messagePayload(content), true);
        check(r);
        return DiscordMessageModel.messageId(r.body());
    }

    String getMessage(String channel, String messageId) {
        String path = "channels/" + snowflake(channel) + "/messages/" + snowflake(messageId);
        HttpResponse<String> r = request("GET", path, "", true);
        check(r);
        return DiscordMessageModel.message(r.body());
    }

    String listMessages(String channel, String cursor, String direction, int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Message page size must be 1-100");
        if (!direction.equals("before") && !direction.equals("after"))
            throw new IllegalArgumentException("Cursor direction must be before or after");
        String path = "channels/" + snowflake(channel) + "/messages?limit=" + limit;
        if (!cursor.isEmpty()) path += "&" + direction + "=" + snowflake(cursor);
        HttpResponse<String> r = request("GET", path, "", true);
        check(r);
        return DiscordMessageModel.messages(r.body());
    }

    String editMessage(String channel, String messageId, String content) {
        String path = "channels/" + snowflake(channel) + "/messages/" + snowflake(messageId);
        HttpResponse<String> r = request("PATCH", path, messagePayload(content), true);
        check(r);
        return DiscordMessageModel.message(r.body());
    }

    int deleteMessage(String channel, String messageId) {
        String path = "channels/" + snowflake(channel) + "/messages/" + snowflake(messageId);
        HttpResponse<String> r = request("DELETE", path, "", true);
        check(r);
        return r.statusCode();
    }

    int addReaction(String channel, String messageId, String emoji) {
        HttpResponse<String> r = request("PUT", reactionPath(channel, messageId, emoji) + "/@me", "", true);
        check(r);
        return r.statusCode();
    }

    int deleteOwnReaction(String channel, String messageId, String emoji) {
        HttpResponse<String> r = request("DELETE", reactionPath(channel, messageId, emoji) + "/@me", "", true);
        check(r);
        return r.statusCode();
    }

    String reactionUsers(String channel, String messageId, String emoji, String after, int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Reaction page size must be 1-100");
        String path = reactionPath(channel, messageId, emoji) + "?limit=" + limit;
        if (!after.isEmpty()) path += "&after=" + snowflake(after);
        HttpResponse<String> r = request("GET", path, "", true);
        check(r);
        return DiscordMessageModel.users(r.body());
    }

    static String reactionPath(String channel, String messageId, String emoji) {
        if (emoji == null || emoji.isBlank() || emoji.length() > 100)
            throw new IllegalArgumentException("Emoji must be nonempty and at most 100 characters");
        // Path-encode all Unicode bytes and custom emoji names (name:id), never
        // allow an emoji string to inject a slash, query, or new endpoint.
        String encoded = java.net.URLEncoder.encode(emoji, StandardCharsets.UTF_8)
            .replace("+", "%20");
        return "channels/" + snowflake(channel) + "/messages/" + snowflake(messageId)
            + "/reactions/" + encoded;
    }

    int replyRichInteraction(String id, String token, String payloadJson, boolean ephemeral) {
        HttpResponse<String> r = request("POST",
            "interactions/" + snowflake(id) + "/" + interactionToken(token) + "/callback",
            RichPayload.callback(payloadJson, 4, ephemeral), false);
        check(r);
        return r.statusCode();
    }
    int updateComponentMessage(String id, String token, String payloadJson) {
        HttpResponse<String> r = request("POST",
            "interactions/" + snowflake(id) + "/" + interactionToken(token) + "/callback",
            RichPayload.callback(payloadJson, 7, false), false);
        check(r);
        return r.statusCode();
    }
    int openModal(String id, String token, String modalJson) {
        HttpResponse<String> r = request("POST",
            "interactions/" + snowflake(id) + "/" + interactionToken(token) + "/callback",
            RichPayload.modal(modalJson), false);
        check(r);
        return r.statusCode();
    }

    static String snowflake(String id) {
        if (id == null || !id.matches("[0-9]{1,22}"))
            throw new IllegalArgumentException("Invalid Discord snowflake");
        return id;
    }
    static String commandPayload(String name, String desc) {
        return commandPayload(name, desc, null);
    }
    static String commandPayload(String name, String desc, String defaultPermissions) {
        if (name == null || !name.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid slash command name");
        if (desc == null || desc.isBlank() || desc.length() > 100)
            throw new IllegalArgumentException("Description must have 1-100 characters");
        if (defaultPermissions == null)
            return Json.write(Map.of("type", 1, "name", name, "description", desc));
        return Json.write(Map.of("type", 1, "name", name, "description", desc,
            "default_member_permissions", DiscordPermissions.defaultPermissions(defaultPermissions)));
    }
    static String messagePayload(String content) {
        return Json.write(Map.of("content", content(content),
            "allowed_mentions", Map.of("parse", List.of())));
    }
    static String replyPayload(String content) {
        return replyPayload(content, false);
    }
    static String replyPayload(String content, boolean ephemeral) {
        return Json.write(Map.of("type", 4, "data", followupData(content, ephemeral)));
    }
    static String deferPayload(boolean ephemeral) {
        if (ephemeral)
            return Json.write(Map.of("type", 5, "data", Map.of("flags", 64)));
        return Json.write(Map.of("type", 5));
    }
    static String followupPayload(String content, boolean ephemeral) {
        return Json.write(followupData(content, ephemeral));
    }
    private static Map<String, Object> followupData(String value, boolean ephemeral) {
        if (ephemeral)
            return Map.of("content", content(value), "flags", 64,
                "allowed_mentions", Map.of("parse", List.of()));
        return Map.of("content", content(value),
            "allowed_mentions", Map.of("parse", List.of()));
    }
    private static String content(String content) {
        if (content == null || content.length() > 2000)
            throw new IllegalArgumentException("Message content must be at most 2000 characters");
        return content;
    }

    /**
     * Serializing calls protects rate-limit observations within one OWCord
     * interpreter. This is bounded (at most three HTTP attempts) and never
     * blindly retries IO failures or ambiguous 5xx POST results.
     */
    private synchronized HttpResponse<String> request(String method, String path, String json, boolean auth) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json")
            .header("User-Agent", "OWCord/0.4 (https://github.com/OW-Programing-Language/OWCord)");
        if (auth) b.header("Authorization", "Bot " + token);
        switch (method) {
            case "POST", "PATCH" -> b.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            case "GET" -> b.GET();
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.noBody());
            case "DELETE" -> b.DELETE();
            default -> throw new IllegalArgumentException("Unsupported Discord HTTP method");
        }
        HttpRequest request = b.build();

        String route = DiscordRateLimiter.routeKey(method, path);
        String major = DiscordRateLimiter.majorResource(path);
        long start = System.nanoTime();
        // Interaction callbacks must be acknowledged in 3 seconds. Don't sleep
        // through that deadline waiting for a bucket to open.
        long maxWait = path.startsWith("interactions/") ? 2_000_000_000L : 8_000_000_000L;
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                long now = System.nanoTime();
                long delay = limits.waitNanos(route, major, auth, now);
                if (delay > 0) {
                    if (delay > maxWait - (now - start))
                        throw new IllegalStateException("Discord rate-limit wait exceeds request budget");
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
                }

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                limits.observe(route, major, auth, response, System.nanoTime());
                if (response.statusCode() != 429) return response;
                if (attempt == 2) throw new IllegalStateException("Discord rate limit persisted after three attempts");
            }
            throw new IllegalStateException("Discord retry limit exceeded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Discord request interrupted", e);
        } catch (IOException e) {
            // A write may already have been processed remotely: avoid duplication.
            throw new IllegalStateException("Discord request failed without retry", e);
        }
    }
    private static void check(HttpResponse<String> r) {
        if (r.statusCode() < 200 || r.statusCode() >= 300)
            throw new IllegalStateException("Discord HTTP status " + r.statusCode());
    }
}
