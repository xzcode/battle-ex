package com.battleex.platform;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;

import java.util.UUID;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

public final class MongoAccounts implements AccountStore {
    private final MongoClient client;
    private final MongoCollection<Document> accounts;

    public MongoAccounts(String uri, String database) {
        if (!database.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid database name");
        client = MongoClients.create(new ConnectionString(uri));
        try {
            accounts = client.getDatabase(database).getCollection("accounts");
            accounts.createIndex(ascending("username"), new IndexOptions().unique(true));
        } catch (RuntimeException e) {
            client.close();
            throw e; // No silent fallback to memory on a configured database failure.
        }
    }
    @Override public Account find(String username) {
        var d = accounts.find(eq("username", username)).first();
        return d == null ? null : new Account(UUID.fromString(d.getString("_id")), d.getString("username"),
                d.getString("passwordHash"));
    }
    @Override public boolean insert(Account account) {
        try {
            accounts.insertOne(new Document("_id", account.id().toString())
                    .append("username", account.username()).append("passwordHash", account.passwordHash()));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) return false;
            throw e;
        }
    }
    @Override public void close() { client.close(); }
}
