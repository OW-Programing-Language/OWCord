package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discord v10 slash-command option and autocomplete schema validation. */
final class DiscordCommandPayload {
    private DiscordCommandPayload() {}
    private static final long JS_SAFE_INTEGER = 9007199254740991L;

    static String command(String name, String description, String permissions, String optionsJson) {
        // Reuse the existing command and permission validators.
        Object baseline = Json.parse(DiscordRest.commandPayload(name, description, permissions));
        if (!(baseline instanceof Map<?, ?> base)) throw new IllegalStateException("Invalid baseline command");
        Map<String, Object> result = new LinkedHashMap<>();
        base.forEach((key, value) -> result.put(String.valueOf(key), value));
        List<?> options = array(optionsJson, 0, 25);
        if (!options.isEmpty()) result.put("options", validateOptions(options, 0));
        return Json.write(result);
    }

    static String autocomplete(String choicesJson) {
        List<?> values = array(choicesJson, 0, 25);
        List<Object> choices = new ArrayList<>();
        for (Object entry : values) choices.add(validatedChoice(object(entry), 0));
        return Json.write(Map.of("type", 8, "data", Map.of("choices", choices)));
    }

    private static List<Object> validateOptions(List<?> input, int depth) {
        if (depth > 2) throw new IllegalArgumentException("Discord command nesting exceeds two levels");
        Set<String> names = new HashSet<>();
        List<Object> output = new ArrayList<>();
        boolean optionalSeen = false;
        boolean hasBranch = false, hasScalar = false;
        for (Object entry : input) {
            Map<?, ?> in = object(entry);
            int type = integer(in.get("type"), "type");
            if (type < 1 || type > 11) throw new IllegalArgumentException("Invalid command option type");
            String name = text(in.get("name"), "name", 1, 32);
            if (!name.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid command option name");
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate command option name");
            String desc = text(in.get("description"), "description", 1, 100);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("name", name);
            item.put("description", desc);

            if (type <= 2) {
                if (depth == 2 || (depth == 1 && type != 1))
                    throw new IllegalArgumentException("Invalid subcommand nesting");
                hasBranch = true;
                Object children = in.get("options");
                if (!(children instanceof List<?> nested) || nested.isEmpty() || nested.size() > 25)
                    throw new IllegalArgumentException("Subcommands/groups need 1-25 children");
                for (Object nestedItem : nested) {
                    int childType = integer(object(nestedItem).get("type"), "type");
                    if (type == 2 && childType != 1)
                        throw new IllegalArgumentException("Groups contain only subcommands");
                    if (type == 1 && childType <= 2)
                        throw new IllegalArgumentException("Subcommand contains only arguments");
                }
                item.put("options", validateOptions(nested, depth + 1));
            } else {
                if (depth == 1 && input.size() > 0) {
                    // Scalars are legal at the top level or inside a subcommand.
                    // Group/subcommand-specific nesting is validated above.
                }
                hasScalar = true;
                boolean required = bool(in.get("required"), false);
                if (required && optionalSeen)
                    throw new IllegalArgumentException("Required options must precede optional options");
                if (!required) optionalSeen = true;
                item.put("required", required);

                boolean auto = bool(in.get("autocomplete"), false);
                if (auto && type != 3 && type != 4 && type != 10)
                    throw new IllegalArgumentException("Autocomplete only supports string/integer/number options");
                if (auto) item.put("autocomplete", true);
                if (in.containsKey("choices")) {
                    if (type != 3 && type != 4 && type != 10)
                        throw new IllegalArgumentException("Choices only valid for string/integer/number");
                    if (auto) throw new IllegalArgumentException("Choices conflict with autocomplete");
                    if (!(in.get("choices") instanceof List<?> opts) || opts.isEmpty() || opts.size() > 25)
                        throw new IllegalArgumentException("Choices must contain 1-25 entries");
                    List<Object> choices = new ArrayList<>();
                    for (Object raw : opts) choices.add(validatedChoice(object(raw), type));
                    item.put("choices", choices);
                }
            }
            output.add(item);
        }
        if (hasBranch && hasScalar)
            throw new IllegalArgumentException("Cannot mix subcommands and top-level arguments");
        return output;
    }

    private static Map<String, Object> validatedChoice(Map<?, ?> choice, int type) {
        String name = text(choice.get("name"), "choice.name", 1, 100);
        Object raw = choice.get("value");
        if (type == 3 && !(raw instanceof String))
            throw new IllegalArgumentException("String choice needs string value");
        if ((type == 4 || type == 10) && !(raw instanceof Number))
            throw new IllegalArgumentException("Numeric choice needs numeric value");
        if (!(raw instanceof Number) && !(raw instanceof String))
            throw new IllegalArgumentException("Invalid choice value");
        if (raw instanceof String str && (str.isEmpty() || str.length() > 100))
            throw new IllegalArgumentException("Choice value too long");
        if (raw instanceof Number num) {
            double x = num.doubleValue();
            if (!Double.isFinite(x) || Math.abs(x) > JS_SAFE_INTEGER || (type == 4 && x != Math.rint(x)))
                throw new IllegalArgumentException("Invalid numeric choice");
        }
        return Map.of("name", name, "value", raw);
    }

    private static Map<?, ?> object(Object input) {
        if (!(input instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected JSON object");
        return map;
    }
    private static List<?> array(String json, int minimum, int maximum) {
        if (json == null || json.length() > 32_768)
            throw new IllegalArgumentException("JSON options payload too large");
        Object value = Json.parse(json);
        if (!(value instanceof List<?> list) || list.size() < minimum || list.size() > maximum)
            throw new IllegalArgumentException("Expected JSON options array");
        return list;
    }
    private static String text(Object value, String name, int min, int max) {
        if (!(value instanceof String str) || str.length() < min || str.length() > max)
            throw new IllegalArgumentException("Invalid " + name);
        return str;
    }
    private static int integer(Object value, String field) {
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
            || n.doubleValue() < Integer.MIN_VALUE || n.doubleValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid " + field);
        return n.intValue();
    }
    private static boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException("Expected boolean");
        return b;
    }
}
