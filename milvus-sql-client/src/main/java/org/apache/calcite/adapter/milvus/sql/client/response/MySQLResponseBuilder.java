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
package org.apache.calcite.adapter.milvus.sql.client.response;

import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;

import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLBinaryColumnType;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCapabilityFlag;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.MySQLColumnDefinition41Packet;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.MySQLFieldCountPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.text.MySQLTextResultSetRowPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.generic.MySQLEofPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.generic.MySQLErrPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.generic.MySQLOKPacket;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builder for MySQL protocol response packets.
 * Converts SQL execution results to MySQL protocol packets.
 */
public final class MySQLResponseBuilder {

  // MySQL Status Flags - based on ShardingSphere MySQLStatusFlag enum
  private static final int SERVER_STATUS_IN_TRANS = 0x0001;
  private static final int SERVER_STATUS_AUTOCOMMIT = 0x0002;
  private static final int SERVER_MORE_RESULTS_EXISTS = 0x0008;
  private static final int SERVER_STATUS_NO_GOOD_INDEX_USED = 0x0010;
  private static final int SERVER_STATUS_NO_INDEX_USED = 0x0020;
  private static final int SERVER_STATUS_METADATA_CHANGED = 0x0400;

  private static final int CHARSET_UTF8MB4 = 45;
  private static final String SCHEMA_DEF = "def";

  // Default status flags: AUTOCOMMIT enabled, not in transaction
  private static final int DEFAULT_STATUS_FLAGS = SERVER_STATUS_AUTOCOMMIT;

  private MySQLResponseBuilder() {
    // utility class
  }

  /**
   * Calculates server status flags.
   * Based on ShardingSphere ServerStatusFlagCalculator.
   *
   * @param autoCommit whether autocommit is enabled (default: true)
   * @param inTransaction whether in transaction (default: false)
   * @param moreResultsExist whether more results exist (default: false)
   * @return calculated status flags
   */
  public static int calculateStatusFlags(boolean autoCommit, boolean inTransaction, boolean moreResultsExist) {
    int result = 0;
    result |= autoCommit ? SERVER_STATUS_AUTOCOMMIT : 0;
    result |= inTransaction ? SERVER_STATUS_IN_TRANS : 0;
    result |= moreResultsExist ? SERVER_MORE_RESULTS_EXISTS : 0;
    return result;
  }

  /**
   * Calculates default status flags (autocommit=ON, not in transaction, no more results).
   */
  public static int calculateStatusFlags() {
    return calculateStatusFlags(true, false, false);
  }

  /**
   * Builds a query response from SQL execution result.
   * Returns: field count + column definitions + (EOF or OK) + rows + (EOF or OK) packet
   * MySQL 5.7.5+ uses OK instead of EOF when CLIENT_DEPRECATE_EOF is set
   */
  public static Collection<DatabasePacket> buildQueryResponse(SQLExecutor.QueryResult result) {
    return buildQueryResponse(result, false); // Default to EOF for backward compatibility
  }

  /**
   * Builds a query response with client capability awareness.
   * @param deprecateEof true if CLIENT_DEPRECATE_EOF is set (MySQL 5.7.5+)
   */
  public static Collection<DatabasePacket> buildQueryResponse(SQLExecutor.QueryResult result, boolean deprecateEof) {
    List<DatabasePacket> packets = new ArrayList<>();

    if (!result.isResultSet()) {
      // Non-query result (UPDATE/INSERT/DELETE)
      packets.add(buildOKPacket(result.getUpdateCount()));
      System.err.println("[RESPONSE] Built non-query response, packets: " + packets.size());
      return packets;
    }

    // 1. Field count packet
    packets.add(new MySQLFieldCountPacket(result.getColumns().size()));

    // 2. Column definition packets
    for (SQLExecutor.ColumnInfo column : result.getColumns()) {
      packets.add(createColumnDefinitionPacket(column));
    }

    // 3. After column definitions: EOF (old) or nothing (new with DEPRECATE_EOF)
    int statusFlags = calculateStatusFlags();
    if (!deprecateEof) {
      // Only send EOF when DEPRECATE_EOF is NOT set
      packets.add(new MySQLEofPacket(0, statusFlags));
    }
    // When DEPRECATE_EOF is set, NO intermediate packet is sent after column definitions

    // 4. Row data packets - handle null values
    for (List<Object> row : result.getRows()) {
      List<Object> safeRow = new ArrayList<>();
      for (Object value : row) {
        safeRow.add(value != null ? value : "");
      }
      packets.add(new MySQLTextResultSetRowPacket(safeRow));
    }

    // 5. Final: Always use EOF for maximum compatibility
    // MySQL CLI 8.0 may have issues with OK packet in result set
    packets.add(new MySQLEofPacket(0, statusFlags));

    System.err.println("[RESPONSE] Built query response (deprecateEof=" + deprecateEof + "):");
    System.err.println("[RESPONSE]   Columns: " + result.getColumns().size());
    System.err.println("[RESPONSE]   Rows: " + result.getRows().size());
    System.err.println("[RESPONSE]   Packets: " + packets.size());
    for (int i = 0; i < packets.size(); i++) {
      System.err.println("[RESPONSE]     [" + i + "] " + packets.get(i).getClass().getSimpleName());
    }

    return packets;
  }

  /**
   * Builds an OK packet for successful operations.
   */
  public static DatabasePacket buildOKPacket(int affectedRows) {
    return new MySQLOKPacket(affectedRows, 0, calculateStatusFlags(), 0, "");
  }

  /**
   * Builds an OK packet for successful operations with custom status flags.
   */
  public static DatabasePacket buildOKPacket(int affectedRows, int statusFlags) {
    return new MySQLOKPacket(affectedRows, 0, statusFlags, 0, "");
  }

  /**
   * Builds an error packet for failed operations.
   */
  public static DatabasePacket buildErrorPacket(String message, int errorCode, String sqlState) {
    SQLException exception = new SQLException(message, sqlState, errorCode);
    return new MySQLErrPacket(exception);
  }

  /**
   * Builds an error packet from SQLException.
   */
  public static DatabasePacket buildErrorPacket(SQLException e) {
    String message = e.getMessage();
    if (message == null) {
      message = "Unknown error";
    }
    String sqlState = e.getSQLState();
    if (sqlState == null) {
      sqlState = "HY000";
    }
    int errorCode = e.getErrorCode() != 0 ? e.getErrorCode() : 1064;
    SQLException wrapped = new SQLException(message, sqlState, errorCode);
    return new MySQLErrPacket(wrapped);
  }

  /**
   * Creates a column definition packet from column info.
   */
  // MySQL Column Definition Flags - from MySQLColumnDefinitionFlag enum
  private static final int FLAG_NOT_NULL = 0x0001;
  private static final int FLAG_UNSIGNED = 0x0020;

  private static DatabasePacket createColumnDefinitionPacket(SQLExecutor.ColumnInfo column) {
    MySQLBinaryColumnType columnType = mapSqlTypeToMySQLType(column.getSqlType());
    // Use empty strings for all optional fields to match native MySQL behavior
    // - schema: native MySQL uses empty string (1 byte: 0x00) instead of "def" (4 bytes)
    // - table/orgTable: empty string (1 byte each) instead of actual names
    // - orgName: empty string (1 byte) instead of repeating column name
    // This reduces packet size from ~59 bytes to ~37 bytes
    String schemaName = "";
    String tableName = "";
    String orgTableName = "";
    String orgColumnName = "";
    String columnLabel = column.getLabel() == null || column.getLabel().isEmpty()
        ? column.getName() : column.getLabel();

    // Calculate flags - set NOT_NULL for all columns, UNSIGNED for numeric types
    int flags = FLAG_NOT_NULL;
    if (isUnsignedType(column.getSqlType())) {
      flags |= FLAG_UNSIGNED;
    }

    // Ensure columnLength is never 0 (use 255 as default, matching ShardingSphere behavior)
    int columnLength = column.getDisplaySize();
    if (columnLength <= 0) {
      columnLength = 255;
    }

    return new MySQLColumnDefinition41Packet(
        CHARSET_UTF8MB4,
        flags,
        schemaName,      // schema - empty for result set metadata
        tableName,       // table - empty
        orgTableName,    // orgTable - empty (not "tableName")
        columnLabel,     // name - the actual column label/name
        orgColumnName,   // orgName - empty (not repeating column name)
        columnLength,
        columnType,
        column.getDecimals(),
        false);
  }

  private static boolean isUnsignedType(int sqlType) {
    switch (sqlType) {
      case java.sql.Types.TINYINT:
      case java.sql.Types.SMALLINT:
      case java.sql.Types.INTEGER:
      case java.sql.Types.BIGINT:
        return true;
      default:
        return false;
    }
  }

  /**
   * Maps JDBC SQL types to MySQL binary column types.
   */
  private static MySQLBinaryColumnType mapSqlTypeToMySQLType(int sqlType) {
    switch (sqlType) {
      case Types.INTEGER:
        return MySQLBinaryColumnType.LONG;
      case Types.BIGINT:
        return MySQLBinaryColumnType.LONGLONG;
      case Types.SMALLINT:
        return MySQLBinaryColumnType.SHORT;
      case Types.TINYINT:
        return MySQLBinaryColumnType.TINY;
      case Types.FLOAT:
      case Types.REAL:
        return MySQLBinaryColumnType.FLOAT;
      case Types.DOUBLE:
        return MySQLBinaryColumnType.DOUBLE;
      case Types.DECIMAL:
      case Types.NUMERIC:
        return MySQLBinaryColumnType.DECIMAL;
      case Types.VARCHAR:
      case Types.CHAR:
      case Types.LONGVARCHAR:
        return MySQLBinaryColumnType.VARCHAR;
      case Types.BLOB:
      case Types.LONGVARBINARY:
      case Types.VARBINARY:
      case Types.BINARY:
        return MySQLBinaryColumnType.BLOB;
      case Types.DATE:
        return MySQLBinaryColumnType.DATE;
      case Types.TIME:
        return MySQLBinaryColumnType.TIME;
      case Types.TIMESTAMP:
        return MySQLBinaryColumnType.TIMESTAMP;
      case Types.BIT:
      case Types.BOOLEAN:
        return MySQLBinaryColumnType.BIT;
      default:
        return MySQLBinaryColumnType.VARCHAR;
    }
  }

  /**
   * Builds response for variable query (SELECT @@variable).
   */
  public static Collection<DatabasePacket> buildVariableQueryResponse(
      java.util.Map<String, String> variables) {
    return buildVariableQueryResponse(variables, false);
  }

  /**
   * Builds response for variable query (SELECT @@variable).
   * @param deprecateEof true if CLIENT_DEPRECATE_EOF is set (MySQL 5.7.5+)
   */
  public static Collection<DatabasePacket> buildVariableQueryResponse(
      java.util.Map<String, String> variables, boolean deprecateEof) {
    return buildVariableQueryResponse(variables, deprecateEof, null);
  }

  public static Collection<DatabasePacket> buildVariableQueryResponse(
      java.util.Map<String, String> variables, boolean deprecateEof, String columnName) {
    List<DatabasePacket> packets = new ArrayList<>();

    // Use first variable name as column name, or default to "@@variable"
    String colName = columnName;
    if (colName == null || colName.isEmpty()) {
      colName = variables.isEmpty() ? "@@variable" : "@@" + variables.keySet().iterator().next();
    }

    // Single column result with variable value
    packets.add(new MySQLFieldCountPacket(1));
    packets.add(
        new MySQLColumnDefinition41Packet(
        CHARSET_UTF8MB4, FLAG_NOT_NULL, "", "", "",
        colName, "", 1024,
        MySQLBinaryColumnType.VARCHAR, 0, false));
    int statusFlags = calculateStatusFlags();
    // Intermediate packet: EOF (old) or nothing (new with DEPRECATE_EOF)
    if (!deprecateEof) {
      packets.add(new MySQLEofPacket(0, statusFlags));
    }

    // Add row with concatenated values if multiple variables
    StringBuilder value = new StringBuilder();
    for (String v : variables.values()) {
      if (value.length() > 0) value.append(",");
      value.append(v);
    }
    List<Object> row = new ArrayList<>();
    row.add(value.toString());
    packets.add(new MySQLTextResultSetRowPacket(row));

    // Final packet: OK if DEPRECATE_EOF, else EOF
    if (deprecateEof) {
      packets.add(new MySQLOKPacket(0, 0, statusFlags, 0, ""));
    } else {
      packets.add(new MySQLEofPacket(0, statusFlags));
    }
    return packets;
  }

  /**
   * Builds response for SHOW VARIABLES query.
   */
  public static Collection<DatabasePacket> buildShowVariablesResponse(
      java.util.Map<String, String> variables) {
    return buildShowVariablesResponse(variables, false);
  }

  /**
   * Builds response for SHOW VARIABLES query.
   * @param deprecateEof true if CLIENT_DEPRECATE_EOF is set (MySQL 5.7.5+)
   */
  public static Collection<DatabasePacket> buildShowVariablesResponse(
      java.util.Map<String, String> variables, boolean deprecateEof) {
    List<DatabasePacket> packets = new ArrayList<>();

    // Two columns: Variable_name, Value
    packets.add(new MySQLFieldCountPacket(2));
    packets.add(
        new MySQLColumnDefinition41Packet(
            CHARSET_UTF8MB4, FLAG_NOT_NULL, "", "", "",
            "Variable_name", "", 64,
            MySQLBinaryColumnType.VARCHAR, 0, false));
    packets.add(
        new MySQLColumnDefinition41Packet(
            CHARSET_UTF8MB4, FLAG_NOT_NULL, "", "", "",
            "Value", "", 1024,
            MySQLBinaryColumnType.VARCHAR, 0, false));
    int statusFlags = calculateStatusFlags();
    // Intermediate packet: EOF (old) or nothing (new with DEPRECATE_EOF)
    if (!deprecateEof) {
      packets.add(new MySQLEofPacket(0, statusFlags));
    }

    // Add rows
    for (java.util.Map.Entry<String, String> entry : variables.entrySet()) {
      List<Object> row = new ArrayList<>();
      row.add(entry.getKey());
      row.add(entry.getValue());
      packets.add(new MySQLTextResultSetRowPacket(row));
    }

    // Final packet: OK if DEPRECATE_EOF, else EOF
    if (deprecateEof) {
      packets.add(new MySQLOKPacket(0, 0, statusFlags, 0, ""));
    } else {
      packets.add(new MySQLEofPacket(0, statusFlags));
    }
    return packets;
  }
}
