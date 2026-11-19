# OWCord v0.6 — slash command options, subcommands and autocomplete

The public command API is written in OW; the Java bridge validates Discord's option schemas and sends REST registration and interaction callbacks. Existing `bot.command` and `bot.command_permissions` remain compatible.

## Typed OW option builders

```ow
import "src/commands/CommandOptions.ow"
set builder: new CommandOptions()

set query: builder.string_option("query", "Search for a term", true, true)
set silent: builder.boolean_option("silent", "Hide the reply", false)
call: bot.command_options("search", "Search for records", "search_handler", [query, silent])

# A nested command route: /tools users show target:<user>
set show: builder.subcommand("show", "Show the selected user", [
  builder.user_option("target", "A user", true)
])
set users: builder.group("users", "User commands", [show])
call: bot.command_options("tools", "User management", "tools_fallback", [users])
call: bot.subcommand_handler("tools", "users show", "show_handler")
call: bot.sync_dev_commands()
```

The option builder supports string, integer, number, boolean, user, channel, role and attachment options, plus static choices, subcommands and single-level subcommand groups. Discord validates the final schema through the Java REST bridge, including required-option ordering, unique names, maximum 25 options/choices, no mixing of top-level arguments with subcommands and no simultaneous choices+autocomplete. Only a **safe supported subset** of Discord's entire schema is emitted; localization, channel type filters and min/max constraints are not supported yet.

The route handler is an OW **label**, not a dynamically invoked Java or JavaScript callback.

## Reading arguments

`bot.next(timeout_ms)` now normalizes a command's nested options and exposes `event.group`, `event.subcommand`, and `event.path` (e.g. `users show`). `bot.option(event, "target", fallback)` extracts a leaf argument regardless of the supported nesting. For new subcommands, register handlers with `bot.subcommand_handler(command_name, "group subcommand", "handler_label")`; the base handler remains the fallback for unrecognized paths.

## Autocomplete

Autocomplete is a **different interaction type** from an executed slash command. When Discord sends `INTERACTION_CREATE` type 4, `event.kind == "autocomplete"`; the parser exposes `event.focused` and `event.focused_value`. Respond with up to 25 `CommandOptions.choice(name, value)` values:

```ow
if: event.kind == "autocomplete"
  if: event.focused == "query"
    set choices: [builder.choice("Alpha", "alpha"), builder.choice("Beta", "beta")]
    call: bot.autocomplete(event, choices)
```

The REST bridge sends interaction callback **type 8**, without a bot Authorization header on the callback. These autocomplete interactions are time sensitive: produce suggestions promptly. The sample handler returns static options rather than calling a slow database.

## Security and testing

- The Java bridge validates the entire schema before HTTP POST and never logs interaction tokens.
- Numeric choices are validated against Discord/JavaScript's safe integer range when applicable; higher precision can be addressed in future versions.
- Existing slash commands continue to work without arguments.
- Use `ow test tests/` and `mvn -f runtime-bridge/pom.xml verify` for deterministic tests.
- Real Discord registration is checked with the existing secret-backed workflow, while a **human** must test typing/searching autocomplete suggestions.
- A reusable interactive demonstration lives at `examples/advanced_commands/main.ow`. It registers `/search` and `/tools users show`.

This release does not claim context-menu commands, localization, command cooldowns, permission hierarchy resolution or arbitrary validation of user-supplied data inside handlers.

Official documentation: [Discord Application Commands](https://docs.discord.com/developers/interactions/application-commands) and [Interactions](https://docs.discord.com/developers/interactions/receiving-and-responding).
