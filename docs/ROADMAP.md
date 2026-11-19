# OWCord development roadmap — planning estimate (November 2026)

OWCord has a working OW-language Discord SDK foundation: Gateway v10, flOW-managed
transport, slash commands, events, interactions, ephemeral/deferred replies,
embeds/buttons/selects/modals, REST rate limiting, permission bitmasks,
and reconnect recovery. v0.6 adds typed slash options, subcommands and
autocomplete callbacks.

"Fully complete" is not a single fixed GitHub milestone: Discord's API
continues to evolve, and SDKs differ in scope.

## Definition A — production-oriented core v1

After PR #6, budget **8–12 more focused PRs** depending on tests and API
compatibility. Tentative sequence (PR numbers are planning labels, not promises):

| Planned work | Focus | Completion criteria |
| --- | --- | --- |
| #7 | Message REST lifecycle & reactions | Create/get/edit/delete, emoji reactions, paging and permission checks |
| #8 | Guild, channel, member and role API | Core CRUD models, pagination, audit-safe operations |
| #9 | Moderation and effective permissions | Roles/channel overwrites, authorization decisions and audit trails |
| #10 | Complete interaction workflows | More callback types, application command contexts, validation and Components V2 |
| #11 | Intents, cache and typed events | Configurable caching, partial updates, cache invalidation, event contracts |
| #12 | Gateway sharding and Identify coordination | Safe multi-shard sessions, reconnect/resume tests and rate-limit coordination |
| #13 | REST errors, observability and tracing | Typed exceptions, metrics, retry policies and sensitive-data redaction |
| #14 | Attachments, webhooks, DMs and threads | File uploads, multipart, thread/forum operations, webhook helpers |
| #15 | Packaging, versioning and SDK developer ergonomics | Versioned distributions, reproducible builds, examples, migration guide |
| #16 | Release hardening and sustained live tests | CI matrix, soak/load tests, security review, real Discord tests and v1 release |

Additional PRs may be needed when a milestone is too large, a blocker is
discovered, or upstream OW/flOW needs changes. Release readiness is based on
verified behavior and documentation, **not the number of merged PRs**.

## Definition B — broad parity with discord.js / JDA

A broad SDK comparable to major established ecosystems needs substantially
more coverage: voice connections and audio, full Discord feature/API surface,
fine-grained caches, activities, large bot sharding, event compatibility,
developer tooling, sample bots, and long-term maintenance.

This could require **15–25+ additional PRs beyond #6**, and is not a hard ceiling.
Native/JVM-free execution of OW bot applications is a **separate `ow-lang` and
native networking runtime project**, not solved by an OWCord SDK pull request.

## Verification policy

Every code PR should include deterministic Java/OWUnit tests, OW/flOW type checks,
a backward compatibility check, and a real Discord smoke test when it can be done
safely with the repository's Actions secret. Human-triggered interactions must be
tested with a real Discord account and explicitly remain unchecked until then.
No bot token or interaction token is committed or logged.

Use the current PR checklist and CI status as the authoritative record of
which tasks are actually implemented and verified.
