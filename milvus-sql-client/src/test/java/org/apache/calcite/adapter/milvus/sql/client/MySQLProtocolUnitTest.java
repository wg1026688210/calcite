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
package org.apache.calcite.adapter.milvus.sql.client;

import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MySQL protocol implementation.
 */
public class MySQLProtocolUnitTest {

  @Test
  public void testShowVariablesResponse() {
    // Create a QueryResult similar to what SHOW VARIABLES returns
    List<SQLExecutor.ColumnInfo> columns = new ArrayList<>();
    columns.add(new SQLExecutor.ColumnInfo("Variable_name", Types.VARCHAR, "VARCHAR"));
    columns.add(new SQLExecutor.ColumnInfo("Value", Types.VARCHAR, "VARCHAR"));

    List<List<Object>> rows = new ArrayList<>();
    rows.add(Arrays.asList("character_set_client", "utf8"));

    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(columns, rows, 0);

    // Build the response
    Collection<DatabasePacket> packets = MySQLResponseBuilder.buildQueryResponse(result);

    // Verify packet structure
    assertEquals(6, packets.size(), "Should have 6 packets: field_count + 2 col_defs + EOF + 1 row + OK");

    // Check packet types
    List<String> packetTypes = new ArrayList<>();
    for (DatabasePacket packet : packets) {
      packetTypes.add(packet.getClass().getSimpleName());
    }

    assertEquals("MySQLFieldCountPacket", packetTypes.get(0));
    assertEquals("MySQLColumnDefinition41Packet", packetTypes.get(1));
    assertEquals("MySQLColumnDefinition41Packet", packetTypes.get(2));
    assertEquals("MySQLEofPacket", packetTypes.get(3), "EOF packet should be at index 3");
    assertEquals("MySQLTextResultSetRowPacket", packetTypes.get(4));
    assertEquals("MySQLOKPacket", packetTypes.get(5));
  }

  @Test
  public void testEmptyResultSetResponse() {
    // Create an empty QueryResult
    List<SQLExecutor.ColumnInfo> columns = new ArrayList<>();
    columns.add(new SQLExecutor.ColumnInfo("id", Types.INTEGER, "INTEGER"));

    List<List<Object>> rows = new ArrayList<>();

    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(columns, rows, 0);

    // Build the response
    Collection<DatabasePacket> packets = MySQLResponseBuilder.buildQueryResponse(result);

    // Verify packet structure
    assertEquals(4, packets.size(), "Should have 4 packets: field_count + 1 col_def + EOF + OK");

    // Check packet types
    List<String> packetTypes = new ArrayList<>();
    for (DatabasePacket packet : packets) {
      packetTypes.add(packet.getClass().getSimpleName());
    }

    assertEquals("MySQLFieldCountPacket", packetTypes.get(0));
    assertEquals("MySQLColumnDefinition41Packet", packetTypes.get(1));
    assertEquals("MySQLEofPacket", packetTypes.get(2), "EOF packet should be at index 2");
    assertEquals("MySQLOKPacket", packetTypes.get(3));
  }

  @Test
  public void testUpdateResponse() {
    // Create a non-QueryResult (update count)
    SQLExecutor.QueryResult result = new SQLExecutor.QueryResult(new ArrayList<>(), new ArrayList<>(), 5);

    // Build the response
    Collection<DatabasePacket> packets = MySQLResponseBuilder.buildQueryResponse(result);

    // Verify packet structure
    assertEquals(1, packets.size(), "Should have 1 packet: OK");

    String packetType = packets.iterator().next().getClass().getSimpleName();
    assertEquals("MySQLOKPacket", packetType);
  }
}
