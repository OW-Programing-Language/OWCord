package org.owcord.runtime;

import dev.oreo.Json;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Persistent daily reservations. One immutable record per logical key. */
final class DailyRewards {
    private final DailyRewardStorage storage;
    private final Clock clock;
    DailyRewards(Path root, Clock clock) {
        this(new JsonDailyRewardStorage(root), clock);
    }
    DailyRewards(DailyRewardStorage storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
    }

    private static String scope(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid reward campaign or job");
        return value;
    }
    private static String id(String value) {
        if (!value.matches("[1-9][0-9]{0,19}"))
            throw new IllegalArgumentException("Expected a Discord snowflake string");
        return value;
    }
    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Expected ISO calendar date");
        return LocalDate.parse(value);
    }
    private static void validateRange(LocalDate first, LocalDate last) {
        if (last.isBefore(first) || last.isAfter(first.plusDays(365)))
            throw new IllegalArgumentException("Campaign must cover 1 to 366 days");
    }
    private ZonedDateTime now(String zone) {
        return ZonedDateTime.now(clock.withZone(ZoneId.of(zone)));
    }
    Map<String, Object> calendar(String zone) {
        return calendar(clock, zone);
    }
    static Map<String, Object> calendar(Clock clock, String zone) {
        ZonedDateTime n = ZonedDateTime.now(clock.withZone(ZoneId.of(zone)));
        return Map.of("date", n.toLocalDate().toString(), "year", n.getYear(),
            "month", n.getMonthValue(), "day", n.getDayOfMonth(),
            "hour", n.getHour(), "minute", n.getMinute(), "zone", zone);
    }
    private String claimPrefix(String guild, String user, String campaign) {
        return "claims/" + id(guild) + "/" + scope(campaign) + "/" + id(user) + "/";
    }
    Map<String, Object> claim(String guild, String user, String campaign, String zone,
                              String start, String end, String reward) {
        id(guild); id(user); scope(campaign);
        LocalDate first = date(start), last = date(end);
        validateRange(first, last);
        if (reward.isBlank() || reward.length() > 1000)
            throw new IllegalArgumentException("Reward must contain 1 to 1000 characters");
        ZonedDateTime n = now(zone);
        LocalDate day = n.toLocalDate();
        if (day.isBefore(first) || day.isAfter(last))
            return Map.of("status", "closed", "date", day.toString());
        Map<String, Object> record = Map.of("guild_id", guild, "user_id", user,
            "campaign", campaign, "date", day.toString(), "reward", reward,
            "claimed_at", n.toInstant().toString());
        return reserve(claimPrefix(guild, user, campaign) + day, record, "claimed", "duplicate");
    }
    Map<String, Object> lookup(String guild, String user, String campaign, String day) {
        Map<String, Object> record = decode(storage.get(claimPrefix(guild, user, campaign) + date(day)));
        return record == null ? Map.of("status", "missing") : result("found", record);
    }
    List<Map<String, Object>> history(String guild, String user, String campaign,
                                     String start, String end) {
        id(guild); id(user); scope(campaign);
        LocalDate first = date(start), last = date(end);
        validateRange(first, last);
        List<Map<String, Object>> records = new ArrayList<>();
        for (String json : storage.history(claimPrefix(guild, user, campaign), first, last)) {
            records.add(decode(json));
        }
        return records;
    }
    // Poll after Gateway READY. Reserves today's occurrence only, at or after local HH:mm.
    Map<String, Object> due(String guild, String job, String zone, String start,
                            String end, String localTime) {
        id(guild); scope(job);
        LocalDate first = date(start), last = date(end);
        validateRange(first, last);
        if (!localTime.matches("[0-9]{2}:[0-9]{2}"))
            throw new IllegalArgumentException("Expected HH:mm");
        LocalTime time = LocalTime.parse(localTime);
        ZonedDateTime n = now(zone);
        LocalDate day = n.toLocalDate();
        if (day.isBefore(first) || day.isAfter(last) ||
            n.toInstant().isBefore(day.atTime(time).atZone(n.getZone()).toInstant()))
            return Map.of("status", "not_due", "date", day.toString());
        String path = "jobs/" + guild + "/" + job + "/" + day;
        return reserve(path, Map.of("guild_id", guild, "job", job,
            "date", day.toString(), "reserved_at", n.toInstant().toString()),
            "due", "already_reserved");
    }
    private static Map<String, Object> result(String status, Map<String, Object> record) {
        Map<String, Object> out = new LinkedHashMap<>(record);
        out.put("status", status);
        return out;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(String json) {
        if (json == null) return null;
        Object value = Json.parse(json);
        if (!(value instanceof Map<?, ?>))
            throw new IllegalStateException("Invalid daily reward record");
        return (Map<String, Object>) value;
    }
    private Map<String, Object> reserve(String key, Map<String, Object> record,
                                        String success, String duplicate) {
        DailyRewardStorage.Reservation reservation = storage.reserve(key, Json.write(record));
        return result(reservation.inserted() ? success : duplicate, decode(reservation.json()));
    }
    void close() { storage.close(); }
}
