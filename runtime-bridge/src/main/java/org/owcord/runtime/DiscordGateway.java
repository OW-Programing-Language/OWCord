package org.owcord.runtime;

import dev.oreo.Json;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Gateway v10: Identify, heartbeat, resume and bounded dispatch queue. */
final class DiscordGateway implements AutoCloseable {
    private final DiscordRest rest;
    private final int intents;
    private final int shardId, totalShards;
    private final GatewayIdentifyCoordinator identifyCoordinator;
    private volatile ScheduledFuture<?> pendingIdentify;
    private final GatewayEventCache cache;
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(1024);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "owcord-gateway"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pendingReconnect = new AtomicBoolean();
    private final GatewayReconnectPolicy reconnectPolicy = new GatewayReconnectPolicy();
    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile String gatewayUrl;
    private volatile String resumeUrl;
    private volatile String sessionId;
    private volatile Number sequence;
    private volatile boolean ready;
    private volatile boolean ack = true;

    DiscordGateway(DiscordRest rest, int intents) {
        this(rest, intents, GatewayEventCache.Config.fromEnvironment());
    }
    DiscordGateway(DiscordRest rest, int intents, GatewayEventCache.Config cacheConfig) {
        this(rest, intents, cacheConfig, 0, 1,
            new GatewayIdentifyCoordinator(1, 1000, 1000, 86_400_000L));
    }
    DiscordGateway(DiscordRest rest, int intents, GatewayEventCache.Config cacheConfig,
                   int shardId, int totalShards, GatewayIdentifyCoordinator coordinator) {
        GatewayIntents.parse(Integer.toString(intents));
        if (totalShards < 1 || shardId < 0 || shardId >= totalShards || coordinator == null)
            throw new IllegalArgumentException("Invalid shard Gateway configuration");
        this.rest = rest;
        this.intents = intents;
        this.shardId = shardId;
        this.totalShards = totalShards;
        this.identifyCoordinator = coordinator;
        this.cache = new GatewayEventCache(cacheConfig);
    }
    int shardId() { return shardId; }
    String shardStatus() {
        return Json.write(Map.of("shard_id", shardId, "total_shards", totalShards,
            "ready", ready, "running", running.get(),
            "reconnect_failures", reconnectPolicy.consecutiveFailures()));
    }
    GatewayEventCache cache() { return cache; }
    int requestedIntents() { return intents; }

    int connect() { return connect(rest.gatewayUrl()); }
    int connect(String discoveredUrl) {
        if (!running.compareAndSet(false, true)) return 1;
        try {
            gatewayUrl = discoveredUrl;
            connectSocket();
            return 1;
        } catch (RuntimeException e) { running.set(false); throw e; }
    }
    boolean isReady() { return ready; }
    String poll(int timeoutMs) {
        if (timeoutMs < 0 || timeoutMs > 30000)
            throw new IllegalArgumentException("Polling timeout must be 0-30000ms");
        try {
            String next = events.poll(timeoutMs, TimeUnit.MILLISECONDS);
            return next == null ? "{\"type\":\"NONE\",\"data\":{}}" : next;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"type\":\"NONE\",\"data\":{}}";
        }
    }
    private void connectSocket() {
        if (!running.get()) return;
        String url = resumeUrl == null ? gatewayUrl : resumeUrl;
        URI uri = URI.create(url + (url.contains("?") ? "&" : "?") + "v=10&encoding=json");
        rest.http().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
            .buildAsync(uri, new Listener()).whenComplete((ws, error) -> {
                if (error != null) reconnect();
            });
    }

    void receive(String json, WebSocket ws) {
        Object value = Json.parse(json);
        if (!(value instanceof Map<?, ?> e) || !(e.get("op") instanceof Number n)) return;
        switch (n.intValue()) {
            case 0 -> {
                if (e.get("s") instanceof Number seq) sequence = seq;
                String eventType = String.valueOf(e.get("t"));
                Object d = e.get("d");
                if ("READY".equals(eventType) && d instanceof Map<?, ?> m) {
                    if (m.get("session_id") instanceof String id) sessionId = id;
                    if (m.get("resume_gateway_url") instanceof String url) resumeUrl = url;
                    ready = true;
                    reconnectPolicy.reset();
                }
                if ("RESUMED".equals(eventType)) {
                    ready = true;
                    reconnectPolicy.reset();
                }
                // Cache only approved metadata, before delivery to OW. A malformed
                // cache projection must not drop the original Gateway dispatch.
                try { cache.apply(eventType, d); }
                catch (RuntimeException ignored) {
                    System.err.println("OWCord: malformed cache metadata ignored");
                }
                // OW's current JSON parser does not understand exponent notation (1.23E18).
                // Discord can include large numeric metadata in Gateway events.
                // Preserve the payload as far as possible and make re-encoding parseable.
                String outgoing = Json.write(Map.of("type", eventType,
                    "shard_id", shardId, "data", owCompatible(d == null ? Map.of() : d)));
                Json.parse(outgoing); // Guard the Java -> OW JSON boundary.
                push(outgoing);
            }
            case 10 -> {
                if (e.get("d") instanceof Map<?, ?> hello &&
                    hello.get("heartbeat_interval") instanceof Number ms) {
                    startHeartbeat(ws, ms.longValue());
                    if (sessionId != null && sequence != null) resume(ws);
                    else identify(ws);
                }
            }
            case 11 -> ack = true;
            case 1 -> sendHeartbeat(ws);
            case 7 -> { if (ws != null) ws.abort(); reconnect(); }
            case 9 -> {
                // Discord can indicate whether the previous session is resumable.
                // A false/absent flag requires a fresh Identify, not a Resume loop.
                if (!Boolean.TRUE.equals(e.get("d"))) {
                    resetSession();
                    cache.clear();
                }
                ready = false;
                if (ws != null) ws.abort();
                reconnect();
            }
            default -> { }
        }
    }
    /**
     * OW JSON currently rejects numeric exponent notation. The OW parser also
     * reads integers beyond 32 bits as doubles, so keep those values as decimal
     * strings rather than inventing an imprecise integer. Native JSON payloads
     * and numeric values already supported by OW are unchanged.
     */
    private static Object owCompatible(Object value) {
        if (value instanceof Double d && (d.toString().contains("E") || d.toString().contains("e"))) {
            return BigDecimal.valueOf(d).toPlainString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                safe.put(String.valueOf(entry.getKey()), owCompatible(entry.getValue()));
            }
            return safe;
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>();
            for (Object element : list) safe.add(owCompatible(element));
            return safe;
        }
        return value;
    }

    static boolean requiresFreshSession(int closeCode) {
        return closeCode == 4007 || closeCode == 4009;
    }

    static boolean isFatalCloseCode(int closeCode) {
        return closeCode == 4004 || (closeCode >= 4010 && closeCode <= 4014);
    }

    private void resetSession() {
        sessionId = null;
        sequence = null;
        resumeUrl = null;
    }

    static String identifyPayload(String token, int intents, int shardId, int totalShards) {
        if (token == null || token.isBlank() || shardId < 0 ||
            totalShards < 1 || shardId >= totalShards)
            throw new IllegalArgumentException("Invalid Identify payload");
        return Json.write(Map.of("op", 2, "d", Map.of(
            "token", token, "intents", intents, "shard", List.of(shardId, totalShards),
            "properties", Map.of("os", System.getProperty("os.name", "unknown"),
                "browser", "OWCord", "device", "OWCord"))));
    }
    private void identify(WebSocket ws) {
        if (ws == null || !running.get()) return;
        long delay = identifyCoordinator.reserveDelayMillis(shardId, totalShards);
        if (pendingIdentify != null) pendingIdentify.cancel(false);
        pendingIdentify = timer.schedule(() -> {
            // A stale HELLO must not Identify on a replacement socket.
            if (!running.get() || socket != ws || sessionId != null) return;
            ws.sendText(identifyPayload(
                OWCordExtension.requiredEnv("DISCORD_BOT_TOKEN"), intents,
                shardId, totalShards), true);
        }, delay, TimeUnit.MILLISECONDS);
    }
    private void resume(WebSocket ws) {
        if (ws == null || !running.get()) return;
        ws.sendText(Json.write(Map.of("op", 6, "d", Map.of(
            "token", OWCordExtension.requiredEnv("DISCORD_BOT_TOKEN"),
            "session_id", sessionId, "seq", sequence))), true);
    }
    private synchronized void startHeartbeat(WebSocket ws, long ms) {
        if (heartbeat != null) heartbeat.cancel(false);
        if (ws == null || ms <= 0 || ms > 120000) return;
        ack = true;
        heartbeat = timer.scheduleAtFixedRate(() -> {
            if (!running.get() || socket != ws) return;
            if (!ack) { ws.abort(); reconnect(); return; }
            ack = false;
            sendHeartbeat(ws);
        }, ms, ms, TimeUnit.MILLISECONDS);
    }
    private void sendHeartbeat(WebSocket ws) {
        if (ws == null || !running.get()) return;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("op", 1); d.put("d", sequence);
        ws.sendText(Json.write(d), true);
    }
    private void reconnect() { reconnect(0); }

    private void reconnect(int closeCode) {
        ready = false;
        if (!running.get() || !pendingReconnect.compareAndSet(false, true)) return;
        long delay = reconnectPolicy.nextDelayMillis(closeCode);
        timer.schedule(() -> {
            pendingReconnect.set(false);
            if (running.get()) connectSocket();
        }, delay, TimeUnit.MILLISECONDS);
    }
    private void push(String json) {
        if (!events.offer(json)) {
            events.poll(); events.offer(json);
            System.err.println("OWCord: full event queue; oldest event discarded");
        }
    }
    @Override public synchronized void close() {
        running.set(false); ready = false;
        if (heartbeat != null) heartbeat.cancel(false);
        if (pendingIdentify != null) pendingIdentify.cancel(false);
        if (socket != null) socket.abort();
        socket = null;
        timer.shutdownNow();
        cache.clear();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();
        @Override public void onOpen(WebSocket ws) { socket = ws; ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            if (message.length() + text.length() > 1_048_576) {
                // Avoid unbounded accumulation of malformed or oversized frames.
                message.setLength(0);
                ws.abort();
                reconnect();
                return CompletableFuture.completedFuture(null);
            }
            message.append(text);
            if (last) {
                String raw = message.toString(); message.setLength(0);
                try { receive(raw, ws); }
                catch (RuntimeException error) {
                    System.err.println("OWCord: invalid Gateway event discarded");
                }
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            if (socket != null && socket != ws) return CompletableFuture.completedFuture(null);
            if (socket == ws) socket = null;
            ready = false;
            if (pendingIdentify != null) pendingIdentify.cancel(false);
            synchronized (DiscordGateway.this) {
                if (heartbeat != null) heartbeat.cancel(false);
            }
            if (requiresFreshSession(code)) {
                resetSession();
                cache.clear();
            }
            if (isFatalCloseCode(code)) {
                running.set(false);
                push("{\"type\":\"ERROR\",\"data\":{\"reason\":\"Gateway rejected session or configuration\"}}");
            } else reconnect(code);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable t) {
            if (socket != null && socket != ws) return;
            if (socket == ws) socket = null;
            if (pendingIdentify != null) pendingIdentify.cancel(false);
            reconnect();
        }
    }
}
