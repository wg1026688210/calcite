# Memory

## Milvus SQL Gateway Module

### Overview
Implemented `milvus-sql-gateway` module that provides MySQL protocol support for querying Milvus via standard MySQL clients (mysql-cli, JDBC, etc.).

### Module Structure
```
milvus-sql-gateway/
├── build.gradle.kts                          # Dependencies: Netty, SS protocol layer, BouncyCastle
├── src/main/java/org/apache/calcite/adapter/milvus/sql/client/
│   ├── MilvusMySQLServerBootstrap.java       # Entry point, loads YAML config
│   ├── server/
│   │   └── MilvusMySQLServer.java            # Netty TCP server (supports dynamic port binding)
│   ├── handler/
│   │   ├── MilvusChannelInitializer.java     # Netty pipeline initialization
│   │   ├── MilvusFrontendHandler.java        # Routes reads to auth/dispatch based on handshake state
│   │   ├── MilvusAuthHandler.java            # MySQL handshake & authentication
│   │   ├── MilvusCommandDispatcher.java      # Command routing/dispatching
│   │   └── ChannelAttrInitializer.java       # Inline replacement for SS proxy-frontend attr init
│   ├── auth/
│   │   ├── MilvusAuthenticator.java
│   │   ├── MySQLNativePasswordAuthenticator.java
│   │   └── MySQLCachingSha2PasswordAuthenticator.java  # MySQL 8.0+ default auth
│   ├── command/
│   │   ├── CommandExecutor.java
│   │   ├── MilvusComQueryExecutor.java
│   │   ├── MilvusComPingExecutor.java
│   │   ├── MilvusComInitDbExecutor.java
│   │   ├── MilvusComQuitExecutor.java
│   │   ├── MilvusComUnsupportedExecutor.java
│   │   ├── SystemVariableHandler.java        # Mock system variable queries
│   │   ├── MilvusCommandExecutorFactory.java
│   │   └── MilvusCommandPacketFactory.java
│   ├── response/
│   │   ├── MySQLResponseBuilder.java         # Result set / OK / Error packet builder
│   │   └── MilvusErrorPacketFactory.java
│   ├── session/
│   │   └── ConnectionSession.java            # Per-connection state (decoupled from Channel)
│   ├── config/
│   │   ├── MilvusServerConfig.java           # Port, host, auth plugin, SSL, etc.
│   │   ├── ConfigLoader.java                 # YAML config loader
│   │   └── SystemVariables.java              # Default mock system variable values
│   ├── ssl/
│   │   ├── MilvusSslContextFactory.java      # Auto self-signed cert generation
│   │   └── MilvusSSLRequestHandler.java
│   └── executor/
│       └── SQLExecutor.java                  # Executes SQL via Calcite + MilvusPrepareImpl
└── src/test/java/...
    ├── MilvusMySQLClientE2ETest.java         # Basic mysql-cli E2E
    ├── MilvusMySQLJdbcE2ETest.java           # JDBC driver E2E
    ├── MilvusMySQLSslE2ETest.java            # SSL/TLS connection E2E
    ├── MilvusMySQLAuthE2ETest.java           # Authentication plugin E2E
    ├── MultiDatabaseE2ETest.java             # Multi-database scenario E2E
    ├── MySQLProtocolUnitTest.java            # Protocol-level unit tests
    ├── RawSocketTest.java                    # Raw socket protocol tests
    ├── handler/MilvusAuthHandlerTest.java
    └── auth/MySQLNativePasswordAuthenticatorTest.java
```

### Build Changes
- settings.gradle.kts: Added `milvus-sql-gateway` to includes
- gradle.properties: Added `netty.version=4.1.110.Final`
- milvus/build.gradle.kts: Added testOutput configuration to share test classes

### Key Implementation Notes
- **ShardingSphere dependency trimmed**: Only `shardingsphere-protocol-mysql` and `shardingsphere-database-protocol-core` are retained. `shardingsphere-proxy-frontend-mysql` has been removed to avoid backend routing/sharding transitive dependencies.
- Reuses existing Milvus module: `MilvusSchema`, `MilvusPrepareImpl`, `MilvusBaseE2ETest`
- Supports: COM_QUERY, COM_INIT_DB, COM_PING, COM_QUIT
- **Authentication plugins**: `mysql_native_password` and `caching_sha2_password` (default for MySQL 8.0+ JDBC compatibility)
- **SSL/TLS**: Supported with auto-generated self-signed certificates via BouncyCastle (`bcprov` + `bcpkix`)
- **Dynamic port binding**: `MilvusMySQLServer.getPort()` returns actual bound port (useful when port=0)
- **Logging**: SLF4J replaced all `System.err.println` scatter in handlers
- Test requires Docker for Testcontainers (`MilvusExtension`)

### Build Commands
```bash
./gradlew :milvus-sql-gateway:compileJava          # Compile main
./gradlew :milvus-sql-gateway:compileTestJava      # Compile tests
./gradlew :milvus-sql-gateway:test                 # Run tests (needs Docker)
```

### Future Improvements
- Support prepared statements (COM_STMT_PREPARE/EXECUTE)
- Implement more MySQL protocol commands
- Query timeout / cancel (KILL QUERY)
- Connection pooling / SQLExecutor connection reuse
- Protocol compression support (CLIENT_COMPRESS)

---

# Milvus SQL Gateway - Programming Guidelines

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
