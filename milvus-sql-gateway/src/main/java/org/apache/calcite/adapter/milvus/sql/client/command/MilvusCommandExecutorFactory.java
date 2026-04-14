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
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;

import org.apache.shardingsphere.database.protocol.mysql.packet.command.MySQLCommandPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.initdb.MySQLComInitDbPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.ping.MySQLComPingPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.quit.MySQLComQuitPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.text.fieldlist.MySQLComFieldListPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.text.query.MySQLComQueryPacket;

import io.netty.channel.ChannelHandlerContext;

/**
 * Factory for creating command executors from parsed MySQL command packets.
 */
public final class MilvusCommandExecutorFactory {

  private MilvusCommandExecutorFactory() {
    // utility class
  }

  /**
   * Creates a CommandExecutor for the given MySQL command packet.
   */
  public static CommandExecutor createExecutor(MySQLCommandPacket command,
      ChannelHandlerContext ctx, ConnectionSession session, SQLExecutor sqlExecutor) {
    if (command instanceof MySQLComQueryPacket) {
      return new MilvusComQueryExecutor(
          (MySQLComQueryPacket) command, session, sqlExecutor);
    } else if (command instanceof MySQLComPingPacket) {
      return new MilvusComPingExecutor();
    } else if (command instanceof MySQLComInitDbPacket) {
      return new MilvusComInitDbExecutor(
          (MySQLComInitDbPacket) command, session, sqlExecutor);
    } else if (command instanceof MySQLComQuitPacket) {
      return new MilvusComQuitExecutor(ctx);
    } else if (command instanceof MySQLComFieldListPacket) {
      return new MilvusComUnsupportedExecutor(command);
    }
    return new MilvusComUnsupportedExecutor(command);
  }
}
