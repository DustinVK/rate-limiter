#!/usr/bin/env bash
# Shows two in-memory failure modes with real processes:
#   1. a restart gives a limited org a fresh allowance
#   2. two replicas let an org through at twice its limit
# Uses ratelimit.yml: org globex (standard) may burst 5 writes to POST /items.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -q -DskipTests package
JAR=target/rate-limiter.jar
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

start() { # port
  java -jar "$JAR" --server.port="$1" >"target/demo-$1.log" 2>&1 &
  PIDS+=($!)
  local pid=$!
  until curl -sf "localhost:$1/health" >/dev/null; do
    kill -0 "$pid" 2>/dev/null || { echo "server on :$1 failed to start, see target/demo-$1.log"; exit 1; }
    sleep 0.2
  done
}
stop_all() { cleanup; PIDS=(); sleep 1; }
post() { # port -> status code
  curl -s -o /dev/null -w '%{http_code}' -X POST -H 'X-Api-Key: key-globex' "localhost:$1/items"
}

echo "== 1. Restart resets limits =="
start 8081
printf 'before restart: '; for i in $(seq 7); do printf '%s ' "$(post 8081)"; done; echo
stop_all
start 8081
printf 'after restart:  '; for i in $(seq 7); do printf '%s ' "$(post 8081)"; done; echo
stop_all

echo "== 2. Two replicas, round-robin =="
start 8081; start 8082
ok=0
printf 'responses:      '
for i in $(seq 14); do
  port=$((8081 + i % 2)); code=$(post $port); printf '%s ' "$code"
  [ "$code" = 201 ] && ok=$((ok + 1))
done
echo
echo "allowed: $ok (configured burst: 5)"
