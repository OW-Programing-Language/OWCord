package org.owcord.runtime;

import dev.oreo.Json;
import java.net.URI;
import java.util.Map;

/** Validated discovery response from Discord's GET /gateway/bot. */
record GatewayBotInfo(String url,int recommendedShards,int totalStarts,
                      int remainingStarts,long resetAfterMs,int maxConcurrency) {
    GatewayBotInfo {
        if (recommendedShards<1 || recommendedShards>4096 || totalStarts<1 ||
            remainingStarts<0 || remainingStarts>totalStarts || resetAfterMs<1000 ||
            resetAfterMs>86_400_000L || maxConcurrency<1 || maxConcurrency>4096)
            throw new IllegalStateException("Invalid Gateway session limits");
        URI uri=URI.create(url);
        String host=uri.getHost();
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || host==null ||
            !(host.equals("discord.gg") || host.endsWith(".discord.gg") ||
              host.equals("discord.com") || host.endsWith(".discord.com")))
            throw new IllegalStateException("Untrusted Gateway URL");
    }
    static GatewayBotInfo parse(String json) {
        Object parsed=Json.parse(json);
        if (!(parsed instanceof Map<?,?> map) ||
            !(map.get("session_start_limit") instanceof Map<?,?> limit) ||
            !(map.get("url") instanceof String url))
            throw new IllegalStateException("Missing Gateway discovery fields");
        return new GatewayBotInfo(url,number(map,"shards"),number(limit,"total"),
            number(limit,"remaining"),number(limit,"reset_after"),
            number(limit,"max_concurrency"));
    }
    private static int number(Map<?,?> m,String key) {
        Object raw=m.get(key);
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) ||
            n.doubleValue()!=Math.rint(n.doubleValue()) ||
            n.doubleValue()<0 || n.doubleValue()>Integer.MAX_VALUE)
            throw new IllegalStateException("Invalid Gateway field: "+key);
        return n.intValue();
    }
    GatewayIdentifyCoordinator coordinator() {
        return new GatewayIdentifyCoordinator(maxConcurrency,totalStarts,
            remainingStarts,resetAfterMs);
    }
}
