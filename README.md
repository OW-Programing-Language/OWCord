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

This first version handles one session per interpreter, a maximum 1,024 queued events, and immediate slash-command replies. Some reconnect/resume situations are handled, but full connection lifecycle, bucket-aware rate limits, sharding, structured errors, interaction deferral and queue durability are still future work. A short 429 retry is supported. Discord may disconnect sessions with invalid intents. Gateway bot token remains in environment variables, and automatic mentions are disabled in outgoing messages.

Read [Discord's Gateway API](https://docs.discord.com/developers/events/gateway) and [interaction guide](https://docs.discord.com/developers/interactions/receiving-and-responding).

Next milestones: typed command router, strongly typed events, embeds/buttons/modals, permissions, better reconnect and rate limits, sharding, and optional Night HTTP interactions.

License: MIT.
