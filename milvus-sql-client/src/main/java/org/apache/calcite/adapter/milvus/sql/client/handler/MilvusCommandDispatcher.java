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
package org.apache.calcite.adapter.milvus.sql.client.handler;

import org.apache.calcite.adapter.milvus.sql.client.command.CommandExecutor;
import org.apache.calcite.adapter.milvus.sql.client.command.MilvusComInitDbExecutor;
import org.apache.calcite.adapter.milvus.sql.client.command.MilvusComPingExecutor;
import org.apache.calcite.adapter.milvus.sql.client.command.MilvusComQueryExecutor;
import org.apache.calcite.adapter.milvus.sql.client.command.MilvusComQuitExecutor;
import org.apache.calcite.adapter.milvus.sql.client.command.MilvusComUnsupportedExecutor;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;

import org.apache.shardingsphere.database.protocol.mysql.packet.command.MySQLCommandPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.MySQLCommandPacketType;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.initdb.MySQLComInitDbPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.ping.MySQLComPingPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.quit.MySQLComQuitPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.text.query.MySQLComQueryPacket;
import org.apache.shardingsphere.database.protocol.mysql.payload.MySQLPacketPayload;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;


import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.sql.SQLException;
import java.util.Collection;

/**
 * Dispatches MySQL commands to appropriate executors.
 * This is the command layer handler that routes commands to business logic.
 */
public class MilvusCommandDispatcher extends ChannelInboundHandlerAdapter {

  private final SQLExecutor sqlExecutor;

  public MilvusCommandDispatcher(MilvusServerConfig config) {
    this.sqlExecutor =
        new SQLExecutor(config.getMilvusHost(),
        config.getMilvusPort(),
        config.getMilvusDatabase(),
        config.getMilvusUsername(),
        config.getMilvusPassword());
  }

  @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
    System.err.println("[DISPATCH] Received message type: " + msg.getClass().getName());

    // Handle MySQLCommandPacket directly (from codec)
    if (msg instanceof MySQLCommandPacket) {
      handleCommandPacket(ctx, (MySQLCommandPacket) msg);
      return;
    }

    // Handle ByteBuf (raw data from codec)
    if (msg instanceof ByteBuf) {
      ByteBuf buffer = (ByteBuf) msg;

      // Defensive: skip empty buffers (can happen during SSL handshake)
      if (buffer.readableBytes() == 0) {
        buffer.release();
        return;
      }
      System.err.println("[DISPATCH] Received buffer with " + buffer.readableBytes() + " bytes");
      try {
        MySQLCommandPacket command = parseCommandPacket(ctx, buffer);
        if (command != null) {
          handleCommandPacket(ctx, command);
        }
      } finally {
        buffer.release();
      }
      return;
    }

    // Forward other message types
    ctx.fireChannelRead(msg);
  }

  /**
   * Parses ByteBuf into MySQLCommandPacket.
   */
  private MySQLCommandPacket parseCommandPacket(ChannelHandlerContext ctx, ByteBuf buffer) {
    try {
      MySQLPacketPayload payload =
          new MySQLPacketPayload(buffer, ctx.channel().attr(org.apache.shardingsphere.database.protocol.constant.CommonConstants.CHARSET_ATTRIBUTE_KEY).get());

      int commandTypeInt = payload.readInt1();
      MySQLCommandPacketType commandType = MySQLCommandPacketType.valueOf(commandTypeInt);
      System.err.println("[DISPATCH] Command type: " + commandType + " (" + commandTypeInt + ")");

      return createCommandPacket(commandType, payload);
    } catch (Exception e) {
      System.err.println("[DISPATCH] Failed to parse command packet: " + e.getMessage());
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Handles MySQLCommandPacket execution.
   */
  private void handleCommandPacket(ChannelHandlerContext ctx, MySQLCommandPacket command) {
    try {
      // Debug logging
      if (command instanceof MySQLComQueryPacket) {
        String sql = ((MySQLComQueryPacket) command).getSQL();
        System.err.println("[DEBUG] Executing SQL: " + sql);
      }

      // Execute command
      CommandExecutor executor = createExecutor(command, ctx);
      Collection<DatabasePacket> response = executor.execute();

      // Note: Sequence ID is automatically managed by ShardingSphere:
      // - MySQLSequenceIdInboundHandler reads client packet seq and sets to seq+1
      // - MySQLPacketCodecEngine uses getAndIncrement() for each outgoing packet

      // Write all response packets - flush each packet separately for MySQL CLI 8.0 compatibility
      // MySQL CLI 8.0 may not handle batched packets correctly
      int packetCount = 0;
      for (DatabasePacket packet : response) {
        ctx.writeAndFlush(packet);
        packetCount++;
        System.err.println("[DISPATCH] Sent packet " + packetCount + ": " + packet.getClass().getSimpleName());
      }
    } catch (SQLException e) {
      System.err.println("[DISPATCH] SQL Error: " + e.getMessage());
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(e));
    } catch (Exception e) {
      System.err.println("[DISPATCH] Unexpected error:");
      e.printStackTrace();
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Internal error: " + e.getMessage(), 1105, "HY000"));
    }
  }

  private MySQLCommandPacket createCommandPacket(MySQLCommandPacketType type, MySQLPacketPayload payload) {
    switch (type) {
      case COM_QUERY:
        return new MySQLComQueryPacket(payload);
      case COM_PING:
        return new MySQLComPingPacket();
      case COM_INIT_DB:
        return new MySQLComInitDbPacket(payload);
      case COM_QUIT:
        return new MySQLComQuitPacket();
      default:
        // For unsupported commands, just return a placeholder that will be handled
        return new MySQLComQueryPacket(new MySQLPacketPayload(payload.getByteBuf().alloc().buffer(0), null));
    }
  }

  private CommandExecutor createExecutor(MySQLCommandPacket command,
                                         ChannelHandlerContext ctx) {
    ConnectionSession session = ctx.channel().attr(MilvusAuthHandler.SESSION_KEY).get();

    if (command instanceof MySQLComQueryPacket) {
      return new MilvusComQueryExecutor(
          (MySQLComQueryPacket) command, session, sqlExecutor);
    } else if (command instanceof MySQLComPingPacket) {
      return new MilvusComPingExecutor();
    } else if (command instanceof MySQLComInitDbPacket) {
      return new MilvusComInitDbExecutor(
          (MySQLComInitDbPacket) command, session);
    } else if (command instanceof MySQLComQuitPacket) {
      return new MilvusComQuitExecutor(ctx);
    }

    return new MilvusComUnsupportedExecutor(command);
  }

}
