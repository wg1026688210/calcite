# Milvus SQL Client 部署指南

## 编译打包运行问题汇总

### 1. 代码风格检查失败

**问题**: 构建时 `autostyleApply` 和 `checkstyle` 任务失败

**错误信息**:
```
Run './gradlew autostyleApply' to fix the violations.
[ant:checkstyle] [ERROR] ... Missing package-info.java file
```

**解决方案**:
```bash
# 应用代码风格修复
./gradlew :milvus-sql-client:autostyleApply

# 或者跳过风格检查直接构建
./gradlew :milvus-sql-client:build -x test -x autostyleJavaCheck -x autostyleKotlinCheck -x checkstyleMain -x checkstyleTest
```

---

### 2. Gradle 脚本缩进问题

**问题**: `build.gradle.kts` 缩进不一致导致构建失败

**错误信息**:
```
Error on line: 22, column: 1
Unexpected indentation (2) (it should be 4)
```

**解决方案**:
统一使用 4 空格缩进，不要使用 Tab 或 2 空格。

修复后的格式:
```kotlin
tasks.compileTestJava {
    dependsOn(":milvus:compileTestJava")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.remove("-Werror")
}
```

---

### 3. Fat JAR SPI 文件合并问题（关键）

**问题**: ShardingSphere 使用 SPI 机制加载数据库类型，但 Fat JAR 打包时多个 jar 的 SPI 文件被覆盖，导致 `MySQLDatabaseType` 无法加载。

**错误信息**:
```
org.apache.shardingsphere.infra.spi.exception.ServiceProviderNotFoundException: 
SPI-00001: No implementation class load from SPI 
'org.apache.shardingsphere.database.connector.core.type.DatabaseType' with type 'MySQL'.
```

**根本原因**:
- `shardingsphere-database-connector-h2`、`shardingsphere-database-connector-mysql`、`shardingsphere-database-connector-sql92` 
- 都包含 `META-INF/services/org.apache.shardingsphere.database.connector.core.type.DatabaseType`
- 默认 `DuplicatesStrategy.EXCLUDE` 导致只保留第一个（H2）
- `DuplicatesStrategy.INCLUDE` 导致文件内容重复拼接，格式错误

**解决方案**:
在 `build.gradle.kts` 中手动合并 SPI 文件:

```kotlin
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    
    // 收集所有 SPI 文件
    val serviceFiles = mutableMapOf<String, MutableSet<String>>()
    configurations.runtimeClasspath.get().forEach { jar ->
        if (jar.isFile && jar.name.endsWith(".jar")) {
            zipTree(jar).matching { include("META-INF/services/*") }.forEach { file ->
                val serviceName = file.name
                val implementations = file.readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .toSet()
                serviceFiles.getOrPut(serviceName) { mutableSetOf() }.addAll(implementations)
            }
        }
    }
    
    // 排除原始的 SPI 文件
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/services/*")
    }
    with(tasks.jar.get())
    
    // 添加合并后的 SPI 文件
    serviceFiles.forEach { (name, implementations) ->
        val content = implementations.joinToString("\n")
        val serviceFile = File(temporaryDir, "META-INF/services/$name")
        serviceFile.parentFile.mkdirs()
        serviceFile.writeText(content)
    }
    from(temporaryDir)
    
    manifest {
        attributes["Main-Class"] = "org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

---

### 4. 解压 JAR 时的 License 目录冲突

**问题**: 解压 fat jar 时出现 `META-INF/license` 文件和目录冲突

**错误信息**:
```
checkdir error: /tmp/milvus-server-extract/META-INF/license exists but is not directory
unable to process META-INF/license/LICENSE.aix-netbsd.txt
```

**原因**: 
某些依赖的 `META-INF/license` 是文件而非目录，与其他依赖的 `META-INF/license/` 目录冲突。

**解决方案**:
解压时使用 `-o` 覆盖选项，或忽略错误:
```bash
unzip -q -o calcite-milvus-sql-client-*-all.jar
```

---

### 5. 运行方式

**直接使用 Gradle 运行**（开发调试）:
```bash
./gradlew :milvus-sql-client:run
```

**构建 Fat JAR 并运行**（生产部署）:
```bash
# 1. 构建
./gradlew :milvus-sql-client:fatJar

# 2. 解压
mkdir /opt/milvus-sql-server
cd /opt/milvus-sql-server
unzip -q -o /path/to/calcite-milvus-sql-client-1.41.0-SNAPSHOT-all.jar

# 3. 准备配置文件（可选）
cp /path/to/milvus-server.yml .

# 4. 运行
java -cp . org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap [配置文件路径]
```

---

## 配置文件说明

默认配置文件位置: `milvus-sql-client/src/main/resources/milvus-server.yml`

关键配置项:
```yaml
# MySQL 协议层（客户端连接认证）
mysqlUsername: "root"
mysqlPassword: "1111111111"

# Milvus 后端连接
milvusHost: "localhost"
milvusPort: 19530
milvusUsername: ""
milvusPassword: ""

# 其他配置
port: 3307
idleTimeoutSeconds: 1800
sslEnabled: false
```

---

## 6. MySQL 命令行客户端兼容性问题

**问题**: MySQL 8.0+ / 9.0+ 命令行客户端连接后执行查询时断开

**错误信息**:
```
ERROR 2013 (HY000): Lost connection to MySQL server during query
```

**已实施的修复**:
- ✅ `CLIENT_DEPRECATE_EOF` 支持 - 自动检测并使用 OK/EOF 包
- ✅ `CLIENT_SESSION_TRACK` 屏蔽 - 防止 session tracking 问题
- ✅ `version_comment` 变量 - 返回 "Milvus SQL Gateway"
- ✅ 异步写入 - 避免 Netty 阻塞

**原因分析**:
MySQL 9.6.0 客户端能力标志 `0x19bfa285` 包含以下未完全支持的特性：
- `CLIENT_SESSION_TRACK` (0x800000)
- `CLIENT_QUERY_ATTRIBUTES` (0x8000000)
- `MULTI_FACTOR_AUTHENTICATION` (0x10000000)

**解决方案**:
1. **推荐**: 使用 JDBC 驱动（完全兼容，所有测试通过）
2. **临时**: 使用较旧版本的 mysql 客户端（5.7 或 8.0）
3. **待修复**: 完全实现 MySQL 8.0+ 协议特性

---

## 依赖版本冲突注意

ShardingSphere 相关依赖版本必须一致:
```kotlin
val shardingsphereVersion = "5.5.3"  // 或使用的版本

implementation("org.apache.shardingsphere:shardingsphere-protocol-mysql:$shardingsphereVersion")
implementation("org.apache.shardingsphere:shardingsphere-database-protocol-core:$shardingsphereVersion")
implementation("org.apache.shardingsphere:shardingsphere-proxy-frontend-mysql:$shardingsphereVersion")
```

版本不一致可能导致 SPI 加载失败或类找不到。

---

## 快速启动脚本

```bash
#!/bin/bash
set -e

# 构建
./gradlew :milvus-sql-client:fatJar -x test --quiet

# 部署目录
DEPLOY_DIR=/tmp/milvus-sql-server
rm -rf $DEPLOY_DIR
mkdir -p $DEPLOY_DIR
cd $DEPLOY_DIR

# 解压
unzip -q -o /Users/wgcn007/workspace/java/apache/calcite/milvus-sql-client/build/libs/calcite-milvus-sql-client-*-all.jar

# 启动
echo "Starting Milvus SQL Server..."
java -cp . org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap &
echo $! > /tmp/milvus-sql-server.pid

sleep 3
echo "Server started on port 3307"
```

---

## 7. MySQL 命令行客户端调试记录

### 已尝试的修复

1. DEPRECATE_EOF 支持 - 根据客户端能力标志自动选择 OK/EOF 包
2. SESSION_TRACK 屏蔽 - 清除 CLIENT_SESSION_TRACK (0x800000)
3. QUERY_ATTRIBUTES 屏蔽 - 清除 CLIENT_QUERY_ATTRIBUTES (0x8000000)
4. MFA 屏蔽 - 清除 MULTI_FACTOR_AUTHENTICATION (0x10000000)
5. 列名修复 - 使用实际变量名（@@version_comment）代替固定值（@@variable）
6. 版本注释 - 返回 "MySQL Community Server - Milvus Gateway"
7. 异步写入 - 避免 Netty 阻塞

### 问题现象

MySQL 9.6.0 客户端行为：
1. 连接建立
2. 发送: select @@version_comment limit 1
3. 服务端返回: 结果集（5个包）
4. 客户端断开连接（没有发送后续查询）

JDBC 驱动行为：
1. 连接建立
2. 发送: select @@version_comment limit 1
3. 服务端返回: 结果集
4. 继续发送: SHOW VARIABLES WHERE Variable_name = 'character_set_client'
5. 正常执行后续查询

### 结论

- JDBC 驱动（Java）完全兼容，所有 7 个 E2E 测试通过
- MySQL 9.6.0 命令行客户端在连接初始化阶段断开，需进一步调查协议差异
- 生产环境推荐使用 JDBC 驱动连接
