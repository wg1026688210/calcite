<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Milvus SQL Client 改造计划

本文档用于说明 `milvus-sql-client` 后续如何进一步复用 ShardingSphere 的 MySQL Proxy 相关实现，减少自研协议轮子，并按阶段推进改造。

当前目标不是引入完整的 ShardingSphere Proxy runtime，而是：

- 复用或对齐 ShardingSphere 已验证过的 MySQL 前端协议处理方式
- 保留 `milvus-sql-client` 自己的轻量执行层
- 让 JDBC、mysql client、以及最基本的 metadata/query 场景稳定工作

---

## 1. 当前问题与改造边界

### 1.1 当前已知问题

结合 `milvus-sql-client` 现状和现有单测失败情况，当前主要问题集中在协议层，而不是 Milvus/Calcite 查询本身：

- MySQL 握手和命令解析链路仍有较多自研逻辑
- `sequence id` 处理方式与 ShardingSphere 官方 Proxy 不一致
- result set 尾包与 MySQL JDBC 驱动兼容性存在问题
- 列元数据较为简化，按列名取值和 JDBC metadata 兼容性不足
- `SQLExecutor` 对默认 schema、系统查询和元数据查询的兼容仍不完整

### 1.2 设计边界

本次改造建议遵循以下边界：

**应尽量复用或对齐的部分：**

- MySQL 前端协议生命周期
- packet decode/encode 时序
- sequence id 管理方式
- command packet factory / executor factory 结构
- query result set 的 packet 组织方式
- column metadata 的填充方式

**保留 `milvus-sql-client` 自定义的部分：**

- `SQLExecutor`
- Milvus / Calcite 查询执行逻辑
- 轻量级 `ConnectionSession`
- 针对 JDBC 初始化查询的最小兼容实现

**暂不建议直接引入的部分：**

- ShardingSphere 完整 `ProxyContext`
- `AuthorityRule` / 完整认证授权体系
- backend admin executor 整套运行时
- 重型 `ConnectionSession` / process engine / connection manager

---

## 2. 可复用类清单

下面列出建议优先参考或迁移设计的 ShardingSphere 类。

### 2.1 强烈建议复用“设计或实现方式”的类

#### A. `MySQLSequenceIdInboundHandler`

来源：ShardingSphere `database/protocol/dialect/mysql/.../netty/MySQLSequenceIdInboundHandler`

建议用途：

- 统一处理 MySQL sequence id
- 避免在业务 handler 内手动 `skipBytes(1)`
- 让后续 handler 只处理纯 payload

可落地方式：

- 直接仿照其职责实现一个轻量版本
- 或在条件允许时直接复用其行为模式

---

#### B. `MySQLAuthenticationEngine`

来源：ShardingSphere `proxy/frontend/dialect/mysql/.../authentication/MySQLAuthenticationEngine`

建议用途：

- 对齐握手包发送和握手响应读取方式
- 对齐 capability、charset、database 的读取方式
- 避免在认证链路里自行偏移 payload

复用边界：

- 参考其协议处理方式
- 不建议直接接入其完整 authority/auth plugin/SSL 依赖链

---

#### C. `MySQLCommandExecuteEngine`

来源：ShardingSphere `proxy/frontend/dialect/mysql/.../command/MySQLCommandExecuteEngine`

建议用途：

- 对齐 MySQL command 读取方式
- 对齐 query rows 的写包流程
- 对齐 query result 尾部 EOF 的写法

复用边界：

- 建议借鉴其 command lifecycle
- 不建议完整依赖其 backend connection manager 体系

---

#### D. `MySQLCommandPacketFactory`

来源：ShardingSphere `proxy/frontend/dialect/mysql/.../command/MySQLCommandPacketFactory`

建议用途：

- 统一 `COM_QUERY` / `COM_INIT_DB` / `COM_PING` / `COM_QUIT` 等命令解析入口
- 为未来支持 `COM_FIELD_LIST`、`COM_STMT_PREPARE` 提供结构基础

复用边界：

- 很适合按同样模式在 `milvus-sql-client` 内部落一个轻量 factory

---

#### E. `ResponsePacketBuilder`

来源：ShardingSphere `proxy/frontend/dialect/mysql/.../command/query/builder/ResponsePacketBuilder`

建议用途：

- 对齐 query header packet 构造方式
- 对齐 `field-count + column definitions + EOF` 的 header 输出
- 对齐 column metadata 的字段填充方式

复用边界：

- 强烈建议参考其 packet 组织方式
- rows 与 final EOF 可以保留轻量实现，但行为要对齐官方

---

### 2.2 可以参考返回格式，但不建议整段复用的类

#### F. `MySQLSystemVariableQueryExecutor`

建议用途：

- 参考 `SHOW VARIABLES` / `SELECT @@SESSION...` 的列名和返回格式

不建议整段复用原因：

- 依赖 ShardingSphere backend/runtime

---

#### G. `MySQLShowTablesExecutor` / `MySQLShowDatabasesExecutor`

建议用途：

- 参考 `SHOW TABLES` / `SHOW DATABASES` 应该返回什么列名、什么形状的数据

不建议整段复用原因：

- 依赖 Proxy metadata context 和 backend admin executor 框架

---

#### H. `MySQLQueryHeaderBuilder`

建议用途：

- 参考 query header 到 MySQL column definition 的映射思路

不建议整段复用原因：

- 依赖 Proxy backend response header 体系

---

## 3. 不建议复用类清单

以下内容不建议直接引入到 `milvus-sql-client`：

### 3.1 完整 Proxy runtime 相关

- `ProxyContext`
- `ConnectionSession`（ShardingSphere 重型版本）
- `ProcessEngine`
- `ProxyDatabaseConnectionManager`
- frontend/backend runtime 全局对象

原因：

- 依赖重
- 侵入大
- 与当前轻量 Milvus SQL Client 的目标不匹配

### 3.2 认证授权体系

- `AuthorityRule`
- `AuthenticatorFactory`
- `ShardingSphereUser`
- 完整 auth plugin 切换逻辑

原因：

- 当前阶段只需要轻量连接成功和基础协议兼容
- 不值得为此引入完整安全模型

### 3.3 backend admin executor 框架

- Admin executor factory
- backend special table / system schema 查询框架
- 完整 metadata-driven show/select executor 体系

原因：

- `milvus-sql-client` 只需要最小兼容集合
- 可按返回格式轻量模拟或桥接到 `SQLExecutor`

---

## 4. 推荐改造顺序

### 4.1 Execution tracker（可直接开工的 issue checklist）

#### Phase 1 checklist

- [x] P1-1：在 `MilvusChannelInitializer` 中对齐 sequence/pipeline 处理方式
  - 涉及：`MilvusChannelInitializer`
  - 目标：明确 sequence id 在 pipeline 中只处理一次
  - 验证：协议链路可继续完成握手和命令解析
- [x] P1-2：移除 `MilvusAuthHandler` 中对握手响应的手工 `skipBytes(1)`
  - 涉及：`MilvusAuthHandler`
  - 目标：握手响应按纯 payload 解析
  - 验证：JDBC 连接初始化不再在握手阶段失败
- [x] P1-3：移除 `MilvusCommandDispatcher` 中对命令包的手工 `skipBytes(1)`
  - 涉及：`MilvusCommandDispatcher`
  - 目标：`COM_QUERY` / `COM_INIT_DB` / `COM_PING` / `COM_QUIT` 按纯 payload 解析
  - 验证：`SELECT 1` 与 `USE default` 可完成闭环
- [x] P1-4：将 query result set 尾包从 `rows + OK` 改为 `rows + EOF`
  - 涉及：`MySQLResponseBuilder`
  - 目标：对齐 ShardingSphere text result set 结束方式
  - 验证：`SHOW VARIABLES` 初始化查询不再读超时
- [x] P1-5：同步更新协议测试和 JDBC E2E 测试
  - 涉及：`MySQLProtocolUnitTest`、`RawSocketTest`、`MilvusMySQLJdbcE2ETest`
  - 目标：让 Phase 1 验收可自动化执行
  - 验证：Phase 1 固定验证集全部通过

#### Phase 2 checklist

- [x] P2-1：解决 `SQLExecutor` 默认 schema 问题
- [x] P2-2：完善 column metadata（schema/table/label/name/flags/length 等）
- [x] P2-3：对齐 `SHOW VARIABLES` / `SHOW TABLES` / `SHOW DATABASES` 返回列名
- [x] P2-4：补充按列名访问和 metadata 断言
- [x] P2-5：完成最小 metadata/query 回归验证（核心查询路径已通过）

#### Phase 3 checklist

- [ ] P3-1：引入 command packet factory / executor factory 结构
- [ ] P3-2：进一步解耦 response header / row writing
- [ ] P3-3：统一 auth / decode / execute / write 职责
- [ ] P3-4：评估并决定 `COM_FIELD_LIST` 支持范围
- [ ] P3-5：评估并决定 `COM_STMT_PREPARE` / `COM_STMT_EXECUTE` 支持范围
- [ ] P3-6：补齐回归测试并明确已支持/未支持边界

#### 固定验收顺序

每次提交建议按下面顺序做验收：

1. 协议单测：`MySQLProtocolUnitTest`
2. 原始协议探针：`RawSocketTest`
3. JDBC 最小链路：`MilvusMySQLJdbcE2ETest.testBasicSelect`
4. JDBC 命令链路：`MilvusMySQLJdbcE2ETest.testUseDatabase`
5. JDBC collection 查询：`MilvusMySQLJdbcE2ETest.testSelectFromCollection`
6. direct client 对照：`MilvusMySQLClientE2ETest.testSelectFromCollection`

---

建议按三个阶段推进：

- **Phase 1：协议层打通，先恢复 JDBC 连接稳定性**
- **Phase 2：查询与 metadata 兼容完善，打通基础 JDBC 查询链路**
- **Phase 3：进一步结构化复用 ShardingSphere 前端设计，降低长期维护成本**

下面每个 phase 都给出目标、范围、任务、可验证项和通过标准。

---

# Phase 1：协议层修复与 JDBC 初始化打通

## 4.1 目标

优先解决“JDBC 连接初始化阶段失败”的问题，让以下场景先稳定：

- `DriverManager.getConnection(...)`
- `SHOW VARIABLES` 初始化查询
- `SELECT 1`
- `USE default`

这个阶段重点是协议兼容，不追求一次性解决所有 metadata / prepared statement 问题。

## 4.2 涉及文件

- `src/main/java/.../handler/MilvusChannelInitializer.java`
- `src/main/java/.../handler/MilvusAuthHandler.java`
- `src/main/java/.../handler/MilvusCommandDispatcher.java`
- `src/main/java/.../response/MySQLResponseBuilder.java`
- `src/test/java/.../MilvusMySQLJdbcE2ETest.java`
- `src/test/java/.../RawSocketTest.java`
- `src/test/java/.../MySQLProtocolUnitTest.java`

## 4.3 任务清单

1. 引入或仿照 `MySQLSequenceIdInboundHandler` 的 sequence 处理方式
2. 移除 `MilvusAuthHandler` / `MilvusCommandDispatcher` 中自行 `skipBytes(1)` 的逻辑
3. 对齐握手响应 payload 的读取方式
4. 将 query result set 改为：
   - `field-count`
   - `column-definitions`
   - `EOF`
   - `rows`
   - `EOF`
5. 确认 `COM_QUERY`、`COM_INIT_DB`、`COM_PING`、`COM_QUIT` 基础链路可用
6. 保持现有最小 session 结构，不引入 ShardingSphere 重型 session/runtime

## 4.4 可验证项

### Phase 1 建议 UT / E2E 测试

**已存在且当前应作为 Phase 1 门禁的测试：**

- `MySQLProtocolUnitTest`
- `RawSocketTest`
- `MilvusMySQLJdbcE2ETest.testBasicSelect`
- `MilvusMySQLJdbcE2ETest.testUseDatabase`

**建议新增或细化的测试：**

- 一个只覆盖 `DriverManager.getConnection(...)` 的最小 JDBC 初始化测试
- 一个专门断言 `SHOW VARIABLES` / `SELECT @@session.auto_increment_increment` 可被驱动消费的测试

### 验证项 A：JDBC 连接初始化

验证方式：

- 运行 `MilvusMySQLJdbcE2ETest` 中仅建立连接的路径
- 或新增/保留一个只做 `DriverManager.getConnection(...)` + `SELECT 1` 的测试

通过标准：

- 不再出现 `CommunicationsException`
- 不再出现 `SocketTimeoutException: Read timed out`
- `SHOW VARIABLES` 初始化查询能被客户端正常消费

### 验证项 B：协议单元测试

验证方式：

- 更新 `MySQLProtocolUnitTest`
- 验证 query result set 的 packet 顺序为：`field-count + col defs + EOF + rows + EOF`

通过标准：

- 单测明确断言 final packet 为 `MySQLEofPacket`
- 不再断言 `rows + OK` 为 query 正确结束方式

### 验证项 C：最小命令闭环

验证方式：

- 运行 `testBasicSelect`
- 运行 `testUseDatabase`
- 如保留 `RawSocketTest`，检查 sequence / packet 顺序日志

通过标准：

- `SELECT 1` 返回成功
- `USE default` 返回 OK，且无连接中断
- 原始协议测试中 packet 顺序与预期一致

## 4.5 Phase 1 完成判定

满足以下条件即可认为 Phase 1 完成：

- JDBC 连接建立成功
- `SELECT 1` 成功
- `USE default` 成功
- `SHOW VARIABLES` 不再导致连接初始化超时
- query result 的 packet 尾部已调整为兼容的 EOF 结束方式

---

# Phase 2：查询链路与元数据兼容完善

## 5.1 目标

在连接稳定后，继续打通真正的 collection 查询和 JDBC metadata 基础兼容，确保：

- `SELECT book_name, book_content FROM ... LIMIT 10` 能工作
- `ResultSet` 按列名访问可用
- schema / tables / variables 的最小元数据查询可用

## 5.2 涉及文件

- `src/main/java/.../executor/SQLExecutor.java`
- `src/main/java/.../response/MySQLResponseBuilder.java`
- `src/main/java/.../command/MilvusComQueryExecutor.java`
- `src/main/java/.../session/ConnectionSession.java`
- `src/test/java/.../MilvusMySQLJdbcE2ETest.java`
- `src/test/java/.../MilvusMySQLClientE2ETest.java`

## 5.3 任务清单

1. 解决 `SQLExecutor` 默认 schema 问题
   - 设置默认 schema 为 `milvus`
   - 或对裸表名做明确路由
2. 补齐 query column metadata
   - `schema`
   - `table`
   - `orgTable`
   - `columnLabel`
   - `columnName`
   - `columnLength`
   - `flags`
   - `decimals`
3. 对齐 `SHOW VARIABLES` / `SHOW TABLES` / `SHOW DATABASES` 的返回列名
4. 验证 `ResultSetMetaData` 与 `rs.getString("book_name")` 的兼容性
5. 让最小 metadata/query 初始化链路稳定通过

## 5.4 可验证项

### Phase 2 建议 UT / E2E 测试

**已存在且当前应作为 Phase 2 核心路径门禁的测试：**

- `MilvusMySQLJdbcE2ETest.testSelectFromCollection`
- `MilvusMySQLClientE2ETest.testSelectFromCollection`
- `MilvusMySQLJdbcE2ETest.testPreparedStatement`

**建议新增或细化的测试：**

- 一个断言 `ResultSetMetaData.getColumnName()` / `getColumnLabel()` 的测试
- 一个专门覆盖 `SHOW TABLES` 的测试
- 一个专门覆盖 `SHOW DATABASES` 的测试
- 一个专门覆盖按列名访问 `rs.getString("book_name")` / `rs.getString("book_content")` 的测试

### 验证项 A：collection 查询

验证方式：

- 运行 `MilvusMySQLJdbcE2ETest.testSelectFromCollection`
- 运行 `MilvusMySQLClientE2ETest.testSelectFromCollection` 作为对照

通过标准：

- JDBC 查询能返回非空结果
- 结果集中至少能成功读取 `book_name` 和 `book_content`
- 与 direct client 路径相比，结果数量与字段读取逻辑一致

### 验证项 B：按列名访问

验证方式：

- 在 JDBC 测试中保留 `rs.getString("book_name")`
- 补充 `ResultSetMetaData.getColumnName()` / `getColumnLabel()` 断言

通过标准：

- `getString("book_name")` 和 `getString("book_content")` 成功
- metadata 中列名与 label 符合预期

### 验证项 C：最小元数据命令

验证方式：

- 增加或补充 `SHOW TABLES`
- 增加或补充 `SHOW DATABASES`
- 验证 `SELECT @@SESSION...` / `SHOW VARIABLES`

通过标准：

- 命令返回包结构正确
- 列名符合 MySQL 客户端/JDBC 基础预期
- 不出现连接断开、未知命令或结果集读取异常

## 5.5 Phase 2 完成判定

满足以下条件即可认为 Phase 2 完成：

- collection 查询可通过 JDBC 成功执行
- `ResultSet` 按列名访问成功
- schema 解析不再阻塞裸表查询
- `SHOW TABLES` / `SHOW DATABASES` / `SHOW VARIABLES` 至少支持最小兼容集
- 最小 JDBC metadata/query 路径稳定可用

---

# Phase 3：结构化复用 ShardingSphere 前端设计

## 6.1 目标

在协议和查询都稳定后，再进一步收敛内部实现，尽量减少自研协议逻辑，让 `milvus-sql-client` 更接近一个“轻量版 ShardingSphere MySQL frontend”。

这个阶段目标是降低长期维护成本，而不是单纯修 bug。

## 6.2 涉及文件

- `src/main/java/.../handler/MilvusChannelInitializer.java`
- `src/main/java/.../handler/MilvusAuthHandler.java`
- `src/main/java/.../handler/MilvusCommandDispatcher.java`
- `src/main/java/.../command/*`
- `src/main/java/.../response/MySQLResponseBuilder.java`
- `src/main/java/.../session/ConnectionSession.java`
- 必要时新增：
  - `command/factory/*`
  - `protocol/*`
  - `metadata/*`

## 6.3 任务清单

1. 引入 command packet factory / executor factory 结构
2. 将 response header / row writing 逻辑进一步解耦
3. 统一 handler 职责：
   - auth
   - command decode
   - command execute
   - response write
4. 评估是否支持 `COM_FIELD_LIST`
5. 评估是否支持 `COM_STMT_PREPARE` / `COM_STMT_EXECUTE`
6. 将“不兼容 SQL 的临时特判”收敛为可维护的 compatibility layer

## 6.4 可验证项

### Phase 3 建议 UT / E2E 测试

**应保留的回归测试：**

- `MySQLProtocolUnitTest`
- `MilvusMySQLJdbcE2ETest`
- `MilvusMySQLClientE2ETest.testSelectFromCollection`

**建议在 Phase 3 新增的结构性测试：**

- command factory / executor factory 的单元测试
- response header builder / row writer 的单元测试
- unsupported command 的错误包测试
- 若实现 `COM_FIELD_LIST`，增加对应协议测试
- 若实现 `COM_STMT_PREPARE` / `COM_STMT_EXECUTE`，增加 prepared statement 协议测试

### 验证项 A：命令覆盖度

验证方式：

- 保留并运行：
  - `SELECT 1`
  - `USE default`
  - `SHOW VARIABLES`
  - `SHOW TABLES`
  - `SELECT ... FROM collection LIMIT 1`
- 如实现 prepared statement，则运行 `testPreparedStatement`

通过标准：

- 每个命令都走统一的 command factory / executor 路径
- 不再依赖散落在 handler 中的大段 `if/else` 特判
- 已支持命令的行为稳定且可测试

### 验证项 B：Prepared Statement（可选）

验证方式：

- 运行 `MilvusMySQLJdbcE2ETest.testPreparedStatement`

通过标准：

- 若选择支持：`PreparedStatement` 查询成功
- 若暂不支持：测试需被明确标记为 deferred / disabled，并在文档中记录原因

### 验证项 C：回归稳定性

验证方式：

- 汇总运行 `milvus-sql-client` 全部协议相关单测/E2E 测试
- 至少覆盖连接、基本查询、collection 查询、metadata 命令

通过标准：

- 已支持能力全部通过
- 无新增协议回归
- 文档中的“支持范围”和“未支持范围”与实际行为一致

## 6.5 Phase 3 完成判定

满足以下条件即可认为 Phase 3 完成：

- 协议层结构已接近 ShardingSphere 前端分层模式
- 自研协议细节显著减少
- 支持能力有清晰边界和自动化测试覆盖
- 维护者可明确知道哪些能力已支持、哪些暂未支持

---

## 7. 推荐执行策略

建议按以下节奏推进：

1. **先做 Phase 1**
   - 目标：先让 JDBC 连上
   - 这是当前最关键的阻塞项

2. **再做 Phase 2**
   - 目标：让真正的 collection 查询和最小 metadata 可用
   - 这是“用户能否真正使用”的核心阶段

3. **最后做 Phase 3**
   - 目标：减少长期维护成本
   - 这一步应在功能稳定后进行，避免边修 bug 边大重构

---

## 8. 质量门禁建议

每个 phase 完成后，建议至少做以下验证：

- Build：模块可编译
- Unit tests：协议单测通过
- E2E tests：JDBC 与 collection 查询测试通过
- Smoke test：最小 JDBC 连接和一条简单 SQL 成功

建议最终形成固定验证集：

- `MilvusMySQLJdbcE2ETest.testBasicSelect`
- `MilvusMySQLJdbcE2ETest.testUseDatabase`
- `MilvusMySQLJdbcE2ETest.testSelectFromCollection`
- `MilvusMySQLClientE2ETest.testSelectFromCollection`
- `MySQLProtocolUnitTest`
- 必要时保留 `RawSocketTest` 用于协议级调试

---

## 9. 当前建议结论

推荐路线是：

- **复用 ShardingSphere 前端协议设计与关键实现方式**
- **保留 `milvus-sql-client` 自己的轻量执行层**
- **避免引入完整 ShardingSphere Proxy backend/runtime**

这是当前“少造轮子”和“控制复杂度”之间最平衡的方案。
