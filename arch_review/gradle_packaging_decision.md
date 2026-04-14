# Milvus SQL Gateway 打包方案决策文档

**决策日期**: 2026-04-14  
**决策人**: 架构师E  
**相关方**: B (验证), C (执行)  
**状态**: 方案已验证通过，待 C 完成最后代码修改

---

## 1. 背景与问题

原 `milvus-sql-gateway` 模块使用手搓的 `fatJar` task 进行生产打包。该方案存在以下问题：

1. **SPI 文件合并风险**：ShardingSphere 和 Calcite 大量依赖 `ServiceLoader` 机制。`fatJar` 通过手动遍历 `META-INF/services/*` 并去重合并来规避文件覆盖，但此做法：
   - 逻辑复杂，维护成本高
   - 无法保证所有边缘 SPI 文件都被正确处理
   - 与上游依赖升级时存在兼容性隐患

2. **License 目录冲突**：解压 fat jar 时多个依赖的 `META-INF/license` 文件/目录冲突，导致解压异常。

3. **包体积膨胀**：fat jar 去重策略不当会导致重复类被包含，ZIP 体积大于原始 jar 之和。

---

## 2. 决策结论：不合并 SPI，改用 `distribution` 插件

### 核心原则

> **将 `lib/` 目录下放所有 runtime 依赖的原始 jar 文件，通过 `-cp "conf:lib/*"` 方式启动。不合并 SPI，不制作 fat jar。**

### 方案详情

- **插件**：使用 Gradle 内置 `distribution` 插件的 `distZip` task
- **产物结构**：
  ```
  milvus-sql-gateway-<version>/
  ├── bin/
  │   └── start.sh              # 启动脚本
  ├── conf/
  │   └── milvus-server.yml     # 配置文件
  ├── doc/
  │   └── code-duplication-analysis.md
  └── lib/
      ├── milvus-sql-gateway.jar
      ├── shardingsphere-protocol-mysql.jar
      ├── calcite-core.jar
      └── ... (共 104 个 runtime jar)
  ```
- **启动方式**：
  ```bash
  bash bin/start.sh
  # 内部执行: java -cp "conf:lib/*" org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap
  ```

---

## 3. B 的验证结果

B 已完成完整验证，核心结论如下：

| 验证项 | 结果 |
|--------|------|
| `distZip` 产物中 `lib/` 包含所有 runtime 原始 jar | 通过（共 104 个） |
| 启动脚本 `-cp "conf:lib/*"` 能成功启动并监听 3307 端口 | 通过 |
| `ServiceLoader` 加载 Calcite 和 SS 的 SPI 服务 | 通过（共 57 个服务文件正常加载） |
| 与参考项目 `milvus-seeder` 的 Maven assembly ZIP 结构等效 | 通过 |
| ZIP 体积优于 fat jar | 通过（避免了 fat jar 的重复问题） |

**结论**：`ServiceLoader` 在原始 jar 分散放置的情况下可正常工作，**无需合并 SPI 文件**。

---

## 4. 与参考项目的对比

参考项目 `milvus-seeder` 使用 Maven `maven-assembly-plugin` 生成 ZIP，结构为：
```
lib/        ← 所有原始 jar
conf/       ← 配置文件
bin/        ← 启动脚本
```

本方案采用 Gradle `distribution` 插件，产物结构与上述完全一致，实现了跨构建工具的等效打包。

---

## 5. 待 C 执行的最后开发项

以下事项需由 C 完成并验证：

### 5.1 `build.gradle.kts` 修改
- **删除**第 94-129 行的手搓 `fatJar` task
- **保留**已有的 `plugins { distribution }` 和 `distributions { main { ... } }` 配置（已正确）

### 5.2 `scripts/test_gateway.sh` 修改
- 将第 70-124 行的 `fatJar` 构建/解压/启动逻辑，替换为 `distZip` 版本
- 具体修改点：
  1. `./gradlew :milvus-sql-gateway:fatJar` → `./gradlew :milvus-sql-gateway:distZip`
  2. `find build/libs -name "*-all.jar"` → `find build/distributions -name "*.zip"`
  3. `unzip JAR_PATH` → `unzip ZIP_PATH` 到临时目录，再 `cd` 到解压后的版本号子目录
  4. `java -cp . ...` → `bash bin/start.sh`

### 5.3 启动脚本
- **无需修改**。`scripts/start.sh` 已存在且内容正确（`-cp "conf:lib/*"`），会被 `distribution` 自动打包到 `bin/` 目录。

### 5.4 验证清单
- [ ] `./gradlew :milvus-sql-gateway:distZip` 构建成功
- [ ] 解压 ZIP 后目录结构包含 `bin/` `conf/` `doc/` `lib/`
- [ ] `bash bin/start.sh` 能成功启动并监听 3307 端口
- [ ] `mysql -h 127.0.0.1 -P 3307 -u root -p1111111111 -e "SELECT 1"` 返回正确结果
- [ ] `scripts/test_gateway.sh` 完整运行通过

---

## 6. 架构师意见

1. **不合并 SPI 是正确且必要的**：Gradle `shadow` / fat jar 的 SPI 合并本质上是在用构建脚本替代 JVM 类加载器的工作，复杂且脆弱。原始 jar 方案让 `URLClassLoader` 按自然顺序加载，风险最低。

2. **结构等效于参考项目**：无需为了“Gradle 仪式感”而引入 `shadowJar` 等第三方插件。`distribution` 插件即可满足需求。

3. **删除 `fatJar` 后无遗留风险**：`test_gateway.sh` 是唯一的 `fatJar` 调用方，修改后即可完全移除该 task。

---

## 7. 关联文件

- `milvus-sql-gateway/build.gradle.kts`
- `milvus-sql-gateway/scripts/test_gateway.sh`
- `milvus-sql-gateway/scripts/start.sh`
- `arch_review/e_combined_priorities.md`
