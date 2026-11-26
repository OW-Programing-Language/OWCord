# OWCord v0.14 — Daily scheduling and reward reservations

Import `src/scheduling/DailyRewards.ow` and construct `new DailyRewards()`. These JVM-backed helpers are independent of Discord transport and need no bot token. Use the same IANA time zone and campaign identifier throughout a campaign.

- `calendar(zone)`: local ISO date, year, month, day, hour, minute and zone.
- `claim(guild_id, user_id, campaign, zone, start_date, end_date, reward)`: reserves one reward per guild/user/campaign/local date during the inclusive date window. Returns `status: claimed` for the winner, `duplicate` with the original immutable record for subsequent requests, or `closed` outside the window. Reward text is stored in the record.
- `lookup(guild_id, user_id, campaign, date)`: returns `found` plus the original record or `missing`.
- `history(guild_id, user_id, campaign, start_date, end_date)`: ordered claim records, up to 366 calendar days.
- `due(guild_id, job, zone, start_date, end_date, local_time)`: poll from an event loop; returns `due` once per local date at or after HH:mm, `already_reserved` thereafter, or `not_due`. On restart later the same day an unreserved occurrence can run; missed previous days are not replayed. DST gaps shift forward and overlaps use the earlier offset.

## Storage configuration (no OW code changes)

Choose a backend using environment variables. SQLite is the default for fresh installations; if the default data directory already has JSON claims/jobs, an unset provider preserves JSON. Explicit selection always wins. Database switches do not migrate records: stop writers, back up, migrate/verify every claim and job before switching an existing campaign, or use a new campaign. Never run one campaign across independent stores.

| OWCORD_STORAGE | Configuration |
| --- | --- |
| sqlite | OWCORD_STORAGE_SQLITE_PATH (default .owcord-data/daily-rewards.db) |
| json | OWCORD_DATA_DIR (default .owcord-data) |
| postgresql | OWCORD_STORAGE_JDBC_URL=jdbc:postgresql://localhost:5432/owcord |
| mysql | OWCORD_STORAGE_JDBC_URL=jdbc:mysql://localhost:3306/owcord |
| mariadb | OWCORD_STORAGE_JDBC_URL=jdbc:mariadb://localhost:3306/owcord |
| mongodb | OWCORD_STORAGE_MONGO_URI=mongodb://localhost:27017 and OWCORD_STORAGE_MONGO_DATABASE=owcord |

SQL server providers also accept OWCORD_STORAGE_USER and OWCORD_STORAGE_PASSWORD. Use a dedicated database, TLS and restrictive credentials in production. The SQL schema is created automatically and uses a unique primary key; MongoDB uses the unique _id and majority writes. MongoDB assumes a collection with default simple/binary collation and no additional unique indexes. All SQL connections use autocommit; the database decides the single winner. SQL operations open a connection per call (no pool yet). Set appropriate connect/read timeouts in connection strings for remote databases. SQLite uses a busy timeout; keep SQLite and JSON on a persistent local volume with verified locking/hard-link semantics. JSON keeps the original atomic immutable-file format.

Drivers are bundled into the JVM bridge jar. Custom providers can implement public DailyRewardStorage and DailyRewardStorageProvider, register the provider in META-INF/services/org.owcord.runtime.DailyRewardStorageProvider on the JVM classpath and select its lowercase name in OWCORD_STORAGE. A custom provider must implement atomic insert-if-absent, durable reads and consistent ordered history. There is no silent fallback on database errors. Only trusted provider jars should be installed.

The database stores Discord IDs, reward text, and job timestamps; not Discord interaction tokens. Protect access, back up data and configure retention. SQL/MongoDB support multiple bot processes sharing the same database, but do not change Discord Gateway/REST coordination limits.

## Delivery boundary

Reservation is not a transaction with Discord or any external economy. Only the winner should initiate a side effect. A crash after reservation but before delivery can leave an undelivered reward or announcement; the SDK intentionally does not blindly repeat an ambiguous external write. For real licences, currencies, roles or purchases, implement an idempotent fulfillment service/outbox keyed by the original guild/user/campaign/date and reconcile incomplete deliveries. Do not pass user-supplied reward values, campaign IDs or time zones without authorization; derive guild/user from a verified interaction. Duplicate results are for private responses to that same user only.

The bundled [Advent example](../examples/advent_calendar/main.ow) uses a virtual collectible stored directly in the claim record, private deferred replies, a button, and a daily public reminder. It defaults to December 1–24, 2026 in Europe/Paris. Configure DISCORD_BOT_TOKEN, DISCORD_APPLICATION_ID, DISCORD_GUILD_ID and DISCORD_TEST_CHANNEL_ID; use a development guild and set OWCORD_DATA_DIR to an ignored/persistent location. It does not award real currency, roles or licences. Launch using the same JVM classpath as the ping bot, replacing the example entry with examples/advent_calendar/main.ow.

Discord REST requests continue through the existing bucket/global limiter. v0.14 removes unused route-only cooldown entries, prunes expired deadlines and correctly handles negative monotonic clock origins. The limiter remains per interpreter, not a distributed limiter.

Verification: Java tests cover date boundaries, restart, duplicate and concurrent reservations, history, scheduler and invalid input; the OW example is included in CI type checks. Real Discord interaction/fulfillment still requires a development-guild test.
