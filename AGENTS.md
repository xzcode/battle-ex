# Battle EX development

Read README.md and docs/ROOM_NETWORK_CONTRACT.md. Keep game business logic separate from Network Core EX. No duplicated network engine or game redistribution. Do not treat a client-provided player ID, room ID, key or match result as authenticated truth. Do not claim game compatibility without real versions and Windows tests. No anonymous relay or public deployment by default. Record tests, commit and unresolved gates. Keep shared branches fast-forward only. Project license is owner-pending; do not select it unilaterally.

## MongoDB persistence

Never write a `_class` field, even in nested documents or arrays. Use explicit BSON schemas and no automatic Java type metadata. Apply the [M10 storage contract](docs/M10_STORAGE_CONTRACT.md) to every persistence path and migration. Keep the real-database schema regression enabled.
