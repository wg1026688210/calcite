/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.milvus.sql.client.executor;

import org.apache.calcite.adapter.milvus.factory.MilvusSchema;
import org.apache.calcite.adapter.milvus.hint.MilvusPrepareImpl;
import org.apache.calcite.adapter.milvus.sql.client.config.SystemVariables;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.schema.SchemaPlus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SQLExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(SQLExecutor.class);

  private final String milvusHost;
  private final int milvusPort;
  private final String milvusDatabase;
  private final String milvusUsername;
  private final String milvusPassword;
  private final int queryTimeoutSeconds;

  public SQLExecutor(String milvusHost, int milvusPort, String milvusDatabase,
      String milvusUsername, String milvusPassword, int queryTimeoutSeconds) {
    this.milvusHost = milvusHost;
    this.milvusPort = milvusPort;
    this.milvusDatabase = milvusDatabase;
    this.milvusUsername = milvusUsername;
    this.milvusPassword = milvusPassword;
    this.queryTimeoutSeconds = queryTimeoutSeconds;
  }



  /**
   * Executes SQL with specified database context.
   */
  public QueryResult execute(String sql, String currentDatabase) throws SQLException {
    // Handle JDBC driver initialization queries that Calcite doesn't support
    String trimmedSql = sql.trim();
    // Remove leading comments like /* ... */
    if (trimmedSql.startsWith("/*")) {
      int commentEnd = trimmedSql.indexOf("*/");
      if (commentEnd != -1) {
        trimmedSql = trimmedSql.substring(commentEnd + 2).trim();
      }
    }
    String upperSql = trimmedSql.toUpperCase();
    // System variable queries are handled by SystemVariableHandler at the command layer.



    try (Connection connection = createConnection(currentDatabase)) {
      try (Statement statement = connection.createStatement()) {
        if (queryTimeoutSeconds > 0) {
          statement.setQueryTimeout(queryTimeoutSeconds);
        }
        boolean hasResultSet = statement.execute(sql);
        if (hasResultSet) {
          try (ResultSet rs = statement.getResultSet()) {
            return convertResultSet(rs);
          }
        } else {
          return new QueryResult(new ArrayList<>(), new ArrayList<>(), statement.getUpdateCount());
        }
      }
    }
  }

  private static Properties createCalciteProperties() {
    Properties info = new Properties();
    info.setProperty("lex", "mysql");
    info.setProperty("fun", "milvus");
    info.setProperty("defaultCharset", "UTF-8");
    return info;
  }

  protected Connection createConnection(String currentDatabase) throws SQLException {
    Properties info = createCalciteProperties();

    final Driver driver = new Driver().withPrepareFactory(MilvusPrepareImpl::new);
    Connection connection = driver.connect("jdbc:calcite:", info);
    CalciteConnection calciteConnection = connection.unwrap(CalciteConnection.class);
    SchemaPlus rootSchema = calciteConnection.getRootSchema();

    // Get all databases from Milvus and create schema for each
    List<String> databases = listMilvusDatabases();
    for (String dbName : databases) {
      MilvusSchema dbSchema = createMilvusSchema(dbName);
      rootSchema.add(dbName, dbSchema);
    }

    // Set current database (may be different from default after USE command)
    String dbToUse = currentDatabase != null && !currentDatabase.isEmpty()
        ? currentDatabase : milvusDatabase;
    calciteConnection.setSchema(dbToUse);

    return connection;
  }

  /**
   * Checks if a database exists in Milvus.
   */
  public boolean databaseNotExists(String databaseName) {
    if (databaseName == null || databaseName.isEmpty()) {
      return true;
    }
    List<String> databases = listMilvusDatabases();
    return !databases.contains(databaseName);
  }

  /**
   * Lists all databases from Milvus server using MilvusClientV2.
   */
  public List<String> listMilvusDatabases() {
    List<String> databases = new ArrayList<>();
    try {
      // Connect to Milvus using v2 SDK to list databases
      io.milvus.v2.client.ConnectConfig.ConnectConfigBuilder builder =
          io.milvus.v2.client.ConnectConfig.builder()
              .uri("http://" + milvusHost + ":" + milvusPort);
      if (milvusUsername != null && !milvusUsername.isEmpty()) {
        builder.username(milvusUsername);
      }
      if (milvusPassword != null && !milvusPassword.isEmpty()) {
        builder.password(milvusPassword);
      }
      io.milvus.v2.client.ConnectConfig connectConfig = builder.build();
      io.milvus.v2.client.MilvusClientV2 client = new io.milvus.v2.client.MilvusClientV2(connectConfig);

      io.milvus.v2.service.database.response.ListDatabasesResp response = client.listDatabases();
      databases.addAll(response.getDatabaseNames());
      client.close();
    } catch (Exception e) {
      // Fallback to default database if cannot connect
      LOGGER.warn("[SQLExecutor] Failed to list databases: {}", e.getMessage());
      databases.add(milvusDatabase);
    }
    return databases;
  }

  public MilvusSchema createMilvusSchema(String databaseName) {
    return new MilvusSchema(milvusHost, milvusPort, databaseName,
        milvusUsername, milvusPassword);
  }

  private QueryResult convertResultSet(ResultSet rs) throws SQLException {
    ResultSetMetaData metaData = rs.getMetaData();
    int columnCount = metaData.getColumnCount();

    List<ColumnInfo> columns = new ArrayList<>();
    for (int i = 1; i <= columnCount; i++) {
      columns.add(
          new ColumnInfo(
          metaData.getColumnName(i),
          metaData.getColumnLabel(i),
          metaData.getSchemaName(i),
          metaData.getTableName(i),
          metaData.getColumnType(i),
          metaData.getColumnTypeName(i),
          metaData.getColumnDisplaySize(i),
          metaData.getScale(i)));
    }

    List<List<Object>> rows = new ArrayList<>();
    while (rs.next()) {
      List<Object> row = new ArrayList<>();
      for (int i = 1; i <= columnCount; i++) {
        row.add(rs.getObject(i));
      }
      rows.add(row);
    }

    return new QueryResult(columns, rows, 0);
  }



  public static class ColumnInfo {
    private final String name;
    private final String label;
    private final String schemaName;
    private final String tableName;
    private final int sqlType;
    private final String typeName;
    private final int displaySize;
    private final int decimals;

    public ColumnInfo(String name, int sqlType, String typeName) {
      this(name, name, "def", "", sqlType, typeName, 255, 0);
    }

    public ColumnInfo(String name, String label, String schemaName, String tableName,
        int sqlType, String typeName, int displaySize, int decimals) {
      this.name = name;
      this.label = label;
      this.schemaName = schemaName == null || schemaName.isEmpty() ? "def" : schemaName;
      this.tableName = tableName == null ? "" : tableName;
      this.sqlType = sqlType;
      this.typeName = typeName;
      this.displaySize = displaySize > 0 ? displaySize : 255;
      this.decimals = Math.max(decimals, 0);
    }

    public String getName() {
      return name;
    }

    public String getLabel() {
      return label;
    }

    public String getTableName() {
      return tableName;
    }

    public int getSqlType() {
      return sqlType;
    }

    public String getTypeName() {
      return typeName;
    }

    public int getDisplaySize() {
      return displaySize;
    }

    public int getDecimals() {
      return decimals;
    }
  }

  public static class QueryResult {
    public static final QueryResult EMPTY_RESULT = new QueryResult(new ArrayList<>(), new ArrayList<>(), 0);
    private final List<ColumnInfo> columns;
    private final List<List<Object>> rows;
    private final int updateCount;

    public QueryResult(List<ColumnInfo> columns, List<List<Object>> rows, int updateCount) {
      this.columns = columns;
      this.rows = rows;
      this.updateCount = updateCount;
    }

    public List<ColumnInfo> getColumns() {
      return columns;
    }

    public List<List<Object>> getRows() {
      return rows;
    }

    public int getUpdateCount() {
      return updateCount;
    }

    public boolean isResultSet() {
      return !columns.isEmpty();
    }
  }
}
