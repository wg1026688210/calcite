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

import org.apache.shardingsphere.database.protocol.mysql.packet.generic.MySQLErrPacket;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import java.sql.SQLException;

/**
 * Factory for creating standardized MySQL error packets.
 */
public final class MilvusErrorPacketFactory {

  private MilvusErrorPacketFactory() {
    // utility class
  }

  /**
   * Creates a MySQL error packet from any exception.
   */
  public static DatabasePacket newInstance(Exception cause) {
    if (cause instanceof SQLException) {
      SQLException sqlEx = (SQLException) cause;
      String sqlState = sqlEx.getSQLState() != null ? sqlEx.getSQLState() : "HY000";
      int errorCode = sqlEx.getErrorCode() != 0 ? sqlEx.getErrorCode() : 1064;
      SQLException wrapped = new SQLException(sqlEx.getMessage(), sqlState, errorCode);
      return new MySQLErrPacket(wrapped);
    }
    SQLException wrapped = new SQLException(
        cause.getMessage() != null ? cause.getMessage() : "Unknown error", "HY000", 1105);
    return new MySQLErrPacket(wrapped);
  }

  /**
   * Creates a MySQL error packet from message, error code and SQL state.
   */
  public static DatabasePacket newInstance(String message, int errorCode, String sqlState) {
    SQLException exception = new SQLException(message, sqlState, errorCode);
    return new MySQLErrPacket(exception);
  }
}
