# OWCord v0.2 command and event layer

The new `OWCordClient` facade registers commands, decodes Gateway envelopes, routes by handler labels, and replies through the existing flOW-managed REST and Gateway modules. The command registry is **OW source code**, not a Java command framework.

## How to use

See [examples/command_router/main.ow](../examples/command_router/main.ow).

1. Boot the existing typed `DISCORD_GATEWAY` and `DISCORD_REST` modules with flOW.
2. Create `new OWCordClient(gateway, rest)`.
3. Register commands using `bot.command("ping", "description", "ping_handler")`. Duplicate registrations return `0`; order is preserved. At most 32 commands are supported in the first routing layer.
4. Call `bot.sync_dev_commands()` and then `bot.next(250)` inside the event loop.
5. Match `event.handler` and run your OW logic. OW currently does **not** have JavaScript-like user-provided async function callback references; labels let OW code remain in charge and avoid pretending these callbacks exist.

## Normalized event fields

Every event has `kind`, `name`, `handler`, `id`, `token`, `guild_id`, `channel_id`, `user_id`, `custom_id`, `content`, `options`, and `payload`. `kind` is `none`, `ready`, `error`, `message`, `command`, `component`, `interaction` or `other`.

`event.payload` contains the unmodified Gateway event `d` field. `bot.option(event, "target", "default")` finds a simple, top-level slash-command option. Nested subcommands/options are not yet normalized.

The `handler` field is empty for unknown commands; these are not automatically acknowledged, and the application must choose whether to ignore/respond.

## Replies

- `bot.reply(event, "Hello")`: immediate public channel-message response, Discord interaction callback type 4.
- `bot.reply_private(event, "Hello")`: immediate ephemeral response (message flags 64).
- `bot.defer(event, 0)`: deferred public interaction response (callback type 5). `bot.defer(event, 1)` defers ephemerally.
- `bot.followup(event, "Done")`: webhook-style follow-up using the application ID and interaction token after acknowledgement. `bot.followup_private(event, "Done")` requests ephemeral delivery.
- `bot.send_message(channel, "text")`: regular channel message.

**Important:** Discord expects the initial interaction acknowledgement within 3 seconds. Call `defer` early when processing may be slow. The initial ephemeral/public state of a deferred reply affects follow-up visibility; Discord may preserve the original ephemeral state when using the first follow-up.

Security: REST routes validate snowflake IDs and tokens, outgoing content disables automatic mentions, and tokens remain in environment secrets. Do not log `event.token`, and do not cache interaction payloads indefinitely. A user calling a command on Discord is still a live integration scenario and is not covered by offline OW tests.

## Limits / next

- A handler label is not a JavaScript callback, and no automatic background event loop is introduced.
- v0.2 routing supports chat-input commands and records component interactions, but does not yet handle subcommand graphs, autocomplete, modal submissions, or component acknowledgement.
- No full route-aware REST rate-limit scheduler, durable delivery, or sharding.
