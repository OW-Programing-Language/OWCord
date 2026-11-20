# OWCord v0.12 — Sharded Gateway & Identify coordination

OWCord can run **multiple Discord Gateway v10 shards inside one interpreter**,
each with independent sequence, session ID, resume endpoint, heartbeat, reconnect,
and bounded metadata cache. One process-local coordinator admits Identify
requests according to Discord's `shard_id % max_concurrency` bucket rules
with a conservative 5.1-second interval.

## Configuration

```bash
# Defaults: one shard, ID 0, unchanged for existing users.
OWCORD_SHARD_COUNT=1
OWCORD_SHARD_IDS=0

# Example: all four shards in ONE process. Discord may recommend a different count.
OWCORD_SHARD_COUNT=4
OWCORD_SHARD_IDS=0,1,2,3

# Combined with v0.11 named intents and optional metadata cache.
OWCORD_GATEWAY_INTENTS=GUILDS,GUILD_MESSAGES
OWCORD_CACHE_MODE=metadata
OWCORD_CACHE_MAX_ENTRIES=256
OWCORD_CACHE_TTL_SECONDS=300
```

`OWCORD_SHARD_COUNT` must be 1–4096; the process supports at most 64 assigned
IDs. All local IDs must be unique and in `[0, shard_count)`. If there are 64
or fewer total shards and no `SHARD_IDS` is supplied, OWCord starts all of
them locally. When total is above 64, **explicit** local assignments are
required.

**Do not independently start multiple processes with the same bot token**
without a distributed Identify admission service. This PR coordinates
only shards **inside one process**, not across containers, Kubernetes pods,
hosts, or separately launched OW interpreters. Disjoint IDs alone do not
satisfy the shared Discord Identify max-concurrency limit.

The existing bot startup API remains unchanged:

```ow
set app: new App()
# ... same DISCORD_GATEWAY and DISCORD_REST flOW registrations ...
set bot: new OWCordClient(gateway, rest)
set config: bot.shard_config()
print: "Total shards: " + str(config.total_shards)
set health: bot.shard_status()
print: "All assigned shards READY: " + str(health.all_ready)
```

Shard health includes `local_shard_ids`, `total_shards`, `all_ready`,
and per-shard `ready`, `running`, and `reconnect_failures`. Once started,
it also reports Discord's recommended count, `max_concurrency`, and remaining
process-local session-start reservation budget. Do not confuse this with a
fresh server-wide budget read after other processes have started.

Each Gateway dispatch envelope now includes numeric `shard_id`. The typed
OW `EventParser` preserves it as `event.shard_id`; older standalone/mock
events default to shard 0. `bot.next(timeout_ms)` transparently merges the
bounded queues from assigned shards using fair round-robin polling.

## Identify / Resume correctness

- OWCord queries **GET /gateway/bot** once per startup. Its response supplies
  the trusted WSS URL, recommended shard count, remaining session starts,
  reset interval and Identify `max_concurrency`.
- Shard Identify is sent as opcode 2 with `shard: [id, count]`, the existing
  intents, bot token, and connection properties.
- Shards with identical `id % max_concurrency` cannot Identify within the
  same 5.1-second reservation window. Independent buckets may start together.
- When the reported start budget is smaller than the local shard count,
  startup refuses rather than knowingly exhausting Discord's budget.
  Additional re-Identifies after invalid/nonresumable sessions are gated again.
- A valid session resumes with opcode 6 immediately (no new Identify permit);
  a nonresumable invalid session or close 4007/4009 discards that shard's
  previous session/partial cache. Fatal close codes still stop the shard.
- Shards maintain separate heartbeat and resume state, and the SDK stops all
  shard timers/sockets on disconnect.
- The cache remains **partial, in-memory and optional**. Each shard has its
  own bounded LRU+TTL cache; the OW cache reader searches local shards and
  cache statistics aggregate local entry counts. Cached state is never an
  authorization source.

## Safety and remaining work

A distributed Identify coordinator (for example an atomic lock/lease backed
by Redis) and token-wide session-start accounting across processes are out of
scope. This milestone deliberately avoids claiming that cross-process
horizontal sharding is production-ready. Discord can require additional
shard counts for large guild populations; obey its recommendations and errors.

CI exercises two+ shard event flow with mocked Gateway messages, independent
READY/RESUMED, fair delivery, bucket spacing, budget resets and malformed
configurations. The real-Discord smoke uses **one shard only** to validate
backward compatibility without multiplying live sessions or making guild
modifications. A genuine multi-shard live load test remains separate and
requires a sufficiently sized, appropriately configured bot.

References: [Discord Gateway & Sharding](https://docs.discord.com/developers/events/gateway)
and [Discord Gateway Events](https://docs.discord.com/developers/events/gateway-events).
