# OWCord v0.3 — rich Discord UI

This release adds a pure-OW `DiscordUI` builder and REST operations for **embeds**, **interactive buttons**, **single-choice string selects**, and **modal text inputs**.

## OW API

```ow
import "src/components/DiscordUI.ow"
set ui: new DiscordUI()
set card: ui.embed("OWCord", "Test the new UI", 5793266)
set button: ui.button("feedback_button", "Send feedback", 1)
set menu: ui.select("color_select", "Choose a color", [
  ui.option("Blue", "blue", "A cool tone"),
  ui.option("Green", "green", "A warm tone")
])
set payload: ui.message("", [card], [ui.row([button]), ui.row([menu])])
call: bot.reply_rich(event, payload)
```

In `event.kind == "component"`, inspect `event.custom_id` and (for string selects) `event.values`. Use `bot.update_message(event, ui.message(...))` to replace the clicked message, or `bot.show_modal(event, ui.modal(...))` to open a modal. In `event.kind == "modal"`, call `bot.modal_value(event, "feedback", "")` to retrieve the submitted input. Send the modal confirmation using `bot.reply_private` for privacy.

The modal builder uses Discord's newer Label component (type 18) around Text Input (type 4). Older action-row submissions are also understood by the OW event parser.

## Limits

- Supported message layouts: up to 10 simple embeds and 5 legacy action rows, with buttons (styles 1-4) or exactly one string select per row.
- Embeds currently support title, description and color. The bridge will discard unsupported rich keys instead of relaying arbitrary payloads.
- String selects currently support a single selection from up to 25 options.
- Modals support up to 5 labels with short/paragraph text inputs.
- The Java bridge checks type, size, and structure, and overwrites `allowed_mentions` to disable automatic mentions.
- OW facade is transport-neutral; the Java 17 RuntimeExtension is required to actually connect to Discord today.
- These examples cover Discord's legacy message component layout, not the full Components v2 system.

## Tests

```sh
ow test tests/RichComponentsTest.ow
mvn -f runtime-bridge/pom.xml verify
```

Run `examples/components_demo/main.ow` in a real development guild with credentials kept in environment variables. Invoke `/components`, click **Send feedback** and submit the modal; also try the select menu. Never commit bot tokens or interaction tokens.

Documentation: [Discord component reference](https://docs.discord.com/developers/components/reference) and [Discord interaction callbacks](https://docs.discord.com/developers/interactions/receiving-and-responding).
