# Milvus SQL Gateway 总体重构清单

**评审人**: 架构师E  
**评审日期**: 2026-04-14  
**来源**: A (ShardingSphere源码优化分析) + B (项目重构点分析)  
**原则**: 坚守轻量级设计，不复用 SS-Proxy 完整前端，保持6层架构边界清晰

---

## P0 - 必须修复（生产可用性底线）

| 序号 | 标题 | 简述 | 意见 | 理由 | 难易程度 | 完成状态 |
|------|------|------|------|------|----------|----------|
| P0-1 | 修复非查询响应返回 EOF 包 | `MySQLResponseBuilder.buildQueryResponse()` 在无结果集时返回 `MySQLEofPacket`，应改为 `MySQLOKPacket` 并携带 `affectedRows`。 | **采纳** | 明确的 MySQL 协议语义错误，会导致 JDBC/CLI 在收到 UPDATE/USE 等无结果集响应时解析异常。 | 简单 | 已完成 |
| P0-2 | 接入 `caching_sha2_password` 认证插件 | `MilvusServerConfig` 默认使用 `caching_sha2_password`，但 `MilvusAuthHandler` 的 switch 语句将其 fallback 到 `NATIVE`，导致默认配置失效。 | **采纳** | 类 `MySQLCachingSha2PasswordAuthenticator` 已实现但从未注册，修复即可恢复 MySQL 8.0+ 默认兼容性。 | 简单 | 已完成 |
| P0-3 | 移除 `shardingsphere-proxy-frontend-mysql` 依赖 | `build.gradle.kts:49` 仍保留 SS-Proxy 前端依赖，会引入大量后端路由/分片无用传递依赖。 | **采纳** | 与"仅复用 SS 协议层、保持轻量"的核心设计目标直接冲突。当前只使用了其中 `ChannelAttrInitializer`，可内联或自研替换。 | 简单 | 已完成 |
| P0-4 | 增加 I/O 背压控制 (AutoRead) | `MilvusCommandDispatcher` 在命令执行期间从未调用 `setAutoRead(false)`，客户端发送过快时 Inbound ByteBuf 会无限堆积。 | **采纳** | 几行代码即可防御高并发慢查询场景下的 Direct Memory OOM，是生产可用性底线。 | 简单 | 已完成 |
| P0-5 | SQLExecutor 连接复用/缓存 | 每次查询都新建 `CalciteConnection` + `MilvusClientV2` + 枚举所有数据库，存在严重性能瓶颈。 | **采纳** | 这是当前系统吞吐量的最大瓶颈。高并发下频繁创建/销毁后端连接不可接受。 | 复杂 | 明确未完成 |
| P0-6 | Netty ServerBootstrap 参数优化 | 当前未配置 `TCP_NODELAY`、`SO_REUSEADDR`、`ALLOCATOR`，且 `SO_BACKLOG` 仅 128，应增加相关配置。 | **采纳** | B 分析中识别出的零成本、无副作用优化。`TCP_NODELAY` 降低 MySQL 小包延迟；`SO_REUSEADDR` 避免重启端口占用；`SO_BACKLOG` 应对突发连接；`ALLOCATOR` 确保池化 Direct Buffer。 | 简单 | 已完成 |

## P1 - 高优先级（兼容性/可维护性/资源安全）

| 序号 | 标题 | 简述 | 意见 | 理由 | 难易程度 | 完成状态 |
|------|------|------|------|------|----------|----------|
| P1-1 | 命令执行 Task 化 | 当前 SQL 直接在 Netty EventLoop 中同步执行，慢查询会阻塞 I/O 线程影响其他连接。 | **采纳** | 方向正确，能解耦 I/O 与业务线程。建议在 P0-5（连接复用）完成后实施，否则收益被连接创建开销掩盖。 | 复杂 | 明确未完成 |
| P1-2 | 结果集流式返回 | `MilvusComQueryExecutor` 一次性将所有结果行读到内存再构建 packet，存在 OOM 风险。 | **采纳** | 对 Milvus 向量大数据场景有价值。但需改动 `SQLExecutor.QueryResult` 为游标/迭代器模式，成本较高。 | 复杂 | 明确未完成 |
| P1-3 | 增加查询超时机制 | `SQLExecutor.execute()` 直接调用 `statement.execute(sql)`，无任何超时控制，慢查询会无限阻塞。 | **采纳** | 生产运维必备。可分两步：先通过 `statement.setQueryTimeout()` 实现基础超时；`KILL QUERY` 命令实现较复杂，可延后。 | 中等 | 已完成 |
| P1-4 | 统一系统变量和 SHOW 命令处理 | `SQLExecutor` 和 `SystemVariableHandler` 同时拦截 mock 系统变量查询，同一类查询有两套数据和两个入口。 | **采纳** | `SQLExecutor` 被 mock 逻辑严重污染，职责边界不清。统一迁移到 `SystemVariableHandler` 是必要清理。 | 简单 | 已完成 |
| P1-5 | 清理调试日志并引入 SLF4J | 大量 `System.err.println` 散布在 Handler 和 Builder 中，无法被日志框架管理，且高并发下影响性能。 | **采纳** | 项目已依赖 `slf4j-api` 但未使用。替换为 `LOGGER.debug/info/warn/error` 是生产化必要工作。 | 简单 | 已完成 |
| P1-6 | 删除死代码 `DebugPacketCodec` / `DebugPacketEncoder` | 两个调试类存在于代码库中，但 `MilvusChannelInitializer` 从未引用它们。 | **采纳** | 直接删除即可，零风险清理。 | 简单 | 已完成 |
| P1-7 | 增加执行器 `close()` 生命周期 | SS 的 `CommandExecutorTask` 会在 finally 中调用 `executor.close()`，当前项目中没有此机制，可能导致 JDBC 资源泄露。 | **采纳** | 应在 `CommandExecutor` 接口增加 `default close()`，并在 `MilvusCommandDispatcher` 的 try-finally 中调用。 | 简单 | 已完成 |
| P1-8 | `CompositeByteBuf` 安全释放 | `MySQLPacketCodecEngine` 处理大于 16MB 的分片包时会生成 `CompositeByteBuf`，当前统一调用 `buffer.release()` 可能释放不彻底。 | **采纳** | 超大 SQL 场景下的内存安全防御措施，成本低。 | 简单 | 已完成 |
| P1-9 | 慢查询空闲检测保护 | `MilvusAuthHandler` 收到 `IdleStateEvent` 后直接关闭连接，未判断当前连接是否正在执行查询，慢查询可能被误杀。 | **采纳** | 长查询场景下的真实可用性问题。需引入 `QueryContext` 记录执行状态，空闲检测时先检查是否 idle。 | 中等 | 明确未完成 |
| P1-10 | 提取 E2E 测试公共基类 | 5 个 E2E 测试类中存在大量重复的 JDBC URL 拼接和 MySQL 8.0+ init query 容错逻辑。 | **采纳** | 提取 `AbstractMySQLE2ETest` 基类，减少重复样板代码，提升测试可维护性。 | 简单 | 明确未完成 |
| P1-11 | `ConnectionSession` 解耦 `Channel` | 会话层直接持有 Netty `Channel` 引用，增加了层间耦合，且 `Channel` 仅在日志中使用。 | **采纳** | 移除 `Channel` 字段，日志直接使用 `ctx.channel()` 获取即可。 | 简单 | 已完成 |
| P1-12 | 提取 `CommandExecutorFactory` 与 `CommandPacketFactory` | `MilvusCommandDispatcher` 内部硬编码了 `createCommandPacket()` 和 `createExecutor()`，同时承担了解析和路由职责。 | **采纳** | 合理的内部分层优化。提取后 Dispatcher 更聚焦"分发+写响应"，新增命令类型时不影响分发逻辑。 | 简单 | 已完成 |

## P2 - 中优先级（生产化细节/协议完整性/测试补强）

| 序号 | 标题 | 简述 | 意见 | 理由 | 难易程度 | 完成状态 |
|------|------|------|------|------|----------|----------|
| P2-1 | 事务状态管理 | `ConnectionSession` 中没有事务状态字段，OK 包中硬编码 `autoCommit=true, inTransaction=false`。 | **采纳** | Milvus 本身不支持事务，但 JDBC 连接池会读取 autocommit 标志。增加状态字段并正确返回 status flags 是兼容性优化。 | 中等 | 明确未完成 |
| P2-2 | `CLIENT_DEPRECATE_EOF` 动态判断 | 当前 `MySQLResponseBuilder` 中 `deprecateEof` 参数硬编码为 `false`，永远使用 EOF 包。 | **采纳** | 当前策略（强制 EOF）已证明兼容性良好。动态判断是协议正确性优化，但非紧迫，可后续平滑升级。 | 中等 | 明确未完成 |
| P2-3 | 字符集与 Column Definition 联动 | `MilvusAuthHandler` 已将客户端字符集写入 Channel Attribute，但 `MySQLResponseBuilder` 中字符集固定写死为 `CHARSET_UTF8MB4 = 45`。 | **采纳** | 当前所有场景使用 UTF-8，硬编码没有问题。如未来需要支持非 UTF-8 客户端，再做联动。 | 简单 | 明确未完成 |
| P2-4 | Max Packet Size 限制 | `max_allowed_packet` 系统变量仅在 mock 中定义，没有任何真实限制逻辑。 | **采纳** | 防御性措施。在 `MilvusServerConfig` 中增加配置，并在 Dispatcher 解析 SQL 前校验 payload 大小。 | 简单 | 明确未完成 |
| P2-5 | 异常分类处理 | 所有异常统一记录为 ERROR 级别，导致用户输入错误（如 SQL 语法错误）淹没真正的系统故障告警。 | **采纳** | 生产日志质量优化。定义预期异常集合（如语法错误、数据库不存在），这些异常降级为 warn/debug。 | 中等 | 已完成 |
| P2-6 | 增加 Dispatcher 和 ResponseBuilder 的单元测试 | 当前缺少 `MilvusCommandDispatcher` 和 `MySQLResponseBuilder` 的独立单元测试，重构时缺乏回归保护。 | **采纳** | 使用 Netty `EmbeddedChannel` 和 packet 类型断言补强测试。 | 中等 | 明确未完成 |
| P2-7 | 重构或移除 `RawSocketTest` | 该类被 `@Disabled`，且测试依赖人工阅读 stderr 输出，没有清晰的自动化断言。 | **采纳** | 建议重构为带断言的协议单元测试，或移入 `src/test/java/.../debug/` 目录并明确标记为手动调试工具。 | 简单 | 部分完成 |
| P2-8 | SSL/TLS 完善测试 | 已有 `MilvusSSLRequestHandler` 和 `MilvusMySQLSslE2ETest`，但可能覆盖不完整。 | **采纳** | 保持现有实现，补充完整测试用例确保自签名证书流程在各种客户端下稳定。 | 中等 | 部分完成 |
| P2-9 | 配置 `WRITE_BUFFER_WATER_MARK` | 当前未配置 Netty 写缓冲区水位线，无法利用 `channel.isWritable()` 进行 TCP 背压控制。 | **采纳** | 需配合 P1-1（Task 化）和 P3-9（`channelWritabilityChanged`）才能发挥完整作用。单独配置也有防御价值，建议设定为 8MB/16MB。 | 简单 | 明确未完成 |
| P2-10 | `MilvusServerConfig` 增加 `workerThreads` | 当前 `workerGroup` 使用 Netty 默认线程数（CPU * 2），在高核心数机器上缺少运维调优入口。 | **采纳** | 改动成本低，允许运维根据部署环境手动调整 Netty worker 线程数。 | 简单 | 明确未完成 |

## P3 - 低优先级/未来扩展

| 序号 | 标题 | 简述 | 意见 | 理由 | 难易程度 | 完成状态 |
|------|------|------|------|------|----------|----------|
| P3-1 | Prepared Statement 二进制协议支持 | 当前 `COM_STMT_PREPARE` / `EXECUTE` 未处理，JDBC `PreparedStatement` 靠驱动 fallback 到文本协议模拟。 | **采纳（延后）** | MEMORY 和 CLAUDE.md 均明确排除。ORM 兼容性需求高，但实现成本高（2-3 天），建议专门迭代。 | 复杂 | 明确未完成 |
| P3-2 | 协议压缩支持 | MySQL 协议支持通过 `CLIENT_COMPRESS` 协商启用 zlib 压缩，当前未支持。 | **采纳（延后）** | 向量结果集远程传输有价值，但实现成本高（1-2 天），非当前重点。 | 复杂 | 明确未完成 |
| P3-3 | 多结果集返回 | `allowMultiQueries=true` 时 JDBC 驱动可能解析异常，当前只返回单个结果集。 | **采纳（延后）** | MEMORY 明确排除。Milvus 场景以单条查询为主。 | 复杂 | 明确未完成 |
| P3-4 | 连接属性 (Connection Attributes) | MySQL 5.6+ 支持客户端在握手响应中发送连接属性，当前未读取。 | **采纳（延后）** | 运维友好但非功能必需，实现成本低，可在后续小版本中顺带加入。 | 简单 | 明确未完成 |
| P3-5 | `LOAD DATA LOCAL INFILE` 支持 | 标准 MySQL 数据批量导入命令，当前协议层没有相关 Handler。 | **采纳（延后）** | 高价值但高成本（2-3 天），建议后续专门迭代。 | 复杂 | 明确未完成 |
| P3-6 | 查询缓存/结果缓存 | 当前每次 `COM_QUERY` 都直接透传到 Calcite + Milvus，无结果集缓存层。 | **采纳（延后）** | 可减少重复查询的后端负载，但引入缓存一致性、失效策略等复杂度。建议后续专门优化。 | 复杂 | 明确未完成 |
| P3-7 | 二进制结果集协议 | 当前统一使用 `MySQLTextResultSetRowPacket`，无二进制行包构建逻辑。 | **采纳（延后）** | 与 P3-1（Prepared Statement）配套使用，单独实现收益有限。 | 中等 | 明确未完成 |
| P3-8 | MDC 日志追踪 | 高并发场景下缺少 database/username 等上下文，日志交叉混乱。 | **采纳（延后）** | 若未来实现 Task 化（P1-1），可顺带加入。当前单线程 EventLoop 模型下日志交叉问题不严重。 | 简单 | 明确未完成 |
| P3-9 | `channelWritabilityChanged` 资源锁 | 当客户端消费速度慢时，Netty 发送缓冲区满会导致 `ctx.write()` 不断堆积内存。 | **采纳（延后）** | 仅在流式写大数据 + 客户端消费慢的场景下有价值。当前紧迫性低。 | 复杂 | 明确未完成 |
| P3-10 | `ServerStatusFlagCalculator` | 当前 `MySQLResponseBuilder` 硬编码状态标志，缺少统一的动态计算工具。 | **采纳（延后）** | 可与 P2-1（事务状态管理）合并实现，单独优先级不高。 | 简单 | 明确未完成 |

## 拒绝/暂缓项

| 序号 | 标题 | 简述 | 意见 | 理由 | 难易程度 | 完成状态 |
|------|------|------|------|------|----------|----------|
| R-1 | Dispatcher 手动解析 ByteBuf | B 认为 `MilvusCommandDispatcher` 接收 `ByteBuf` 而非 `MySQLCommandPacket` 是架构偏离。 | **拒绝** | `shardingsphere-database-protocol-core` 仅提供帧编解码和包 POJO，命令解析工厂在 `shardingsphere-proxy-frontend-mysql` 中。既然我们的核心设计是**不引入 proxy-frontend**，手动解析 ByteBuf 是**正确且必然的实现**，不是缺陷。 | 不适用 | 不适用 |
| R-2 | 统一前端入口 Handler | A/B 建议创建单一 `MilvusFrontendHandler` 替代当前的 `MilvusAuthHandler` + `MilvusCommandDispatcher`。 | **拒绝** | SS-Proxy 使用单一 Handler 是为了承载复杂状态机（熔断、限流等）。我们的 6 层架构** intentionally **将认证和命令分发分离，职责边界更清晰。统一 Handler 会增加不必要的耦合。 | 简单 | 已完成 |
| R-3 | 用 Channel Attribute 替代 `SESSION_KEY` | A 建议将 `ConnectionSession` 的存储机制从自定义 `AttributeKey` 改为 SS 的 Channel Attribute。 | **拒绝** | 当前 `ConnectionSession` 绑定到 Channel Attribute 是清晰做法。建议将字符集、事务状态等上下文聚合到 `ConnectionSession` 内部，而不是改变 Attribute 存储机制。 | 不适用 | 不适用 |
| R-4 | 真正支持 Session Tracking | B 建议在 OK 包中追加 session state info 并移除 `CLIENT_SESSION_TRACK` 的 mask。 | **拒绝** | 当前 mask 掉 `CLIENT_SESSION_TRACK` 是**有意为之**。轻量级网关不需要 session state tracking，支持它会无意义地增加 OK 包构建复杂度，与轻量级目标相悖。 | 不适用 | 不适用 |
| R-5 | Epoll 支持 | B 分析中提到 SS 使用 `EpollEventLoopGroup` 在 Linux 下获得更好性能。 | **拒绝** | 增加平台相关代码和条件编译复杂度。Milvus 网关以跨平台部署为主，NIO 已足够。如未来有 Linux 极致性能需求再考虑。 | 不适用 | 不适用 |
| R-6 | SS 双级线程池模型 | SS 使用 `ConnectionThreadExecutorGroup` / `UserExecutorGroup` 为分片事务和多租户设计。 | **拒绝** | 这是为 SS-Proxy 复杂后端（分片、事务）设计的线程模型。Milvus 单数据源场景无需此复杂度，一个简单的固定线程池（Task 化）即可满足需求。 | 不适用 | 不适用 |
| R-7 | 默认开启 Netty `LoggingHandler` | SS 在 `ServerBootstrap` 中默认配置了 `LoggingHandler(LogLevel.INFO)`。 | **拒绝** | 生产环境会输出大量连接/断开日志，增加 I/O 开销。除非有明确的调试需求，否则不建议默认开启。保持当前无日志 Handler 的配置即可。 | 不适用 | 不适用 |
| R-8 | `FrontendChannelLimitationInboundHandler` 连接数硬限制 | SS 通过返回 `TooManyConnections` 错误包后强制 close 来限制连接数，且源码中有 TODO 标注这不是真实数据库的做法。 | **拒绝** | 硬限制后强制关闭连接不是优雅的限流方式。当前 `maxConnections` 已存在但未使用，建议采用更轻量的连接数监控，而非 SS 式的强制拒绝。 | 不适用 | 不适用 |

---

## 关键架构决策摘要

1. **手动解析 ByteBuf 不是缺陷**：`shardingsphere-database-protocol-core` 不提供命令包解析，命令解析属于 `proxy-frontend-mysql`。不引入 proxy-frontend 的前提下，`MilvusCommandDispatcher` 手动解析是正确实现。
2. **不合并 FrontendHandler**：SS-Proxy 的单一 Handler 模式是为承载复杂状态机（熔断、限流）设计的。我们的 6 层架构将 Auth 和 Dispatch 分离为两个 Handler，是轻量级网关的合理选择。
3. **先修连接复用，再 Task 化/流式化**：当前最大性能瓶颈是每次查询重建 Calcite 连接。如果不先解决 P0-5，Task 化和流式返回的收益会被连接创建开销严重稀释。
4. **继续 mask Session Tracking**：轻量级网关不需要在 OK 包中追加 session state info，支持它只会增加无意义的复杂度。
5. **Netty 参数走轻量调优，不照搬 SS 全套**：`TCP_NODELAY`、`SO_REUSEADDR`、`SO_BACKLOG`、`ALLOCATOR` 等是零成本收益项，应立即采纳。`Epoll`、`双级线程池`、`默认 LoggingHandler`、SS 式的连接数硬限制属于 SS-Proxy 特有或过度设计，不适合轻量级网关。
6. **背压控制分阶段实施**：最低成本且必须的防御是 `setAutoRead(false/true)`（P0-4）。`WRITE_BUFFER_WATER_MARK` + `channelWritabilityChanged` + `ConnectionResourceLock` 的完整背压闭环需在 Task 化和流式查询实现后再引入。

---

## 架构师推荐待审批项

基于 **收益/成本比** 和 **架构覆盖度**（协议兼容性、性能、资源安全、可观测性），从 P0/P1/P2 中推荐以下 **6 项** 进入下一阶段审批与实施队列：

| 推荐项 | 标题 | 难易程度 | 推荐理由 |
|--------|------|----------|----------|
| **P0-4** | 增加 I/O 背压控制 (AutoRead) | 简单 | **生产可用性底线**。仅 2-3 行代码即可防御高并发慢查询导致的 Direct Memory OOM，属于零成本、高收益的防御性优化。 |
| **P0-6** | Netty ServerBootstrap 参数优化 | 简单 | **性能与稳定性双收益**。`TCP_NODELAY` + `SO_REUSEADDR` + `SO_BACKLOG` + `ALLOCATOR` 均为纯配置项改动，零副作用，直接提升网络层性能和可运维性。 |
| **P1-3** | 增加查询超时机制 | 中等 | **生产运维必备**。基础版仅需 `statement.setQueryTimeout()` 即可实现，成本可控但能避免慢查询无限阻塞导致的服务雪崩。 |
| **P1-7** | 增加执行器 `close()` 生命周期 | 简单 | **资源安全补丁**。在 `CommandExecutor` 接口增加 `default close()` 并在 Dispatcher 中调用，几行代码即可消除 JDBC 资源泄露风险。 |
| **P1-8** | `CompositeByteBuf` 安全释放 | 简单 | **内存安全防御**。针对 >16MB 超大 SQL 分片包的内存泄露风险，属于低成本的防御性编程，改动面极小。 |
| **P2-5** | 异常分类处理 | 中等 | **可观测性提升**。定义预期异常集合并降级日志级别，能避免用户语法错误淹没真正的系统故障告警，显著提升生产排障效率。 |

### 推荐组合逻辑

- **2 项 P0 + 2 项 P1 + 1 项 P2**：覆盖了网络层稳定性（P0-4/P0-6）、执行层资源安全（P1-3/P1-7/P1-8）和可观测性（P2-5）。
- **全部为简单或中等难度**：在 1 天内可以完成大部分，2 天内可以全部完成，不会阻塞其他长周期任务（如 P0-5 连接复用）。
- **不推荐的项（现阶段）**：
  - **P0-5（连接复用）**：虽然优先级最高，但属于"复杂"级别，需要独立排期，不应与上述短周期项混在同一批次。
  - **P1-1（Task 化）/ P1-2（流式返回）**：均依赖 P0-5 先完成，否则收益被连接创建开销稀释。
  - **P2-6（Dispatcher 单元测试）**：推荐度次于 P2-5，当前阶段优先修复生产可用性问题，测试补强可紧随其后。

---

## C 工程师完成状态说明

### 已完成项说明

- **P0-1 修复非查询响应返回 EOF 包**：已删除 `MySQLResponseBuilder.buildQueryResponse()` 中的 `if (!result.isResultSet())` 分支，非结果集命令（如 USE、SET）改由 `MilvusComQueryExecutor` / `MilvusComInitDbExecutor` 直接返回 `MySQLResponseBuilder.buildOKPacket()`。
  - **收益**：修复 MySQL 协议语义错误，使 JDBC/CLI 对 USE/SET 等无结果集命令正确解析，提升协议兼容性。

- **P0-2 接入 `caching_sha2_password` 认证插件**：在 `MilvusAuthHandler.createAuthenticator()` 的 switch 语句中补充 `case CACHING_SHA2_PASSWORD`，返回已实现的 `MySQLCachingSha2PasswordAuthenticator`；同步修复 `RawSocketTest` 握手响应长度以适配新默认插件。
  - **收益**：修复默认认证插件配置失效问题，恢复 MySQL 8.0+ JDBC 驱动的默认连接兼容性，降低用户连接失败率。

- **P0-3 移除 `shardingsphere-proxy-frontend-mysql` 依赖**：从 `build.gradle.kts` 中删除该依赖；新建自研 `ChannelAttrInitializer.java` 内联替换原先引用的单类；显式补充 `bcpkix-jdk18on:1.78` 以保持 SSL 证书生成能力。
  - **收益**：消除对 SS-Proxy 后端（路由、分片等）的间接依赖，降低部署包体积和类路径冲突风险，践行轻量级设计目标。

- **P1-4 统一系统变量和 SHOW 命令处理**：`SQLExecutor.execute()` 中原有的 `SELECT @@variable`、`SHOW VARIABLES`、`SHOW TABLES` 等 mock 逻辑已全部移除，统一收口到 `SystemVariableHandler.java`；新增 `SystemVariables` 常量类集中管理默认值。
  - **收益**：统一系统变量数据源，消除 `SQLExecutor` 的职责污染，避免跨文件重复定义导致的数据不一致，降低后续维护成本。

- **P1-5 清理调试日志并引入 SLF4J**：`MilvusAuthHandler`、`MilvusCommandDispatcher`、`SQLExecutor`、`MySQLResponseBuilder` 中所有 `System.err.println` 已替换为 `LOGGER.debug/warn/error`。
  - **收益**：使日志可被日志框架统一收集、轮转和过滤，提升生产环境可运维性；消除高并发下 `stderr` 的潜在性能瓶颈。

- **P1-6 删除死代码**：`DebugPacketCodec.java` 和 `DebugPacketEncoder.java` 已从仓库中删除。
  - **收益**：减少代码库噪音和编译维护成本，避免误导新开发者。

- **P1-11 `ConnectionSession` 解耦 `Channel`**：`ConnectionSession` 已移除 `Channel` 字段、构造参数及 getter，构造函数简化为 `ConnectionSession(int connectionId, String defaultDatabase)`。
  - **收益**：降低会话层与网络层的耦合，提升单元测试的可 mock 性，使架构分层更清晰。

- **P1-12 提取 CommandExecutorFactory 与 CommandPacketFactory**：新建 `MilvusCommandExecutorFactory.java` 和 `MilvusCommandPacketFactory.java`，`MilvusCommandDispatcher` 不再内联解析和创建逻辑。
  - **收益**：减少 `Dispatcher` 的职责膨胀，新增命令类型时无需改动分发主逻辑，提升代码的可扩展性。

- **P0-4 增加 I/O 背压控制 (AutoRead)**：在 `MilvusCommandDispatcher.handleCommandPacket()` 中，命令执行前调用 `ctx.channel().config().setAutoRead(false)`，执行完成后在 finally 块中恢复 `setAutoRead(true)`。
  - **收益**：防御高并发慢查询场景下客户端发送过快导致的 Inbound ByteBuf 无限堆积，避免 Direct Memory OOM。

- **P0-6 Netty ServerBootstrap 参数优化**：在 `MilvusMySQLServer.createBootstrap()` 中增加 `TCP_NODELAY=true`、`SO_REUSEADDR=true`（服务端 socket 级别）、`ALLOCATOR=PooledByteBufAllocator.DEFAULT`，并将 `SO_BACKLOG` 默认值提高到 1024（从 `MilvusServerConfig` 读取）。
  - **收益**：降低 MySQL 小包网络延迟、避免重启端口占用、提升突发连接承载能力、确保使用池化 Direct Buffer。

- **P1-3 增加查询超时机制**：`MilvusServerConfig` 新增 `queryTimeoutSeconds`（默认 30，0 表示无超时）；`SQLExecutor` 构造方法接收该值，在 `execute()` 中对 `Statement` 调用 `setQueryTimeout(timeoutSeconds)`。
  - **收益**：避免慢查询无限阻塞，防止单条慢查询拖垮服务，提升生产可运维性。

- **P1-7 增加执行器 `close()` 生命周期**：在 `CommandExecutor` 接口中新增 `default void close() throws SQLException {}`；在 `MilvusCommandDispatcher.handleCommandPacket()` 的 try-finally 中确保调用 `executor.close()`。
  - **收益**：消除 JDBC 资源泄露风险，与 SS 的 `CommandExecutorTask` 生命周期对齐。

- **P1-8 `CompositeByteBuf` 安全释放（精简方案）**：恢复使用 SS 官方 `MySQLPacketCodecEngine`，仅在 `MilvusCommandDispatcher` 消费端新增 `safeRelease(ByteBuf buffer)` 方法。对 `CompositeByteBuf` 执行 `skipBytes` + `discardReadComponents` 后再调用 `release()`，确保分片包彻底释放。
  - **收益**：以最小改动面解决 >16MB 超大 SQL 分片包的内存安全释放问题，避免内存泄漏。

- **P2-5 异常分类处理**：在 `MilvusCommandDispatcher` 中新增 `isExpectedSqlException()` 方法，根据 SQLState（42000/42S02/42S22）和 ErrorCode（1049/1146/1054/1064）判断预期异常（语法错误、库表不存在等），将其日志降级为 `warn`，系统异常仍保持 `error`。
  - **收益**：避免用户输入错误淹没真正的系统故障告警，显著提升生产环境的可观测性和排障效率。

### 部分完成项说明

- **P2-7 重构或移除 `RawSocketTest`**：修复了 `testRawSocketMySQLCLI` 因 auth response 长度为 0 触发 auth switch 导致超时的 bug，测试现已通过。但未按建议移入 debug 目录或重构为纯断言驱动。
  - **收益**：修复协议单元测试在默认认证插件变更后的超时问题，保障 CI 稳定性。

- **P2-8 SSL/TLS 完善测试**：通过预热 `MilvusSslContextFactory` 和改用动态端口，修复了 `MilvusMySQLSslE2ETest` 的偶发连接超时。但尚未补充多种客户端/证书场景下的完整覆盖。
  - **收益**：消除 SSL E2E 测试的偶发超时，提高 CI 可靠性。

### 冲突项特别说明

- **R-2 统一前端入口 Handler**：此项 E 建议拒绝，但用户已审批通过（出现在用户的最终执行 checklist 中）。代码中 `MilvusFrontendHandler.java` 已实际存在，负责将 `channelRead` 路由给 `MilvusAuthHandler`（握手前）或 `MilvusCommandDispatcher`（握手后）。
  - **收益**：以极轻量的方式将认证与命令分发的路由收敛到单一 Handler，简化 Netty Pipeline 结构，为后续连接级监控或限流提供统一入口。

---

## 架构师评审确认

针对以下 6 项已完成的重构， architecture review conclusions and residual risk assessment are as follows:

| 序号 | 标题 | 评审结论 | 残余风险评估 |
|------|------|----------|--------------|
| **P0-4** | 增加 I/O 背压控制 (AutoRead) | **通过**。`MilvusCommandDispatcher.handleCommandPacket()` 在执行前正确调用 `setAutoRead(false)`，在 finally 块中恢复 `setAutoRead(true)`，符合 Netty 背压控制的最佳实践。 | 低。当前实现能防御 Inbound ByteBuf 堆积，但慢查询仍同步阻塞 EventLoop（P1-1 Task 化尚未完成），极端高并发下连接间仍有互相影响的可能。 |
| **P0-6** | Netty ServerBootstrap 参数优化 | **通过**。`MilvusMySQLServer.createBootstrap()` 已完整配置 `TCP_NODELAY`、`SO_REUSEADDR`、`SO_BACKLOG` 和 `PooledByteBufAllocator.DEFAULT`，均为零副作用的纯性能/稳定性提升项。 | 极低。这些参数本身无引入新 bug 的风险。完整的 TCP 背压闭环仍需后续配合 `WRITE_BUFFER_WATER_MARK` 和 `channelWritabilityChanged` 实现。 |
| **P1-3** | 增加查询超时机制 | **通过**。`SQLExecutor.execute()` 中通过 `statement.setQueryTimeout(queryTimeoutSeconds)` 实现了基础超时控制，配置默认值 30 秒合理。 | 中。`setQueryTimeout()` 依赖 JDBC 驱动实现，通常只能中断客户端网络等待，无法真正"杀掉" Milvus 后端正在执行的查询。若需彻底 kill backend query，后续仍需补充 `KILL QUERY` 机制。 |
| **P1-7** | 增加执行器 `close()` 生命周期 | **通过**。`CommandExecutor` 接口新增 `default void close()`，并在 `MilvusCommandDispatcher` 的 try-finally 中确保调用，与 SS 生命周期对齐。 | 低。当前各执行器大多无需要显式关闭的资源（JDBC Statement/ResultSet 由 `SQLExecutor` 自行管理），但若未来新增持有外部连接或文件句柄的执行器，必须记得覆盖 `close()`。 |
| **P1-8** | `CompositeByteBuf` 安全释放 | **通过**。采纳了精简方案：恢复使用 SS 官方 `MySQLPacketCodecEngine`，仅在消费端 `MilvusCommandDispatcher` 中通过 `safeRelease()` 对 `CompositeByteBuf` 执行 `skipBytes` + `discardReadComponents` 后再 `release()`，改动面最小且有效。 | 低。这是 consumer-side 的防御性修补，已验证可彻底释放分片包组件。若未来升级 Netty/SS 版本导致 `CompositeByteBuf` 内部语义变化，需重新验证，但当前方案不依赖特定版本细节。 |
| **P2-5** | 异常分类处理 | **通过**。`isExpectedSqlException()` 基于 SQLState（42000/42S02/42S22）和 ErrorCode（1049/1146/1054/1064）准确覆盖了语法错误、库表/列不存在等常见用户输入错误，日志降级策略合理。 | 低。异常分类基于启发式规则，边缘场景（如某些驱动特有的错误码）可能仍被记为 ERROR，但主要噪声源已被过滤，不影响系统稳定性。 |

### 总体评审意见

上述 6 项重构均已完成并达到合并标准。其中：
- **P0-4 / P0-6** 属于网络层防御，代码简单、收益明确，建议立即合入。
- **P1-3 / P1-7 / P1-8** 属于执行层资源安全，实现了低成本高价值的防护，建议立即合入。
- **P2-5** 属于可观测性提升，对生产排障效率有显著帮助，建议立即合入。

**整体残余风险可控**，主要遗留问题（连接复用 P0-5、Task 化 P1-1、流式返回 P1-2）不在本批次范围内，不影响当前 6 项的独立交付。
