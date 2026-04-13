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
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.schema.SchemaPlus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class SQLExecutor {
  private final String milvusHost;
  private final int milvusPort;
  private final String milvusDatabase;
  private final String milvusUsername;
  private final String milvusPassword;

  public SQLExecutor(String milvusHost, int milvusPort, String milvusDatabase,
      String milvusUsername, String milvusPassword) {
    this.milvusHost = milvusHost;
    this.milvusPort = milvusPort;
    this.milvusDatabase = milvusDatabase;
    this.milvusUsername = milvusUsername;
    this.milvusPassword = milvusPassword;
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
    if (upperSql.startsWith("SET ")) {
      return new QueryResult(new ArrayList<>(), new ArrayList<>(), 0);
    }
    if (upperSql.startsWith("SHOW VARIABLES")) {
      List<ColumnInfo> columns = new ArrayList<>();
      columns.add(new ColumnInfo("Variable_name", Types.VARCHAR, "VARCHAR"));
      columns.add(new ColumnInfo("Value", Types.VARCHAR, "VARCHAR"));
      List<List<Object>> rows = new ArrayList<>();
      rows.add(Arrays.asList("character_set_client", "utf8"));
      rows.add(Arrays.asList("character_set_connection", "utf8"));
      rows.add(Arrays.asList("character_set_results", "utf8"));
      rows.add(Arrays.asList("character_set_server", "utf8"));
      rows.add(Arrays.asList("time_zone", "UTC"));
      rows.add(Arrays.asList("system_time_zone", "UTC"));
      rows.add(Arrays.asList("max_allowed_packet", "16777216"));
      rows.add(Arrays.asList("net_buffer_length", "16384"));
      rows.add(Arrays.asList("sql_mode", "STRICT_TRANS_TABLES"));
      rows.add(Arrays.asList("lower_case_table_names", "0"));
      rows.add(Arrays.asList("wait_timeout", "28800"));
      rows.add(Arrays.asList("interactive_timeout", "28800"));
      rows.add(Arrays.asList("auto_increment_increment", "1"));
      return new QueryResult(columns, rows, 0);
    }
    if (upperSql.startsWith("SELECT @@SESSION.")) {
      return buildSessionVariableResult(trimmedSql, "@@session.");
    }
    if (upperSql.startsWith("SELECT @@GLOBAL.")) {
      return buildSessionVariableResult(trimmedSql, "@@global.");
    }
    if (upperSql.startsWith("SHOW DATABASES") || upperSql.startsWith("SHOW SCHEMAS")) {
      return buildShowDatabasesResult();
    }
    if (upperSql.matches("(?i)^SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*;?$")) {
      List<ColumnInfo> columns = new ArrayList<>();
      columns.add(new ColumnInfo("DATABASE()", Types.VARCHAR, "VARCHAR"));
      List<List<Object>> rows = new ArrayList<>();
      rows.add(Arrays.asList(currentDatabase != null ? currentDatabase : ""));
      return new QueryResult(columns, rows, 0);
    }
    if (upperSql.startsWith("SHOW TABLES")) {
      return buildShowTablesResult(currentDatabase);
    }
    if (upperSql.startsWith("SHOW SESSION STATUS") ||
        upperSql.startsWith("SHOW COLLATION") ||
        upperSql.startsWith("SHOW CHARACTER SET")) {
      List<ColumnInfo> columns = new ArrayList<>();
      columns.add(new ColumnInfo("Variable_name", Types.VARCHAR, "VARCHAR"));
      columns.add(new ColumnInfo("Value", Types.VARCHAR, "VARCHAR"));
      return new QueryResult(columns, new ArrayList<>(), 0);
    }



    try (Connection connection = createConnection(currentDatabase)) {
      try (Statement statement = connection.createStatement()) {
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

  private Connection createConnection(String currentDatabase) throws SQLException {
    Properties info = new Properties();
    info.setProperty("lex", "mysql");
    info.setProperty("fun", "milvus");
    info.setProperty("defaultCharset", "UTF-8");

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
  private List<String> listMilvusDatabases() {
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
      System.err.println("[SQLExecutor] Failed to list databases: " + e.getMessage());
      databases.add(milvusDatabase);
    }
    return databases;
  }

  private MilvusSchema createMilvusSchema(String databaseName) {
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

  private QueryResult buildSessionVariableResult(String sql, String prefix) {
    String normalized = sql.trim().replace("`", "");
    String lower = normalized.toLowerCase();
    int start = lower.indexOf(prefix);
    String variable = start >= 0 ? normalized.substring(start + prefix.length()).trim() : normalized;
    int end = variable.indexOf(',');
    if (end >= 0) {
      variable = variable.substring(0, end).trim();
    }
    end = variable.indexOf(' ');
    if (end >= 0) {
      variable = variable.substring(0, end).trim();
    }

    String value;
    switch (variable.toLowerCase()) {
      case "auto_increment_increment":
        value = "1";
        break;
      case "max_allowed_packet":
        value = "16777216";
        break;
      case "net_buffer_length":
        value = "16384";
        break;
      case "sql_mode":
        value = "STRICT_TRANS_TABLES";
        break;
      default:
        value = "0";
        break;
    }

    List<ColumnInfo> columns = new ArrayList<>();
    columns.add(new ColumnInfo(variable, Types.VARCHAR, "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();
    rows.add(Arrays.asList(value));
    return new QueryResult(columns, rows, 0);
  }

  private QueryResult buildShowDatabasesResult() {
    List<ColumnInfo> columns = new ArrayList<>();
    columns.add(new ColumnInfo("Database", Types.VARCHAR, "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();

    List<String> databases = listMilvusDatabases();
    for (String dbName : databases) {
      rows.add(Arrays.asList(dbName));
    }

    return new QueryResult(columns, rows, 0);
  }

  private QueryResult buildShowTablesResult(String currentDatabase) throws SQLException {
    String dbName = currentDatabase != null && !currentDatabase.isEmpty()
        ? currentDatabase : milvusDatabase;
    List<ColumnInfo> columns = new ArrayList<>();
    columns.add(new ColumnInfo("Tables_in_" + dbName, Types.VARCHAR, "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();
    Properties info = new Properties();
    info.setProperty("lex", "JAVA");
    info.setProperty("fun", "milvus");
    info.setProperty("defaultCharset", "UTF-8");
    MilvusSchema milvusSchema = createMilvusSchema(dbName);
    for (String tableName : milvusSchema.getTableNames()) {
      rows.add(Arrays.asList(tableName));
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
