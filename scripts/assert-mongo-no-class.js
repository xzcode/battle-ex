// Read-only regression for the disposable Battle EX test database.
// Check field names recursively, including array entries and the legacy
// authority_state JSON payload. Never print document contents or secrets.
function rejectClass(value, collection) {
    if (value === null || typeof value !== "object") return;
    if (Object.prototype.hasOwnProperty.call(value, "_class")) {
        throw new Error("Forbidden MongoDB type metadata in " + collection);
    }
    for (const child of Object.values(value)) rejectClass(child, collection);
}
if (db.getName() !== "battle_ex_isolated_test") {
    throw new Error("This regression must run against the disposable test database");
}
let checked = 0;
for (const name of db.getCollectionNames().filter(n => !n.startsWith("system."))) {
    db.getCollection(name).find({}).forEach(doc => {
        rejectClass(doc, name);
        if (name === "authority_state" && typeof doc.payload === "string") {
            rejectClass(JSON.parse(doc.payload), name + ".payload");
        }
        checked++;
    });
}
if (checked === 0) throw new Error("MongoDB regression inspected no documents");
print("PASS: no _class fields in " + checked + " persisted documents or nested payloads");
