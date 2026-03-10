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

  private static final int SERVER_STATUS_AUTOCOMMIT = 0x0002;
  private static final int CHARSET_UTF8MB4 = 45;
  private static final String SCHEMA_DEF = "def";

  private MySQLResponseBuilder() {
    // utility class
  }

  /**
   * Builds a query response from SQL execution result.
   * Returns: field count + column definitions + EOF + rows + EOF packet
   */
  public static Collection<DatabasePacket> buildQueryResponse(SQLExecutor.QueryResult result) {
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

    // 3. EOF packet after column definitions (required for MySQL 5.1 compatibility)
    packets.add(new MySQLEofPacket(SERVER_STATUS_AUTOCOMMIT));

    // 4. Row data packets - handle null values
    for (List<Object> row : result.getRows()) {
      List<Object> safeRow = new ArrayList<>();
      for (Object value : row) {
        safeRow.add(value != null ? value : "");
      }
      packets.add(new MySQLTextResultSetRowPacket(safeRow));
    }

    // 5. Final EOF packet
    packets.add(new MySQLEofPacket(SERVER_STATUS_AUTOCOMMIT));

    System.err.println("[RESPONSE] Built query response:");
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
    return new MySQLOKPacket(affectedRows, 0, SERVER_STATUS_AUTOCOMMIT);
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
  private static DatabasePacket createColumnDefinitionPacket(SQLExecutor.ColumnInfo column) {
    MySQLBinaryColumnType columnType = mapSqlTypeToMySQLType(column.getSqlType());

    return new MySQLColumnDefinition41Packet(
        CHARSET_UTF8MB4,                 // characterSet
        SCHEMA_DEF,                      // schema
        "",                              // table
        "",                              // orgTable
        column.getName(),                // name
        column.getName(),                // orgName
        255,                             // columnLength
        columnType,                      // columnType
        0,                               // decimals
        false                            // containDefaultValues
    );
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
}
