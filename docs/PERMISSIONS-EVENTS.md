# OWCord v0.5 — permission-aware commands and normalized Gateway events

## Discord permissions are decimal strings

Discord serializes permission bitfields as decimal strings. OW's current numeric runtime cannot reliably represent every high-order permission bit, so the SDK intentionally treats permission values as **strings** and evaluates masks in the Java bridge with unsigned 64-bit `BigInteger`.

To register a public and a restricted command:

```ow
call: bot.command("ping", "Public ping", "ping_handler")
call: bot.command_permissions("modcheck", "Moderator-only", "mod_handler", "8192")
call: bot.sync_dev_commands()
```

The `8192` mask is Discord's `MANAGE_MESSAGES` flag. Some other examples: `8` Administrator, `32` Manage Guild, and `0` disabled by default (guild administrators may override command access). These are **decimal strings**, not floating-point numeric literals.

`command_permissions` adds a command to the OW `CommandRouter`; on sync, it POSTs the guild command with `default_member_permissions` set to the validated decimal string. Existing `command(...)` calls continue working unchanged.

On each interaction, `EventParser` exposes `event.member_permissions` and `event.app_permissions` as received. `bot.member_can(event, "8192")` returns integer 1 when the member mask includes the requested flag or Administrator, and 0 otherwise. **A missing member mask fails closed.** Do not treat a client-facing default command restriction as a complete authorization policy: always check permissions again before privileged actions, validate resource scope and relevant guild permissions, and don't trust arbitrary user input.

**Limitations:** This provides bitmask-based command defaults and a simple member-permissions check. It does not compute all channel permission overwrites, role hierarchies, custom command permission overrides, OAuth authorization or audit logging. If you need those features, perform server-side authorization with suitable Discord context.

## OW Gateway event normalization

The SDK's `bot.next(timeout_ms)` now maps additional event types to a stable `event.kind` and `event.action`:

| Gateway event | `kind` | `action` |
| --- | --- | --- |
| `MESSAGE_CREATE / UPDATE / DELETE` | `message` | `create / update / delete` |
| `GUILD_MEMBER_ADD / UPDATE / REMOVE` | `member` | `add / update / remove` |
| `CHANNEL_CREATE / UPDATE / DELETE` | `channel` | `create / update / delete` |
| `GUILD_CREATE / UPDATE / DELETE` | `guild` | `create / update / delete` |
| `READY / RESUMED` | `ready` | `"" / resumed` |

`MESSAGE_UPDATE` payloads can be partial. The normalizer preserves missing data as empty fields instead of fabricating a message. Unrecognized events retain `kind == "other"` and their original `event.name`; the raw `event.payload` is available.

Receiving member events may require the **GUILD_MEMBERS privileged intent** and corresponding Discord developer portal configuration. Do not request privileged intents unnecessarily. The default OWCord development bot uses intents 513 (guilds and guild messages), and therefore cannot be expected to receive member-join/leave events without configuration changes.

## Reconnect resilience

The Gateway now uses bounded exponential backoff with jitter, capped at 60 seconds. `READY` and `RESUMED` reset the backoff counter. Discord close code 4008 delays reconnection by at least 10 seconds; fatal errors stop reconnecting. Oversized text WebSocket messages are rejected before growing beyond 1 MiB in memory. Stale listener callbacks are ignored when a newer socket exists.

This is an in-process single-session Gateway. It does not yet provide sharding, distributed identify concurrency, durable queue delivery or a complete metrics surface.

## Verification

- Offline Java tests: full unsigned masks, admin overrides, invalid defaults, restricted REST registration payloads, reconnect jitter and backoff bounds.
- OWUnit tests: permission-routing facade, lifecycle event categorization and missing-data handling.
- Existing real Discord smoke test: login, Gateway READY and message round-trip, without triggering privileged member events or disruption scenarios.

References: [Discord application commands](https://docs.discord.com/developers/interactions/application-commands), [permissions](https://docs.discord.com/developers/topics/permissions) and [Gateway](https://docs.discord.com/developers/events/gateway).
