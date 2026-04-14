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
package org.apache.calcite.adapter.milvus.sql.client.command;

import org.apache.calcite.adapter.milvus.sql.client.config.SystemVariables;
import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;

import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles MySQL system variable and mock queries for JDBC compatibility.
 * MySQL 8.0+ driver sends these queries during connection initialization.
 */
public final class SystemVariableHandler {

  // Pattern to match SELECT @@variable or SELECT @@session.variable
  private static final Pattern VARIABLE_PATTERN =
      Pattern.compile("@@(?:session\\.|global\\.)?([a-zA-Z_]+)");

  // Mock values for common system variables - centralized in SystemVariables
  private static final Map<String, String> VARIABLE_VALUES = SystemVariables.VARIABLE_VALUES;

  /**
   * Checks if the SQL is a system query that should be handled here.
   */
  public static boolean isSystemVariableQuery(String sql) {
    if (sql == null) return false;
    String upper = sql.toUpperCase();
    return upper.startsWith("SET ")
        || upper.contains("@@")
        || upper.contains("SHOW VARIABLES")
        || upper.startsWith("SHOW DATABASES")
        || upper.startsWith("SHOW SCHEMAS")
        || upper.startsWith("SHOW TABLES")
        || upper.startsWith("SHOW SESSION STATUS")
        || upper.startsWith("SHOW COLLATION")
        || upper.startsWith("SHOW CHARACTER SET")
        || upper.matches("(?i)^SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*;?$");
  }

  /**
   * Handles system query and returns protocol packets.
   */
  public static Collection<DatabasePacket> handle(String sql,
      SQLExecutor sqlExecutor, ConnectionSession session) throws SQLException {
    String trimmedSql = sql.trim();
    String upperSql = trimmedSql.toUpperCase();

    if (upperSql.startsWith("SET ")) {
      return Collections.singletonList(MySQLResponseBuilder.buildOKPacket());
    }

    if (upperSql.contains("SHOW VARIABLES")) {
      return handleShowVariables(sql);
    }

    if (upperSql.startsWith("SELECT @@SESSION.") || upperSql.startsWith("SELECT @@GLOBAL.")) {
      String prefix = upperSql.startsWith("SELECT @@SESSION.") ? "@@session." : "@@global.";
      return buildSessionVariableResponse(trimmedSql, prefix);
    }

    if (upperSql.matches("(?i)^SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*;?$")) {
      return buildSelectDatabaseResponse(session);
    }

    if (upperSql.startsWith("SHOW DATABASES") || upperSql.startsWith("SHOW SCHEMAS")) {
      return buildShowDatabasesResponse(sqlExecutor);
    }

    if (upperSql.startsWith("SHOW TABLES")) {
      return buildShowTablesResponse(sqlExecutor, session);
    }

    if (upperSql.startsWith("SHOW SESSION STATUS")
        || upperSql.startsWith("SHOW COLLATION")
        || upperSql.startsWith("SHOW CHARACTER SET")) {
      return buildEmptyTwoColumnResponse();
    }

    // Handle SELECT @@variable AS alias, ...
    Matcher matcher = VARIABLE_PATTERN.matcher(sql);
    Map<String, String> results = new HashMap<>();
    while (matcher.find()) {
      String varName = matcher.group(1).toLowerCase();
      String value = VARIABLE_VALUES.getOrDefault(varName, "");
      results.put(varName, value);
    }

    if (results.isEmpty()) {
      return Collections.singletonList(MySQLResponseBuilder.buildOKPacket());
    }

    return buildVariableResultSet(results);
  }

  /**
   * Handles SHOW VARIABLES query.
   */
  private static Collection<DatabasePacket> handleShowVariables(String sql) {
    List<String> requestedVars = extractVariableNames(sql);
    Map<String, String> results = new HashMap<>();
    if (requestedVars.isEmpty()) {
      results.putAll(VARIABLE_VALUES);
    } else {
      for (String var : requestedVars) {
        String value = VARIABLE_VALUES.getOrDefault(var.toLowerCase(), "");
        results.put(var, value);
      }
    }
    return MySQLResponseBuilder.buildShowVariablesResponse(results);
  }

  /**
   * Extracts variable names from SHOW VARIABLES WHERE clause.
   */
  private static List<String> extractVariableNames(String sql) {
    List<String> vars = new ArrayList<>();
    Pattern pattern =
        Pattern.compile("Variable_name\\s*(?:=|LIKE)\\s*['\"]([^'\"]+)['\"]",
        Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(sql);
    while (matcher.find()) {
      vars.add(matcher.group(1));
    }
    return vars;
  }

  private static Collection<DatabasePacket> buildSessionVariableResponse(String sql, String prefix) {
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

    String value = VARIABLE_VALUES.getOrDefault(variable.toLowerCase(), "0");

    List<SQLExecutor.ColumnInfo> columns = new ArrayList<>();
    columns.add(new SQLExecutor.ColumnInfo(variable, Types.VARCHAR, "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();
    rows.add(Arrays.asList(value));
    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(columns, rows, 0);
    return MySQLResponseBuilder.buildQueryResponse(result);
  }

  private static Collection<DatabasePacket> buildSelectDatabaseResponse(ConnectionSession session) {
    String db = session != null ? session.getCurrentDatabase() : "";
    List<SQLExecutor.ColumnInfo> columns = new ArrayList<>();
    columns.add(new SQLExecutor.ColumnInfo("DATABASE()", Types.VARCHAR, "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();
    rows.add(Arrays.asList(db));
    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(columns, rows, 0);
    return MySQLResponseBuilder.buildQueryResponse(result);
  }

  private static Collection<DatabasePacket> buildShowDatabasesResponse(SQLExecutor sqlExecutor) {
    List<SQLExecutor.ColumnInfo> columns = new ArrayList<>();
    columns.add(new SQLExecutor.ColumnInfo("Database", Types.VARCHAR, "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();
    for (String dbName : sqlExecutor.listMilvusDatabases()) {
      rows.add(Arrays.asList(dbName));
    }
    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(columns, rows, 0);
    return MySQLResponseBuilder.buildQueryResponse(result);
  }

  private static Collection<DatabasePacket> buildShowTablesResponse(
      SQLExecutor sqlExecutor, ConnectionSession session) throws SQLException {
    String dbName = session != null && session.getCurrentDatabase() != null
        && !session.getCurrentDatabase().isEmpty()
        ? session.getCurrentDatabase()
        : "default";
    // Try to infer from SQLExecutor default database if session is empty
    List<SQLExecutor.ColumnInfo> columns = new ArrayList<>();
    columns.add(new SQLExecutor.ColumnInfo("Tables_in_" + dbName, Types.VARCHAR, "VARCHAR"));
    List<List<Object>> rows = new ArrayList<>();
    for (String tableName : sqlExecutor.createMilvusSchema(dbName).getTableNames()) {
      rows.add(Arrays.asList(tableName));
    }
    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(columns, rows, 0);
    return MySQLResponseBuilder.buildQueryResponse(result);
  }

  private static Collection<DatabasePacket> buildEmptyTwoColumnResponse() {
    List<SQLExecutor.ColumnInfo> columns = new ArrayList<>();
    columns.add(new SQLExecutor.ColumnInfo("Variable_name", Types.VARCHAR, "VARCHAR"));
    columns.add(new SQLExecutor.ColumnInfo("Value", Types.VARCHAR, "VARCHAR"));
    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(columns, new ArrayList<>(), 0);
    return MySQLResponseBuilder.buildQueryResponse(result);
  }

  /**
   * Builds result set for SELECT @@variable queries.
   */
  private static Collection<DatabasePacket> buildVariableResultSet(
      Map<String, String> variables) {
    String columnName = variables.isEmpty() ? null : "@@" + variables.keySet().iterator().next();
    return MySQLResponseBuilder.buildVariableQueryResponse(variables, columnName);
  }
}
