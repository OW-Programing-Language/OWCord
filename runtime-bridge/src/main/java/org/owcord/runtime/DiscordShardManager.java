package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs one Gateway per assigned shard in one interpreter/process. Each shard
 * independently maintains heartbeat, sequence, session ID, cache and reconnect.
 * Identify slots come from ONE shared process-local coordinator.
 */
final class DiscordShardManager implements AutoCloseable {
    private static final String NONE = "{\"type\":\"NONE\",\"data\":{}}";
    private final DiscordRest rest;
    private final int intents;
    private final GatewayShardConfig assignment;
    private final GatewayEventCache.Config cacheConfig;
    private final Map<Integer, DiscordGateway> shards = new LinkedHashMap<>();
    private GatewayBotInfo discovery;
    private GatewayIdentifyCoordinator coordinator;
    private int cursor;
    private boolean started;

    DiscordShardManager(DiscordRest rest,int intents,GatewayShardConfig assignment,
                        GatewayEventCache.Config cacheConfig) {
        if (rest==null || assignment==null || cacheConfig==null)
            throw new IllegalArgumentException("Shard manager requires transport and config");
        GatewayIntents.parse(Integer.toString(intents));
        this.rest=rest;
        this.intents=intents;
        this.assignment=assignment;
        this.cacheConfig=cacheConfig;
    }
    int requestedIntents() { return intents; }
    synchronized int connect() {
        if (started) return 1;
        GatewayBotInfo info=rest.gatewayBotInfo();
        if (info.remainingStarts()<assignment.localIds().size())
            throw new IllegalStateException("Insufficient Discord Identify session-start budget");
        prepare(info);
        try {
            started=true;
            for (DiscordGateway shard: shards.values()) shard.connect(info.url());
            return 1;
        } catch(RuntimeException error) {
            close();
            throw error;
        }
    }

    /** Reused in deterministic tests; production always fetches /gateway/bot. */
    synchronized void prepare(GatewayBotInfo info) {
        if (started || !shards.isEmpty())
            throw new IllegalStateException("Cannot replace active shard assignment");
        this.discovery=info;
        this.coordinator=info.coordinator();
        for(int id:assignment.localIds()) {
            shards.put(id,new DiscordGateway(rest,intents,cacheConfig,
                id,assignment.total(),coordinator));
        }
    }

    synchronized DiscordGateway shard(int id) { return shards.get(id); }
    synchronized boolean isReady() {
        return !shards.isEmpty() && shards.values().stream().allMatch(DiscordGateway::isReady);
    }
    String poll(int timeoutMs) {
        if (timeoutMs<0 || timeoutMs>30000)
            throw new IllegalArgumentException("Polling timeout must be 0-30000ms");
        List<DiscordGateway> ordered;
        synchronized(this) {
            if (shards.isEmpty()) throw new IllegalStateException("Gateway shards not started");
            ordered=new ArrayList<>(shards.values());
        }
        final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            for(int j=0;j<ordered.size();j++) {
                int i=(cursor+j)%ordered.size();
                String next=ordered.get(i).poll(0);
                if (!NONE.equals(next)) {
                    cursor=(i+1)%ordered.size();
                    return next;
                }
            }
            long left=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
            if (timeoutMs==0 || left<=0) return NONE;
            try { Thread.sleep(Math.min(20,left)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); return NONE; }
        }
    }
    private synchronized String lookup(String kind,String id,String user) {
        for(DiscordGateway shard:shards.values()) {
            String result=switch(kind) {
                case "guild" -> shard.cache().getGuild(id);
                case "channel" -> shard.cache().getChannel(id);
                case "member" -> shard.cache().getMember(id,user);
                default -> throw new IllegalArgumentException("Invalid cache kind");
            };
            Object obj=Json.parse(result);
            if(obj instanceof Map<?,?> m && Boolean.TRUE.equals(m.get("found")))
                return result;
        }
        return "{\"found\":false}";
    }
    String cacheGuild(String id) { return lookup("guild",id,""); }
    String cacheChannel(String id) { return lookup("channel",id,""); }
    String cacheMember(String guild,String user) { return lookup("member",guild,user); }

    synchronized int cacheClear() {
        for(DiscordGateway shard:shards.values()) shard.cache().clear();
        return 1;
    }
    synchronized String cacheStats() {
        if (shards.isEmpty()) throw new IllegalStateException("Gateway shards not started");
        int totalEntries=0;
        Map<String,Object> result=new LinkedHashMap<>();
        for(DiscordGateway shard:shards.values()) {
            Map<?,?> stats=(Map<?,?>)Json.parse(shard.cache().stats());
            for(var entry:stats.entrySet()) result.put(String.valueOf(entry.getKey()),entry.getValue());
            totalEntries+=((Number)stats.get("entries")).intValue();
        }
        result.put("entries",totalEntries);
        result.put("local_shard_count",shards.size());
        result.put("max_entries_per_shard",cacheConfig.maxEntries());
        return Json.write(result);
    }

    synchronized String status() {
        List<Object> local=new ArrayList<>();
        for(DiscordGateway shard:shards.values()) local.add(Json.parse(shard.shardStatus()));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("total_shards",assignment.total());
        result.put("local_shard_ids",assignment.localIds());
        result.put("local_shard_count",assignment.localIds().size());
        result.put("all_ready",isReady());
        result.put("shards",local);
        result.put("coordinator_scope","process");
        if (discovery!=null) {
            result.put("recommended_shards",discovery.recommendedShards());
            result.put("max_concurrency",discovery.maxConcurrency());
            result.put("remaining_local_identifies",coordinator.remaining());
        }
        return Json.write(result);
    }
    @Override public synchronized void close() {
        for(DiscordGateway shard:shards.values()) shard.close();
        shards.clear();
        started=false;
        discovery=null;
        coordinator=null;
    }
}
