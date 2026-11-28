#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
name="battle-ex-ha-$(date +%s)-$RANDOM"
image="${BATTLE_MONGO_TEST_IMAGE:-mongo:8.0.15}"
nodes=()
cleanup(){ for n in "${nodes[@]}"; do docker rm -f "$n" >/dev/null 2>&1 || true;done; }
trap cleanup EXIT
read -r -a ports <<< "$(python3 - <<'PY'
import socket
sockets=[socket.socket() for _ in range(3)]
for s in sockets:s.bind(('127.0.0.1',0))
print(*(s.getsockname()[1] for s in sockets))
for s in sockets:s.close()
PY
)"
for i in 0 1 2; do
 n="$name-$i";nodes+=("$n")
 docker run -d --rm --name "$n" --network host --tmpfs /data/db:rw,size=1024m --tmpfs /data/configdb:rw,size=64m "$image" mongod --port "${ports[i]}" --bind_ip 127.0.0.1 --replSet battle_ha --wiredTigerCacheSizeGB 0.25 --oplogSize 64 >/dev/null
done
for i in 0 1 2; do
 ready=0
 for attempt in {1..40}; do
  if docker exec "${nodes[i]}" mongosh --quiet --port "${ports[i]}" --eval 'db.adminCommand({ping:1}).ok' 2>/dev/null|grep -qx 1;then ready=1;break;fi
  sleep 1
 done
 test "$ready" = 1
done
docker exec "${nodes[0]}" mongosh --quiet --port "${ports[0]}" --eval "rs.initiate({_id:'battle_ha',members:[{_id:0,host:'127.0.0.1:${ports[0]}'},{_id:1,host:'127.0.0.1:${ports[1]}'},{_id:2,host:'127.0.0.1:${ports[2]}'}]})" >/dev/null
export BATTLE_MONGO_URI="mongodb://127.0.0.1:${ports[0]},127.0.0.1:${ports[1]},127.0.0.1:${ports[2]}/?replicaSet=battle_ha&serverSelectionTimeoutMS=5000"
export BATTLE_MONGO_DB=battle_ex_isolated_test BATTLE_HA_NAMES="$(IFS=,;echo "${nodes[*]}")" BATTLE_HA_PORTS="$(IFS=,;echo "${ports[*]}")"
export BATTLE_STATE_MODE=mongo-split BATTLE_TEST_STATE_MODE=mongo-split
node scripts/test-split-failover.mjs
