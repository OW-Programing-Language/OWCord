# OWCord v0.8 — Guild, Channel, Member & Role API

OWCord now supports a narrow Discord API v10 subset with typed OW-friendly return objects and explicit audit reasons on modifying operations. Existing flOW-managed Gateway/REST and slash-command APIs remain unchanged.

## Read-only inspection

```ow
set guild: bot.guild_get(guild_id)
set channels: bot.guild_channels(guild_id)
set channel: bot.channel_get(channel_id)
set roles: bot.guild_roles(guild_id)

# A single member query; no privileged member-list intent required.
set member: bot.guild_member(guild_id, user_id)

# Paginated member list. May require enabling GUILD_MEMBERS privileged intent.
set page: bot.guild_members(guild_id, "", 25)
if: len(page) > 0
  set next_page: bot.guild_members(guild_id, page[len(page) - 1].user_id, 25)
```

**Normalized records (exact decimal-string IDs):**

| Object | Exposed fields |
| --- | --- |
| Guild | `id`, `name`, `owner_id`, `description`, `member_count` |
| Channel | `id`, `guild_id`, `name`, `type`, `topic`, `parent_id`, `position`, `nsfw` |
| Role | `id`, `name`, `permissions` (decimal string), `position`, `managed`, `mentionable` |
| Member | `user_id`, `nick`, `joined_at`, `roles` (array of decimal strings), `pending` |

Unreturned optional text/ID fields become empty strings and optional numbers become 0. `member_count` is the API's `approximate_member_count` when supplied; it may be 0 when omitted, **not necessarily the actual number of members**.

`guild_members` supports `after=""` for the first page and a user-ID cursor for subsequent pages, limit 1–1000. Discord requires the GUILD_MEMBERS privileged intent for listing members, and the bot must be configured appropriately in the Developer Portal. The live smoke test intentionally does not list members. Guild channel listing is subject to the bot's channel visibility permissions.

## Explicit, audited management

```ow
# These make REAL changes to Discord. Use only with permission and an audit reason.
set created_channel: bot.guild_channel_create(guild_id, "ow-tests", "Testing OW", "Set up OW testing")
set renamed: bot.channel_edit(created_channel.id, "ow-testing", "OW testing", "Rename test channel")
set deleted_status: bot.channel_delete(created_channel.id, "Remove temporary test channel")

# Newly created roles are always unprivileged, not hoisted and not mentionable.
set created_role: bot.guild_role_create(guild_id, "OW Tester", "Create OW Tester role")
set renamed_role: bot.guild_role_edit(guild_id, created_role.id, "OW QA", "Rename OW Tester")
set add_status: bot.guild_member_role_add(guild_id, user_id, created_role.id, "Assign testing role")
set remove_status: bot.guild_member_role_remove(guild_id, user_id, created_role.id, "Remove testing role")
set delete_status: bot.guild_role_delete(guild_id, created_role.id, "Remove temporary role")
```

Each mutation requires a printable audit reason (1–160 characters, encoded to a max 512-character Discord header). The Java bridge validates channel/role names and snowflake IDs, and preserves safe role creation permissions (`"0"`). Rename-role cannot modify permission bits. Only guild **text channels** (type 0) are supported for creation. The channel rename API is intended for text channels only.

**Important:** These APIs are not a replacement for authorization checks. Discord enforces MANAGE_CHANNELS and MANAGE_ROLES permissions, role hierarchy and membership rules. Application developers must check user authorization and scope before calling modifying methods; the bot's token alone must never be treated as an authorization decision.

The SDK does **not** provide unrestricted permission grant operations, bulk role management, kicks/bans, channel overwrite management or guild ownership controls in v0.8.

## Verification

- Java local HTTP tests validate all read/write request shapes, normalized response values, audit-reason encoding and refusal of malformed arguments without network calls.
- OWUnit and flOW contract tests verify all public facades and types.
- The secret-backed live smoke checks **only** `guild_get`, `guild_channels`, `channel_get`, and `guild_roles`. It does not create, delete, or edit live roles, channels or members.

References: [Discord Guild resource](https://discord.com/developers/docs/resources/guild), [Discord Channel resource](https://discord.com/developers/docs/resources/channel), and [Discord Permissions](https://discord.com/developers/docs/topics/permissions).
