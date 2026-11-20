package org.owcord.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.oreo.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordRestObservabilityTest {
    private static HttpServer start(java.util.function.BiConsumer<com.sun.net.httpserver.HttpExchange,Integer> handler,
                                    AtomicInteger count) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            int n=count.incrementAndGet();
            try{handler.accept(exchange,n);}
            finally{exchange.close();}
        });
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange x,int status,String body) {
        try{
            byte[] data=body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status,data.length==0?-1:data.length);
            if(data.length>0) try(var out=x.getResponseBody()){out.write(data);}
        }catch(java.io.IOException e){throw new RuntimeException(e);}
    }

    private static DiscordRest local(HttpServer server) {
        return new DiscordRest(HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),
            "fake-discord-bot-secret");
    }

    @Test void forbiddenAndMissingErrorsAreTypedAndContainNoBodyOrSecret() throws Exception {
        AtomicInteger counter=new AtomicInteger();
        HttpServer server=start((x,n)->respond(x,403,
            "{\"code\":50013,\"message\":\"SECRET-TOKEN leaking from server message\"}"),counter);
        try{
            DiscordRest rest=local(server);
            DiscordApiException error=assertThrows(DiscordApiException.class,
                ()->rest.getGuild("111"));
            assertEquals(403,error.status());
            assertEquals("forbidden",error.category());
            assertEquals("50013",error.discordCode());
            assertEquals("GET guilds/*",error.route());
            assertFalse(error.retryable());
            assertTrue(error.requestId().matches("rest-[a-z0-9]+"));
            assertFalse(error.getMessage().contains("SECRET-TOKEN"));
            assertFalse(error.getMessage().contains("fake-discord"));
            assertFalse(rest.lastError().contains("SECRET-TOKEN"));
            assertFalse(rest.metrics().contains("fake-discord"));
            Map<?,?> snapshot=(Map<?,?>)Json.parse(rest.lastError());
            assertEquals(true,snapshot.get("present"));
            assertEquals(403,snapshot.get("status"));
            assertEquals("50013",snapshot.get("discord_code"));
            Map<?,?> metrics=(Map<?,?>)Json.parse(rest.metrics());
            assertEquals(1L,((Number)metrics.get("attempts")).longValue());
            assertEquals(1L,((Number)metrics.get("failed")).longValue());
            assertEquals(1,counter.get());
        }finally{server.stop(0);}
    }

    @Test void interactionTokenAndDiscordResponseMessagesNeverLeak() throws Exception {
        String token="sensitive.interaction-token";
        AtomicInteger count=new AtomicInteger();
        HttpServer server=start((x,n)->respond(x,401,
            "{\"code\":10062,\"message\":\"Should not log interaction "+token+"\"}"),count);
        try{
            DiscordRest rest=local(server);
            DiscordApiException error=assertThrows(DiscordApiException.class,()->
                rest.replyInteraction("111",token,"Hello"));
            assertEquals("POST interactions/*/callback",error.route());
            assertEquals("unauthorized",error.category());
            assertEquals("10062",error.discordCode());
            assertFalse(rest.lastError().contains(token));
            assertFalse(rest.metrics().contains(token));
            assertFalse(error.getMessage().contains(token));
            assertEquals(1,count.get());
            assertEquals("POST other/*",DiscordRateLimiter.routeKey("POST",
                "strange/opaque-token?authorization=never-keep-me"));
        }finally{server.stop(0);}
    }

    @Test void safeGetRetries502And503WithBoundedBackoff() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=start((x,n)->{
            if(n==1) respond(x,502,"temporary gateway error");
            else if(n==2) respond(x,503,"temporarily unavailable");
            else respond(x,200,
                "{\"id\":\"111\",\"name\":\"OWCord\",\"owner_id\":\"222\"}");
        },calls);
        try{
            DiscordRest rest=local(server);
            assertEquals("111",((Map<?,?>)Json.parse(rest.getGuild("111"))).get("id"));
            assertEquals(3,calls.get());
            Map<?,?> m=(Map<?,?>)Json.parse(rest.metrics());
            assertEquals(3L,((Number)m.get("attempts")).longValue());
            assertEquals(2L,((Number)m.get("retries")).longValue());
            assertEquals(2L,((Number)m.get("failed")).longValue());
            assertEquals(1L,((Number)m.get("successful")).longValue());
            assertEquals(false,((Map<?,?>)Json.parse(rest.lastError())).get("present"),
                "A recovered retry is not the last unrecovered API error");
        }finally{server.stop(0);}
    }

    @Test void postIsNeverRetriedAfterAmbiguousServerFailure() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=start((x,n)->respond(x,503,"{\"code\":0,\"message\":\"unsafe retry\"}"),calls);
        try{
            DiscordRest rest=local(server);
            DiscordApiException error=assertThrows(DiscordApiException.class,
                ()->rest.sendMessage("111","one only"));
            assertEquals(503,error.status());
            assertEquals("server_error",error.category());
            assertTrue(error.retryable(),"Retry classification is not an automatic replay guarantee");
            assertEquals(1,calls.get(),"Mutating POST must never be blindly replayed");
            assertEquals(0L,((Number)((Map<?,?>)Json.parse(rest.metrics())).get("retries")).longValue());
        }finally{server.stop(0);}
    }

    @Test void rateLimitExhaustionReportsTyped429WithoutSensitiveResponse() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        HttpServer server=start((x,n)->{
            x.getResponseHeaders().add("Retry-After","0.001");
            respond(x,429,"{\"global\":false,\"retry_after\":0.001,\"message\":\"do-not-store-me\"}");
        },calls);
        try{
            DiscordRest rest=local(server);
            DiscordApiException error=assertThrows(DiscordApiException.class,
                ()->rest.getGuild("111"));
            assertEquals(429,error.status());
            assertEquals("rate_limited",error.category());
            assertTrue(error.retryable());
            assertEquals(3,calls.get());
            assertEquals(false,rest.lastError().contains("do-not-store-me"));
            Map<?,?> metrics=(Map<?,?>)Json.parse(rest.metrics());
            assertEquals(3L,((Number)metrics.get("rate_limited")).longValue());
            assertEquals(2L,((Number)metrics.get("retries")).longValue());
        }finally{server.stop(0);}
    }

    @Test void perRouteDiagnosticsAreStrictlyBoundedAndNoRawPathsStored() {
        DiscordRestTelemetry stats=new DiscordRestTelemetry();
        for(int i=0;i<100;i++)
            stats.completed("GET mocked/*/"+i,200,1_000_000);
        Map<?,?> snapshot=(Map<?,?>)Json.parse(stats.snapshot());
        assertEquals(32,((java.util.List<?>)snapshot.get("routes")).size());
        assertEquals(100L,((Number)snapshot.get("completed")).longValue());
        assertEquals(100L,((Number)snapshot.get("latency_lt_250ms")).longValue());
        assertEquals(32,snapshot.get("route_limit"));
    }
}
