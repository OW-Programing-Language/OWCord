# OWCord — a modular Discord bot SDK for OW

OWCord is an experimental Discord SDK using **OW source modules**, **flOW typed lifecycle/contracts**, and an optional **Java 17 RuntimeExtension** for networking.

**v0.1 foundation:** Discord Gateway v10 WebSocket, Identify/Resume/Heartbeat, bounded event polling, REST guild slash-command registration, interaction replies and message sends. This is an early milestone, **not production-ready** or a complete discord.js/JDA replacement.

## Setup

1. Install JDK 17+, Maven and [OW](https://github.com/OW-Programing-Language/ow-lang).
2. Clone OW to a sibling directory named `ow-lang`.
3. Build OW and the optional runtime provider:

~~~sh
mvn -f ../ow-lang/pom.xml -DskipTests install
mvn -f runtime-bridge/pom.xml verify
ow deps install
ow run tests/headless_contracts.ow
~~~

4. Create a Discord application and bot; invite it to a development server. Configure these **environment variables** (never commit tokens):

~~~text
DISCORD_BOT_TOKEN=...
DISCORD_APPLICATION_ID=...
DISCORD_GUILD_ID=...
OWCORD_GATEWAY_INTENTS=1
~~~

5. Run the example through the OW runtime with the provider on the classpath:

~~~sh
java -cp "../ow-lang/target/OwIDE.jar:runtime-bridge/target/owcord-runtime-0.1.0-SNAPSHOT.jar" dev.oreo.Main run examples/ping_bot/main.ow
~~~

On Windows replace the classpath colon with a semicolon. The example registers a development guild `/ping` command and replies **Pong from OW!**. Do **not** configure an HTTP interactions endpoint for the same bot when using Gateway-delivered interactions.

## Layout

~~~text
dependencies.yml / dependencies.lock.yml   pinned flOW version
src/gateway/api, src/rest/api               public typed module signatures
src/gateway/infra, src/rest/infra           OW networking adapters
runtime-bridge/                             Java HttpClient + WebSocket provider
examples/ping_bot/                          real OW bot
tests/headless_contracts.ow                 offline flOW smoke test
.github/workflows/ci.yml                    Java + OW verification
~~~

## Limits and security

This first version handles one session per interpreter, a maximum 1,024 queued events, and immediate slash-command replies. Some reconnect/resume situations are handled, but full connection lifecycle, bucket-aware rate limits, sharding, structured errors, full interaction lifecycle coverage and queue durability are still future work. Bounded 429 backoff and response-header-based rate-limit tracking are supported in v0.4; see the reliability guide. Discord may disconnect sessions with invalid intents. Gateway bot token remains in environment variables, and automatic mentions are disabled in outgoing messages.

Read [Discord's Gateway API](https://docs.discord.com/developers/events/gateway) and [interaction guide](https://docs.discord.com/developers/interactions/receiving-and-responding).

Next milestones: permissions, stronger Gateway reconnect behavior, distributed REST rate-limit coordination, sharding, and optional Night HTTP interactions. OW-native command routing and event normalization are introduced in v0.2.

License: MIT.

## Live Discord tests (real token in GitHub Secrets)

The live smoke workflow is `.github/workflows/live-discord-smoke.yml`. It runs **automatically on relevant pushes to the trusted `main` branch**, plus on manually selected `workflow_dispatch` runs. It does not supply the bot token to ordinary pull-request workflows; pull requests run Java/OW tests without secrets.

The bot token must be stored only as repository Actions secret `DISCORD_BOT_TOKEN`. GitHub Actions pre-fills the development application, guild and test-channel IDs; credentials are read from the environment, never committed or printed.

The live v0.2 scenario in `examples/live_smoke/main.ow`:

1. Boots Gateway and REST services through flOW and creates an OW-native `OWCordClient`.
2. Checks command registration and duplicate handling in OW, then connects to the **real** Discord Gateway and parses READY.
3. Creates or updates real guild `/ping` and `/hello` commands through the SDK.
4. Sends one uniquely marked message to the development channel and confirms the matching Gateway `MESSAGE_CREATE` using the OW `EventParser`, including nonempty message and author IDs and matching channel ID.
5. Closes the Gateway and flOW services.

**Evidence:** [successful v0.2 live Discord run](https://github.com/OW-Programing-Language/OWCord/actions/runs/44478263864) plus [successful Java/OW CI](https://github.com/OW-Programing-Language/OWCord/actions/runs/44478269511).

**Coverage boundary:** Bots cannot generate their own genuine user slash-command interactions through the Discord API. This automation verifies real command *registration*, but not a human invoking a command and seeing an interaction response. Ephemeral responses, deferral and follow-ups have offline unit/mocked transport coverage; they still need a human-initiated interaction integration check. Do not describe them as live-tested until that check is performed.

Future OWCord features should add an offline test and extend this live scenario for any behavior verifiable using the bot token. Keep real Discord tests on a dedicated development guild and channel because each run posts one audit message. Do not expose secrets to forks, PR comments, logs, or code.


## v0.2: commands, normalized events, and deferred replies

See [commands guide](docs/COMMANDS.md) and [runnable two-command example](examples/command_router/main.ow).

- Pure OW `CommandRouter` registers up to 32 named slash commands and resolves deterministic handler labels.
- Pure OW `EventParser` normalizes `READY`, `MESSAGE_CREATE`, `INTERACTION_CREATE` and unrecognized events into stable records.
- `OWCordClient` combines the two with the flOW-managed Gateway/REST services, plus `bot.next(timeout_ms)`, `bot.reply(event, text)`, `bot.reply_private(event, text)`, `bot.defer(event, 0/1)`, and `bot.followup(event, text)`.
- Java REST provider supports ephemeral interaction responses, deferred acknowledgement, and public/private follow-ups, with content validation and no automatic mentions.
- Run offline OW tests: `ow test tests/CommandRouterTest.ow`; Java transport tests run through `mvn -f runtime-bridge/pom.xml verify`.

This API dispatches **handler labels**, not arbitrary function callbacks. The example explicitly routes handler names into OW functions/logic. Live tests run on trusted main-branch changes and manual dispatches, but not on ordinary pull requests.

## v0.3 — Rich Discord UI (PR #3)

OWCord now provides pure-OW builders for embedded messages, clickable buttons,
single-choice string selects and modern Discord modal text inputs via
`src/components/DiscordUI.ow`. The OW `OWCordClient` adds
`send_rich`, `reply_rich`, `reply_rich_private`, `update_message`,
`show_modal` and `modal_value`. The Java bridge validates every supported
payload and disables mass-mention parsing.

Start with [the components guide](docs/COMPONENTS.md) and
[real bot demo](examples/components_demo/main.ow). Java transport tests,
OWUnit tests and the CI type check cover the initial implementation.
Real clicks and modal submissions need a Discord-user interaction test
before claiming complete live validation; the existing secret is held in
GitHub Actions and not in this repository's source.

## v0.4 — reliability and rate-limit handling (PR #4)

REST now honors Discord response headers for per-route buckets and global 429s, applies bounded retries with interaction-aware wait budgets, and avoids replaying ambiguous failed POST requests. Gateway reconnection now resets invalid sequence/session state, handles non-retriable close codes and restores readiness after Resume.

Read the [reliability guide](docs/RELIABILITY.md). Java/JUnit local-server tests verify the new behavior without consuming real Discord API requests.
