# OWCord v0.10 — Components V2 and Interaction Lifecycle

OWCord v0.10 introduces pure-OW builders and strict Java-side validation for a
**supported subset** of Discord Components V2, along with initial response and
follow-up editing/deletion.

## Components V2 messages

The `IS_COMPONENTS_V2` message flag is `32768`. V2 messages **cannot carry
legacy `content` or `embeds`**. Text is instead represented by type-10
Text Displays. This implementation always writes `allowed_mentions.parse=[]`
and rejects unsupported component types, media schemes and malformed layouts.

```ow
set ui: new DiscordUI()
set panel: ui.v2_container([
  ui.v2_text("## OWCord"),
  ui.v2_separator(true, 1),
  ui.v2_section(
    [ui.v2_text("Open your settings:")],
    ui.button("settings", "Settings", 1)
  )
], 5793266)

set payload: ui.v2_message([panel])
set message_id: bot.v2_create(channel_id, payload)
```

Builder functions: `v2_text(content)`, `v2_separator(divider, spacing)`,
`v2_section(text_displays, accessory)`, `v2_thumbnail(https_url)`,
`v2_gallery(items)`, `v2_media_item(url, description, spoiler)`,
`v2_container(children, accent_color)` and `v2_message(components)`.

The Java validator supports types 1 (legacy-compatible action row), 9 (section),
10 (text), 11 (thumbnail only as section accessory), 12 (media gallery),
14 (separator) and 17 (container); up to 40 total components and a conservative
10-component top-level/container limit. Section accessories support thumbnail or
interactive button. Only HTTPS external media is supported in this PR; multipart
`attachment://` files, arbitrary V2 controls, nested containers, and rich
button types beyond the existing supported subset are deferred.

The V2 builder and validator are **separate** from legacy `ui.message` /
`RichPayload.message`; previously supported embeds, buttons, selects and modals
are not changed.

## Interaction response lifecycle

```ow
# Use a V2 response for an interaction. private=1 sets EPHEMERAL (64) alongside 32768.
call: bot.v2_reply(event, ui.v2_message([ui.v2_text("Hello!")]), 1)

# Type 7 updates the component's original message immediately.
call: bot.v2_update(event, ui.v2_message([ui.v2_text("Updated")]))
# Type 6 acknowledges a component without editing it.
call: bot.defer_component_update(event)

# After deferring a channel-message response, edit the original response:
set updated: bot.edit_original(event, "Done!")
set followup_id: bot.v2_followup(event, ui.v2_message([ui.v2_text("Next step")]), 0)
set edited: bot.edit_followup(event, followup_id, "Revised text")
call: bot.delete_followup(event, followup_id)
call: bot.delete_original(event)
```

These are alternatives depending on an interaction's current state: **do not**
send multiple initial callbacks to the same interaction, and do not use type 6
on slash-command interactions. Discord requires the initial interaction
acknowledgement within approximately three seconds. Webhook interaction tokens
usually expire after 15 minutes; do not persist or log them.

Webhook edits use `PATCH /webhooks/{application}/{token}/messages/@original`
and `PATCH /webhooks/{application}/{token}/messages/{message}`; deletes use
`DELETE` on the same paths. These token-bearing calls do not include the
bot Authorization header. Follow-up V2 sends `POST /webhooks/{application}/{token}`
with `IS_COMPONENTS_V2`, and the SDK returns the created message's ID.

The plain-text edit methods are intended for plain-text messages. Existing V2
messages should be updated with V2-compatible payloads (e.g. type 7 on a
component interaction); converting a V2 message back to legacy content is not
supported in this PR.

## Global command installation and invocation contexts

Discord global application commands can select `integration_types` (0 guild
installation, 1 user installation), and `contexts` (0 guild, 1 bot DM,
2 private channel). `PRIVATE_CHANNEL` requires a user installation.
These fields do not apply to guild-only command registration.

```ow
call: bot.command_global("profile", "Show profile", "profile_handler", [0, 1, 2], [0, 1])
call: bot.sync_dev_commands()
```

Despite the legacy method name, `sync_dev_commands()` now dispatches
**global-scope registered commands** via the global REST endpoint, and existing
guild-scoped commands through guild REST endpoints. Global registration depends
on the application's enabled installation contexts and may take time to propagate.
The live smoke test does **not** register global commands and therefore does not
alter your application's global command settings.

The EventParser also exposes `event.context`, `event.integration_owners`
and `event.locale` for interaction payloads. Always validate the actual guild,
invoking user and integration owners before executing privileged actions.

## Tests and limitations

- Java tests: V2 strict flags/schema, nesting, 40-component bound, HTTPS
  media, callback type 4/7, type-6 acknowledgement and webhook edit/delete.
- OWUnit: pure-OW builders, fake-REST lifecycle and normalized interaction contexts.
- Live Discord: creates one temporary V2 panel, reads it and deletes the
  **same bot-owned message ID**; existing Gateway, REST, interactions and
  message/reaction smoke cases still run.

This is not complete Discord component/API parity. Image uploads, file components,
new modal controls (file upload/radio/checkbox), arbitrary component nesting,
select types, and global command propagation monitoring are future work.

References:
- [Discord Components Reference](https://docs.discord.com/developers/components/reference)
- [Using Message Components](https://docs.discord.com/developers/components/using-message-components)
- [Receiving and Responding to Interactions](https://docs.discord.com/developers/interactions/receiving-and-responding)
- [Application Commands](https://docs.discord.com/developers/interactions/application-commands)
