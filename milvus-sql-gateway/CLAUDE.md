编程原则：
Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.



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



关键变更: 移除 shardingsphere-proxy-frontend-mysql 依赖，仅保留协议编解码包，避免引入 SS-Proxy 的完整后端。

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
../gradlew :milvus-sql-client:compileJava

# 2. 单元测试
../gradlew :milvus-sql-client:test

# 3. E2E 测试 (需要 Docker 运行 Milvus)
../gradlew :milvus-sql-client:test --tests "*E2ETest"

# 4. 手动测试
../gradlew :milvus-sql-client:run &
mysql -h 127.0.0.1 -P 3307 -e "SELECT * FROM milvus.test_collection"

# 5. 生产打包
./gradlew :milvus-sql-gateway:distZip
