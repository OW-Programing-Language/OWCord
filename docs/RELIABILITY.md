# OWCord v0.4 — REST rate limits and Gateway recovery

Discord imposes per-route, shared-bucket and global REST limits. OWCord now reads live response headers rather than hardcoding Discord quotas.

## REST behavior

- Read `X-RateLimit-Bucket`, `X-RateLimit-Remaining` and `X-RateLimit-Reset-After`.
- Preemptively pause after receiving `Remaining: 0`; calculate delays using monotonic time (not host clock).
- On `429`, prefer `Retry-After` (seconds), then JSON `retry_after`, then a conservative fallback.
- Honor global 429 scope for authenticated bot requests while excluding webhook/interaction callbacks from the authenticated bot global quota.
- Keep routes with separate channel/guild major-resource IDs separate from one another; share observed bucket names where relevant.
- At most **three attempts** after receiving rate limits. Maximum cumulative wait is **8 seconds** for ordinary REST and **2 seconds** for interaction callbacks; requests that cannot proceed safely fail with a clear exception.
- Do **not** blindly retry transport failures or 5xx on a POST, because the request may have been committed despite a lost response.

This initial limiter is **per OWCord interpreter** and serializes HTTP requests to prevent internal racing. It is not a cross-process or distributed rate limiter, and it does not yet implement priority scheduling for interaction ACKs. Configure multiple bot instances and global REST traffic carefully. Do not assume all rate limits are prevented in the presence of other clients sharing a token.

No raw credentials, interaction tokens, or Discord error response bodies are logged. Interactions must be acknowledged promptly; a rate-limit error may require application-level recovery.

## Gateway behavior

- Opcode 7 requests a reconnect; opcode 9 invalid-session data determines whether resumption remains allowed.
- When Discord closes with **4007 (invalid sequence)** or **4009 (timed-out session)**, clear resumable session state and reconnect with a new Identify.
- Mark the connection ready again after a **RESUMED** dispatch.
- **4004, 4010–4014** are non-retriable failures; emit a safe error event rather than reconnecting forever.
- **4008** rate-limited closes use a more conservative 10-second reconnect delay instead of the default 3 seconds.
- Cancel a stale heartbeat after the connection closes.

The current Gateway remains a single session with a 1,024-entry event queue. Sharding, randomized reconnect backoff, durable event storage and true multi-process coordination remain future work.

## Tests and live checks

- Java JUnit local HTTP server: real wait after a 429, preemptive bucket throttling, JSON retry-after and timeout budget, no repeated 5xx POSTs.
- Java JUnit: Gateway close-code classification, RESUMED readiness, route key token sanitization.
- The existing `examples/live_smoke/main.ow` and GitHub Actions live smoke workflow remain the external Discord transport check, using the existing `DISCORD_BOT_TOKEN` repository secret.

Official references: [Discord rate limits](https://docs.discord.com/developers/topics/rate-limits) and [Gateway close codes](https://docs.discord.com/developers/topics/opcodes-and-status-codes).
