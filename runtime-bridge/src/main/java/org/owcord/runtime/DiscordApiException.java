package org.owcord.runtime;

import dev.oreo.Json;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Sanitized typed Discord HTTP failure. Never exposes response bodies, tokens,
 * Authorization, query parameters or original request URLs in error messages.
 * Extends IllegalStateException for backwards compatibility with OWCord v0.12.
 */
public final class DiscordApiException extends IllegalStateException {
    private final int status;
    private final String category, route, requestId, discordCode;
    private final boolean retryable;

    private DiscordApiException(int status, String category, String route,
                                String requestId, String discordCode, boolean retryable) {
        super("Discord HTTP " + status + " (" + category + "), request " + requestId);
        this.status=status;
        this.category=category;
        this.route=route;
        this.requestId=requestId;
        this.discordCode=discordCode;
        this.retryable=retryable;
    }

    static DiscordApiException from(HttpResponse<String> response, String route, String id) {
        int status=response.statusCode();
        String category=switch(status) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 429 -> "rate_limited";
            default -> status>=500 ? "server_error" : "http_error";
        };
        String code="";
        try {
            Object value=Json.parse(response.body());
            if (value instanceof Map<?,?> obj) {
                Object raw=obj.get("code");
                if (raw instanceof Number n && n.doubleValue()==Math.rint(n.doubleValue())
                    && n.longValue()>=0 && n.longValue()<=99_999_999L)
                    code=Long.toString(n.longValue());
            }
        } catch(RuntimeException ignored) { /* Untrusted Discord error data */ }
        return new DiscordApiException(status,category,route,id,code,
            status==429 || status==502 || status==503 || status==504);
    }

    public int status() { return status; }
    public String category() { return category; }
    public String route() { return route; }
    public String requestId() { return requestId; }
    public String discordCode() { return discordCode; }
    public boolean retryable() { return retryable; }

    String snapshot() {
        return Json.write(Map.of("present",true,"status",status,"category",category,
            "route",route,"request_id",requestId,"discord_code",discordCode,
            "retryable",retryable));
    }
}
