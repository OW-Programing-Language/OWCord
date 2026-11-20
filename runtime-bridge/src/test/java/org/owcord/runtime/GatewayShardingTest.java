package org.owcord.runtime;

import dev.oreo.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GatewayShardingTest {
    private static final String GATEWAY = "wss://gateway.discord.gg/";

    @Test void parsesShardAssignmentsAndPreventsDuplicates() {
        assertEquals(List.of(0),GatewayShardConfig.parse("1","").localIds());
        assertEquals(List.of(0,1,2,3),GatewayShardConfig.parse("4","").localIds());
        GatewayShardConfig subset=GatewayShardConfig.parse("64","1,9,17");
        assertEquals(List.of(1,9,17),subset.localIds());
        assertEquals(64,subset.total());
        assertEquals("process",((Map<?,?>)Json.parse(subset.summary())).get("coordinator_scope"));
        for(String[] input:new String[][]{
            {"0",""},{"4","0,0"},{"4","4"},{"4","-1"},{"4","1, "},
            {"4","oops"},{"4097",""},{"65",""},{"x",""},{"1","1"}}) {
            assertThrows(IllegalArgumentException.class,
                ()->GatewayShardConfig.parse(input[0],input[1]),
                input[0]+":"+input[1]);
        }
    }

    @Test void identifiesAreRateGatedByShardModuloMaxConcurrency() {
        AtomicLong now=new AtomicLong(1_000_000);
        GatewayIdentifyCoordinator gate=new GatewayIdentifyCoordinator(
            2,20,20,60_000,now::get);
        assertEquals(0,gate.reserveDelayMillis(0,8));
        assertEquals(0,gate.reserveDelayMillis(1,8));
        assertEquals(5_100,gate.reserveDelayMillis(2,8),
            "Shard 2 shares bucket 0 with shard 0");
        assertEquals(5_100,gate.reserveDelayMillis(3,8),
            "Shard 3 shares bucket 1 with shard 1");
        assertEquals(10_200,gate.reserveDelayMillis(4,8));
        assertEquals(15,gate.remaining());
        now.addAndGet(15_000);
        assertEquals(0,gate.reserveDelayMillis(6,8),
            "A new bucket reservation can be used once 5s elapsed");
        assertThrows(IllegalArgumentException.class,
            ()->gate.reserveDelayMillis(8,8));
    }

    @Test void refusesOversubscribedOrUnsafeIdentifyBudgets() {
        AtomicLong now=new AtomicLong(4_000_000);
        GatewayIdentifyCoordinator gate=new GatewayIdentifyCoordinator(
            1,2,1,60_000,now::get);
        assertEquals(0,gate.reserveDelayMillis(0,2));
        assertEquals(60_000,gate.reserveDelayMillis(1,2),
            "Budget renewal must delay new Identify until reset");
        assertThrows(IllegalStateException.class,()->{
            GatewayIdentifyCoordinator unsafe=new GatewayIdentifyCoordinator(
                1,1,0,86_400_000,now::get);
            unsafe.reserveDelayMillis(0,1);
        });
        assertThrows(IllegalArgumentException.class,
            ()->new GatewayIdentifyCoordinator(0,2,2,60_000));
        assertThrows(IllegalArgumentException.class,
            ()->new GatewayIdentifyCoordinator(1,2,3,60_000));
    }

    @Test void parsesGatewayBotMetadataAndRejectsUntrustedUrls() {
        String valid="{\"url\":\"wss://gateway.discord.gg\",\"shards\":4,"
            +"\"session_start_limit\":{\"total\":1000,\"remaining\":942,"
            +"\"reset_after\":86400000,\"max_concurrency\":2}}";
        GatewayBotInfo info=GatewayBotInfo.parse(valid);
        assertEquals(4,info.recommendedShards());
        assertEquals(942,info.remainingStarts());
        assertEquals(2,info.maxConcurrency());
        assertEquals(2,info.coordinator().concurrency());
        assertThrows(IllegalStateException.class,()->
            GatewayBotInfo.parse(valid.replace("gateway.discord.gg","evil.example")));
        assertThrows(IllegalStateException.class,()->
            GatewayBotInfo.parse(valid.replace("942","1001")));
        assertThrows(IllegalStateException.class,()->
            GatewayBotInfo.parse("{\"url\":\"wss://gateway.discord.gg\"}"));
    }

    @Test void identifyPayloadCarriesExactShardIdAndTotal() {
        Map<?,?> frame=(Map<?,?>)Json.parse(DiscordGateway.identifyPayload(
            "masked-test-token",513,3,8));
        assertEquals(2,frame.get("op"));
        Map<?,?> data=(Map<?,?>)frame.get("d");
        assertEquals(513,data.get("intents"));
        assertEquals(List.of(3,8),data.get("shard"));
        assertThrows(IllegalArgumentException.class,()->
            DiscordGateway.identifyPayload("token",1,8,8));
        assertThrows(IllegalArgumentException.class,()->
            DiscordGateway.identifyPayload("",1,0,1));
    }

    @Test void shardsHaveIndependentSessionsCachesAndFairEventDelivery() {
        DiscordRest rest=new DiscordRest(HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1/"),"not-a-live-token");
        GatewayShardConfig assignment=GatewayShardConfig.parse("4","0,2");
        GatewayEventCache.Config cache=new GatewayEventCache.Config(
            GatewayEventCache.Mode.METADATA,10,300);
        try(DiscordShardManager mgr=new DiscordShardManager(rest,513,assignment,cache)) {
            mgr.prepare(new GatewayBotInfo(GATEWAY,4,1000,1000,60_000,2));
            assertFalse(mgr.isReady());
            mgr.shard(0).receive("{\"op\":0,\"s\":1,\"t\":\"GUILD_CREATE\","
                +"\"d\":{\"id\":\"111\",\"name\":\"one\"}}",null);
            mgr.shard(2).receive("{\"op\":0,\"s\":2,\"t\":\"GUILD_CREATE\","
                +"\"d\":{\"id\":\"222\",\"name\":\"two\"}}",null);
            assertTrue(mgr.cacheGuild("111").contains("one"));
            assertTrue(mgr.cacheGuild("222").contains("two"));
            assertFalse(mgr.shard(0).cache().getGuild("222").contains("two"),
                "Shard caches must not share references");
            Map<?,?> event0=(Map<?,?>)Json.parse(mgr.poll(0));
            Map<?,?> event2=(Map<?,?>)Json.parse(mgr.poll(0));
            assertEquals(0,event0.get("shard_id"));
            assertEquals(2,event2.get("shard_id"));
            assertEquals(2,((Number)((Map<?,?>)Json.parse(mgr.cacheStats())).get("entries")).intValue());
            mgr.shard(0).receive("{\"op\":0,\"s\":3,\"t\":\"READY\",\"d\":{\"session_id\":\"0\"}}",null);
            assertFalse(mgr.isReady(),"All assigned shards must be READY");
            mgr.shard(2).receive("{\"op\":0,\"s\":4,\"t\":\"RESUMED\",\"d\":{}}",null);
            assertTrue(mgr.isReady());
            Map<?,?> status=(Map<?,?>)Json.parse(mgr.status());
            assertEquals(4,status.get("total_shards"));
            assertEquals(2,status.get("local_shard_count"));
            assertEquals(true,status.get("all_ready"));
            assertEquals(2,((List<?>)status.get("shards")).size());
            assertEquals(1,mgr.cacheClear());
        }
    }
}
