#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR="$ROOT_DIR/tools/.load-build"
RESULT_DIR="$ROOT_DIR/ops/results"
MVN=${MVN:-mvn}
OTLP_ENDPOINT=${OTLP_ENDPOINT:-http://127.0.0.1:4318/v1/metrics}

mkdir -p "$BUILD_DIR" "$RESULT_DIR"
"$MVN" -q -pl yunqi-courier-plugin/yunqi-courier-telemetry-otlp,yunqi-courier-plugin/yunqi-courier-network-netty -am install \
  -DskipTests
"$MVN" -q -pl yunqi-courier-plugin/yunqi-courier-telemetry-otlp dependency:build-classpath \
  -Dmdep.outputFile="$BUILD_DIR/telemetry-classpath.txt" -Dmdep.includeScope=runtime
"$MVN" -q -pl yunqi-courier-plugin/yunqi-courier-network-netty dependency:build-classpath \
  -Dmdep.outputFile="$BUILD_DIR/network-classpath.txt" -Dmdep.includeScope=runtime
CP="$ROOT_DIR/yunqi-courier-plugin/yunqi-courier-telemetry-otlp/target/classes:$ROOT_DIR/yunqi-courier-plugin/yunqi-courier-network-netty/target/classes:$ROOT_DIR/yunqi-courier-kernel/target/classes:$ROOT_DIR/yunqi-courier-api/target/classes:$ROOT_DIR/yunqi-courier-common/target/classes:$ROOT_DIR/yunqi-courier-plugin/yunqi-courier-registry-memory/target/classes:$ROOT_DIR/yunqi-courier-plugin/yunqi-courier-proxying-jdk/target/classes:$ROOT_DIR/yunqi-courier-plugin/yunqi-courier-serialization-json/target/classes:$ROOT_DIR/yunqi-courier-plugin/yunqi-courier-traffic-loadbalancing-random/target/classes:$(tr -d '\n' < "$BUILD_DIR/network-classpath.txt"):$(tr -d '\n' < "$BUILD_DIR/telemetry-classpath.txt")"
javac --release 21 -cp "$CP" -d "$BUILD_DIR" "$ROOT_DIR/tools/java/NettyLoadTest.java"
CP="$BUILD_DIR:$CP"

docker-compose -f "$ROOT_DIR/ops/docker-compose.yml" up -d otel-collector >/dev/null
stamp=$(date +%Y%m%d-%H%M%S)
java -cp "$CP" NettyLoadTest 50000 32 false "$OTLP_ENDPOINT" | tee "$RESULT_DIR/load-$stamp.txt"
java -cp "$CP" NettyLoadTest 50000 32 true "$OTLP_ENDPOINT" | tee "$RESULT_DIR/fault-$stamp.txt"
echo "Results written to $RESULT_DIR (collector: docker logs yunqi-courier-otel)"
