package org.owcord.runtime;

import java.util.function.LongSupplier;

/**
 * Process-local Identify coordination. Per Discord: identify key is
 * shardId % max_concurrency; one request per key per five seconds.
 * RESUME never reserves an Identify slot.
 */
final class GatewayIdentifyCoordinator {
    static final long BUCKET_INTERVAL_MS=5_100L;
    private final int concurrency,total;
    private final long resetAfter;
    private final LongSupplier clock;
    private final long[] nextBucket;
    private int remaining;
    private long resetAt;

    GatewayIdentifyCoordinator(int concurrency,int total,int remaining,long resetAfter) {
        this(concurrency,total,remaining,resetAfter,System::currentTimeMillis);
    }
    GatewayIdentifyCoordinator(int concurrency,int total,int remaining,long resetAfter,
                               LongSupplier clock) {
        if (concurrency<1 || concurrency>4096 || total<1 ||
            remaining<0 || remaining>total || resetAfter<1000 ||
            resetAfter>86_400_000L || clock==null)
            throw new IllegalArgumentException("Invalid Discord Identify limits");
        this.concurrency=concurrency;
        this.total=total;
        this.remaining=remaining;
        this.resetAfter=resetAfter;
        this.clock=clock;
        this.nextBucket=new long[concurrency];
        this.resetAt=clock.getAsLong()+resetAfter;
    }
    synchronized long reserveDelayMillis(int shardId,int shardCount) {
        if (shardCount<1 || shardId<0 || shardId>=shardCount)
            throw new IllegalArgumentException("Invalid Identify shard");
        long now=clock.getAsLong();
        int bucket=shardId%concurrency;
        long scheduled=Math.max(now,nextBucket[bucket]);
        if (scheduled>=resetAt) {
            remaining=total;
            resetAt=scheduled+resetAfter;
        }
        if (remaining==0) {
            scheduled=Math.max(scheduled,resetAt);
            remaining=total;
            resetAt=scheduled+resetAfter;
        }
        long delay=Math.max(0,scheduled-now);
        if (delay>20*60_000L)
            throw new IllegalStateException("Discord Identify reset exceeds safe wait");
        remaining--;
        nextBucket[bucket]=scheduled+BUCKET_INTERVAL_MS;
        return delay;
    }
    synchronized int remaining(){return remaining;}
    int concurrency(){return concurrency;}
}
