package org.owcord.runtime;

import dev.oreo.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayEventCacheTest {
    private static Map<?,?> entry(String json) {
        return (Map<?,?>)Json.parse(json);
    }
    private static Map<?,?> data(String json) {
        return (Map<?,?>)entry(json).get("data");
    }
    private static GatewayEventCache metadata(int limit, int ttl) {
        return new GatewayEventCache(new GatewayEventCache.Config(
            GatewayEventCache.Mode.METADATA, limit, ttl));
    }

    @Test void usesBoundedLruAndMergesPartialChannelUpdatesWithoutFabricatingFields() {
        GatewayEventCache cache = metadata(2, 300);
        cache.apply("GUILD_CREATE", Map.of("id","111","name","Test"));
        cache.apply("CHANNEL_CREATE", Map.of("id","222","guild_id","111",
            "name","before","type",0));
        assertEquals("Test", data(cache.getGuild("111")).get("name"));
        cache.apply("CHANNEL_UPDATE", Map.of("id","222","guild_id","111","name","after"));
        Map<?,?> channel = data(cache.getChannel("222"));
        assertEquals("after", channel.get("name"));
        assertEquals(0, channel.get("type"));
        assertEquals(true, entry(cache.getChannel("222")).get("partial"));
        cache.apply("CHANNEL_CREATE", Map.of("id","333","guild_id","111","name","new"));
        assertEquals(false, entry(cache.getGuild("111")).get("found"), "LRU evicts oldest");
        assertEquals("after", data(cache.getChannel("222")).get("name"));
        assertEquals(1L, ((Number)entry(cache.stats()).get("evictions")).longValue());
        assertEquals(2, entry(cache.stats()).get("entries"));
    }

    @Test void invalidatesGuildsMembersAndChannelsAndGuardsRoleChanges() {
        GatewayEventCache cache = new GatewayEventCache(new GatewayEventCache.Config(
            GatewayEventCache.Mode.MEMBERS, 20, 300));
        cache.apply("GUILD_CREATE", Map.of("id","111","name","Guild"));
        cache.apply("CHANNEL_CREATE", Map.of("id","222","guild_id","111","name","Channel"));
        cache.apply("GUILD_MEMBER_ADD", Map.of("guild_id","111",
            "user", Map.of("id","555","username","never-cache-me"),
            "roles",List.of("333")));
        Map<?,?> member = data(cache.getMember("111","555"));
        assertEquals(List.of("333"), member.get("roles"));
        assertFalse(member.containsKey("username"));
        cache.apply("GUILD_MEMBER_UPDATE", Map.of("guild_id","111",
            "user", Map.of("id","555")));
        assertEquals(List.of("333"), data(cache.getMember("111","555")).get("roles"),
            "Omitted role array must not wipe valid cached roles");
        cache.apply("GUILD_MEMBER_UPDATE", Map.of("guild_id","111",
            "user", Map.of("id","555"),"roles", List.of()));
        assertEquals(List.of(), data(cache.getMember("111","555")).get("roles"),
            "Explicit empty roles clears roles");
        cache.apply("GUILD_ROLE_UPDATE", Map.of("guild_id","111","role",Map.of("id","333")));
        assertEquals(false, entry(cache.getMember("111","555")).get("found"),
            "Role changes invalidate member associations");
        cache.apply("GUILD_MEMBER_ADD", Map.of("guild_id","111",
            "user",Map.of("id","555"),"roles",List.of("333")));
        cache.apply("GUILD_MEMBER_REMOVE", Map.of("guild_id","111","user",Map.of("id","555")));
        assertEquals(false, entry(cache.getMember("111","555")).get("found"));
        cache.apply("GUILD_MEMBER_ADD", Map.of("guild_id","111",
            "user",Map.of("id","555"),"roles",List.of("333")));
        cache.apply("GUILD_DELETE", Map.of("id","111"));
        assertEquals(false, entry(cache.getGuild("111")).get("found"));
        assertEquals(false, entry(cache.getChannel("222")).get("found"));
        assertEquals(false, entry(cache.getMember("111","555")).get("found"));
    }

    @Test void disabledOrMetadataOnlyModesNeverCacheMemberDataOrTokens() {
        GatewayEventCache off = new GatewayEventCache(new GatewayEventCache.Config(
            GatewayEventCache.Mode.OFF, 20, 300));
        off.apply("GUILD_CREATE", Map.of("id","111","name","hidden"));
        assertEquals(false, entry(off.getGuild("111")).get("found"));
        assertEquals(0, entry(off.stats()).get("entries"));
        GatewayEventCache cache = metadata(20, 300);
        cache.apply("GUILD_MEMBER_ADD", Map.of("guild_id","111",
            "user",Map.of("id","555"),"roles",List.of("333")));
        assertEquals(false, entry(cache.getMember("111","555")).get("found"));
        cache.apply("MESSAGE_CREATE", Map.of("id","999","guild_id","111",
            "content","do not cache message text","token","secret"));
        assertEquals(0, entry(cache.stats()).get("entries"));
        cache.apply("CHANNEL_CREATE", Map.of("id","222","guild_id","111",
            "name","public","token","also-secret","topic","okay"));
        assertFalse(cache.getChannel("222").contains("also-secret"));
        assertFalse(cache.getChannel("222").contains("do not cache message text"));
    }

    @Test void expiresAndRejectsInvalidConfig() throws Exception {
        GatewayEventCache cache = metadata(3, 1);
        cache.apply("CHANNEL_CREATE", Map.of("id","222","guild_id","111","name","temporary"));
        assertEquals(true, entry(cache.getChannel("222")).get("found"));
        Thread.sleep(1100);
        assertEquals(false, entry(cache.getChannel("222")).get("found"));
        assertEquals(0, entry(cache.stats()).get("entries"));
        assertEquals(GatewayEventCache.Mode.MEMBERS,
            GatewayEventCache.Config.parse("members","20","30").mode());
        for(String bad : List.of("invalid", "all", "MESSAGES"))
            assertThrows(IllegalArgumentException.class, () ->
                GatewayEventCache.Config.parse(bad,"20","30"));
        assertThrows(IllegalArgumentException.class, () ->
            GatewayEventCache.Config.parse("metadata","4097","30"));
        assertThrows(IllegalArgumentException.class, () ->
            GatewayEventCache.Config.parse("metadata","20","0"));
        assertThrows(IllegalArgumentException.class, () ->
            GatewayEventCache.Config.parse("metadata","x","30"));
    }

    @Test void gatewayDispatchAutomaticallyUpdatesCacheAndReadyInvalidates() {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(),
            URI.create("http://localhost/"),"safe-token");
        try(DiscordGateway gateway = new DiscordGateway(rest,513,
            new GatewayEventCache.Config(GatewayEventCache.Mode.METADATA,10,300))) {
            gateway.receive("{\"op\":0,\"s\":1,\"t\":\"GUILD_CREATE\",\"d\":{\"id\":\"111\",\"name\":\"Live\"}}", null);
            assertEquals("Live", data(gateway.cache().getGuild("111")).get("name"));
            gateway.receive("{\"op\":0,\"s\":2,\"t\":\"CHANNEL_CREATE\",\"d\":{\"id\":\"222\",\"guild_id\":\"111\",\"name\":\"Lobby\"}}", null);
            assertEquals("Lobby", data(gateway.cache().getChannel("222")).get("name"));
            gateway.receive("{\"op\":0,\"s\":3,\"t\":\"READY\",\"d\":{\"session_id\":\"safe-session\"}}", null);
            assertEquals(false, entry(gateway.cache().getGuild("111")).get("found"));
            assertEquals(false, entry(gateway.cache().getChannel("222")).get("found"));
        }
    }
}
