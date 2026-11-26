package org.owcord.runtime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage contract for immutable daily records. Keys are slash-separated validated
 * ASCII identifiers ending in an ISO date. Implementations must atomically insert
 * only when absent and return the persisted winning value on conflicts.
 */
public interface DailyRewardStorage extends AutoCloseable {
    record Reservation(boolean inserted, String json) {}
    Reservation reserve(String key, String json);
    /** Returns null only when absent. Storage failures must throw. */
    String get(String key);
    /** Inclusive date range, sorted ascending. */
    default List<String> history(String prefix, LocalDate first, LocalDate last) {
        List<String> records = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            String record = get(prefix + day);
            if (record != null) records.add(record);
        }
        return records;
    }
    @Override default void close() {}
}
