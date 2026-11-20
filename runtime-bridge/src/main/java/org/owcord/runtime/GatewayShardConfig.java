package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, disjoint process-local shard IDs. The coordinator is not distributed. */
record GatewayShardConfig(int total, List<Integer> localIds) {
    GatewayShardConfig {
        if (total < 1 || total > 4096 || localIds == null ||
            localIds.isEmpty() || localIds.size() > 64)
            throw new IllegalArgumentException("Use 1-4096 total and 1-64 local shards");
        Set<Integer> seen = new HashSet<>();
        for (int id : localIds)
            if (id < 0 || id >= total || !seen.add(id))
                throw new IllegalArgumentException("Duplicate/out-of-range shard ID");
        localIds = List.copyOf(localIds);
    }
    static GatewayShardConfig parse(String count, String ids) {
        if (count == null || !count.matches("[0-9]{1,4}"))
            throw new IllegalArgumentException("OWCORD_SHARD_COUNT must be decimal");
        int total = Integer.parseInt(count);
        List<Integer> result = new ArrayList<>();
        if (ids == null || ids.isBlank()) {
            if (total > 64) throw new IllegalArgumentException(
                "OWCORD_SHARD_IDS required if total >64");
            for (int i=0;i<total;i++) result.add(i);
        } else {
            for (String part : ids.split(",",-1)) {
                String v=part.strip();
                if (!v.matches("[0-9]{1,4}"))
                    throw new IllegalArgumentException("Invalid OWCORD_SHARD_IDS");
                result.add(Integer.parseInt(v));
            }
        }
        return new GatewayShardConfig(total,result);
    }
    static GatewayShardConfig fromEnvironment() {
        return parse(System.getenv().getOrDefault("OWCORD_SHARD_COUNT","1"),
            System.getenv().getOrDefault("OWCORD_SHARD_IDS",""));
    }
    String summary() {
        return Json.write(Map.of("total_shards",total,"local_shard_ids",localIds,
            "local_shard_count",localIds.size(),"coordinator_scope","process"));
    }
}
