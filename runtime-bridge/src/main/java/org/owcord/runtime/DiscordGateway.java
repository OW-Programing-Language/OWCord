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
    private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(1024);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "owcord-gateway"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pendingReconnect = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile String gatewayUrl;
    private volatile String resumeUrl;
    private volatile String sessionId;
    private volatile Number sequence;
    private volatile boolean ready;
    private volatile boolean ack = true;

    DiscordGateway(DiscordRest rest, int intents) { this.rest = rest; this.intents = intents; }

    int connect() {
        if (!running.compareAndSet(false, true)) return 1;
        try {
            gatewayUrl = rest.gatewayUrl();
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
                }
                // OW's current JSON parser does not understand exponent notation (1.23E18).
                // Discord can include large numeric metadata in Gateway events.
                // Preserve the payload as far as possible and make re-encoding parseable.
                String outgoing = Json.write(Map.of("type", eventType,
                    "data", owCompatible(d == null ? Map.of() : d)));
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
                sessionId = null; sequence = null; resumeUrl = null; ready = false;
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

    private void identify(WebSocket ws) {
        if (ws == null || !running.get()) return;
        ws.sendText(Json.write(Map.of("op", 2, "d", Map.of(
            "token", OWCordExtension.requiredEnv("DISCORD_BOT_TOKEN"),
            "intents", intents,
            "properties", Map.of("os", System.getProperty("os.name"),
                "browser", "OWCord", "device", "OWCord")))), true);
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
    private void reconnect() {
        ready = false;
        if (!running.get() || !pendingReconnect.compareAndSet(false, true)) return;
        timer.schedule(() -> {
            pendingReconnect.set(false);
            if (running.get()) connectSocket();
        }, 3, TimeUnit.SECONDS);
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
        if (socket != null) socket.abort();
        socket = null;
        timer.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder message = new StringBuilder();
        @Override public void onOpen(WebSocket ws) { socket = ws; ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
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
            if (socket == ws) socket = null;
            if (code == 4004 || code == 4013 || code == 4014) {
                running.set(false);
                push("{\"type\":\"ERROR\",\"data\":{\"reason\":\"Gateway credentials or intents rejected\"}}");
            } else reconnect();
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws, Throwable t) {
            if (socket == ws) socket = null;
            reconnect();
        }
    }
}
