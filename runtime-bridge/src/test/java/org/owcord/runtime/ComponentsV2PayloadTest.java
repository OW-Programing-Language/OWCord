package org.owcord.runtime;

import dev.oreo.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentsV2PayloadTest {
    private static Map<String,Object> text(String value) {
        return Map.of("type", 10, "content", value);
    }
    private static Map<String,Object> button(String id) {
        return Map.of("type", 2, "style", 1, "custom_id", id, "label", "OK");
    }
    private static String payload(Object... components) {
        return Json.write(Map.of("flags", 32768, "components", List.of(components)));
    }

    @Test void validatesNestedLayoutsAndForcesMentionSuppression() {
        var row = Map.of("type", 1, "components", List.of(button("confirm")));
        var section = Map.of("type", 9, "components", List.of(text("Welcome")),
            "accessory", Map.of("type", 11, "media",
                Map.of("url", "https://example.org/thumb.png")));
        var gallery = Map.of("type", 12, "items",
            List.of(Map.of("media", Map.of("url", "https://example.org/image.png"),
                "description", "Artwork", "spoiler", false)));
        var container = Map.of("type", 17, "accent_color", 123456,
            "components", List.of(text("**OWCord**"), section,
                Map.of("type", 14, "divider", true, "spacing", 2), row, gallery));
        Map<?,?> parsed = (Map<?,?>) Json.parse(ComponentsV2Payload.message(payload(container)));
        assertEquals(32768, parsed.get("flags"));
        assertFalse(parsed.containsKey("content"));
        assertFalse(parsed.containsKey("embeds"));
        assertEquals(List.of(), ((Map<?,?>)parsed.get("allowed_mentions")).get("parse"));
        Map<?,?> validated = (Map<?,?>)((List<?>)parsed.get("components")).get(0);
        assertEquals(17, validated.get("type"));
        assertEquals(123456, validated.get("accent_color"));
        List<?> nested = (List<?>) validated.get("components");
        assertEquals(5, nested.size());
        assertEquals(9, ((Map<?,?>)nested.get(1)).get("type"));
        Map<?,?> checkedRow = (Map<?,?>)nested.get(3);
        assertEquals(1, checkedRow.get("type"));
    }

    @Test void validatesV2InitialAndUpdateCallbacks() {
        String p = payload(text("Private!"));
        Map<?,?> initial = (Map<?,?>)Json.parse(ComponentsV2Payload.callback(p, true, false));
        assertEquals(4, initial.get("type"));
        assertEquals(32832, ((Map<?,?>)initial.get("data")).get("flags"));
        Map<?,?> update = (Map<?,?>)Json.parse(ComponentsV2Payload.callback(p, false, true));
        assertEquals(7, update.get("type"));
        assertEquals(32768, ((Map<?,?>)update.get("data")).get("flags"));
        assertThrows(IllegalArgumentException.class,
            () -> ComponentsV2Payload.callback(p, true, true));
        assertEquals(32832, ((Map<?,?>)Json.parse(ComponentsV2Payload.message(p, true))).get("flags"));
    }

    @Test void rejectsLegacyContentAndMalformedComponentShapes() {
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(Json.write(Map.of("flags",32768,
                "content", "No mixed content", "components", List.of(text("Hello"))))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(Json.write(Map.of("flags",32768,
                "embeds", List.of(), "components", List.of(text("Hello"))))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(Json.write(Map.of("flags",64,
                "components", List.of(text("Hello"))))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(Map.of("type", 11,
                "media", Map.of("url","https://example.org/a.png")))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(Map.of("type", 99))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(Map.of("type", 14, "spacing", 3))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(Map.of("type", 9, "components",
                List.of(text("Only text")), "accessory", Map.of("type", 14)))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(Map.of("type", 17, "accent_color",16777216,
                "components", List.of(text("Bad color"))))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(Map.of("type", 12, "items",
                List.of(Map.of("media",Map.of("url", "http://not-https.example.org/x.png")))))));
    }

    @Test void rejectsDuplicateIdsAndExcessiveOrUnsafeNesting() {
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(
                Map.of("type",1,"components",List.of(button("duplicate"))),
                Map.of("type",9,"components",List.of(text("Test")),
                    "accessory",button("duplicate")))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(Map.of("type",17,"components",
                List.of(Map.of("type",17,"components",List.of(text("bad"))))))));
        List<Object> tooMany = new ArrayList<>();
        for (int i=0; i<5; i++) {
            List<Object> children = new ArrayList<>();
            for(int j=0; j<10; j++) children.add(text("item "+i+"/"+j));
            tooMany.add(Map.of("type",17,"components",children));
        }
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(Json.write(Map.of("flags",32768,
                "components",tooMany))));
        assertThrows(IllegalArgumentException.class, () ->
            ComponentsV2Payload.message(payload(text("x".repeat(4001)))));
    }

    @Test void validatesSectionButtonAccessoryAndPublicHttpsMedia() {
        Map<?,?> root = (Map<?,?>)Json.parse(ComponentsV2Payload.message(payload(
            Map.of("type",9,"components",List.of(text("Do you agree?")),
                "accessory",button("accept")),
            Map.of("type",12,"items",List.of(Map.of("media",
                Map.of("url","https://example.org/a.jpg")))))));
        assertEquals(2, ((List<?>)root.get("components")).size());
    }
}
