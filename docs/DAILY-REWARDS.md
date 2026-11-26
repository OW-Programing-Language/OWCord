# OWCord v0.14 — Daily scheduling and reward reservations

Import `src/scheduling/DailyRewards.ow` and construct `new DailyRewards()`. These JVM-backed helpers are independent of Discord transport and need no bot token. Use the same IANA time zone and campaign identifier throughout a campaign.

- `calendar(zone)`: local ISO date, year, month, day, hour, minute and zone.
- `claim(guild_id, user_id, campaign, zone, start_date, end_date, reward)`: reserves one reward per guild/user/campaign/local date during the inclusive date window. Returns `status: claimed` for the winner, `duplicate` with the original immutable record for subsequent requests, or `closed` outside the window. Reward text is stored in the record.
- `lookup(guild_id, user_id, campaign, date)`: returns `found` plus the original record or `missing`.
- `history(guild_id, user_id, campaign, start_date, end_date)`: ordered claim records, up to 366 calendar days.
- `due(guild_id, job, zone, start_date, end_date, local_time)`: poll from an event loop; returns `due` once per local date at or after HH:mm, `already_reserved` thereafter, or `not_due`. On restart later the same day an unreserved occurrence can run; missed previous days are not replayed. DST gaps shift forward and overlaps use the earlier offset.

Set `OWCORD_DATA_DIR` to an application-owned persistent directory (default `.owcord-data`). Mount that directory on a persistent volume in containers and restrict access; it stores Discord IDs and reward text, not tokens. Each claim/job is an immutable JSON file. The runtime writes and forces a temporary file before atomically publishing a hard link with no replacement. Concurrent threads or processes using the same local filesystem and directory cannot both reserve a key. Unsupported filesystems and I/O failures fail closed. Do not edit or delete records while the application is running. Backup the directory; retention, disaster recovery and power-loss directory durability are deployment responsibilities. Do not use network/distributed filesystems unless their atomic-link semantics are verified.

## Delivery boundary

Reservation is not a transaction with Discord or any external economy. Only the winner should initiate a side effect. A crash after reservation but before delivery can leave an undelivered reward or announcement; the SDK intentionally does not blindly repeat an ambiguous external write. For real licences, currencies, roles or purchases, implement an idempotent fulfillment service/outbox keyed by the original guild/user/campaign/date and reconcile incomplete deliveries. Do not pass user-supplied reward values, campaign IDs or time zones without authorization; derive guild/user from a verified interaction. Duplicate results are for private responses to that same user only.

The bundled [Advent example](../examples/advent_calendar/main.ow) uses a virtual collectible stored directly in the claim record, private deferred replies, a button, and a daily public reminder. It defaults to December 1–24, 2026 in Europe/Paris. Configure DISCORD_BOT_TOKEN, DISCORD_APPLICATION_ID, DISCORD_GUILD_ID and DISCORD_TEST_CHANNEL_ID; use a development guild and set OWCORD_DATA_DIR to an ignored/persistent location. It does not award real currency, roles or licences. Launch using the same JVM classpath as the ping bot, replacing the example entry with examples/advent_calendar/main.ow.

Discord REST requests continue through the existing bucket/global limiter. v0.14 removes unused route-only cooldown entries, prunes expired deadlines and correctly handles negative monotonic clock origins. The limiter remains per interpreter, not a distributed limiter.

Verification: Java tests cover date boundaries, restart, duplicate and concurrent reservations, history, scheduler and invalid input; the OW example is included in CI type checks. Real Discord interaction/fulfillment still requires a development-guild test.
