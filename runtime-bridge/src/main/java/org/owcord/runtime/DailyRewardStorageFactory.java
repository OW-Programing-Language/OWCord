package org.owcord.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class DailyRewardStorageFactory {
    static DailyRewardStorage open(Map<String, String> env) {
        Path root = Path.of(env.getOrDefault("OWCORD_DATA_DIR", ".owcord-data")).toAbsolutePath().normalize();
        String kind = env.getOrDefault("OWCORD_STORAGE", "").trim().toLowerCase(Locale.ROOT);
        // Preserve existing JSON deployments rather than silently resetting claim history.
        if (kind.isEmpty())
            kind = Files.exists(root.resolve("claims")) || Files.exists(root.resolve("jobs")) ? "json" : "sqlite";
        return switch (kind) {
            case "json" -> new JsonDailyRewardStorage(root);
            case "sqlite" -> sqlite(root, env);
            case "postgresql", "mysql", "mariadb" -> {
                String url = required(env, "OWCORD_STORAGE_JDBC_URL");
                if (!url.startsWith("jdbc:" + kind + ":"))
                    throw new IllegalArgumentException("JDBC URL must match OWCORD_STORAGE");
                yield new JdbcDailyRewardStorage(kind, url, env.get("OWCORD_STORAGE_USER"),
                    env.get("OWCORD_STORAGE_PASSWORD"));
            }
            case "mongodb" -> new MongoDailyRewardStorage(required(env, "OWCORD_STORAGE_MONGO_URI"),
                required(env, "OWCORD_STORAGE_MONGO_DATABASE"));
            default -> {
                DailyRewardStorageProvider selected = null;
                for (DailyRewardStorageProvider provider : ServiceLoader.load(DailyRewardStorageProvider.class)) {
                    if (kind.equals(provider.name())) {
                        if (selected != null) throw new IllegalStateException("Duplicate storage provider");
                        selected = provider;
                    }
                }
                if (selected == null) throw new IllegalArgumentException("Unknown OWCord storage provider");
                yield Objects.requireNonNull(selected.open(Map.copyOf(env)));
            }
        };
    }
    private static DailyRewardStorage sqlite(Path root, Map<String, String> env) {
        Path path = Path.of(env.getOrDefault("OWCORD_STORAGE_SQLITE_PATH",
            root.resolve("daily-rewards.db").toString())).toAbsolutePath().normalize();
        try { Files.createDirectories(path.getParent()); }
        catch (IOException e) { throw new IllegalStateException("Cannot create SQLite directory", e); }
        return new JdbcDailyRewardStorage("sqlite", "jdbc:sqlite:" + path, null, null);
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
