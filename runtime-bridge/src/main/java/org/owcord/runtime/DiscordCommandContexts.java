package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Global Discord application command installation and invocation contexts. */
final class DiscordCommandContexts {
    private DiscordCommandContexts() {}

    static String command(String name, String description, String contextsJson, String installsJson) {
        Map<?,?> baseline = (Map<?,?>) Json.parse(DiscordRest.commandPayload(name, description));
        List<Integer> contexts = enums(contextsJson, 0, 2);
        List<Integer> installs = enums(installsJson, 0, 1);
        if (contexts.isEmpty() || installs.isEmpty())
            throw new IllegalArgumentException("Global command contexts/installations must not be empty");
        if (contexts.contains(2) && !installs.contains(1))
            throw new IllegalArgumentException("PRIVATE_CHANNEL needs USER_INSTALL");
        Map<String,Object> result = new LinkedHashMap<>();
        baseline.forEach((k,v) -> result.put(String.valueOf(k),v));
        result.put("contexts",contexts);
        result.put("integration_types", installs);
        return Json.write(result);
    }

    private static List<Integer> enums(String source, int min, int max) {
        if (source == null || source.length() > 100)
            throw new IllegalArgumentException("Invalid command context JSON");
        Object raw = Json.parse(source);
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 3)
            throw new IllegalArgumentException("Expected nonempty context array");
        List<Integer> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Object value : list) {
            if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max)
                throw new IllegalArgumentException("Unsupported command context value");
            int v = n.intValue();
            if (!seen.add(v)) throw new IllegalArgumentException("Duplicate command context value");
            out.add(v);
        }
        return out;
    }
}
