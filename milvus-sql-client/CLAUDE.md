Ready to code?

Here is Claude's plan:
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
Milvus SQL Client - 轻量级 MySQL Frontend Server 重构计划

背景与目标

当前 milvus-sql-client 模块实现了基于 Netty 的 MySQL 协议服务器，但 MySQLProtocolHandler 混合了认证、命令分发、响应编码等多重职责，难以维护。

参考 ShardingSphere-Proxy 的前端分层架构，重构为一个职责清晰、易于扩展的轻量级 MySQL Frontend Server。

设计原则

1. 分层清晰：每层只负责一个职责，通过接口交互
2. 复用 ShardingSphere 协议层：使用其 MySQL 协议编解码，但自研轻量级执行层
3. 保持轻量：不引入 ShardingSphere-Proxy 的完整后端（分片、路由等）
4. 可测试：每层可独立测试

目标架构（6层）

┌─────────────────────────────────────────────────────────────────┐
│ 客户端 (mysql-cli / JDBC)                                        │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ 第1层: Network Layer                                            │
│ 类: MilvusMySQLServer                                           │
│ 职责: TCP连接管理, ChannelPipeline初始化                         │
│ 输出: ByteBuf                                                   │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ 第2层: Protocol Layer (复用 ShardingSphere)                      │
│ 类: MySQLPacketCodecEngine                                       │
│ 职责: MySQL协议包编解码                                           │
│ 输出: MySQLCommandPacket                                        │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ 第3层: Authentication Layer                                     │
│ 类: MilvusAuthHandler                                           │
│ 职责: 握手认证, 会话创建                                          │
│ 输出: 认证通过 → ConnectionSession                               │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ 第4层: Command Dispatch Layer                                   │
│ 类: MilvusCommandDispatcher                                     │
│ 职责: 命令识别与路由                                              │
│ 输出: 对应 CommandExecutor                                       │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ 第5层: Command Execution Layer                                  │
│ 类: *CommandExecutor (QueryExecutor/PingExecutor等)              │
│ 职责: 业务逻辑执行, 调用 SQLExecutor                             │
│ 输出: ExecutionResult                                           │
└─────────────────────────────────────────────────────────────────┘
│
▼
┌─────────────────────────────────────────────────────────────────┐
│ 第6层: Response Encode Layer                                    │
│ 类: MySQLResponseBuilder                                        │
│ 职责: 结果集编码为 MySQL 协议包                                   │
│ 输出: DatabasePacket[]                                          │
└─────────────────────────────────────────────────────────────────┘

注：暂不考虑 SSL/TLS 支持（未来可扩展）

代码结构变更

新目录结构

milvus-sql-client/src/main/java/org/apache/calcite/adapter/milvus/sql/client/
├── bootstrap/
│   └── MilvusMySQLServerBootstrap.java          # 入口（简化）
│
├── server/
│   └── MilvusMySQLServer.java                   # Netty Server（简化）
│
├── handler/
│   ├── MilvusChannelInitializer.java            # ChannelPipeline 初始化
│   ├── MilvusAuthHandler.java                   # 认证层（原 handshake 逻辑）
│   └── MilvusCommandDispatcher.java             # 命令分发层
│
├── command/
│   ├── CommandExecutor.java                     # 执行器接口
│   ├── MilvusComQueryExecutor.java              # SQL 查询执行
│   ├── MilvusComPingExecutor.java               # PING 处理
│   ├── MilvusComInitDbExecutor.java             # USE db 处理
│   ├── MilvusComQuitExecutor.java               # QUIT 处理
│   └── MilvusComUnsupportedExecutor.java        # 不支持命令处理
│
├── response/
│   └── MySQLResponseBuilder.java                # 响应包构建工具
│
├── session/
│   └── ConnectionSession.java                   # 连接会话（简化）
│
├── config/
│   └── MilvusServerConfig.java                  # 服务器配置（保留）
│
└── executor/
└── SQLExecutor.java                         # Calcite 集成（保留）

删除的文件

- MySQLProtocolHandler.java - 职责拆分后删除
- MySQLCodecEngine.java - 直接使用 ShardingSphere codec

关键类设计

1. MilvusChannelInitializer

public class MilvusChannelInitializer extends ChannelInitializer<SocketChannel> {

     @Override
     protected void initChannel(SocketChannel ch) {
         ch.pipeline()
             // Protocol Layer: ShardingSphere 编解码
             .addLast(new PacketCodec(new MySQLPacketCodecEngine()))

             // Authentication Layer
             .addLast(new MilvusAuthHandler(config))

             // Command Layer
             .addLast(new MilvusCommandDispatcher(config));
     }
}

2. MilvusAuthHandler

public class MilvusAuthHandler extends ChannelInboundHandlerAdapter {

     private boolean handshakeComplete = false;
     private MySQLAuthenticationPluginData authPluginData;

     @Override
     public void channelActive(ChannelHandlerContext ctx) {
         // 发送 Handshake 包
         sendHandshake(ctx);
     }

     @Override
     public void channelRead(ChannelHandlerContext ctx, Object msg) {
         if (!handshakeComplete) {
             processHandshakeResponse(ctx, (ByteBuf) msg);
         } else {
             // 认证完成，传递给下一层
             ctx.fireChannelRead(msg);
         }
     }

     private void sendHandshake(ChannelHandlerContext ctx) {
         int connectionId = generateConnectionId();
         authPluginData = new MySQLAuthenticationPluginData();
         MySQLHandshakePacket handshake = new MySQLHandshakePacket(
             connectionId, false, authPluginData);
         ctx.writeAndFlush(handshake);
     }

     private void processHandshakeResponse(ChannelHandlerContext ctx, ByteBuf buffer) {
         // 解析响应，验证（当前接受任何凭据）
         // 创建 ConnectionSession 并绑定到 channel
         // 发送 OK 包
         // 移除自己或标记 handshakeComplete
     }
}

3. MilvusCommandDispatcher

public class MilvusCommandDispatcher extends ChannelInboundHandlerAdapter {

     private final MilvusServerConfig config;
     private final SQLExecutor sqlExecutor;

     @Override
     public void channelRead(ChannelHandlerContext ctx, Object msg) {
         if (!(msg instanceof MySQLCommandPacket)) {
             return;
         }

         MySQLCommandPacket command = (MySQLCommandPacket) msg;
         CommandExecutor executor = createExecutor(command, ctx);

         try {
             Collection<DatabasePacket> response = executor.execute();
             for (DatabasePacket packet : response) {
                 ctx.write(packet);
             }
             ctx.flush();
         } catch (SQLException e) {
             ctx.writeAndFlush(MySQLErrPacketFactory.newInstance(e));
         }
     }

     private CommandExecutor createExecutor(MySQLCommandPacket command,
                                            ChannelHandlerContext ctx) {
         ConnectionSession session = ctx.channel().attr(SESSION_KEY).get();

         if (command instanceof MySQLComQueryPacket) {
             return new MilvusComQueryExecutor(
                 (MySQLComQueryPacket) command, session, sqlExecutor);
         } else if (command instanceof MySQLComPingPacket) {
             return new MilvusComPingExecutor();
         } else if (command instanceof MySQLComInitDbPacket) {
             return new MilvusComInitDbExecutor(
                 (MySQLComInitDbPacket) command, session);
         } else if (command instanceof MySQLComQuitPacket) {
             return new MilvusComQuitExecutor(ctx);
         }
         return new MilvusComUnsupportedExecutor(command);
     }
}

4. CommandExecutor 接口

public interface CommandExecutor {
Collection<DatabasePacket> execute() throws SQLException;
}

5. MilvusComQueryExecutor

public class MilvusComQueryExecutor implements CommandExecutor {

     private final String sql;
     private final ConnectionSession session;
     private final SQLExecutor sqlExecutor;

     @Override
     public Collection<DatabasePacket> execute() throws SQLException {
         QueryResult result = sqlExecutor.execute(sql);
         return MySQLResponseBuilder.buildQueryResponse(result);
     }
}

6. MySQLResponseBuilder

public final class MySQLResponseBuilder {

     public static Collection<DatabasePacket> buildQueryResponse(QueryResult result) {
         List<DatabasePacket> packets = new ArrayList<>();

         // Field count
         packets.add(new MySQLFieldCountPacket(result.getColumns().size()));

         // Column definitions
         for (ColumnInfo column : result.getColumns()) {
             packets.add(createColumnDefPacket(column));
         }

         // Rows
         for (List<Object> row : result.getRows()) {
             packets.add(new MySQLTextResultSetRowPacket(row));
         }

         // EOF/OK
         packets.add(new MySQLOKPacket(0, 0, SERVER_STATUS_AUTOCOMMIT));

         return packets;
     }

     public static DatabasePacket buildOKPacket(int affectedRows) {
         return new MySQLOKPacket(affectedRows, 0, SERVER_STATUS_AUTOCOMMIT);
     }

     public static DatabasePacket buildErrorPacket(String message, int errorCode) {
         return new MySQLErrPacket(errorCode, "42000", message);
     }
}

7. ConnectionSession（简化版）

public class ConnectionSession {
private final int connectionId;
private final Channel channel;
private String currentDatabase;
private volatile boolean authenticated;

     // getters/setters
}

依赖调整

build.gradle.kts

dependencies {
api(project(":milvus"))
api(project(":core"))

     // Netty
     implementation("io.netty:netty-common:$nettyVersion")
     implementation("io.netty:netty-transport:$nettyVersion")
     implementation("io.netty:netty-handler:$nettyVersion")
     implementation("io.netty:netty-codec:$nettyVersion")

     // ShardingSphere: 仅协议层，不引入 proxy-backend
     implementation("org.apache.shardingsphere:shardingsphere-protocol-mysql:$shardingsphereVersion")
     implementation("org.apache.shardingsphere:shardingsphere-database-protocol-core:$shardingsphereVersion")

     implementation("org.slf4j:slf4j-api")

     // test
     testImplementation(platform("org.junit:junit-bom:5.10.0"))
     testImplementation("org.junit.jupiter:junit-jupiter")
     testImplementation(project(":testkit"))
     testImplementation("org.testcontainers:testcontainers")
     testImplementation("mysql:mysql-connector-java:$mysqlConnectorVersion")
     testImplementation(project(path = ":milvus", configuration = "testOutput"))
}

关键变更: 移除 shardingsphere-proxy-frontend-mysql 依赖，仅保留协议编解码包，避免引入 SS-Proxy 的完整后端。

测试策略

单元测试

@DisplayName("MilvusComQueryExecutor 测试")
class MilvusComQueryExecutorTest {

     @Mock SQLExecutor sqlExecutor;
     @Mock ConnectionSession session;

     @Test
     void shouldReturnQueryResponse() throws SQLException {
         // given
         when(sqlExecutor.execute("SELECT 1"))
             .thenReturn(new QueryResult(...));

         // when
         MilvusComQueryExecutor executor = new MilvusComQueryExecutor(
             new MySQLComQueryPacket("SELECT 1"), session, sqlExecutor);
         Collection<DatabasePacket> result = executor.execute();

         // then
         assertThat(result).hasSize(4); // field_count + 1 col_def + 1 row + ok
     }
}

集成测试

保留现有的 MilvusMySQLClientE2ETest，验证重构后功能一致。

新增 JDBC 驱动 E2E 测试 (MilvusMySQLJdbcE2ETest.java):

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MilvusMySQLJdbcE2ETest extends MilvusBaseE2ETest {

     private MilvusMySQLServer server;
     private Connection jdbcConnection;

     @BeforeAll
     void startServer() throws Exception {
         MilvusServerConfig config = new MilvusServerConfig();
         config.setPort(3308); // 避免冲突
         server = new MilvusMySQLServer(config);
         server.start();

         // 等待服务器启动
         Thread.sleep(1000);

         // 使用 MySQL JDBC 驱动连接
         String url = "jdbc:mysql://127.0.0.1:3308/test?useSSL=false&serverTimezone=UTC";
         jdbcConnection = DriverManager.getConnection(url, "any_user", "any_password");
     }

     @Test
     void testSelectWithJdbc() throws SQLException {
         // 创建 collection
         createTestCollection("jdbc_test_collection");
         insertTestData("jdbc_test_collection");

         // 使用标准 JDBC 查询
         try (Statement stmt = jdbcConnection.createStatement();
              ResultSet rs = stmt.executeQuery(
                  "SELECT * FROM milvus.jdbc_test_collection LIMIT 10")) {

             assertTrue(rs.next());
             assertNotNull(rs.getString("id"));
             assertNotNull(rs.getString("vector"));
         }
     }

     @Test
     void testPreparedStatement() throws SQLException {
         createTestCollection("jdbc_prep_collection");
         insertTestData("jdbc_prep_collection");

         // 测试 PreparedStatement（如果支持）
         String sql = "SELECT * FROM milvus.jdbc_prep_collection WHERE id = ?";
         try (PreparedStatement pstmt = jdbcConnection.prepareStatement(sql)) {
             pstmt.setString(1, "test_id_1");
             try (ResultSet rs = pstmt.executeQuery()) {
                 assertTrue(rs.next());
                 assertEquals("test_id_1", rs.getString("id"));
             }
         }
     }

     @AfterAll
     void stopServer() throws SQLException {
         if (jdbcConnection != null) {
             jdbcConnection.close();
         }
         if (server != null) {
             server.stop();
         }
     }
}

实施步骤

1. 创建新目录结构 - 建立 handler/, command/, response/ 目录
2. 实现新类 - 按依赖顺序实现（先 ResponseBuilder → CommandExecutors → Dispatcher → AuthHandler）
3. 重构 Server - 简化 MilvusMySQLServer，使用新的 Initializer
4. 删除旧类 - 移除 MySQLProtocolHandler, MySQLCodecEngine
5. 运行测试 - 确保 E2E 测试通过
6. 添加单元测试 - 为新类补充测试

回滚策略

- 所有变更在 milvus-sql-client 模块内
- 保留 SQLExecutor 和 MilvusServerConfig 不变
- 通过 git 可完整回滚

实施步骤

1. 创建新目录结构 - 建立 handler/, command/, response/ 目录
2. 实现新类 - 按依赖顺序实现：
- MySQLResponseBuilder (无依赖)
- ConnectionSession (无依赖)
- *CommandExecutor 系列 (依赖 SQLExecutor)
- MilvusCommandDispatcher (依赖 Executors)
- MilvusAuthHandler (依赖 ConnectionSession)
- MilvusChannelInitializer (整合所有层)
3. 重构 Server - 简化 MilvusMySQLServer，使用新的 Initializer
4. 删除旧类 - 移除 MySQLProtocolHandler, MySQLCodecEngine
5. 添加 JDBC E2E 测试 - 新增 MilvusMySQLJdbcE2ETest
6. 运行测试 - 确保所有测试通过

验证方式

# 1. 编译
./gradlew :milvus-sql-client:compileJava

# 2. 单元测试
./gradlew :milvus-sql-client:test

# 3. E2E 测试 (需要 Docker 运行 Milvus)
./gradlew :milvus-sql-client:test --tests "*E2ETest"

# 4. 手动测试
./gradlew :milvus-sql-client:run &
mysql -h 127.0.0.1 -P 3307 -e "SELECT * FROM milvus.test_collection"
