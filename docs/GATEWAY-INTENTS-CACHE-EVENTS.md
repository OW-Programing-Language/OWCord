# OWCord v0.11 — Gateway intents, bounded cache and typed events

The core SDK now supports validated Gateway intent profiles, an **opt-in**
metadata cache, and typed partial-event normalization. The Guild/Channel/Member
REST APIs remain the authoritative source for up-to-date data.

## Gateway intents

Configure the bot before starting flOW / connecting to the Gateway:

```bash
# Existing numeric configuration continues to work
OWCORD_GATEWAY_INTENTS=513

# New: equivalent, readable named configuration
OWCORD_GATEWAY_INTENTS=GUILDS,GUILD_MESSAGES
```

`GUILDS,GUILD_MESSAGES` equals `513`. The parser rejects unknown names,
undefined bits, duplicate intent names, negative numbers and numeric overflows.
It never silently requests additional intents.

Read the effective configuration in OW:

```ow
set intents: bot.gateway_intents()
print: "Intent mask: " + str(intents.mask)
print: "Portal approval needed: " + str(intents.portal_configuration_required)
```

`GUILD_MEMBERS`, `GUILD_PRESENCES`, and `MESSAGE_CONTENT` are privileged
intents. OWCord describes their presence, **but cannot grant them**. Enable
approved privileged intents in the Discord Developer Portal as appropriate.
Discord may reject a session that requests an unapproved privileged intent.

The default remains `GUILDS` (bitmask `1`) when unset. Intent selection is
fixed per Gateway session; changing the environment requires restarting the
bot. A cache mode does not automatically request additional privileged intents.

## Opt-in metadata cache

```bash
OWCORD_CACHE_MODE=metadata        # off (default) | metadata | members
OWCORD_CACHE_MAX_ENTRIES=256      # 1–4096
OWCORD_CACHE_TTL_SECONDS=300      # 1–3600
```

- `off`: no Gateway data is cached (default)
- `metadata`: bounded guild name and channel/thread ID/name/topic/type/parent
- `members`: metadata plus member **user ID and role IDs only**

No message content, DM content, token, raw user profiles, email addresses,
nicknames, presence or message attachments enter the cache.

```ow
set info: bot.gateway_cache_stats()
print: "Cache mode: " + info.mode
set guild: bot.cached_guild(guild_id)
if: guild.found == true
  print: guild.data.name
# Every hit is explicitly marked partial; it is NOT a complete Guild/Channel.
set channel: bot.cached_channel(channel_id)
set member: bot.cached_member(guild_id, user_id)
call: bot.clear_gateway_cache()
```

**Important:** Cache hits are Gateway-derived, partial snapshots, explicitly
tagged `partial: true`. Do not use them as authorization decisions or a substitute
for `bot.effective_permissions()` and fresh Discord REST reads. Missing fields
are omitted, not fabricated. `found: false` means absent, expired, evicted or
disabled; it does not establish that a guild/channel/member does not exist.

The cache merges only fields actually present on partial updates. An explicit
empty member role array replaces the previous roles; omitted roles do not.
Deletes invalidate the relevant key. Guild deletion purges all associated
channel/member metadata. Role changes invalidate a guild's cached member-role
associations. A fresh `READY` session clears the cache to avoid using state
that might be stale after reconnect; a normal `RESUMED` keeps continuous state.
An LRU limit and monotonic TTL bound in-memory usage. The cache is per bot
interpreter, not a shared distributed store.

## Typed Gateway events

The public `EventParser` and `OWCordClient.next()` now return structured
objects for more event families. `event.payload` retains the original Gateway
event data.

| Gateway event | `kind` | `action` | Important fields |
| --- | --- | --- | --- |
| `GUILD_ROLE_CREATE/UPDATE/DELETE` | `role` | `create/update/delete` | `guild_id`, `role_id`, `role` |
| `THREAD_CREATE/UPDATE/DELETE` | `thread` | `create/update/delete` | `thread_id`, `guild_id`, `channel_id` (parent) |
| `MESSAGE_REACTION_ADD/REMOVE` | `reaction` | `add/remove` | `message_id`, `channel_id`, `user_id`, `emoji` |
| `MESSAGE_REACTION_REMOVE_ALL/REMOVE_EMOJI` | `reaction` | `remove_all/remove_emoji` | `message_id`, `emoji` if present |
| `MESSAGE_DELETE_BULK` | `message_bulk` | `delete` | `channel_id`, full `payload.ids` |

`MESSAGE_UPDATE`, `CHANNEL_UPDATE`, `GUILD_UPDATE`,
`GUILD_MEMBER_UPDATE`, `THREAD_UPDATE` and `GUILD_ROLE_UPDATE` report
`partial: true`. `MESSAGE_UPDATE.content_present` distinguishes a missing
`content` field from an explicitly empty string—especially important when
MESSAGE_CONTENT privileged intent is disabled.

Unknown Gateway types still surface as `kind: other` and are not discarded.

## Testing and operational cautions

- Java tests cover named/bitmask parsing, privileged-intent reporting, LRU
  eviction, cache TTL, partial merges, no sensitive raw payload retention,
  deletion, hierarchy invalidation and session resets.
- OWUnit tests cover typed event shapes and the client/cache API;
  flOW checks enforce the native Gateway module contract.
- The live smoke verifies the configured named intents, opt-in cache settings,
  read-only cache introspection, existing Gateway event delivery, and its
  prior safe Discord REST/message lifecycle scenarios. It does not enable
  privileged intents, monitor members or modify server settings.
- For a large distributed bot, future PRs must address shard-specific caches,
  gap recovery and cross-process consistency. Cache snapshots are never used
  by the v0.9 authorization engine.

Official docs: [Gateway Intents](https://docs.discord.com/developers/events/gateway#gateway-intents)
and [Gateway Events](https://docs.discord.com/developers/events/gateway-events).
