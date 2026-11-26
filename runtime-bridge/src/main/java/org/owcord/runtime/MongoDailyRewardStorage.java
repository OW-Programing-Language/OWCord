package org.owcord.runtime;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.*;
import org.bson.Document;
import java.time.LocalDate;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.ascending;

final class MongoDailyRewardStorage implements DailyRewardStorage {
    private final MongoClient client;
    private final MongoCollection<Document> collection;
    MongoDailyRewardStorage(String uri, String database) {
        MongoClient opened = null;
        try {
            opened = MongoClients.create(uri);
            collection = opened.getDatabase(database).getCollection("owcord_daily_records")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
            client = opened;
        } catch (RuntimeException e) {
            if (opened != null) opened.close();
            throw new IllegalStateException("Cannot initialize daily MongoDB storage");
        }
    }
    @Override public Reservation reserve(String key, String json) {
        try {
            collection.insertOne(new Document("_id", key).append("payload", json));
            return new Reservation(true, json);
        } catch (MongoWriteException e) {
            if (e.getError().getCode() != 11000) throw failure("reserve", e);
        } catch (MongoException e) { throw failure("reserve", e); }
        String existing = get(key);
        if (existing == null) throw new IllegalStateException("Daily MongoDB reservation conflict without record");
        return new Reservation(false, existing);
    }
    @Override public String get(String key) {
        try {
            Document row = collection.find(eq("_id", key)).first();
            return row == null ? null : payload(row);
        } catch (MongoException e) { throw failure("read", e); }
    }
    @Override public List<String> history(String prefix, LocalDate first, LocalDate last) {
        try (MongoCursor<Document> cursor = collection.find(and(gte("_id", prefix + first),
                lte("_id", prefix + last))).sort(ascending("_id")).iterator()) {
            List<String> result = new ArrayList<>();
            while (cursor.hasNext()) result.add(payload(cursor.next()));
            return result;
        } catch (MongoException e) { throw failure("history", e); }
    }
    private static String payload(Document row) {
        String value = row.getString("payload");
        if (value == null) throw new IllegalStateException("Invalid daily MongoDB record");
        return value;
    }
    private static IllegalStateException failure(String operation, MongoException e) {
        return new IllegalStateException("Daily MongoDB storage " + operation + " failed (code " + e.getCode() + ")");
    }
    @Override public void close() { client.close(); }
}
