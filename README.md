# YunQi-Courier

YunQi-Courier is the consolidated first release of the YunQi RPC experiments. The repository root is the only supported Maven build entry point, and all active Java packages use `yunqi.courier.*`.

The historical source trees and design exports are retained under [`archive/legacy`](archive/legacy) for reference only. They are not part of the root reactor and must not be added as Maven modules or runtime dependencies.

For the consolidated delivery background, scope, acceptance criteria and
operations handover, see [`docs/DELIVERY.md`](docs/DELIVERY.md).

## What works in v1

- Service export and typed JDK proxy references
- Optional Byte Buddy interface proxies (`proxying="bytebuddy"`)
- Group/version service keys and request attachments
- SPI extension loading with default memory registry, random load balancing and JSON serialization
- Optional CBOR serialization for compact POJO envelopes (`serialization="cbor"`); the Protobuf plugin is retained as a standalone generated-message SPI for a future envelope version
- Optional smooth weighted load balancing with registry health markers (`loadBalancing="weighted"`)
- Optional least-active load balancing with in-flight request tracking (`loadBalancing="least-active"`)
- Optional round-robin load balancing (`loadBalancing="round-robin"`)
- Optional LZ4 payload compression (`compression="lz4"`) with decompression bounds
- Optional AES-GCM payload encryption (`encryption="aes-gcm"`) for deployments that need message-level protection in addition to TLS
- UTF-8 and Base64/Base64URL SPI plugins for text and metadata integration
- Netty request/response transport with magic/version/length validation
- Configurable request deadline, opt-in retry/failover and bounded retry backoff
- Heartbeat ping/ack and channel cleanup
- Bounded server business executor with overload rejection
- Configurable server connection limit and graceful GoAway drain
- Bounded client pending requests with fail-fast overload rejection
- Bounded request metadata (up to 256 parameters, 64 attachments and 4,096
  characters per metadata string) before dispatch
- Idempotent stop/close and bootstrap restart
- Per-bootstrap invocation metrics for success, failure, timeout, retry and latency
- Optional Netty TLS and mutual TLS with JDK key/trust stores
- Request authentication/authorization with constant-time bearer tokens, service/method allow-lists and pluggable access controllers
- Registry lease renewal, change watches and active TCP/TLS health probes
- Cross-process file-lock registry (`registry="file"`) for single-host deployments
- Curator/ZooKeeper registry (`registry="zookeeper"`) with ephemeral provider
  nodes, local watch cache and reconnect re-registration
- Explicit asynchronous references (`referAsync`) with cancellation, context propagation and a demand-aware `Flow.Publisher` adapter
- Pluggable invocation metrics exporters/listeners

The memory registry is JVM-local. It is suitable for development and integration tests, not for cross-process production discovery.
For a single host, `FileServiceRegistry` provides lock-coordinated discovery and expiring leases. For multi-host deployments, the
included ZooKeeper plugin implements the same `ServiceRegistry` lease/watch contract:

```java
config.setRegistry("zookeeper");
config.setRegistryAddress("zk-1.internal:2181,zk-2.internal:2181");
config.setRegistryNamespace("yunqi-prod");
config.setRegistrySecurityEnabled(true);
config.setRegistrySecurityUsername("courier");
config.setRegistrySecurityPassword(System.getenv("COURIER_ZK_PASSWORD"));
config.setRegistryTlsEnabled(true);
config.setRegistryTlsTrustStorePath("/etc/courier/zk-trust.p12");
config.setRegistryTlsTrustStorePassword(System.getenv("COURIER_ZK_TRUSTSTORE_PASSWORD"));
// Hostname verification is enabled by default; only disable it for controlled private PKI.
// config.setRegistryTlsHostnameVerificationEnabled(false);
```

ZooKeeper providers are ephemeral nodes, so session loss removes an endpoint
automatically. Curator watches keep consumer discovery in memory, while a
reconnected session recreates provider nodes. With registry security enabled,
digest authentication and per-node ACLs protect the namespace, while
`registryTls*` protects the ZooKeeper transport independently from Netty TLS.
ZooKeeper TLS hostname verification is enabled by default; disabling it is an
explicit opt-out for controlled private networks and should not be used with
public or shared endpoints.
The registry stores only validated service metadata and attributes; application
payloads are never deserialized by the registry.

`host`/`port` control the Netty bind address. `advertisedHost` and
`advertisedPort` optionally control the endpoint written to the registry;
they are required when the bind address is a wildcard such as `0.0.0.0` or
`::`, because wildcard addresses are not routable service endpoints.

The server accepts at most `maxConnections` active child channels (default
`1024`). On `stop()`, it stops accepting new requests, closes the listening
socket, broadcasts a protocol-level GoAway message, waits briefly for queued
business work, and then closes remaining channels. Clients fail pending calls
on that channel immediately. The Netty client can recreate previously used
endpoint channels in the background with bounded exponential backoff; callers
should still handle the in-flight failure and retry only idempotent operations.

## Modules

```text
yunqi-courier
├── yunqi-courier-common
├── yunqi-courier-api
├── yunqi-courier-kernel
├── yunqi-courier-plugin
│   ├── yunqi-courier-registry-memory
│   ├── yunqi-courier-registry-file
│   ├── yunqi-courier-registry-zookeeper
│   ├── yunqi-courier-serialization-json
│   ├── yunqi-courier-serialization-cbor
│   ├── yunqi-courier-serialization-protobuf
│   ├── yunqi-courier-proxying-jdk
│   ├── yunqi-courier-proxying-bytebuddy
│   ├── yunqi-courier-traffic-loadbalancing-random
│   ├── yunqi-courier-traffic-loadbalancing-round-robin
│   ├── yunqi-courier-traffic-loadbalancing-weighted
│   ├── yunqi-courier-traffic-loadbalancing-least-active
│   ├── yunqi-courier-compression-lz4
│   ├── yunqi-courier-encryption-aes-gcm
│   ├── yunqi-courier-encoding-utf8
│   ├── yunqi-courier-binary2text-base64
│   ├── yunqi-courier-binary2text-base64url
│   ├── yunqi-courier-telemetry-otlp
│   └── yunqi-courier-network-netty
└── yunqi-courier-starter
```

`yunqi-courier-starter` is the recommended application dependency. It brings the kernel and all default plugins into one runtime classpath. Individual plugin modules can still be selected for custom deployments.

## Quick start

```java
CourierConfig config = new CourierConfig();
config.setPort(20880);
// When binding to 0.0.0.0, advertise the address consumers can reach:
// config.setAdvertisedHost("rpc.example.internal");

YunqiCourierBootstrap courier = new YunqiCourierBootstrap(config);
courier.export(ServiceExportConfig.of(EchoService.class, new EchoServiceImpl()));
courier.start();

EchoService echo = courier.refer(ServiceReferenceConfig.of(EchoService.class));
String result = echo.echo("ok");

courier.close();
```

The optional annotations are explicit convenience APIs rather than classpath
scanning. Mark an implementation with `@CourierProvider`, call
`exportAnnotated(provider)`, and call `injectReferences(consumer)` for fields
annotated with `@CourierReference`.

References are bound to the Bootstrap components that exist when `refer` is
called. After a stop/start cycle, obtain new references from that Bootstrap.

Retry is deliberately opt-in because a transport timeout can happen after a server has already executed a non-idempotent operation:

```java
ServiceReferenceConfig.of(EchoService.class)
        .retryable(true)
        .idempotent(true)
        .retries(2)
        .timeoutMillis(3000);
```

For interfaces that mix read and write operations, annotate only the safe
operation with `@CourierIdempotent` instead of marking the whole reference
idempotent.

Retries and failover apply to transport/deadline failures only. A serialized
remote business exception is returned as a terminal application error; it is
not retried or used to trip the circuit breaker because the current protocol
does not classify application errors by provider health.

Authentication is disabled by default. Configure `authToken` and
`requireAuthentication=true` on both client and server, and enable TLS; the
configuration rejects bearer tokens over plaintext transport. For service-level
authorization, add `authorizationRule(serviceKey, "methodA,methodB")`, or supply
a custom `RequestAccessController` via `bootstrap.accessController(...)`.

`referAsync` is intentionally separate from synchronous proxies. Its generic
`invoke` returns a `CompletableFuture` and cancellation interrupts the waiting
call. `invoke(Method, ...)` preserves the reflected overload, while the
name-based form resolves compatible overloads and rejects ambiguity. Request
attachments and trace IDs are captured at submission time. `stream` adapts an
`Iterable`/`Stream` result to a demand-aware, cancellable `Flow.Publisher` and
never runs iteration on a Netty event loop; true wire-level incremental
streaming remains a protocol extension.

Remote failures are exposed as `RpcException` with the protocol
`ResponseCode` and (when supplied by the provider) its error class via
`getResponseCode()` and `getRemoteErrorClass()`.

Metrics exporters/listeners are dispatched to a bounded daemon worker so a
slow telemetry backend cannot extend an RPC deadline; events may be dropped
when that queue is saturated.

The `yunqi-courier-telemetry-otlp` plugin exports cumulative invocation metrics
to an OTLP/HTTP collector. Register it on a bootstrap before `start()` and
flush/close it with the application lifecycle:

```java
try (OtlpInvocationMetricsExporter otlp = OtlpInvocationMetricsExporter.builder()
        .endpoint("http://otel-collector:4318/v1/metrics")
        .serviceName(config.getApplicationName())
        .exportInterval(Duration.ofSeconds(10))
        .timeout(Duration.ofSeconds(5))
        .build()) {
    YunqiCourierBootstrap courier = new YunqiCourierBootstrap(config)
            .registerMetricsExporter(otlp);
    courier.start();
    // export happens asynchronously; flush before shutdown
    otlp.forceFlush(5, TimeUnit.SECONDS);
    courier.close();
}
```

The exporter is deliberately opt-in: the kernel remains usable without an
external collector. The included Docker stack starts an OTLP collector on
`127.0.0.1:4318` and prints received metrics with
`docker logs yunqi-courier-otel`.

The optional `weighted` load balancer uses `ServiceMetadata` attributes:
`weight` is a positive integer (capped internally), and `healthy=false`,
`health=down` or `health=unhealthy` removes an endpoint from selection. Missing
health means healthy. This is a registry health marker, not an active probe;
the default remains `random`.

`least-active` tracks in-flight calls through the invocation lifecycle and
prefers the provider with the fewest active calls. It is local to a bootstrap
and should be paired with provider health filtering in the registry.

`round-robin` rotates across the healthy provider snapshot and is useful when
provider capacity is intentionally uniform. `weighted` is preferable when
capacity differs, while `least-active` is preferable for uneven request cost.
The Byte Buddy proxy requires a public service interface; use the default JDK
proxy for package-private test contracts.

Payload transforms are applied after serialization and before framing. The
order is compression then AES-GCM encryption on encode, and decryption then
decompression on decode. Both peers must use the same `compression` and
`encryption` settings; the protocol header remains v1-compatible. LZ4 output
contains a bounded original-length prefix. AES-GCM expects a Base64-encoded
16/24/32-byte key and generates a fresh nonce for every payload:

```java
config.setSerialization("cbor");
config.setCompression("lz4");
config.setEncryption("aes-gcm");
config.setEncryptionKey(System.getenv("COURIER_PAYLOAD_KEY_B64"));
config.setLoadBalancing("least-active");
```

TLS remains the preferred transport security boundary. Payload AES-GCM is an
additional application-level protection mechanism and does not replace TLS
certificate validation or endpoint authentication. The Protobuf plugin follows
the generated-message contract (`MessageLite`) and is not a current Courier
envelope serializer because the v1 wire envelope is a POJO. Use CBOR when
existing Courier DTOs need a compact binary representation; introduce
Protobuf only with a protocol version that defines a generated envelope schema.

TLS is disabled by default. To enable transport encryption, set
`tlsEnabled=true`, provide a PKCS12 (or configured type) key store containing
the server identity, and configure a trust store for the client CA set. Set
`tlsMutualAuth=true` to require client certificates; the client then also uses
the configured key store. Certificate-chain validation is always enabled,
trust-all managers are not supported, and hostname verification is enabled by
default (`tlsHostnameVerificationEnabled=false` is an explicit opt-out for
private networks). TLS contexts are loaded lazily by the relevant Netty side,
so an unused client or server path does not read key material during bootstrap
construction.

## Build and verify

Requirements: JDK 21 and Maven 3.9 or newer.

```bash
mvn clean verify
```

The default end-to-end path is covered by `yunqi-courier-plugin/yunqi-courier-network-netty/src/test/java/yunqi/courier/network/netty/NettyRpcE2eTest.java`. The same module also covers protocol validation, heartbeat handling, connection limits, GoAway drain, lifecycle restart, resilience behavior and multi-bootstrap operation; JSON security and kernel rate-limit/circuit-breaker tests live in their respective modules.

The optional management server exposes `/health/live` and `/health/ready` when
`managementEnabled=true`. Bind it explicitly for container probes:

```java
config.setManagementEnabled(true);
config.setManagementHost("0.0.0.0");
config.setManagementPort(8081);
```

For the Docker-backed ZooKeeper/mTLS smoke test and load test, see
[`ops/README.md`](ops/README.md). The stack exposes only secure ZooKeeper
port `2281`; the plaintext `2181` listener is disabled.

Optional supply-chain checks:

```bash
mvn -Psbom verify -DskipTests                 # target/yunqi-courier-bom.json per module
mvn -Psecurity-scan verify -DskipTests       # target/dependency-check-report.*
mvn -Prelease package -DskipTests             # attach source jars
```

For Maven consumers, use `yunqi.courier:yunqi-courier-starter:1.0-SNAPSHOT` during development.

## Remaining production integrations

The built-in circuit breaker, rate limiter, health probes and metrics are local
per-reference/per-bootstrap controls. The optional OTLP plugin is included for
metrics export, but the collector, metrics backend, credentials and delivery
SLO remain deployment responsibilities. Production deployments still need
certificate rotation backed by an external key manager and an explicitly
reviewed application DTO schema. Wire-level streaming and trace propagation
remain extension points; the synchronous request/response protocol is stable.
