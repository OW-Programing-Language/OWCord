package org.owcord.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyRewardStorageTest {
    @TempDir Path root;

    @Test void sqliteIsDefaultAndSurvivesReopening() throws Exception {
        Map<String,String> env = Map.of("OWCORD_DATA_DIR", root.toString());
        contract(env);
        assertTrue(Files.isRegularFile(root.resolve("daily-rewards.db")));
    }

    @Test void existingJsonIsPreserved() throws Exception {
        Map<String,String> env = Map.of("OWCORD_DATA_DIR", root.toString(), "OWCORD_STORAGE", "json");
        contract(env);
        assertTrue(Files.isDirectory(root.resolve("claims")));
        try (var storage = DailyRewardStorageFactory.open(Map.of("OWCORD_DATA_DIR", root.toString()))) {
            assertInstanceOf(JsonDailyRewardStorage.class, storage);
        }
    }

    @Test void invalidConfigurationFailsWithoutFallback() {
        assertThrows(IllegalArgumentException.class, () -> DailyRewardStorageFactory.open(Map.of("OWCORD_STORAGE", "unknown")));
        assertThrows(IllegalArgumentException.class, () -> DailyRewardStorageFactory.open(Map.of("OWCORD_STORAGE", "postgresql")));
        assertThrows(IllegalArgumentException.class, () -> DailyRewardStorageFactory.open(Map.of("OWCORD_STORAGE", "mysql", "OWCORD_STORAGE_JDBC_URL", "jdbc:postgresql://localhost/test")));
    }

    @Test void externalProvidersWhenConfigured() throws Exception {
        String kind = System.getenv("OWCORD_STORAGE_INTEGRATION");
        org.junit.jupiter.api.Assumptions.assumeTrue(kind != null && !kind.isBlank());
        Map<String,String> env = new HashMap<>(System.getenv());
        env.put("OWCORD_STORAGE", kind);
        contract(env);
    }

    private void contract(Map<String,String> env) throws Exception {
        String campaign = "test-" + UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.parse("2026-12-01T10:00:00Z"), ZoneOffset.UTC);
        try (var first = DailyRewardStorageFactory.open(env);
             var second = DailyRewardStorageFactory.open(env)) {
            DailyRewards a = new DailyRewards(first, clock), b = new DailyRewards(second, clock);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Map<String,Object>>> tasks = new ArrayList<>();
                for (int i = 0; i < 24; i++) {
                    DailyRewards api = i % 2 == 0 ? a : b;
                    String reward = "gift-" + i;
                    tasks.add(() -> api.claim("123", "456", campaign, "UTC", "2026-12-01", "2026-12-24", reward));
                }
                List<Map<String,Object>> results = new ArrayList<>();
                for (var future : pool.invokeAll(tasks)) results.add(future.get());
                assertEquals(1, results.stream().filter(r -> r.get("status").equals("claimed")).count());
                assertEquals(1, results.stream().map(r -> r.get("reward")).distinct().count());
            } finally { pool.shutdownNow(); }
            assertEquals("due", a.due("123", campaign, "UTC", "2026-12-01", "2026-12-24", "00:00").get("status"));
            assertEquals("already_reserved", b.due("123", campaign, "UTC", "2026-12-01", "2026-12-24", "00:00").get("status"));
        }
        try (var reopened = DailyRewardStorageFactory.open(env)) {
            DailyRewards api = new DailyRewards(reopened, clock);
            assertEquals("duplicate", api.claim("123", "456", campaign, "UTC", "2026-12-01", "2026-12-24", "another").get("status"));
            assertEquals("found", api.lookup("123", "456", campaign, "2026-12-01").get("status"));
            assertEquals("missing", api.lookup("999", "456", campaign, "2026-12-01").get("status"));
            assertEquals("claimed", api.claim("123", "456", campaign.toUpperCase(Locale.ROOT), "UTC", "2026-12-01", "2026-12-24", "case-sensitive").get("status"));
            DailyRewards next = new DailyRewards(reopened, Clock.offset(clock, Duration.ofDays(1)));
            assertEquals("claimed", next.claim("123", "456", campaign, "UTC", "2026-12-01", "2026-12-24", "tomorrow").get("status"));
            var history = api.history("123", "456", campaign, "2026-12-01", "2026-12-24");
            assertEquals(2, history.size());
            assertEquals("2026-12-01", history.get(0).get("date"));
            assertEquals("2026-12-02", history.get(1).get("date"));
            assertEquals("already_reserved", api.due("123", campaign, "UTC", "2026-12-01", "2026-12-24", "00:00").get("status"));
        }
    }
}
