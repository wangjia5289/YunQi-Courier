# Docker integration and load tests

The scripts in this directory exercise the distributed deployment path without
requiring a locally installed ZooKeeper or OpenTelemetry Collector.

Prerequisites:

- Docker with the OrbStack/Docker daemon running
- `docker-compose` (the hyphenated CLI is supported)
- JDK 21
- Maven 3.9+

Start the TLS/mTLS ZooKeeper and OTLP collector:

```bash
./ops/zookeeper/generate-certs.sh
docker-compose -f ops/docker-compose.yml up -d
```

Run the cross-JVM registry smoke test, including ACL rejection and session
expiration recovery:

```bash
./ops/zookeeper/run-integration.sh
```

Run the local Netty load test and send cumulative invocation metrics to the
collector:

```bash
./tools/run-load-test.sh
```

The load test prints throughput and p50/p95/p99 latency. The collector output
can be inspected with `docker logs yunqi-courier-otel`.

The integration script removes its disposable ZooKeeper named volumes during
cleanup. The Compose stack maps secure ZooKeeper port `2281` only; plaintext
`2181` is disabled by configuration and is not published.

For dependency security scanning, provide an NVD API key in `NVD_API_KEY` so
the OWASP Dependency-Check database can be updated without rate-limit stalls:

```bash
NVD_API_KEY=... mvn -Psecurity-scan verify -DskipTests
```
