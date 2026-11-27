package com.battleex.platform;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Only password hashes are persisted. Unique, normalized usernames. */
public interface AccountStore extends AutoCloseable {
    record Account(UUID id, String username, String passwordHash) {}
    /** Empty when the username is unknown; database failures remain errors. */
    Mono<Account> find(String username);
    Mono<Boolean> insert(Account account);
    @Override default void close() {}

    final class Memory implements AccountStore {
        private final Map<String, Account> accounts = new ConcurrentHashMap<>();
        @Override public Mono<Account> find(String username) {
            return Mono.fromCallable(() -> accounts.get(username));
        }
        @Override public Mono<Boolean> insert(Account account) {
            return Mono.fromSupplier(() -> accounts.putIfAbsent(account.username(), account) == null);
        }
    }
}
