package org.owcord.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class JsonDailyRewardStorage implements DailyRewardStorage {
    private final Path root;
    JsonDailyRewardStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }
    private Path path(String key) {
        if (!key.matches("(claims|jobs)/[1-9][0-9]{0,19}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}/(?:[1-9][0-9]{0,19}/)?[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Invalid daily storage key");
        return root.resolve(key + ".json");
    }
    @Override public String get(String key) {
        try {
            return Files.readString(path(key), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read daily reward record", e);
        }
    }
    @Override public Reservation reserve(String key, String json) {
        Path path = path(key);
        String existing = get(key);
        if (existing != null) return new Reservation(false, existing);
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".claim-", ".tmp");
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            try {
                Files.createLink(path, temporary);
            } catch (FileAlreadyExistsException e) {
                existing = get(key);
                if (existing == null) throw new IllegalStateException("Reward record disappeared");
                return new Reservation(false, existing);
            }
            return new Reservation(true, json);
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalStateException("Cannot persist daily reward reservation", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }
}
