# M10 MongoDB storage contract

Mandatory persistence rules for the distributed scaling implementation.

- Keep MongoDB Reactive Streams Driver and explicit BSON mapping. Persist business fields, explicit IDs, timestamps and schema versions; do not persist Java class names or package names as mapping metadata.
- No `_class` field in any product-owned collection, including embedded documents, arrays of documents, and serialized legacy authority payloads.
- Do not enable Jackson default typing or automatic Java polymorphic type metadata in persisted records.
- If Spring Data MongoDB is introduced later, configure every write converter with a type mapper that does not write a type key (e.g. `DefaultMongoTypeMapper(null)`), and verify the actual stored documents. Merely configuring a Java class alias is not sufficient.
- Migrations must map legacy data to an explicit schema. Do not blindly remove type metadata from a live database: inspect and back up existing data, establish the correct mapping, and migrate only in an authorized migration run.
- The M10 collection split will use stable field names independent of Java implementation classes. Prefer entity-specific collections, indexes, version checks, and replica-set transactions for cross-entity invariants. Do not reintroduce a whole-platform state document as the scaling solution.

## Regression gate

`scripts/assert-mongo-no-class.js` inspects the disposable MongoDB test database after the normal integration fixtures, recursively checking BSON fields and the legacy JSON payload. It must remain part of acceptance as new collections are introduced. The outage fixture `--cluster` deliberately shuts down its own database at the end; its schema check must be performed before shutdown or covered by the other integration fixtures. No production database is scanned or modified by this test.

The optional mongo-split entity store and migration are documented in [M10 distributed authority](M10_DISTRIBUTED_AUTHORITY.md). The storage contract is mandatory for future scoped repositories.
