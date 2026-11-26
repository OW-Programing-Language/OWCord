package org.owcord.runtime;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** Autocommit insert protected by the database's primary key; no read-then-write race. */
final class JdbcDailyRewardStorage implements DailyRewardStorage {
    private final String dialect, url;
    private final Properties properties;
    JdbcDailyRewardStorage(String dialect, String url, String user, String password) {
        this.dialect = dialect;
        this.url = url;
        properties = new Properties();
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS owcord_daily_records (" +
                "record_key VARCHAR(300) PRIMARY KEY, payload TEXT NOT NULL)");
        } catch (SQLException e) {
            throw failure("initialize", e);
        }
    }
    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, properties);
        try {
            if (dialect.equals("sqlite")) {
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA busy_timeout = 10000");
                }
            }
            return c;
        } catch (SQLException e) {
            try { c.close(); } catch (SQLException ignored) {}
            throw e;
        }
    }
    // Hex keeps identifiers case-sensitive even on case-insensitive SQL collations.
    private static String key(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }
    private static IllegalStateException failure(String operation, SQLException e) {
        // Driver messages/causes may include passwords, URLs or payloads.
        return new IllegalStateException("Daily SQL storage " + operation + " failed (code " + e.getErrorCode() + ")");
    }
    @Override public Reservation reserve(String key, String json) {
        String insert = "INSERT INTO owcord_daily_records (record_key, payload) VALUES (?, ?)";
        boolean conflictClause = dialect.equals("postgresql") || dialect.equals("sqlite");
        if (conflictClause) insert += " ON CONFLICT (record_key) DO NOTHING";
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(insert)) {
            s.setString(1, key(key));
            s.setString(2, json);
            if (s.executeUpdate() == 1) return new Reservation(true, json);
        } catch (SQLException e) {
            // MySQL/MariaDB duplicate key; do not treat unrelated integrity or
            // connectivity failures as duplicates, or retry ambiguous inserts.
            if (conflictClause || e.getErrorCode() != 1062 || !"23000".equals(e.getSQLState()))
                throw failure("reserve", e);
        }
        String existing = get(key);
        if (existing == null) throw new IllegalStateException("Daily SQL reservation conflict without record");
        return new Reservation(false, existing);
    }
    @Override public String get(String key) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT payload FROM owcord_daily_records WHERE record_key = ?")) {
            s.setString(1, key(key));
            try (ResultSet rows = s.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException e) { throw failure("read", e); }
    }
    @Override public List<String> history(String prefix, LocalDate first, LocalDate last) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT payload FROM owcord_daily_records WHERE record_key >= ? AND record_key <= ? ORDER BY record_key")) {
            s.setString(1, key(prefix + first));
            s.setString(2, key(prefix + last));
            List<String> result = new ArrayList<>();
            try (ResultSet rows = s.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException e) { throw failure("history", e); }
    }
}
