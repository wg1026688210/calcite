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

import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;

import org.apache.shardingsphere.database.protocol.mysql.packet.generic.MySQLOKPacket;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles MySQL system variable queries (@@variable) for JDBC compatibility.
 * MySQL 8.0+ driver sends these queries during connection initialization.
 */
public final class SystemVariableHandler {

  // Pattern to match SELECT @@variable or SELECT @@session.variable
  private static final Pattern VARIABLE_PATTERN =
      Pattern.compile("@@(?:session\\.)?([a-zA-Z_]+)");

  // Mock values for common system variables
  private static final Map<String, String> VARIABLE_VALUES = new HashMap<>();

  static {
    // Connection and charset variables
    VARIABLE_VALUES.put("auto_increment_increment", "1");
    VARIABLE_VALUES.put("character_set_client", "utf8mb4");
    VARIABLE_VALUES.put("character_set_connection", "utf8mb4");
    VARIABLE_VALUES.put("character_set_results", "utf8mb4");
    VARIABLE_VALUES.put("character_set_server", "utf8mb4");
    VARIABLE_VALUES.put("collation_server", "utf8mb4_general_ci");
    VARIABLE_VALUES.put("collation_connection", "utf8mb4_general_ci");
    VARIABLE_VALUES.put("init_connect", "");
    VARIABLE_VALUES.put("interactive_timeout", "28800");
    VARIABLE_VALUES.put("wait_timeout", "28800");

    // System info variables
    VARIABLE_VALUES.put("license", "GPL");
    VARIABLE_VALUES.put("lower_case_table_names", "0");
    VARIABLE_VALUES.put("max_allowed_packet", "4194304");
    VARIABLE_VALUES.put("net_write_timeout", "60");
    VARIABLE_VALUES.put("performance_schema", "OFF");
    VARIABLE_VALUES.put("version_comment", "MySQL Community Server - GPL");

    // Cache and mode variables (deprecated in MySQL 8.0 but still queried)
    VARIABLE_VALUES.put("query_cache_size", "0");
    VARIABLE_VALUES.put("query_cache_type", "OFF");
    VARIABLE_VALUES.put("sql_mode", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES");

    // Timezone variables
    VARIABLE_VALUES.put("system_time_zone", "UTC");
    VARIABLE_VALUES.put("time_zone", "SYSTEM");
    VARIABLE_VALUES.put("timezone", "SYSTEM");

    // Transaction variables
    VARIABLE_VALUES.put("transaction_isolation", "REPEATABLE-READ");
    VARIABLE_VALUES.put("tx_isolation", "REPEATABLE-READ");

    // Character set aliases
    VARIABLE_VALUES.put("character_set", "utf8mb4");
  }

  /**
   * Checks if the SQL is a system variable query.
   */
  public static boolean isSystemVariableQuery(String sql) {
    if (sql == null) return false;
    String upper = sql.toUpperCase();
    return upper.contains("@@") || upper.contains("SHOW VARIABLES");
  }

  /**
   * Handles system variable query and returns mock result.
   */
  public static Collection<DatabasePacket> handle(String sql) {
    return handle(sql, false);
  }

  /**
   * Handles system variable query and returns mock result.
   * @param deprecateEof true if CLIENT_DEPRECATE_EOF is set (MySQL 5.7.5+)
   */
  public static Collection<DatabasePacket> handle(String sql, boolean deprecateEof) {
    List<DatabasePacket> packets = new ArrayList<>();

    if (sql.toUpperCase().contains("SHOW VARIABLES")) {
      // Handle SHOW VARIABLES WHERE Variable_name IN (...)
      return handleShowVariables(sql, deprecateEof);
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
      // No variables matched, return empty OK with proper status flags
      packets.add(MySQLResponseBuilder.buildOKPacket(0));
      return packets;
    }

    // Build result set with variable names and values
    return buildVariableResultSet(results, deprecateEof);
  }

  /**
   * Handles SHOW VARIABLES query.
   */
  private static Collection<DatabasePacket> handleShowVariables(String sql, boolean deprecateEof) {
    // Extract variable names from WHERE clause if present
    List<String> requestedVars = extractVariableNames(sql);

    Map<String, String> results = new HashMap<>();
    if (requestedVars.isEmpty()) {
      // Return all known variables
      results.putAll(VARIABLE_VALUES);
    } else {
      // Return only requested variables
      for (String var : requestedVars) {
        String value = VARIABLE_VALUES.getOrDefault(var.toLowerCase(), "");
        results.put(var, value);
      }
    }

    return buildShowVariablesResultSet(results, deprecateEof);
  }

  /**
   * Extracts variable names from SHOW VARIABLES WHERE clause.
   */
  private static List<String> extractVariableNames(String sql) {
    List<String> vars = new ArrayList<>();
    // Match Variable_name = 'xxx' or Variable_name LIKE 'xxx'
    Pattern pattern =
        Pattern.compile("Variable_name\\s*(?:=|LIKE)\\s*['\"]([^'\"]+)['\"]",
        Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(sql);
    while (matcher.find()) {
      vars.add(matcher.group(1));
    }
    return vars;
  }

  /**
   * Builds result set for SELECT @@variable queries.
   */
  private static Collection<DatabasePacket> buildVariableResultSet(
      Map<String, String> variables, boolean deprecateEof) {
    // For SELECT @@var AS alias, we return the values directly
    // Use the first variable name as column name for proper client compatibility
    String columnName = variables.isEmpty() ? null : "@@" + variables.keySet().iterator().next();
    return MySQLResponseBuilder.buildVariableQueryResponse(variables, deprecateEof, columnName);
  }

  /**
   * Builds result set for SHOW VARIABLES queries.
   */
  private static Collection<DatabasePacket> buildShowVariablesResultSet(
      Map<String, String> variables, boolean deprecateEof) {
    return MySQLResponseBuilder.buildShowVariablesResponse(variables, deprecateEof);
  }
}
