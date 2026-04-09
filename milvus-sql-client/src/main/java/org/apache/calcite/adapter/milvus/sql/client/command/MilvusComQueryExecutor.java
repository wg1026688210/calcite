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

import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;

import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCapabilityFlag;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.text.query.MySQLComQueryPacket;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;

/**
 * Executor for MySQL COM_QUERY commands (SQL queries).
 */
public class MilvusComQueryExecutor implements CommandExecutor {

  private final String sql;
  private final ConnectionSession session;
  private final SQLExecutor sqlExecutor;

  public MilvusComQueryExecutor(MySQLComQueryPacket packet,
                                ConnectionSession session,
                                SQLExecutor sqlExecutor) {
    this.sql = packet.getSQL();
    this.session = session;
    this.sqlExecutor = sqlExecutor;
  }

  @Override public Collection<DatabasePacket> execute() throws SQLException {
    String trimmedSql = sql.trim();

    // Check if client supports DEPRECATE_EOF (MySQL 5.7.5+)
    int flags = session != null ? session.getCapabilityFlags() : 0;
    boolean deprecateEof = (flags & MySQLCapabilityFlag.CLIENT_DEPRECATE_EOF.getValue()) != 0;
    System.err.println("[DEBUG] Client capability flags: 0x" + Integer.toHexString(flags) +
        ", DEPRECATE_EOF: " + deprecateEof);

    // Handle system variable queries (@@variable) for MySQL 8.0+ JDBC compatibility
    if (SystemVariableHandler.isSystemVariableQuery(trimmedSql)) {
      return SystemVariableHandler.handle(trimmedSql, deprecateEof);
    }

    // Handle USE database command (MySQL JDBC sends it as COM_QUERY)
    String upperSql = trimmedSql.toUpperCase();
    if (upperSql.startsWith("USE ")) {
      String dbName = trimmedSql.substring(4).trim().replace(";", "").replace("`", "");
      // Validate database exists
      if (!sqlExecutor.databaseExists(dbName)) {
        return Collections.singletonList(
            MySQLResponseBuilder.buildErrorPacket(
                "Unknown database '" + dbName + "'", 1049, "42000"));
      }
      if (session != null) {
        session.setCurrentDatabase(dbName);
      }
      return Collections.singletonList(MySQLResponseBuilder.buildOKPacket(0));
    }

    // Use session's current database for execution
    String currentDb = session != null ? session.getCurrentDatabase() : null;
    SQLExecutor.QueryResult result = sqlExecutor.execute(sql, currentDb);

    return MySQLResponseBuilder.buildQueryResponse(result, deprecateEof);
  }
}
