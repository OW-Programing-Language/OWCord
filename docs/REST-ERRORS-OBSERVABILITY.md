# OWCord v0.13 — Typed REST errors, diagnostics and bounded retries

OWCord's Java REST bridge now reports **typed, sanitized HTTP failures** and
offers explicit read-only diagnostics to OW applications.

## Typed REST failures

`DiscordApiException` is a public Java type that **extends
`IllegalStateException`** for compatibility with existing OWCord applications.
It exposes `status()`, `category()`, `discordCode()`,
`route()`, `requestId()` and `retryable()`.

Common categories include `bad_request` (400), `unauthorized` (401),
`forbidden` (403), `not_found` (404), `rate_limited` (429),
`server_error` (5xx), and `http_error` for other non-2xx responses.

The Discord API's **numeric error code** is kept if it is a bounded
nonnegative integer; arbitrary response strings (including the API error
`message`) are **never propagated**. Request bodies, Authorization tokens,
interaction tokens, webhook tokens, query parameters and raw URLs are never
stored in exception messages or metrics. Request IDs are locally generated
correlation identifiers, not Discord trace IDs.

In an OW application, inspect the last HTTP error **after handling the error**
according to the runtime's supported error-handling flow:

```ow
set diagnostics: bot.rest_metrics()
print: "REST successes: " + str(diagnostics.successful)
print: "HTTP 429 responses: " + str(diagnostics.rate_limited)
print: "Retries performed: " + str(diagnostics.retries)

set error: bot.rest_last_error()
if: error.present == true
  print: "Last Discord status: " + str(error.status)
  print: "Safe category: " + error.category
  print: "Local request ID: " + error.request_id
```

The OW values are snapshot maps parsed by `json_parse`. A fresh OWCord
client starts with `last_error = {present: false}`; HTTP failures update it.
A later successful request does not erase the *last observed failure*.
Transport errors are tracked as a counter but cannot be converted into a
Discord HTTP status, and do not contain a remote Discord numeric error code.

## Bounded in-memory telemetry

`bot.rest_metrics()` returns `attempts`, `completed`, `successful`,
`failed`, `rate_limited`, `retries`, `transport_errors`,
`latency_lt_250ms`, `latency_250_999ms`, `latency_ge_1000ms`,
`route_limit` and `routes`.

Counters count **HTTP attempts**, including attempts that returned 429 or a
transient 5xx before a later success. `retries` counts additional attempts
initiated because of retryable server responses. Latency buckets reflect the
**remote HTTP round trip** rather than rate-limit wait time. `routes` is a
bounded (maximum 32 entries) set of normalized method/route summaries. Old
route rows are evicted LRU-style; aggregate counters continue accumulating.

Metrics are process/interpreter local, never persist to disk, and are not
automatically exported to an external telemetry backend. They do not contain
request/response bodies, arbitrary API responses, or secret-bearing paths.
Unknown paths are classified as `other/*` rather than retaining raw paths.
This keeps observability useful without creating high-cardinality secret leaks.

## Retry policy

- **HTTP 429:** honor Discord's `Retry-After`, bucket and global rate-limit
  signals, and retry at most **3 HTTP attempts**, bounded by the existing
  request time budget. Exhaustion raises a typed `rate_limited` error.
- **HTTP 502, 503, 504 on GET only:** retry up to **3 HTTP attempts** with
  short bounded incremental backoff (50ms, then 100ms).
- **POST / PUT / PATCH / DELETE and other writes:** do **not** automatically
  retry 5xx responses or ambiguous network failures, because the remote
  operation may already have completed. Some existing HTTP 429 retries remain
  necessary to follow Discord's explicit retry protocol.
- **Transport IO failures:** never blindly retry; report a sanitized
  transport failure and increment `transport_errors`.
- **Interactions:** preserve the tighter initial response deadline, rather
  than waiting through a long rate-limit delay.

`retryable` reflects whether a **server response category** is commonly
transient, **not a guarantee that a mutating request may safely be replayed**.
Never automatically reissue a failed moderation or message-creation command
without a business-level deduplication strategy.

## Verification boundaries

JUnit uses a local HTTP server to test real HTTP response status handling
(401/403, 429, 502/503/504), exact bounded retries, no 5xx POST replay,
safe sanitized error snapshots, and telemetry bounds. OWUnit/flOW validates
the public diagnostic methods. The real Discord smoke reads an actual bot's
diagnostics after existing safe test traffic; it deliberately does **not**
trigger real Discord errors or moderation for this milestone.

This milestone does not include OpenTelemetry/Prometheus exporters, an
external tracing backend, or distributed metric collection; these are future
integrations that require an opt-in endpoint and data-retention policy.
