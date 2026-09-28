#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
COMPOSE_FILE="$ROOT_DIR/ops/docker-compose.yml"
TLS_DIR="$ROOT_DIR/ops/zookeeper/tls"
BUILD_DIR="$ROOT_DIR/ops/.integration-build"
MVN=${MVN:-mvn}

if ! command -v docker-compose >/dev/null 2>&1; then
  echo "docker-compose is required" >&2
  exit 2
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not available" >&2
  exit 2
fi

"$ROOT_DIR/ops/zookeeper/generate-certs.sh"
docker-compose -f "$COMPOSE_FILE" up -d --force-recreate zookeeper otel-collector
cleanup() {
  if [[ -n "${PROVIDER_PID:-}" ]]; then
    kill "$PROVIDER_PID" 2>/dev/null || true
    wait "$PROVIDER_PID" 2>/dev/null || true
  fi
  # The named volumes belong to this disposable integration stack. Removing
  # them prevents a previous ZooKeeper dynamic config from leaking into a run.
  docker-compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true
  rm -rf "$BUILD_DIR"
}
trap cleanup EXIT INT TERM

for attempt in $(seq 1 40); do
  status=$(docker inspect -f '{{.State.Health.Status}}' yunqi-courier-zookeeper 2>/dev/null || true)
  if [[ "$status" == "healthy" ]]; then
    break
  fi
  if [[ "$attempt" == 40 ]]; then
    docker logs yunqi-courier-zookeeper >&2 || true
    exit 1
  fi
  sleep 2
done

mkdir -p "$BUILD_DIR"
"$MVN" -q -pl yunqi-courier-plugin/yunqi-courier-registry-zookeeper -am install \
  -DskipTests
"$MVN" -q -pl yunqi-courier-plugin/yunqi-courier-registry-zookeeper \
  dependency:build-classpath -Dmdep.outputFile="$BUILD_DIR/classpath.txt" -Dmdep.includeScope=runtime
CP="$ROOT_DIR/yunqi-courier-plugin/yunqi-courier-registry-zookeeper/target/classes:$ROOT_DIR/yunqi-courier-kernel/target/classes:$ROOT_DIR/yunqi-courier-api/target/classes:$ROOT_DIR/yunqi-courier-common/target/classes:$(tr -d '\n' < "$BUILD_DIR/classpath.txt")"
javac --release 21 -cp "$CP" -d "$BUILD_DIR" "$ROOT_DIR/tools/java/DockerRegistrySmoke.java"
CP="$BUILD_DIR:$CP"

java -cp "$CP" DockerRegistrySmoke provider 127.0.0.1:2281 "$TLS_DIR" >"$BUILD_DIR/provider.log" 2>&1 &
PROVIDER_PID=$!
for attempt in $(seq 1 60); do
  if grep -q PROVIDER_READY "$BUILD_DIR/provider.log"; then break; fi
  if ! kill -0 "$PROVIDER_PID" 2>/dev/null; then
    cat "$BUILD_DIR/provider.log" >&2
    exit 1
  fi
  sleep 1
done
grep -q PROVIDER_READY "$BUILD_DIR/provider.log"
java -cp "$CP" DockerRegistrySmoke consumer 127.0.0.1:2281 "$TLS_DIR" 20
java -cp "$CP" DockerRegistrySmoke unauthorized 127.0.0.1:2281 "$TLS_DIR"

docker-compose -f "$COMPOSE_FILE" stop zookeeper
sleep 8
docker-compose -f "$COMPOSE_FILE" start zookeeper
for attempt in $(seq 1 40); do
  status=$(docker inspect -f '{{.State.Health.Status}}' yunqi-courier-zookeeper 2>/dev/null || true)
  if [[ "$status" == "healthy" ]]; then break; fi
  sleep 2
done
java -cp "$CP" DockerRegistrySmoke consumer 127.0.0.1:2281 "$TLS_DIR" 30
echo "ZooKeeper TLS/mTLS, digest ACL, cross-process discovery and session recovery: PASS"
