package org.owcord.runtime;

import dev.oreo.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Instance-local, bounded REST diagnostics. Only normalized route keys and
 * integers are retained. No response body, request body, token or raw URL.
 */
final class DiscordRestTelemetry {
    private static final int MAX_ROUTES=32;
    private static final AtomicLong SEQUENCE=new AtomicLong();
    private final LinkedHashMap<String,long[]> routes=new LinkedHashMap<>(32,0.75f,true);
    private long attempts, completed, successful, failed, rateLimited, retried, transportErrors;
    private long latencyFast, latencyMedium, latencySlow;
    private String lastError="{\"present\":false}";

    static String nextRequestId() {
        return "rest-"+Long.toUnsignedString(SEQUENCE.incrementAndGet(),36);
    }

    synchronized void attempt() { attempts++; }
    synchronized void retry() { retried++; }
    synchronized void transportError() { transportErrors++; }

    synchronized void completed(String route, int status, long durationNanos) {
        completed++;
        if(status>=200 && status<300) successful++;
        else failed++;
        if(status==429) rateLimited++;
        long millis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0,durationNanos));
        if(millis<250) latencyFast++;
        else if(millis<1000) latencyMedium++;
        else latencySlow++;
        long[] bucket=routes.get(route);
        if(bucket==null) {
            if(routes.size()>=MAX_ROUTES) routes.remove(routes.keySet().iterator().next());
            bucket=new long[2];
            routes.put(route,bucket);
        }
        bucket[0]++;
        if(status<200 || status>=300) bucket[1]++;
    }

    synchronized void failed(DiscordApiException error) {
        lastError=error.snapshot();
    }

    synchronized String lastError() { return lastError; }

    synchronized String snapshot() {
        // Stable names and bounded row count; route labels are normalized.
        List<Object> perRoute=new java.util.ArrayList<>();
        for(Map.Entry<String,long[]> entry:routes.entrySet())
            perRoute.add(Map.of("route",entry.getKey(),
                "completed",entry.getValue()[0],"failed",entry.getValue()[1]));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("attempts",attempts);
        result.put("completed",completed);
        result.put("successful",successful);
        result.put("failed",failed);
        result.put("rate_limited",rateLimited);
        result.put("retries",retried);
        result.put("transport_errors",transportErrors);
        result.put("latency_lt_250ms",latencyFast);
        result.put("latency_250_999ms",latencyMedium);
        result.put("latency_ge_1000ms",latencySlow);
        result.put("route_limit",MAX_ROUTES);
        result.put("routes",perRoute);
        return Json.write(result);
    }
}
