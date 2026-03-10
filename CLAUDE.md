# Memory

## Milvus SQL Client Module

### Overview
Implemented `milvus-sql-client` module that provides MySQL protocol support for querying Milvus via standard MySQL clients.

### Module Structure
```
milvus-sql-client/
├── build.gradle.kts                          # Dependencies: Netty, MySQL JDBC for tests
├── src/main/java/...
│   ├── MilvusMySQLServerBootstrap.java       # Entry point
│   ├── config/MilvusServerConfig.java        # Port, host, Milvus connection settings
│   ├── converter/QueryResultConverter.java   # Calcite ResultSet -> MySQL protocol packets
│   ├── engine/MySQLProtocolHandler.java      # Netty handler for MySQL protocol
│   ├── executor/SQLExecutor.java             # Executes SQL via Calcite + MilvusPrepareImpl
│   ├── server/MilvusMySQLServer.java         # Netty TCP server (port 3307 default)
│   └── session/MilvusConnectionSession.java  # Per-connection state
└── src/test/java/...
    └── MilvusMySQLClientE2ETest.java         # Extends MilvusBaseE2ETest
```

### Build Changes
- settings.gradle.kts: Added `milvus-sql-client` to includes
- gradle.properties: Added `netty.version=4.1.110.Final`
- milvus/build.gradle.kts: Added testOutput configuration to share test classes

### Key Implementation Notes
- Manual MySQL protocol implementation (no ShardingSphere - version compatibility issues)
- Reuses existing Milvus module: MilvusSchema, MilvusPrepareImpl, MilvusBaseE2ETest
- Supports: COM_QUERY, COM_INIT_DB, COM_PING, COM_QUIT
- Test requires Docker for Testcontainers (MilvusExtension)

### Build Commands
```bash
./gradlew :milvus-sql-client:compileJava          # Compile main
./gradlew :milvus-sql-client:compileTestJava      # Compile tests
./gradlew :milvus-sql-client:test                 # Run tests (needs Docker)
```

### Future Improvements
- Add proper authentication (currently accepts any credentials)
- Support prepared statements (COM_STMT_PREPARE/EXECUTE)
- Add SSL/TLS support
- Implement more MySQL protocol commands
