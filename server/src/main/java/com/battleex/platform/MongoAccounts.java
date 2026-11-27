package com.battleex.platform;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
            // Application startup only: establish the unique index before accepting traffic.
            // No blocking database operation in any request/connection handler.
            Mono.from(accounts.createIndex(ascending("username"), new IndexOptions().unique(true)))
                    .block(Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            client.close();
            throw e; // No silent fallback to memory on a configured database failure.
        }
    }
    @Override public Mono<Account> find(String username) {
        return Mono.defer(() -> Mono.from(accounts.find(eq("username", username)).first()))
                .map(d -> new Account(UUID.fromString(d.getString("_id")), d.getString("username"),
                        d.getString("passwordHash")));
    }
    @Override public Mono<Boolean> insert(Account account) {
        return Mono.defer(() -> Mono.from(accounts.insertOne(new Document("_id", account.id().toString())
                .append("username", account.username()).append("passwordHash", account.passwordHash()))))
                .thenReturn(true)
                .onErrorResume(MongoWriteException.class, e ->
                        e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                                ? Mono.just(false) : Mono.error(e));
    }
    @Override public void close() { client.close(); }
}
