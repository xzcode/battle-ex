package com.battleex.platform;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Only password hashes are persisted. Unique, normalized usernames. */
public interface AccountStore extends AutoCloseable {
    record Account(UUID id, String username, String passwordHash) {}
    Account find(String username);
    boolean insert(Account account);
    @Override default void close() {}

    final class Memory implements AccountStore {
        private final Map<String, Account> accounts = new HashMap<>();
        @Override public synchronized Account find(String username) { return accounts.get(username); }
        @Override public synchronized boolean insert(Account account) {
            return accounts.putIfAbsent(account.username(), account) == null;
        }
    }
}
