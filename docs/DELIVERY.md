# YunQi-Courier 1.0 交付文档

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 项目名称 | YunQi-Courier |
| 当前版本 | 1.0-SNAPSHOT |
| 文档用途 | 第一版整合成果、验收依据和运行交接说明 |
| 构建入口 | 仓库根目录 `pom.xml` |
| Java 包前缀 | `yunqi.courier.*` |
| 推荐依赖 | `yunqi.courier:yunqi-courier-starter:1.0-SNAPSHOT` |
| 构建环境 | JDK 21、Maven 3.9 或更高版本 |
| 验证命令 | `mvn clean verify` |

## 2. 背景

YunQi-Courier 源自多个时期的 `yunqi-rpc` 实验版本。历史目录中同时存在不同的模块划分、包名、协议实现、注册中心和网络实现，形成了一个功能重复、依赖方向不一致、构建入口不明确的代码集合。直接从这些版本中选择一个继续演进，会带来以下问题：

1. **无法确定唯一事实来源。** 同一能力在多个版本中有不同实现，修复和测试结果不能自然复用。
2. **构建和发布边界不清晰。** 多个旧 Maven 工程、局部 `target` 产物和嵌套版本容易被误加入构建或运行时 classpath。
3. **基础能力不完整。** 早期实现主要验证 RPC 主流程，对超时、重试、过载、租约、TLS、认证和生命周期等生产边界覆盖不足。
4. **演进成本较高。** 旧包结构和坐标不统一，应用方无法形成稳定的 API、插件和配置约定。

本次交付的任务，是从这些历史实现中提取可复用能力，形成一个唯一、可构建、可验证、可继续扩展的 RPC 基线项目，并正式命名为 **YunQi-Courier**。历史源码仍保留用于追溯，但不再承担构建或运行职责。

## 3. 交付目标

本版本以“可运行的高质量 RPC 基线”为目标，具体包括：

- 只有根目录一个 Maven Reactor 入口。
- 所有活跃 Java 源码统一使用 `yunqi.courier.*` 包结构，Maven 坐标统一使用 `yunqi.courier`。
- 明确 `common -> api -> kernel -> plugin` 的依赖方向，应用使用 `starter` 聚合默认插件。
- 提供 Netty 请求/响应传输、JSON 序列化、JDK 代理、注册中心和负载均衡的完整默认路径。
- 对协议边界、请求元数据、待处理请求、业务队列和重试次数实施上限保护。
- 对超时、重试、熔断、限流、过载、健康检查和租约续期提供可验证行为。
- 提供 TLS/mTLS、认证、授权、错误码、traceId、attachments 和 principal 传播能力。
- 提供异步引用、取消、应用层 `Flow.Publisher` 适配和 metrics 扩展点。
- 支持停止、关闭和重启，并确保网络、注册中心、定时任务和待处理请求得到释放。

以上目标由 [`CONSTRAINTS.md`](../CONSTRAINTS.md) 固化为工程约束；后续修改必须继续满足这些约束。

## 4. 交付范围

### 4.1 当前工程结构

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

### 4.2 模块职责

| 模块 | 职责 |
| --- | --- |
| `common` | 配置、服务元数据、请求/响应模型、协议常量和公共异常 |
| `api` | 服务导出/引用配置、同步和异步调用 API、注解、调用上下文和公开异常 |
| `kernel` | Bootstrap 生命周期、服务仓库、请求处理、代理编排、SPI、安全、韧性和 metrics |
| `plugin` | 可替换的网络、序列化、代理、注册中心和流量策略实现 |
| `starter` | 面向应用的默认依赖聚合，不承载核心业务实现 |

### 4.3 历史版本边界

历史版本统一位于 [`archive/legacy`](../archive/legacy)，仅用于设计追溯和差异参考：

- 不在根 `pom.xml` 的 `<modules>` 中。
- 不作为当前模块的源码目录、依赖或 SPI provider。
- 不应从归档目录启动 Maven 构建。
- 归档目录中的 `target`、`.class`、`.jar` 和嵌套 `.git` 不属于本次运行时交付物。

## 5. 总体架构

### 5.1 调用链路

```text
应用调用接口
    -> JDK Proxy / AsyncServiceReference
    -> ServiceInvocationHandler
    -> 注册中心发现 + 负载均衡 + 健康过滤
    -> 超时/重试/熔断/限流决策
    -> NetworkClient (Netty)
    -> 序列化、可选压缩/加密、协议编码和长度校验
    -> NetworkServer (Netty)
    -> 认证/授权 + 请求校验
    -> 有界业务执行器
    -> ServiceRepository 调用公开服务方法
    -> CourierResponse 编码并返回
```

Netty event-loop 只负责 I/O 和协议处理，用户服务方法始终提交到配置的有界业务执行器执行。网络帧、请求参数、attachments、客户端 pending requests 和服务端队列均有边界，避免输入或并发量无限制地放大内存和线程消耗。

### 5.2 SPI 扩展

运行时实现通过 `META-INF/yunqi-courier/plugin/<interface-name>` 加载。provider 名称必须显式配置，实现类必须提供 public 无参构造函数。当前默认实现为：

| SPI | 默认实现 |
| --- | --- |
| `ServiceRegistry` | `memory` |
| `NetworkClient` / `NetworkServer` | `netty` |
| `Serializer` | `json` |
| `ProxyFactory` | `jdk` |
| `TrafficLoadBalancer` | `random` |

可选的 `weighted` 负载均衡只解释服务属性 `weight`、`healthy` 和 `health`。非法权重回退为 1；明确不健康的 provider 不参与选择。

新增的可选 provider 包括 `cbor`、`protobuf`、`round-robin`、`least-active`、`lz4` 和
`aes-gcm`。CBOR 适用于当前 POJO 请求/响应；Protobuf 保留为实现
`MessageLite` 的独立 SPI，待未来协议版本定义生成式信封后接入。`least-active`
通过调用生命周期维护本地在途计数。
LZ4 与 AES-GCM 是 payload 变换，不改变 v1 协议头；双方必须保持相同配置。
`bytebuddy` 提供 JDK 动态代理之外的接口代理实现，但不放宽服务必须声明接口的边界；
它要求服务接口为 public，package-private 测试接口应继续使用 `jdk`。

### 5.3 Archive 能力落地矩阵

| Archive 能力 | 当前处理 | 说明 |
| --- | --- | --- |
| JSON | 已有并保持默认 | 继续使用安全类型白名单和输入长度约束 |
| CBOR | 已新增并接入 v1 | 适合当前 POJO 请求/响应信封 |
| Protobuf | 已新增独立 SPI | Archive 实现只接受 `MessageLite`，当前 v1 POJO 信封暂不启用 |
| Protostuff / JBoss Marshalling | 不直接迁移 | Archive 中是空壳/注释实现；通用反序列化还会扩大安全边界 |
| JDK proxy | 已有并保持默认 | 当前服务契约要求接口 |
| CGLIB | 不直接迁移 | 用 Byte Buddy 提供 Java 21 友好的接口代理替代；不放宽为类契约 |
| random / weighted | 已有 | random 默认，weighted 使用服务权重和健康标记 |
| least-active | 已新增并接入调用生命周期 | 统计每个 provider 的本地在途请求数 |
| round-robin | 已新增 | 仅在健康 provider 快照中轮询 |
| circuit breaker / rate limiter | 已在 kernel | 作为引用级本地韧性策略，不重复复制 Archive 空间实现 |
| LZ4 | 已新增并接入 Netty payload | 解压长度受 `maxFrameLength` 限制 |
| AES-GCM | 已新增并接入 Netty payload | 每条消息随机 nonce；TLS 仍是首选传输安全边界 |
| UTF-8 / Base64 / Base64URL | 已新增 SPI | 用于文本和元数据转换，不改变 v1 控制帧的 UTF-8 约定 |

## 6. 核心能力

### 6.1 服务模型与调用

- 服务使用 `serviceName:group:version` 形成稳定服务键。
- 导出边界是声明接口的公开实例方法；静态、synthetic 和 bridge 方法不会成为 RPC 入口。
- 请求进入反射前会校验 request id、服务键、方法名、参数类型、参数数量和序列化编码。
- 接口中存在泛型擦除场景时，按接口方法签名分派，保证正常的 erased signature 调用。
- 请求 attachments 最多 64 项，每个元数据字符串最多 4,096 个字符；参数元数据最多 256 项。
- `referAsync` 提供 `CompletableFuture` 和取消；`stream` 是应用层 demand-aware `Flow.Publisher` 适配，不改变当前线上的请求/响应协议。

### 6.2 网络协议与资源保护

每个网络帧包含 magic、协议版本、消息方向、序列化编码、request id 和有界 payload。解码器在分配大对象前拒绝 magic、版本、方向、长度和字段不合法的帧。

客户端 pending request 数量由 `maxPendingRequests` 限制。请求在成功、失败、超时、连接断开、取消和关闭路径都会释放占用的 slot。服务端业务线程数和队列容量由 `serverBusinessThreads`、`serverQueueCapacity` 控制，队列满时快速返回过载错误。服务端活动子连接数由 `maxConnections` 限制，超过上限的新连接会被关闭。

`stop()` 使用有界的优雅停机流程：先切换为不接受新请求，关闭监听 socket，向现有连接广播 request id 为 0 的 `GO_AWAY` 控制帧；停止状态下到达的新 request 返回 503 `UNAVAILABLE`，heartbeat 仍可应答。客户端收到 `GO_AWAY` 后立即失败该连接上的 pending requests 并关闭连接，服务端随后等待业务队列并关闭剩余子连接和 event loop。停机后重新 `start()` 会重新接受连接，但 stop 前创建的引用仍应按生命周期约定重新获取。

### 6.3 超时、重试和韧性

- 请求 deadline 使用单调时钟，不依赖系统时间回拨。
- 重试默认关闭；只有 `retryable=true` 且引用声明 `idempotent=true`，或接口方法带 `@CourierIdempotent` 时才允许。
- 只有传输失败、deadline 超时以及明确分类的 overloaded/unavailable 响应可重试。
- 远程业务异常是 terminal application error，不会被误判为可重试的传输失败。
- 引用可启用本地 circuit breaker 和 token bucket rate limiter。
- 失败、超时和重试事件会进入 invocation metrics；metrics exporter 在有界 daemon worker 上执行，不能拖长 RPC deadline。
- `yunqi-courier-telemetry-otlp` 提供可选 OTLP/HTTP exporter；它把内核累计快照转换为增量 counter，并周期性发送 calls、success、failure、timeout、retries 和 latency histogram。

### 6.4 注册中心与服务发现

- `MemoryServiceRegistry` 适用于 JVM 内开发、测试和同进程集成。
- `FileServiceRegistry` 使用文件锁、租约过期和变更轮询，适用于单主机多进程场景。
- `ZooKeeperServiceRegistry` 使用 Curator 管理临时 provider 节点、本地缓存
  和 watch；连接恢复后会自动重建注册节点，适用于多主机部署。
- 注册中心提供 register/unregister、discover、renew、watch 和 health update 生命周期。
- 重复配置会先清理旧服务、watch、租约和定时任务；关闭后禁止继续写入或订阅。
- wildcard bind（例如 `0.0.0.0`、`::`）不能直接作为消费端点注册，必须配置可达的 `advertisedHost`，必要时配置 `advertisedPort`。

### 6.5 TLS、认证和授权

- TLS 默认关闭；开启后使用 JDK key store/trust store 和证书链校验，不支持 trust-all。
- `tlsMutualAuth=true` 要求客户端证书；hostname verification 默认开启。
- ZooKeeper TLS 使用独立的 `registryTls*` 配置，hostname verification 默认开启；仅在受控私网且证书名称无法匹配时允许显式关闭。
- TLS 握手失败会关闭连接并完成 pending request。
- `reloadTlsContext()` 只影响后续建立的连接，证书轮换的外部密钥管理仍由部署环境负责。
- bearer token 认证默认关闭；配置 token 时必须同时启用 TLS。
- 认证在进入业务队列前执行；授权可使用服务/方法 allow-list 或 `RequestAccessController` SPI。
- mTLS 会话信息可提供给自定义访问控制器。

## 7. 使用与配置

### 7.1 最小启动示例

```java
CourierConfig config = new CourierConfig();
config.setPort(20880);

YunqiCourierBootstrap courier = new YunqiCourierBootstrap(config);
courier.export(ServiceExportConfig.of(EchoService.class, new EchoServiceImpl()));
courier.start();

EchoService echo = courier.refer(ServiceReferenceConfig.of(EchoService.class));
String result = echo.echo("ok");

courier.close();
```

### 7.2 关键配置

| 配置 | 默认值/状态 | 说明 |
| --- | --- | --- |
| `host` / `port` | `127.0.0.1:20880` | Netty 绑定地址和端口 |
| `advertisedHost` / `advertisedPort` | 未设置 | 注册到发现中心的可达地址；wildcard bind 时必须设置 host |
| `timeoutMillis` | `3000` | 默认调用 deadline |
| `connectTimeoutMillis` | `1000` | 建连超时 |
| `retries` | `1` | 只有显式启用 retryable 且满足幂等约束时才使用 |
| `maxFrameLength` | `16 MiB` | 单帧最大长度，上限为 `64 MiB` |
| `maxPendingRequests` | `10000` | 客户端并发 pending request 上限 |
| `maxConnections` | `1024` | 服务端活动子连接上限；超限连接会被关闭 |
| `serverBusinessThreads` | `8` | 服务端业务线程数 |
| `serverQueueCapacity` | `1000` | 服务端业务队列容量 |
| `registry` | `memory` | `memory`、`file` 或 `zookeeper`，也可接入外部 SPI |
| `registryAddress` | 未设置 | ZooKeeper 连接串（选择 `zookeeper` 时必填） |
| `registryNamespace` | `yunqi-courier` | ZooKeeper 命名空间，用于隔离不同环境 |
| `registryConnectTimeoutMillis` | `5000` | ZooKeeper 首次连接超时 |
| `registrySessionTimeoutMillis` | `15000` | ZooKeeper 会话超时；临时节点的租约边界 |
| `registryRetryBaseSleepMillis` / `registryRetryMaxRetries` | `1000` / `5` | Curator 指数退避重试参数 |
| `registrySecurityEnabled` | `false` | 开启 digest 认证和 namespace 节点 ACL；必须同时开启 ZooKeeper TLS |
| `registrySecurityUsername` / `registrySecurityPassword` | 未设置 | ZooKeeper digest 凭据；密码应从密钥管理系统注入 |
| `registryTlsEnabled` | `false` | ZooKeeper TLS，与 Netty `tlsEnabled` 相互独立 |
| `registryTlsTrustStorePath` / `registryTlsTrustStorePassword` | 未设置 | ZooKeeper 服务端 CA 信任库；开启 registry TLS 时必填 |
| `registryTlsKeyStorePath` / `registryTlsKeyStorePassword` | 未设置 | 可选的 ZooKeeper 客户端证书，用于服务端要求 mTLS 的集群 |
| `registryTlsHostnameVerificationEnabled` | `true` | ZooKeeper TLS hostname verification；仅受控私网可显式关闭 |
| `clientAutoReconnect` | `true` | 已建立过连接的 endpoint 断连后自动重建 channel |
| `clientReconnectBaseDelayMillis` / `clientReconnectMaxDelayMillis` | `100` / `5000` | 客户端重连指数退避范围 |
| `loadBalancing` | `random` | `random`、`round-robin`、`weighted` 或 `least-active` |
| `serialization` | `json` | `json` 或 `cbor`；Protobuf 当前不作为 v1 信封 serializer |
| `proxying` | `jdk` | `jdk` 或 `bytebuddy`，两者都面向接口契约 |
| `compression` | `none` | `none` 或 `lz4`；双方必须一致 |
| `encryption` | `none` | `none` 或 `aes-gcm`；双方必须一致 |
| `encryptionKey` | 未设置 | AES-GCM 的 Base64 密钥（16/24/32 字节） |
| `tlsEnabled` | `false` | 是否开启 Netty TLS |
| `tlsMutualAuth` | `false` | 是否要求客户端证书 |
| `requireAuthentication` | `false` | 是否要求 bearer token；开启 token 时必须有 TLS |
| `activeHealthChecks` | `false` | 是否进行 TCP/TLS 健康探测 |
| `managementEnabled` | `false` | 是否启动 `/health/live` 和 `/health/ready` 管理端点 |
| `managementHost` / `managementPort` | `127.0.0.1:8081` | 管理端点绑定地址和端口；容器部署通常绑定 `0.0.0.0` |

重试示例：

```java
ServiceReferenceConfig.of(EchoService.class)
        .retryable(true)
        .idempotent(true)
        .retries(2)
        .timeoutMillis(3000);
```

对于同时包含读写操作的接口，应只在安全方法上使用 `@CourierIdempotent`，不要把整个引用错误地标记为幂等。

### 7.3 注册中心选择建议

| 场景 | 建议 |
| --- | --- |
| 单元测试、同 JVM 集成测试 | `registry=memory` |
| 单主机多进程 | `registry=file`，配置独立的 `registryFilePath` |
| 多主机生产环境 | `registry=zookeeper`，配置 ZooKeeper 连接串和命名空间 |

## 8. 构建、测试与验收

### 8.1 环境要求

- JDK 21。
- Maven 3.9 或更高版本。
- 可访问 Maven 依赖仓库。
- TLS 集成测试由测试代码临时生成证书材料，不要求预置生产证书。

### 8.2 验证步骤

在仓库根目录执行：

```bash
mvn clean verify
```

Docker 集成环境（需要 Docker、`docker-compose` 和 JDK 21）：

```bash
./ops/zookeeper/run-integration.sh
./tools/run-load-test.sh
docker logs yunqi-courier-otel
```

可选的交付加固检查：

```bash
mvn -Psbom verify -DskipTests
mvn -Psecurity-scan verify -DskipTests
mvn -Prelease package -DskipTests
```

`-Psbom`、`-Prelease` 和 `-Psecurity-scan` 仍是可用的交付加固 profile，
其输出数量会随插件模块数量变化。OWASP Dependency-Check 需要 `NVD_API_KEY`
才能稳定更新 NVD 数据库；当前环境未提供该密钥，在线更新超时，离线模式也因
没有本地 CVE 数据库而无法给出漏洞结论。因此本次不把安全扫描写成“通过”，
部署或 CI 应先注入密钥后执行上面的命令。

当前交付验证结果：

```text
exit code: 0
tests: 103
failures: 0
errors: 0
skipped: 0
```

测试覆盖默认 Netty 端到端调用、primitive 参数、泛型接口擦除、协议边界、心跳、连接上限与 GoAway drain、TLS/mTLS、认证/授权、超时、重试、熔断、限流、过载、注册中心租约/watch、生命周期重启、JSON 安全、weighted 负载均衡、管理健康端点和 OTLP exporter。

最近一次 Docker 压测（JDK 21、Netty、32 并发、每场景 50,000 请求）结果：

| 场景 | 成功/失败/超时 | 吞吐 | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 正常 | 50,000 / 0 / 0 | 62,379.46 req/s | 0.482 ms | 0.753 ms | 1.079 ms |
| provider 重启故障演练 | 48,000 / 2,000 / 0 | 59,732.93 req/s | 0.453 ms | 0.879 ms | 1.370 ms |

OTLP collector 日志已收到 `yunqi.rpc.calls`、`yunqi.rpc.success`、`yunqi.rpc.latency`，并带有 `service.name` 资源属性。

### 8.3 验收标准

交付版本满足以下条件才视为可接受：

1. 根目录 `pom.xml` 能独立完成 `clean verify`。
2. active 源码、POM 和 SPI 描述符不出现旧包名或旧 Maven 坐标。
3. 所有测试通过，且没有因跳过测试而掩盖失败。
4. 通过服务导出、引用、调用和关闭的最小端到端路径。
5. 超时、待处理请求上限、服务端连接上限、业务队列上限和帧长度上限在边界输入下仍可控。
6. stop/close 可重复调用，重启后新建引用可以重新调用。
7. TLS、认证、授权和 wildcard advertised endpoint 约束得到验证。
8. `archive/legacy` 不进入 reactor、运行时 classpath 或发布包。

## 9. 运行交接与故障定位

### 9.1 启动前检查

- 确认 JDK 和 Maven 版本符合要求。
- 确认 `host`/`port` 仅用于本机绑定；跨主机调用时检查 `advertisedHost`/`advertisedPort`。
- 使用 TLS 时确认 key store、trust store、密码和类型配置完整。
- 使用 mTLS 时确认服务端 trust store 包含客户端 CA，客户端 key store 包含客户端身份。
- 使用 bearer token 时确认客户端和服务端 token 一致，且 TLS 已启用。
- 使用 file registry 时为不同部署实例指定独立文件路径或明确共享策略。

### 9.2 常见现象

| 现象 | 优先检查 |
| --- | --- |
| 服务发现为空 | registry 类型、serviceName/group/version、租约 TTL、服务是否已经 start |
| wildcard 地址不可调用 | 是否配置了可达的 `advertisedHost` |
| 请求快速返回 overloaded | `maxPendingRequests`、`serverQueueCapacity`、业务线程数和调用方并发 |
| 新连接立即断开 | `maxConnections` 是否达到上限；检查旧连接是否已释放 |
| 停机时调用失败 | 客户端收到 GoAway 后会让 pending call 快速失败；重启后重新获取引用 |
| 请求没有自动重试 | 是否同时配置 `retryable=true` 和幂等声明；应用异常不会触发重试 |
| TLS 握手失败 | 协议是否匹配、证书链、trust store、hostname、mTLS 客户端证书 |
| 重启后旧引用失败 | stop/start 后必须重新调用 `refer` 获取引用 |
| 认证失败 | `requireAuthentication`、TLS、token 和服务端授权规则 |

## 10. 明确的生产边界

以下内容不属于本版遗漏，而是后续生产集成或协议演进项：

- 跨地域多活注册中心和跨注册中心复制；本版已提供基于 ZooKeeper 的多主机注册插件。
- 多连接池和跨 endpoint 的高级连接调度；本版已提供单 endpoint channel 复用、后台指数退避重连、服务端连接数配额和 GoAway 式优雅停机。
- OTLP exporter 已作为可选插件提供；collector、后端存储、告警规则、凭据和高可用由部署环境负责。
- 外部密钥管理、证书自动轮换、OCSP/CRL 策略。
- 面向应用 DTO 的额外 JSON 多态命名空间审批和 schema 治理。
- 线上的增量 streaming、服务端流控和协议级 backpressure；当前 `Flow.Publisher` 仅为应用层适配。
- 压缩、AES 等额外传输插件；如加入，必须扩展协议协商、密钥管理和帧边界测试。

这些能力应以新插件、新协议版本或明确的部署集成方式加入，不能绕过当前的帧边界、认证、生命周期和依赖方向约束。

## 11. 变更约束与后续维护

后续开发应遵循以下顺序：

1. 先修改 `common` 中的稳定模型或约束，再由 `api`、`kernel` 和插件逐层使用。
2. 新网络能力必须先定义协议版本、帧边界、失败分类和兼容策略。
3. 新插件必须通过 SPI 描述符接入，不在核心模块硬编码具体实现。
4. 涉及重试、超时、注册中心或安全的改动必须增加边界测试和失败路径测试。
5. 每次交付都从干净工作树执行 `mvn clean verify`，并检查 active 区域旧包名扫描结果为 0。
6. 发布源码包时排除 `archive/legacy` 和所有 `target` 构建产物。

## 12. 并行审查结论

第一版整合完成后，分别对架构/TLS、残余文件和 RPC 能力进行了独立审查，结论如下：

- **架构与 TLS：** 未发现 P0、P1 或已证实的 P2 缺陷；反射调用已兼容 Java 9+ 模块访问边界，TLS 使用证书链校验，不提供 trust-all 路径。
- **残余文件：** 根 Reactor 是唯一活跃构建入口；active 源码、POM 和 SPI 描述符没有旧包名或旧坐标；`archive/legacy` 未被根构建引用。
- **RPC 能力：** 已覆盖协议校验、服务方法边界、超时/重试分类、熔断/限流、过载、连接上限与 GoAway 停机、租约/watch、健康更新、TLS/mTLS、认证授权、异步取消和生命周期释放。
- **最终验证：** 以本次交付验证结果为准；测试数量随连接控制回归用例增加，失败数、错误数和跳过数均应为 0。

审查中确认的连接池、跨地域注册中心复制、collector/后端高可用、证书轮换和协议级 streaming 等内容，已列入“明确的生产边界”，不作为本版隐藏缺陷处理。

本交付文档与 [`README.md`](../README.md)、[`CONSTRAINTS.md`](../CONSTRAINTS.md) 一起构成第一版项目交接资料：README 面向快速上手，CONSTRAINTS 固化工程规则，本文件说明背景、范围、验收和运维边界。
