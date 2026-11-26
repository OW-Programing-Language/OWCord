package org.owcord.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyRewardsTest {
    @TempDir Path root;
    private DailyRewards at(String instant) {
        return new DailyRewards(root, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
    private Map<String, Object> claim(DailyRewards daily, String user, String reward) {
        return daily.claim("123", user, "advent-2026", "Europe/Paris",
            "2026-12-01", "2026-12-24", reward);
    }
    @Test void timeZoneAndCalendarBoundary() {
        var before = at("2026-11-30T22:59:59Z");
        assertEquals("closed", claim(before, "456", "gift").get("status"));
        var midnight = at("2026-11-30T23:00:00Z");
        assertEquals(1, midnight.calendar("Europe/Paris").get("day"));
        assertEquals("claimed", claim(midnight, "456", "gift").get("status"));
        assertEquals("closed", claim(at("2026-12-24T23:00:00Z"), "456", "gift").get("status"));
    }
    @Test void restartHistoryAndIndependentUsers() {
        var day1 = at("2026-12-01T10:00:00Z");
        assertEquals("claimed", claim(day1, "456", "gift A").get("status"));
        var restarted = at("2026-12-01T11:00:00Z");
        var duplicate = claim(restarted, "456", "different gift");
        assertEquals("duplicate", duplicate.get("status"));
        assertEquals("gift A", duplicate.get("reward"));
        assertEquals("claimed", claim(restarted, "789", "gift B").get("status"));
        var day2 = at("2026-12-02T10:00:00Z");
        assertEquals("claimed", claim(day2, "456", "gift C").get("status"));
        assertEquals(2, day2.history("123", "456", "advent-2026",
            "2026-12-01", "2026-12-24").size());
        assertEquals("gift A", day2.lookup("123", "456", "advent-2026", "2026-12-01").get("reward"));
        assertEquals("missing", day2.lookup("999", "456", "advent-2026", "2026-12-01").get("status"));
    }
    @Test void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                String reward = "gift " + i;
                tasks.add(() -> claim(at("2026-12-01T10:00:00Z"), "456", reward));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (var future : pool.invokeAll(tasks)) results.add(future.get());
            assertEquals(1, results.stream().filter(r -> r.get("status").equals("claimed")).count());
            assertEquals(1, results.stream().map(r -> r.get("reward")).distinct().count());
        } finally { pool.shutdownNow(); }
    }
    private String due(DailyRewards daily) {
        return (String) daily.due("123", "daily-announcement", "Europe/Paris",
            "2026-12-01", "2026-12-24", "09:00").get("status");
    }
    @Test void schedulerReservesOncePerLocalDateAcrossRestarts() {
        assertEquals("not_due", due(at("2026-12-01T07:59:59Z")));
        assertEquals("due", due(at("2026-12-01T08:00:00Z")));
        assertEquals("already_reserved", due(at("2026-12-01T15:00:00Z")));
        assertEquals("due", due(at("2026-12-02T15:00:00Z")));
        assertEquals("not_due", due(at("2026-12-25T10:00:00Z")));
    }
    @Test void rejectsUnsafeKeysAndRanges() {
        var daily = at("2026-12-01T10:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> daily.lookup("123", "456", "../escape", "2026-12-01"));
        assertThrows(IllegalArgumentException.class, () -> daily.history("123", "456", "advent", "2026-01-01", "2028-01-01"));
    }
    @Test void bridgeCalendarOperationDoesNotRequireDiscordToken() {
        try (var extension = new OWCordExtension()) {
            assertTrue(extension.supports("owcord.daily.calendar"));
            assertTrue(((String) extension.invoke("owcord.daily.calendar", List.of("UTC"))).contains("\"date\""));
            assertThrows(IllegalArgumentException.class, () -> extension.invoke("owcord.daily.claim", List.of()));
        }
    }
}
