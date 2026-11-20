# OWCord v0.7 — message lifecycle and emoji reactions

This milestone adds a bounded, straightforward Discord REST API to the OW SDK,
while preserving the flOW-managed runtime and existing slash-command features.

## Message lifecycle

```ow
set id: bot.message_create("CHANNEL_ID", "Hello from OW!")
set message: bot.message_get("CHANNEL_ID", id)
print: message.content

set edited: bot.message_edit("CHANNEL_ID", id, "Edited in OW")
print: edited.content

# Pagination: cursor is empty for the first page, then a returned message ID.
set recent: bot.message_list("CHANNEL_ID", "", "before", 20)
if: len(recent) > 0
  set next_page: bot.message_list("CHANNEL_ID", recent[len(recent) - 1].id, "before", 20)

# Delete only your bot's own message unless you have and intend to use
# the appropriate Discord MANAGE_MESSAGES permission.
set status: bot.message_delete("CHANNEL_ID", id)
```

`message_create` returns the **string snowflake ID**, not HTTP status. Get/edit
return a normalized OW map, while list returns an OW array of those maps.
The stable fields are `id`, `channel_id`, `author_id`, `content`,
`timestamp`, `edited_timestamp`, and `pinned`. Missing optional fields
are represented by empty strings or false. Arbitrary Discord-specific fields,
attachments and embeds are not copied into the normalized map; use the
existing rich message API to send those.

Pagination uses `message_list(channel, cursor, direction, limit)`, where
direction is `"before"` or `"after"`, cursor is `""` or a message snowflake,
and limit is 1–100. The SDK does **not** fetch all history automatically.

## Emoji reactions

```ow
call: bot.reaction_add("CHANNEL_ID", id, "👍")
set users: bot.reaction_users("CHANNEL_ID", id, "👍", "", 20)
call: bot.reaction_remove("CHANNEL_ID", id, "👍")
```

`reaction_users` returns an array of exact **string user IDs**, not usernames,
profiles, or member objects. The optional after-cursor is `""` for the
first page and a returned user ID for subsequent pages. Limit is 1–100.
Custom emoji identifiers use Discord's `name:id` format, which the Java bridge
URL-encodes along with Unicode emojis, preventing URL path injection.

Only **add own reaction** and **delete own reaction** are included here;
the SDK does not expose removal of another user's reaction.

## Safety and behavior

- Discord permission checks and guild/channel rules remain enforced by the API:
  reading requires `VIEW_CHANNEL` and `READ_MESSAGE_HISTORY` as applicable;
  editing requires ownership; deleting others' messages requires `MANAGE_MESSAGES`;
  adding a first reaction can require `ADD_REACTIONS`.
- Channel/message IDs are validated as unsigned decimal snowflake strings;
  emoji paths are percent-encoded; empty/invalid limits and cursors fail early.
- Message creation/editing reuses the safe `allowed_mentions.parse=[]` policy.
- v0.4 route-bucket backoff still applies. The SDK does not retry ambiguous
  failures on writes, which prevents accidental double sending or deleting.
- Message content is fetched only when your bot has permission to access it;
  do not log or persist content without appropriate authorization. The SDK
  does not provide a moderation permission gate beyond Discord's own checks.
- Every operation in this version is synchronous on the current Java bridge;
  full nonblocking request scheduling, cache coordination and multipart
  attachments are separate future milestones.

## Tests

- Run `mvn -B -ntp -f runtime-bridge/pom.xml verify` for Java local HTTP tests.
- Run `ow test tests/` and the flOW headless contract test for the OW facade.
- `examples/live_smoke/main.ow` creates one unique message in the configured
  development test channel, fetches/paginates/edits it, adds/reads/removes one
  reaction, then deletes **that same bot-created message ID**.

Discord documentation: [message resource](https://docs.discord.com/developers/resources/message).
