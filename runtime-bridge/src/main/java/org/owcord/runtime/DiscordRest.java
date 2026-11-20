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
    private final DiscordRestTelemetry telemetry = new DiscordRestTelemetry();
    String metrics() { return telemetry.snapshot(); }
    String lastError() { return telemetry.lastError(); }

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

    GatewayBotInfo gatewayBotInfo() {
        HttpResponse<String> response=request("GET","gateway/bot","",true);
        check(response);
        return GatewayBotInfo.parse(response.body());
    }
    String gatewayUrl() { return gatewayBotInfo().url(); }

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

    // Global command scopes, unlike development guild commands, can specify
    // contexts and integration types. A global command may take time to propagate.
    int registerGlobalContextCommand(String applicationId, String name, String description,
                                     String contextsJson, String installationsJson) {
        String path = "applications/" + snowflake(applicationId) + "/commands";
        HttpResponse<String> r = request("POST", path,
            DiscordCommandContexts.command(name, description, contextsJson, installationsJson), true);
        check(r);
        return r.statusCode();
    }

    /** Creates a Components V2 message and returns its exact snowflake ID. */
    String createV2Message(String channelId, String json) {
        String path = "channels/" + snowflake(channelId) + "/messages";
        HttpResponse<String> r = request("POST", path, ComponentsV2Payload.message(json), true);
        check(r);
        return DiscordMessageModel.messageId(r.body());
    }

    int replyV2Interaction(String interactionId, String token, String json, boolean ephemeral) {
        String path = "interactions/" + snowflake(interactionId) + "/"
            + interactionToken(token) + "/callback";
        HttpResponse<String> r = request("POST", path,
            ComponentsV2Payload.callback(json, ephemeral, false), false);
        check(r);
        return r.statusCode();
    }

    int updateV2Component(String interactionId, String token, String json) {
        String path = "interactions/" + snowflake(interactionId) + "/"
            + interactionToken(token) + "/callback";
        HttpResponse<String> r = request("POST", path,
            ComponentsV2Payload.callback(json, false, true), false);
        check(r);
        return r.statusCode();
    }

    int deferComponentUpdate(String interactionId, String token) {
        String path = "interactions/" + snowflake(interactionId) + "/"
            + interactionToken(token) + "/callback";
        HttpResponse<String> r = request("POST", path, Json.write(Map.of("type", 6)), false);
        check(r);
        return r.statusCode();
    }

    private static String interactionWebhook(String applicationId, String token, String messageId) {
        String message = messageId.equals("@original") ? messageId : snowflake(messageId);
        return "webhooks/" + snowflake(applicationId) + "/" + interactionToken(token)
            + "/messages/" + message;
    }

    String editOriginalResponse(String applicationId, String token, String content) {
        HttpResponse<String> r = request("PATCH",
            interactionWebhook(applicationId, token, "@original"), messagePayload(content), false);
        check(r);
        return DiscordMessageModel.message(r.body());
    }

    int deleteOriginalResponse(String applicationId, String token) {
        HttpResponse<String> r = request("DELETE",
            interactionWebhook(applicationId, token, "@original"), "", false);
        check(r);
        return r.statusCode();
    }

    String editFollowupResponse(String applicationId, String token, String messageId, String content) {
        HttpResponse<String> r = request("PATCH",
            interactionWebhook(applicationId, token, messageId), messagePayload(content), false);
        check(r);
        return DiscordMessageModel.message(r.body());
    }

    int deleteFollowupResponse(String applicationId, String token, String messageId) {
        HttpResponse<String> r = request("DELETE",
            interactionWebhook(applicationId, token, messageId), "", false);
        check(r);
        return r.statusCode();
    }

    String createV2Followup(String applicationId, String token, String json, boolean ephemeral) {
        HttpResponse<String> r = request("POST",
            "webhooks/" + snowflake(applicationId) + "/" + interactionToken(token),
            ComponentsV2Payload.message(json, ephemeral),
            false);
        check(r);
        return DiscordMessageModel.messageId(r.body());
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

    // v0.8: read-only guild, channel, member and role information.
    String getGuild(String guildId) {
        HttpResponse<String> r = request("GET", "guilds/" + snowflake(guildId), "", true);
        check(r);
        return DiscordGuildModel.guild(r.body());
    }

    String listGuildChannels(String guildId) {
        HttpResponse<String> r = request("GET", "guilds/" + snowflake(guildId) + "/channels", "", true);
        check(r);
        return DiscordGuildModel.channels(r.body());
    }

    String getChannel(String channelId) {
        HttpResponse<String> r = request("GET", "channels/" + snowflake(channelId), "", true);
        check(r);
        return DiscordGuildModel.channel(r.body());
    }

    String listGuildRoles(String guildId) {
        HttpResponse<String> r = request("GET", "guilds/" + snowflake(guildId) + "/roles", "", true);
        check(r);
        return DiscordGuildModel.roles(r.body());
    }

    String getGuildMember(String guildId, String userId) {
        String path = "guilds/" + snowflake(guildId) + "/members/" + snowflake(userId);
        HttpResponse<String> r = request("GET", path, "", true);
        check(r);
        return DiscordGuildModel.member(r.body());
    }

    String listGuildMembers(String guildId, String afterId, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Member page limit must be 1-1000");
        String path = "guilds/" + snowflake(guildId) + "/members?limit=" + limit;
        if (!afterId.isEmpty()) path += "&after=" + snowflake(afterId);
        HttpResponse<String> r = request("GET", path, "", true);
        check(r);
        return DiscordGuildModel.members(r.body());
    }

    // Mutations intentionally require explicit audit reasons and are NEVER used
    // by the live development guild smoke test.
    String createTextChannel(String guildId, String name, String topic, String reason) {
        HttpResponse<String> r = request("POST", "guilds/" + snowflake(guildId) + "/channels",
            DiscordGuildPayload.newTextChannel(name, topic), true, reason);
        check(r);
        return DiscordGuildModel.channel(r.body());
    }

    String editTextChannel(String channelId, String name, String topic, String reason) {
        HttpResponse<String> r = request("PATCH", "channels/" + snowflake(channelId),
            DiscordGuildPayload.updateTextChannel(name, topic), true, reason);
        check(r);
        return DiscordGuildModel.channel(r.body());
    }

    int deleteChannel(String channelId, String reason) {
        HttpResponse<String> r = request("DELETE", "channels/" + snowflake(channelId),
            "", true, reason);
        check(r);
        return r.statusCode();
    }

    String createSafeRole(String guildId, String name, String reason) {
        HttpResponse<String> r = request("POST", "guilds/" + snowflake(guildId) + "/roles",
            DiscordGuildPayload.newSafeRole(name), true, reason);
        check(r);
        return DiscordGuildModel.role(r.body());
    }

    String renameRole(String guildId, String roleId, String name, String reason) {
        HttpResponse<String> r = request("PATCH", "guilds/" + snowflake(guildId)
            + "/roles/" + snowflake(roleId), DiscordGuildPayload.renameRole(name), true, reason);
        check(r);
        return DiscordGuildModel.role(r.body());
    }

    int deleteRole(String guildId, String roleId, String reason) {
        HttpResponse<String> r = request("DELETE", "guilds/" + snowflake(guildId)
            + "/roles/" + snowflake(roleId), "", true, reason);
        check(r);
        return r.statusCode();
    }

    int addMemberRole(String guildId, String userId, String roleId, String reason) {
        HttpResponse<String> r = request("PUT", "guilds/" + snowflake(guildId)
            + "/members/" + snowflake(userId) + "/roles/" + snowflake(roleId), "", true, reason);
        check(r);
        return r.statusCode();
    }

    int removeMemberRole(String guildId, String userId, String roleId, String reason) {
        HttpResponse<String> r = request("DELETE", "guilds/" + snowflake(guildId)
            + "/members/" + snowflake(userId) + "/roles/" + snowflake(roleId), "", true, reason);
        check(r);
        return r.statusCode();
    }

    // Moderation operations always carry an explicit validated audit reason.
    // Discord remains authoritative for bot permissions, guild ownership and
    // role hierarchy; this transport does not attempt to bypass these checks.
    int kickMember(String guildId, String userId, String reason) {
        String path = "guilds/" + snowflake(guildId) + "/members/" + snowflake(userId);
        HttpResponse<String> r = request("DELETE", path, "", true, reason);
        check(r);
        return r.statusCode();
    }

    int banMember(String guildId, String userId, int deleteMessageSeconds, String reason) {
        String path = "guilds/" + snowflake(guildId) + "/bans/" + snowflake(userId);
        HttpResponse<String> r = request("PUT", path,
            DiscordModerationPayload.ban(deleteMessageSeconds), true, reason);
        check(r);
        return r.statusCode();
    }

    int unbanMember(String guildId, String userId, String reason) {
        String path = "guilds/" + snowflake(guildId) + "/bans/" + snowflake(userId);
        HttpResponse<String> r = request("DELETE", path, "", true, reason);
        check(r);
        return r.statusCode();
    }

    String timeoutMember(String guildId, String userId, String untilIso, String reason) {
        String path = "guilds/" + snowflake(guildId) + "/members/" + snowflake(userId);
        HttpResponse<String> r = request("PATCH", path,
            DiscordModerationPayload.timeout(untilIso), true, reason);
        check(r);
        return DiscordGuildModel.member(r.body());
    }

    String clearMemberTimeout(String guildId, String userId, String reason) {
        String path = "guilds/" + snowflake(guildId) + "/members/" + snowflake(userId);
        HttpResponse<String> r = request("PATCH", path,
            DiscordModerationPayload.clearTimeout(), true, reason);
        check(r);
        return DiscordGuildModel.member(r.body());
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
    private HttpResponse<String> request(String method, String path, String json, boolean auth) {
        return request(method, path, json, auth, null);
    }

    private synchronized HttpResponse<String> request(String method, String path, String json,
                                                       boolean auth, String auditReason) {
        // All outward-facing labels are normalized: interaction/webhook tokens
        // never appear in diagnostic strings, metrics or exception messages.
        String route=DiscordRateLimiter.routeKey(method,path);
        String major=DiscordRateLimiter.majorResource(path);
        String encodedAuditReason=auditReason==null ? "" : DiscordGuildPayload.auditReason(auditReason);
        HttpRequest.Builder b=HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(12))
            .header("Accept","application/json")
            .header("User-Agent","OWCord/0.13 (https://github.com/OW-Programing-Language/OWCord)");
        if(auth) b.header("Authorization","Bot "+token);
        if(!encodedAuditReason.isEmpty()) b.header("X-Audit-Log-Reason",encodedAuditReason);
        switch(method) {
            case "POST","PATCH" -> b.header("Content-Type","application/json")
                .method(method,HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8));
            case "GET" -> b.GET();
            case "PUT" -> {
                if(json!=null && !json.isEmpty())
                    b.header("Content-Type","application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8));
                else b.PUT(HttpRequest.BodyPublishers.noBody());
            }
            case "DELETE" -> b.DELETE();
            default -> throw new IllegalArgumentException("Unsupported Discord HTTP method");
        }
        HttpRequest request=b.build();
        long start=System.nanoTime();
        long maxWait=path.startsWith("interactions/") ? 2_000_000_000L : 8_000_000_000L;
        try {
            for(int attempt=0;attempt<3;attempt++) {
                long now=System.nanoTime();
                long delay=limits.waitNanos(route,major,auth,now);
                if(delay>0) {
                    if(delay>maxWait-(now-start))
                        throw new IllegalStateException("Discord rate-limit wait exceeds request budget");
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
                }
                telemetry.attempt();
                long sentAt=System.nanoTime();
                HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
                telemetry.completed(route,response.statusCode(),System.nanoTime()-sentAt);
                limits.observe(route,major,auth,response,System.nanoTime());

                if(response.statusCode()==429) {
                    if(attempt==2) {
                        DiscordApiException error=DiscordApiException.from(
                            response,route,DiscordRestTelemetry.nextRequestId());
                        telemetry.failed(error);
                        throw error;
                    }
                    telemetry.retry();
                    continue;
                }

                // Only GET may safely retry transient 502/503/504. Mutation
                // requests are never replayed on server errors or IO failures.
                if(method.equals("GET") &&
                   (response.statusCode()==502 || response.statusCode()==503 ||
                    response.statusCode()==504) && attempt<2) {
                    long backoffMs=50L*(attempt+1);
                    if(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(backoffMs)
                       > maxWait-(System.nanoTime()-start))
                        return response;
                    telemetry.retry();
                    java.util.concurrent.TimeUnit.MILLISECONDS.sleep(backoffMs);
                    continue;
                }
                return response;
            }
            throw new IllegalStateException("Discord retry limit exceeded");
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();
            telemetry.transportError();
            throw new IllegalStateException("Discord request interrupted",error);
        } catch(IOException error) {
            // No network retries: mutations may have been processed remotely.
            telemetry.transportError();
            throw new IllegalStateException("Discord transport failure without retry");
        }
    }
    private void check(HttpResponse<String> r) {
        if(r.statusCode()<200 || r.statusCode()>=300) {
            String rawPath=r.request().uri().getRawPath();
            String basePath=base.getRawPath();
            if(basePath!=null && rawPath.startsWith(basePath)) rawPath=rawPath.substring(basePath.length());
            else while(rawPath.startsWith("/")) rawPath=rawPath.substring(1);
            String route=DiscordRateLimiter.routeKey(r.request().method(),rawPath);
            DiscordApiException error=DiscordApiException.from(
                r,route,DiscordRestTelemetry.nextRequestId());
            telemetry.failed(error);
            throw error;
        }
    }
}
