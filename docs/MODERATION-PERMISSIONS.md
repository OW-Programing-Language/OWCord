# OWCord v0.9 — Effective Permissions & Audited Moderation

This milestone adds OW-friendly permission calculation and narrowly scoped moderation operations. The Java bridge computes exact unsigned 64-bit decimal-string permission masks with `BigInteger`. Discord itself remains the **final authority** for every API operation.

## Effective member/channel permissions

```ow
set mask: bot.effective_permissions(guild_id, user_id, channel_id)
if: bot.has_permission(mask, "8192") == 1
  print: "Can manage messages in this channel"

# Preflight moderation only; does not execute the action.
set allowed: bot.can_moderate(guild_id, moderator_id, target_id, "kick")

# Compare actor's highest non-managed role position to a target role.
set manageable: bot.can_manage_role(guild_id, moderator_id, target_role_id)
```

The algorithm combines `@everyone`, member roles, Administrator/owner bypass,
channel overwrites in the correct order (`@everyone` deny/allow, aggregated
role deny/allow, member-specific deny/allow), and active timeouts. Missing
roles or an incomplete channel overwrite snapshot cause a **fail-closed exception**.
Role masks and snowflakes always stay decimal strings; they are never converted
to JavaScript-compatible floating-point values.

Role hierarchy comparison is intentionally **conservative**: same-position roles
fail, managed roles are protected, and a guild owner/Administrator is not a valid
timeout target. `can_moderate` supports `"kick"`, `"ban"`, `"unban"`,
`"timeout"` and `"untimeout"`. Unban uses actor permission alone because a
banned user has no live guild member record.

**Important:** permission snapshots can become stale between checking and
acting. Authorize the *human requesting an action* against their actual guild
context, confirm the bot has privileges, and let Discord enforce the final
permission/rank decisions. These helpers are not a complete security middleware,
do not handle voice overwrites/thread inheritance or application command
permission overrides, and do not replace Discord server-side checks.

## Audited moderation REST operations

```ow
# First authenticate and authorize the requester's identity:
if: bot.can_moderate(guild_id, actor_id, target_id, "kick") == 1
  set code: bot.moderation_kick(guild_id, target_id, "Repeated rules violation")

# Keep deleted history at zero by default; explicit 0..604800 seconds supported:
set code: bot.moderation_ban(guild_id, target_id, 0, "Malicious behavior")
set code: bot.moderation_unban(guild_id, target_id, "Appeal approved")

# UTC ISO-8601 expiration within the next 28 days:
set result: bot.moderation_timeout(guild_id, target_id, until_iso, "Repeated spam")
set cleared: bot.moderation_clear_timeout(guild_id, target_id, "Timeout appealed")
```

All five methods require an explicit, printable audit reason (1–160 characters,
URL-encoded to Discord's X-Audit-Log-Reason header). Kick/ban/unban return HTTP
status code (204 when successful). Timeout and clearing timeout return a normalized
guild-member map containing `communication_disabled_until` (empty string when
null). The Java bridge validates snowflakes, ban deletion seconds, ISO-8601
timeouts and audit header encoding **before sending** HTTP requests.

Actions require the appropriate Discord bot permissions: `KICK_MEMBERS`,
`BAN_MEMBERS`, or `MODERATE_MEMBERS`. The bot's role hierarchy also applies;
members with Administrator permission or the guild owner cannot be timed out.
There is no bulk ban/kick operation in this release.

**Never invoke a moderation action just because an interaction was received.**
Validate the caller's guild ID, identity, required permission, target role
hierarchy and your own moderation policy. Audit reasons are sent to Discord,
not stored in an independent persistent database by OWCord.

## Verification boundaries

- Local Java tests: overwrite precedence, high-bit permissions, owner/Admin
  bypass, timeout bit restrictions, role hierarchy, bad/partial snapshots.
- Local HTTP test server: kick, ban, unban, timeout and clear timeout endpoint
  shapes, encoded audit headers, refusal of malformed arguments without requests.
- OWUnit/flOW: public OW facade and typed API contracts with fake transport.
- Real GitHub Actions Discord smoke: **read-only** inspection of the bot's own
  permissions and refusal to moderate itself. No actual user is kicked, banned
  or timed out, and no roles are modified.

Official documentation: [Discord permissions](https://docs.discord.com/developers/topics/permissions),
[Guild member modification](https://docs.discord.com/developers/resources/guild#modify-guild-member),
[Guild bans](https://docs.discord.com/developers/resources/guild#create-guild-ban).
