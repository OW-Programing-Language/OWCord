package org.owcord.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.oreo.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordReliabilityTest {
    private static HttpServer server(java.util.function.BiConsumer<com.sun.net.httpserver.HttpExchange, Integer> handler,
                                     AtomicInteger attempts) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int number = attempts.incrementAndGet();
            try { handler.accept(exchange, number); }
            finally { exchange.close(); }
        });
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) {
        try {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
            if (data.length != 0) {
                try (var stream = exchange.getResponseBody()) { stream.write(data); }
            }
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static DiscordRest client(HttpServer server) {
        return new DiscordRest(HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "safe-test-token");
    }

    @Test void retries429AfterDiscordProvidedDelayAndStopsAfterSuccess() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server((exchange, number) -> {
            if (number == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0.04");
                exchange.getResponseHeaders().set("X-RateLimit-Scope", "shared");
                respond(exchange, 429, "{\"message\":\"rate limited\",\"retry_after\":0.04,\"global\":false}");
            } else respond(exchange, 201, "{}");
        }, calls);
        try {
            long start = System.nanoTime();
            assertEquals(201, client(server).sendMessage("123", "safe message"));
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(2, calls.get(), "A 429 should be retried exactly once");
            assertTrue(elapsedMs >= 25, "The client must respect the server retry delay");
        } finally { server.stop(0); }
    }

    @Test void proactivelyPausesOnExhaustedBucketHeaders() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server((exchange, number) -> {
            exchange.getResponseHeaders().set("X-RateLimit-Bucket", "bucket-demo");
            if (number == 1) {
                exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
                exchange.getResponseHeaders().set("X-RateLimit-Reset-After", "0.06");
            } else exchange.getResponseHeaders().set("X-RateLimit-Remaining", "1");
            respond(exchange, 201, "{}");
        }, calls);
        try {
            DiscordRest rest = client(server);
            assertEquals(201, rest.sendMessage("123", "one"));
            long start = System.nanoTime();
            assertEquals(201, rest.sendMessage("123", "two"));
            assertEquals(2, calls.get());
            long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis >= 35, "An exhausted Discord bucket must delay the next request");
        } finally { server.stop(0); }
    }

    @Test void honorsJsonRetryAfterAndRejectsExcessiveWaits() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server((exchange, number) -> {
            respond(exchange, 429, "{\"retry_after\":11,\"global\":false}");
        }, calls);
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client(server).sendMessage("123", "should fail promptly"));
            assertTrue(error.getMessage().contains("budget"));
            assertEquals(1, calls.get(), "Long 429 backoff must not spin");
        } finally { server.stop(0); }
    }

    @Test void doesNotDuplicateAmbiguousHttpFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server((exchange, number) -> respond(exchange, 500, "{}"), calls);
        try {
            assertThrows(IllegalStateException.class, () -> client(server).sendMessage("123", "one only"));
            assertEquals(1, calls.get(), "5xx POST responses must not be automatically retried");
        } finally { server.stop(0); }
    }

    @Test void routeKeysNeverStoreInteractionTokensAndPreserveMajorResources() {
        assertEquals("POST interactions/*/callback",
            DiscordRateLimiter.routeKey("POST", "interactions/123/private-token/callback"));
        assertEquals("POST webhooks/*",
            DiscordRateLimiter.routeKey("POST", "webhooks/123/private-token"));
        assertEquals("channels/123", DiscordRateLimiter.majorResource("channels/123/messages"));
        assertEquals("guilds/456", DiscordRateLimiter.majorResource("applications/123/guilds/456/commands"));
        assertEquals("none", DiscordRateLimiter.majorResource("gateway/bot"));
    }

    @Test void freshLimiterDoesNotBlockNegativeMonotonicOrigin() {
        DiscordRateLimiter limiter = new DiscordRateLimiter();
        assertEquals(0, limiter.waitNanos("POST channels/*/messages", "channels/123", true, -500L));
    }

    @Test void invalidGatewaySessionAndPermanentCloseCodesAreClassified() {
        assertTrue(DiscordGateway.requiresFreshSession(4007));
        assertTrue(DiscordGateway.requiresFreshSession(4009));
        assertFalse(DiscordGateway.requiresFreshSession(4000));
        for (int code : new int[]{4004, 4010, 4011, 4012, 4013, 4014})
            assertTrue(DiscordGateway.isFatalCloseCode(code), "Must not loop after close " + code);
        assertFalse(DiscordGateway.isFatalCloseCode(4007));
        assertFalse(DiscordGateway.isFatalCloseCode(4009));
        assertFalse(DiscordGateway.isFatalCloseCode(4008));
    }

    @Test void gatewayResumedEventRestoresReadyState() {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), URI.create("http://127.0.0.1/"), "test");
        try (DiscordGateway gateway = new DiscordGateway(rest, 1)) {
            gateway.receive("{\"op\":0,\"s\":10,\"t\":\"RESUMED\",\"d\":{}}", null);
            assertTrue(gateway.isReady(), "RESUMED should restore connected() status");
        }
    }
    @Test void rateLimitForOneWebhookDoesNotBlockAnotherWebhook() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server((exchange, number) -> {
            if (number == 1) {
                exchange.getResponseHeaders().set("X-RateLimit-Bucket", "shared-webhook-route");
                exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
                exchange.getResponseHeaders().set("X-RateLimit-Reset-After", "3");
            }
            respond(exchange, 200, "{}");
        }, calls);
        try {
            DiscordRest rest = client(server);
            assertEquals(200, rest.followupInteraction("123", "first-secret-token", "hello", false));
            long start = System.nanoTime();
            assertEquals(200, rest.followupInteraction("456", "second-secret-token", "hello", false));
            long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(2, calls.get());
            assertTrue(millis < 1800, "Distinct major resources should not share cooldown deadlines");
        } finally { server.stop(0); }
    }

}
