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
import org.apache.calcite.adapter.milvus.factory.MilvusSchemaFactory;
import org.apache.calcite.adapter.milvus.hint.MilvusPrepareImpl;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class SQLExecutor {
  private final String milvusHost;
  private final int milvusPort;
  private final String milvusDatabase;

  public SQLExecutor(String milvusHost, int milvusPort, String milvusDatabase) {
    this.milvusHost = milvusHost;
    this.milvusPort = milvusPort;
    this.milvusDatabase = milvusDatabase;
  }

  public QueryResult execute(String sql) throws SQLException {
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
    // Return empty result set for system queries (with proper column definitions)
    if (upperSql.startsWith("SHOW VARIABLES")) {
      List<ColumnInfo> columns = new ArrayList<>();
      columns.add(new ColumnInfo("Variable_name", Types.VARCHAR, "VARCHAR"));
      columns.add(new ColumnInfo("Value", Types.VARCHAR, "VARCHAR"));
      // Return some fake variables for MySQL 5.1 compatibility
      List<List<Object>> rows = new ArrayList<>();
      rows.add(Arrays.asList("character_set_client", "utf8"));
      rows.add(Arrays.asList("character_set_connection", "utf8"));
      rows.add(Arrays.asList("character_set_results", "utf8"));
      rows.add(Arrays.asList("character_set_server", "utf8"));
      rows.add(Arrays.asList("time_zone", "UTC"));
      rows.add(Arrays.asList("system_time_zone", "UTC"));
      return new QueryResult(columns, rows, 0);
    }
    if (upperSql.startsWith("SHOW SESSION STATUS") ||
        upperSql.startsWith("SELECT @@SESSION.") ||
        upperSql.startsWith("SELECT @@GLOBAL.") ||
        upperSql.startsWith("SHOW COLLATION") ||
        upperSql.startsWith("SHOW CHARACTER SET") ||
        upperSql.startsWith("SHOW DATABASES") ||
        upperSql.startsWith("SHOW SCHEMAS") ||
        upperSql.startsWith("SHOW TABLES")) {
      List<ColumnInfo> columns = new ArrayList<>();
      columns.add(new ColumnInfo("Variable_name", Types.VARCHAR, "VARCHAR"));
      columns.add(new ColumnInfo("Value", Types.VARCHAR, "VARCHAR"));
      return new QueryResult(columns, new ArrayList<>(), 0);
    }

    try (Connection connection = createConnection()) {
      try (Statement statement = connection.createStatement()) {
        if (statement.execute(sql)) {
          ResultSet rs = statement.getResultSet();
          return convertResultSet(rs);
        } else {
          int updateCount = statement.getUpdateCount();
          return new QueryResult(new ArrayList<>(), new ArrayList<>(), updateCount);
        }
      }
    }
  }

  private Connection createConnection() throws SQLException {
    Properties info = new Properties();
    info.setProperty("lex", "JAVA");
    info.setProperty("fun", "milvus");
    info.setProperty("defaultCharset", "UTF-8");

    final Driver driver = new Driver().withPrepareFactory(MilvusPrepareImpl::new);
    Connection connection = driver.connect("jdbc:calcite:", info);
    CalciteConnection calciteConnection = connection.unwrap(CalciteConnection.class);
    SchemaPlus rootSchema = calciteConnection.getRootSchema();

    Map<String, Object> operands = new HashMap<>();
    operands.put("host", milvusHost);
    operands.put("port", milvusPort);
    operands.put("databaseName", milvusDatabase);

    MilvusSchemaFactory schemaFactory = new MilvusSchemaFactory();
    Schema milvusSchema = schemaFactory.create(rootSchema, "milvus", operands);
    rootSchema.add("milvus", milvusSchema);

    return connection;
  }

  private QueryResult convertResultSet(ResultSet rs) throws SQLException {
    ResultSetMetaData metaData = rs.getMetaData();
    int columnCount = metaData.getColumnCount();

    List<ColumnInfo> columns = new ArrayList<>();
    for (int i = 1; i <= columnCount; i++) {
      columns.add(new ColumnInfo(
          metaData.getColumnName(i),
          metaData.getColumnType(i),
          metaData.getColumnTypeName(i)
      ));
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
    private final int sqlType;
    private final String typeName;

    public ColumnInfo(String name, int sqlType, String typeName) {
      this.name = name;
      this.sqlType = sqlType;
      this.typeName = typeName;
    }

    public String getName() {
      return name;
    }

    public int getSqlType() {
      return sqlType;
    }

    public String getTypeName() {
      return typeName;
    }
  }

  public static class QueryResult {
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
