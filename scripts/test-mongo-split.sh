#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
name="battle-ex-split-$(date +%s)-$RANDOM"
image="${BATTLE_MONGO_TEST_IMAGE:-mongo:8.0.15}"
cleanup(){ docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# Only a disposable, loopback-bound single-member replica set. No shared
# or production MongoDB. The driver uses the mapped port directly.
docker run -d --rm --name "$name" -p 127.0.0.1::27017 --tmpfs /data/db:rw,size=768m --tmpfs /data/configdb:rw,size=64m "$image" mongod --replSet battle_ci --bind_ip_all >/dev/null
for i in {1..40}; do
  if docker exec "$name" mongosh --quiet --eval 'db.adminCommand({ping:1}).ok' 2>/dev/null | grep -qx 1; then break;fi
  sleep 1
done
docker exec "$name" mongosh --quiet --eval 'rs.initiate({_id:"battle_ci",members:[{_id:0,host:"localhost:27017"}]})' >/dev/null
ready=0
for i in {1..40};do
  if docker exec "$name" mongosh --quiet --eval 'db.hello().isWritablePrimary' 2>/dev/null|grep -qx true;then ready=1;break;fi
  sleep 1
done
test "$ready" = 1
port="$(docker port "$name" 27017/tcp | awk -F: '{print $NF;exit}')"
export BATTLE_MONGO_URI="mongodb://127.0.0.1:$port/?directConnection=true&replicaSet=battle_ci&serverSelectionTimeoutMS=5000"
export BATTLE_SPLIT_FIXTURE_CONTAINER="$name"
export BATTLE_MONGO_DB=battle_ex_isolated_test BATTLE_TEST_STATE_MODE=mongo-split BATTLE_STATE_MODE=mongo-split
case "${1:-competition}" in
 competition) export BATTLE_COMPETITION_CLUSTER_TEST=true; node scripts/test-competition.mjs ;;
 community) export BATTLE_COMMUNITY_CLUSTER_TEST=true; node scripts/test-community-operations.mjs ;;
 moderation) export BATTLE_CLUSTER_TEST=true; node scripts/test-archive-moderation.mjs ;;
 migration) node scripts/test-split-migration.mjs ;;
 workers) node scripts/test-split-workers.mjs ;;
 *) echo "Unknown split fixture" >&2; exit 2 ;;
esac
docker exec "$name" mongosh --quiet --norc "$BATTLE_MONGO_DB" --eval "$(cat scripts/assert-mongo-no-class.js)"
docker exec "$name" mongosh --quiet --norc "$BATTLE_MONGO_DB" --eval '
const m=db.authority_meta.findOne({_id:"battle-authority-v2"});
if(!m||m.schema!==2||db.authority_state.findOne({_id:"battle-authority-v1"}).schema!==2)throw Error("split metadata missing");
if(m.payload!==undefined)throw Error("aggregate payload in split metadata");
print("PASS: explicit split metadata, "+db.getCollectionNames().length+" collections, commit version "+m.version);
'
