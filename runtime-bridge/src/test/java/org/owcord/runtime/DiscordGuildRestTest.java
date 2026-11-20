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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordGuildRestTest {
    static final String G = "111", U = "222", C = "333", R = "444";
    static final String MEMBER = "{\"user\":{\"id\":\"222\"},\"nick\":\"Dev\",\"roles\":[\"444\"],\"joined_at\":\"2026-01-01T00:00:00Z\"}";
    static final String CHANNEL = "{\"id\":\"333\",\"guild_id\":\"111\",\"name\":\"general\",\"type\":0,\"topic\":\"topic\"}";
    static final String ROLE = "{\"id\":\"444\",\"name\":\"Dev\",\"permissions\":\"1152921504606846976\",\"position\":2}";

    @Test void parsesGuildChannelsRolesAndMembersWithExactIds() {
        Map<?, ?> guild = (Map<?, ?>) Json.parse(DiscordGuildModel.guild(
            "{\"id\":\"111\",\"name\":\"Test Guild\",\"owner_id\":\"222\"}"));
        assertEquals(G, guild.get("id"));
        assertEquals(U, guild.get("owner_id"));
        assertEquals("Test Guild", guild.get("name"));
        Map<?, ?> channel = (Map<?, ?>) Json.parse(DiscordGuildModel.channel(CHANNEL));
        assertEquals(C, channel.get("id"));
        assertEquals(0, channel.get("type"));
        assertEquals("topic", channel.get("topic"));
        Map<?, ?> role = (Map<?, ?>) Json.parse(DiscordGuildModel.role(ROLE));
        assertEquals("1152921504606846976", role.get("permissions"));
        assertEquals(2, role.get("position"));
        Map<?, ?> member = (Map<?, ?>) Json.parse(DiscordGuildModel.member(MEMBER));
        assertEquals(U, member.get("user_id"));
        assertEquals(List.of(R), member.get("roles"));
        assertEquals("Dev", member.get("nick"));
        assertEquals(1, ((List<?>) Json.parse(DiscordGuildModel.channels("[" + CHANNEL + "]"))).size());
        assertEquals(1, ((List<?>) Json.parse(DiscordGuildModel.roles("[" + ROLE + "]"))).size());
        assertEquals(1, ((List<?>) Json.parse(DiscordGuildModel.members("[" + MEMBER + "]"))).size());
        assertThrows(IllegalStateException.class, () -> DiscordGuildModel.member("{}"));
        assertThrows(IllegalStateException.class, () -> DiscordGuildModel.role("{\"id\":\"444\"}"));
    }

    @Test void validatesMutationsAndAuditReasonBeforeNetwork() {
        Map<?, ?> text = (Map<?, ?>) Json.parse(DiscordGuildPayload.newTextChannel("tester", "topic"));
        assertEquals(0, text.get("type"));
        assertEquals("tester", text.get("name"));
        Map<?, ?> safeRole = (Map<?, ?>) Json.parse(DiscordGuildPayload.newSafeRole("safe"));
        assertEquals("0", safeRole.get("permissions"));
        assertEquals(false, safeRole.get("mentionable"));
        assertEquals(false, safeRole.get("hoist"));
        assertEquals(Map.of("name", "renamed"),
            Json.parse(DiscordGuildPayload.renameRole("renamed")));
        assertEquals("OWCord%20test%20%26%20audit",
            DiscordGuildPayload.auditReason("OWCord test & audit"));
        for (String bad : List.of("", " ", "a\nb", "\r", "z".repeat(161))) {
            assertThrows(IllegalArgumentException.class, () -> DiscordGuildPayload.auditReason(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> DiscordGuildPayload.newTextChannel("", ""));
        assertThrows(IllegalArgumentException.class, () -> DiscordGuildPayload.newTextChannel("ok", "a".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> DiscordGuildPayload.newSafeRole(""));
        assertThrows(IllegalArgumentException.class, () -> DiscordGuildPayload.renameRole("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> DiscordGuildPayload.auditReason(null));
    }

    @Test void routesReadAndAuditGuardedWriteCallsThroughLocalHttpServer() throws Exception {
        AtomicReference<String> lastPath = new AtomicReference<>("");
        AtomicReference<String> lastMethod = new AtomicReference<>("");
        AtomicReference<String> lastQuery = new AtomicReference<>("");
        AtomicReference<String> lastReason = new AtomicReference<>();
        AtomicReference<String> lastBody = new AtomicReference<>("");
        AtomicInteger requestCount = new AtomicInteger();
        List<String> history = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String p = exchange.getRequestURI().getRawPath();
            String m = exchange.getRequestMethod();
            requestCount.incrementAndGet();
            lastPath.set(p);
            lastQuery.set(exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
            lastMethod.set(m);
            lastReason.set(exchange.getRequestHeaders().getFirst("X-Audit-Log-Reason"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            history.add(m + " " + p);
            String result = "";
            int code = 200;
            if (m.equals("GET")) {
                if (p.equals("/guilds/111")) result = "{\"id\":\"111\",\"name\":\"Test Guild\"}";
                else if (p.equals("/guilds/111/channels")) result = "[" + CHANNEL + "]";
                else if (p.equals("/channels/333")) result = CHANNEL;
                else if (p.equals("/guilds/111/roles")) result = "[" + ROLE + "]";
                else if (p.equals("/guilds/111/members/222")) result = MEMBER;
                else if (p.equals("/guilds/111/members")) result = "[" + MEMBER + "]";
                else code = 404;
            } else if (m.equals("POST") && p.equals("/guilds/111/channels")) {
                code = 201;
                result = "{\"id\":\"555\",\"guild_id\":\"111\",\"name\":\"lab\",\"type\":0}";
            } else if (m.equals("PATCH") && p.equals("/channels/333")) {
                result = "{\"id\":\"333\",\"guild_id\":\"111\",\"name\":\"renamed\",\"type\":0}";
            } else if (m.equals("POST") && p.equals("/guilds/111/roles")) {
                code = 200;
                result = "{\"id\":\"666\",\"name\":\"safe\",\"permissions\":\"0\"}";
            } else if (m.equals("PATCH") && p.equals("/guilds/111/roles/444")) {
                result = "{\"id\":\"444\",\"name\":\"renamed\",\"permissions\":\"0\"}";
            } else if (m.equals("DELETE") || m.equals("PUT")) {
                code = 204;
            } else code = 404;
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            }
            exchange.close();
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "test-token");
            assertEquals(G, ((Map<?, ?>) Json.parse(rest.getGuild(G))).get("id"));
            assertNull(lastReason.get(), "Read requests should not carry an audit header");
            assertEquals(1, ((List<?>) Json.parse(rest.listGuildChannels(G))).size());
            assertEquals(C, ((Map<?, ?>) Json.parse(rest.getChannel(C))).get("id"));
            assertEquals("1152921504606846976",
                ((Map<?, ?>) ((List<?>) Json.parse(rest.listGuildRoles(G))).get(0)).get("permissions"));
            assertEquals(U, ((Map<?, ?>) Json.parse(rest.getGuildMember(G, U))).get("user_id"));
            assertEquals(1, ((List<?>) Json.parse(rest.listGuildMembers(G, "", 5))).size());
            assertEquals("limit=5", lastQuery.get());
            assertEquals(1, ((List<?>) Json.parse(rest.listGuildMembers(G, U, 20))).size());
            assertEquals("limit=20&after=222", lastQuery.get());

            Map<?, ?> created = (Map<?, ?>) Json.parse(rest.createTextChannel(G, "lab", "", "OWCord test"));
            assertEquals("555", created.get("id"));
            assertEquals("OWCord%20test", lastReason.get());
            assertEquals(0, ((Map<?, ?>) Json.parse(lastBody.get())).get("type"));
            assertEquals("renamed", ((Map<?, ?>) Json.parse(rest.editTextChannel(C, "renamed",
                "safe topic", "OWCord test"))).get("name"));
            assertEquals(204, rest.deleteChannel(C, "OWCord test"));
            Map<?, ?> role = (Map<?, ?>) Json.parse(rest.createSafeRole(G, "safe", "OWCord test"));
            assertEquals("0", role.get("permissions"));
            assertEquals("0", ((Map<?, ?>) Json.parse(lastBody.get())).get("permissions"));
            assertEquals("renamed", ((Map<?, ?>) Json.parse(rest.renameRole(G, R, "renamed", "OWCord test"))).get("name"));
            assertEquals(204, rest.deleteRole(G, R, "OWCord test"));
            assertEquals(204, rest.addMemberRole(G, U, R, "OWCord test"));
            assertEquals("PUT", lastMethod.get());
            assertEquals(204, rest.removeMemberRole(G, U, R, "OWCord test"));
            assertEquals("/guilds/111/members/222/roles/444", lastPath.get());
            assertEquals("OWCord%20test", lastReason.get());

            int before = requestCount.get();
            assertThrows(IllegalArgumentException.class, () ->
                rest.createSafeRole(G, "invalid", ""));
            assertThrows(IllegalArgumentException.class, () ->
                rest.deleteChannel(C, "bad\nreason"));
            assertThrows(IllegalArgumentException.class, () ->
                rest.listGuildMembers(G, "", 0));
            assertThrows(IllegalArgumentException.class, () ->
                rest.listGuildMembers(G, "", 1001));
            assertThrows(IllegalArgumentException.class, () ->
                rest.listGuildMembers(G, "../bad", 10));
            assertThrows(IllegalArgumentException.class, () ->
                rest.getGuildMember(G, "bad"));
            assertEquals(before, requestCount.get(), "Invalid input must not hit the network");
            assertEquals(15, history.size());
        } finally {
            server.stop(0);
        }
    }

    @Test void routeKeysDoNotAccumulatePerUserOrPerRole() {
        assertEquals("GET guilds/*/members/*",
            DiscordRateLimiter.routeKey("GET", "guilds/111/members/222"));
        assertEquals("GET guilds/*/members",
            DiscordRateLimiter.routeKey("GET", "guilds/111/members?limit=10&after=222"));
        assertEquals("PUT guilds/*/members/*/roles/*",
            DiscordRateLimiter.routeKey("PUT", "guilds/111/members/222/roles/444"));
        assertEquals("PATCH channels/*",
            DiscordRateLimiter.routeKey("PATCH", "channels/333"));
    }
}
