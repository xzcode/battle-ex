#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
name="battle-ex-test-$(date +%s)-$RANDOM"
password="$(openssl rand -hex 24)"
image="${BATTLE_MONGO_TEST_IMAGE:-mongo:8.0.15}"
cleanup(){ docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --rm --name "$name" -p 127.0.0.1::27017 --tmpfs /data/db:rw,size=512m --tmpfs /data/configdb:rw,size=64m -e MONGO_INITDB_ROOT_USERNAME=battle_test -e "MONGO_INITDB_ROOT_PASSWORD=$password" "$image" >/dev/null
ready=0
for i in {1..40}; do
  if docker exec "$name" mongosh --quiet -u battle_test -p "$password" --authenticationDatabase admin --eval 'db.adminCommand({ping:1}).ok' 2>/dev/null | grep -qx 1; then ready=1;break;fi
  sleep 1
done
test "$ready" = 1
port="$(docker port "$name" 27017/tcp | awk -F: '{print $NF;exit}')"
export BATTLE_MONGO_URI="mongodb://battle_test:$password@127.0.0.1:$port/?authSource=admin&serverSelectionTimeoutMS=5000"
export BATTLE_MONGO_DB=battle_ex_isolated_test BATTLE_TEST_PERSISTENCE=true
if [[ "${1:-}" == "--community-operations" ]]; then
  export BATTLE_COMMUNITY_CLUSTER_TEST=true BATTLE_STATE_MODE=mongo
  node scripts/test-community-operations.mjs
elif [[ "${1:-}" == "--archive-moderation" ]]; then
  export BATTLE_CLUSTER_TEST=true BATTLE_STATE_MODE=mongo
  node scripts/test-archive-moderation.mjs
elif [[ "${1:-}" == "--competition" ]]; then
  export BATTLE_COMPETITION_CLUSTER_TEST=true BATTLE_STATE_MODE=mongo
  node scripts/test-competition.mjs
elif [[ "${1:-}" == "--cluster" ]]; then
  export BATTLE_CLUSTER_TEST=true BATTLE_STATE_MODE=mongo BATTLE_CLUSTER_TEST_MONGO_CONTAINER="$name"
  node scripts/test-cluster.mjs
else
  export BATTLE_STATE_MODE=memory
  node scripts/test-platform.mjs
fi

# The cluster outage test intentionally stops its disposable MongoDB. All
# other fixtures inspect the actual documents before the wrapper cleans up.
if [[ "${1:-}" != "--cluster" ]]; then
  docker exec "$name" mongosh --quiet --norc -u battle_test -p "$password" --authenticationDatabase admin "$BATTLE_MONGO_DB" --eval "$(cat scripts/assert-mongo-no-class.js)"
fi
