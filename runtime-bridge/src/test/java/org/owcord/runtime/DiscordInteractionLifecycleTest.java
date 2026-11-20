package org.owcord.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.oreo.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordInteractionLifecycleTest {
    private static final String APP = "111", CHANNEL = "222", MESSAGE = "333";
    private static final String TOKEN = "opaque.token";
    private static final String PAYLOAD =
        "{\"flags\":32768,\"components\":[{\"type\":10,\"content\":\"Hello V2\"}]}";

    record Request(String method, String path, String auth, String body) {}

    @Test void sendsV2AndEditsWebhookResponsesWithoutLeakingBotAuthorization() throws Exception {
        List<Request> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0), 0);
        server.createContext("/", exchange -> {
            String method=exchange.getRequestMethod();
            String path=exchange.getRequestURI().getRawPath();
            String auth=exchange.getRequestHeaders().getFirst("Authorization");
            String body=new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(method,path,auth,body));
            int status = method.equals("POST") ? 201 : 204;
            String response="";
            if (path.startsWith("/applications/")) {
                status=201; response="{}";
            } else if (path.startsWith("/channels/")) {
                status=201; response="{\"id\":\"333\",\"channel_id\":\"222\",\"content\":\"\"}";
            } else if (path.startsWith("/webhooks/") && method.equals("POST")) {
                status=200; response="{\"id\":\"444\",\"channel_id\":\"222\",\"content\":\"\"}";
            } else if (path.startsWith("/webhooks/") && method.equals("PATCH")) {
                status=200; response="{\"id\":\"444\",\"channel_id\":\"222\",\"content\":\"Updated\"}";
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
            if(bytes.length>0)try(var out=exchange.getResponseBody()){out.write(bytes);}
            exchange.close();
        });
        server.start();
        try {
            DiscordRest rest=new DiscordRest(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"test-bot-token");
            assertEquals(MESSAGE,rest.createV2Message(CHANNEL,PAYLOAD));
            assertEquals("Bot test-bot-token",requests.get(0).auth());
            var created=(Map<?,?>)Json.parse(requests.get(0).body());
            assertEquals(32768,created.get("flags"));
            assertFalse(created.containsKey("content"));

            assertEquals(204,rest.replyV2Interaction("555",TOKEN,PAYLOAD,false));
            assertEquals(4,((Map<?,?>)Json.parse(requests.get(1).body())).get("type"));
            assertNull(requests.get(1).auth());

            assertEquals(204,rest.replyV2Interaction("556",TOKEN,PAYLOAD,true));
            var privateData=(Map<?,?>)((Map<?,?>)Json.parse(requests.get(2).body())).get("data");
            assertEquals(32832,privateData.get("flags"));

            assertEquals(204,rest.updateV2Component("557",TOKEN,PAYLOAD));
            assertEquals(7,((Map<?,?>)Json.parse(requests.get(3).body())).get("type"));
            assertEquals(204,rest.deferComponentUpdate("558",TOKEN));
            assertEquals(6,((Map<?,?>)Json.parse(requests.get(4).body())).get("type"));

            assertEquals("444",rest.createV2Followup(APP,TOKEN,PAYLOAD,false));
            assertEquals(32768,((Map<?,?>)Json.parse(requests.get(5).body())).get("flags"));
            assertEquals("444",rest.createV2Followup(APP,TOKEN,PAYLOAD,true));
            assertEquals(32832,((Map<?,?>)Json.parse(requests.get(6).body())).get("flags"));

            assertEquals("Updated",((Map<?,?>)Json.parse(
                rest.editOriginalResponse(APP,TOKEN,"Updated"))).get("content"));
            assertEquals("PATCH",requests.get(7).method());
            assertTrue(requests.get(7).path().endsWith("/messages/@original"));
            assertEquals(204,rest.deleteOriginalResponse(APP,TOKEN));
            assertEquals("DELETE",requests.get(8).method());

            assertEquals("Updated",((Map<?,?>)Json.parse(
                rest.editFollowupResponse(APP,TOKEN,MESSAGE,"Updated"))).get("content"));
            assertTrue(requests.get(9).path().endsWith("/messages/"+MESSAGE));
            assertEquals(204,rest.deleteFollowupResponse(APP,TOKEN,MESSAGE));
            assertEquals("DELETE",requests.get(10).method());

            assertEquals(201,rest.registerGlobalContextCommand(APP,"profile","Profile","[0,1,2]","[0,1]"));
            assertEquals("/applications/111/commands",requests.get(11).path());
            assertEquals("Bot test-bot-token",requests.get(11).auth());
            var global=(Map<?,?>)Json.parse(requests.get(11).body());
            assertEquals(List.of(0,1,2),global.get("contexts"));
            assertEquals(List.of(0,1),global.get("integration_types"));

            for(int i=1;i<11;i++)assertNull(requests.get(i).auth(),
                "Webhook token routes must not carry bot auth: "+i);
            assertEquals(12,requests.size());
        } finally { server.stop(0); }
    }

    @Test void validatesIdentifiersAndComponentsBeforeAnyRequest() {
        DiscordRest rest=new DiscordRest(HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:1/"),"test-bot-token");
        assertThrows(IllegalArgumentException.class,()->rest.createV2Message("bad",PAYLOAD));
        assertThrows(IllegalArgumentException.class,()->rest.createV2Message(CHANNEL,
            "{\"flags\":32768,\"content\":\"legacy\",\"components\":[{\"type\":10,\"content\":\"v2\"}]}"));
        assertThrows(IllegalArgumentException.class,()->rest.replyV2Interaction("1","invalid/token",PAYLOAD,false));
        assertThrows(IllegalArgumentException.class,()->rest.editFollowupResponse(APP,TOKEN,"../bad","edit"));
        assertThrows(IllegalArgumentException.class,()->rest.deleteFollowupResponse(APP,TOKEN,"../bad"));
        assertThrows(IllegalArgumentException.class,()->rest.registerGlobalContextCommand(APP,
            "profile","Profile","[2]","[0]"));
    }

    @Test void webhookLimiterMasksInteractionTokensAndMessageIds() {
        String p="webhooks/111/opaque.token/messages/@original";
        String q="webhooks/111/opaque.token/messages/333";
        assertEquals("PATCH webhooks/*/messages/*",DiscordRateLimiter.routeKey("PATCH",p));
        assertEquals("PATCH webhooks/*/messages/*",DiscordRateLimiter.routeKey("PATCH",q));
        assertFalse(DiscordRateLimiter.routeKey("DELETE",p).contains(TOKEN));
    }
}
