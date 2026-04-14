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
import org.apache.calcite.adapter.milvus.sql.client.command.MilvusCommandExecutorFactory;
import org.apache.calcite.adapter.milvus.sql.client.command.MilvusCommandPacketFactory;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;

import org.apache.shardingsphere.database.protocol.mysql.packet.command.MySQLCommandPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.MySQLCommandPacketType;
import org.apache.shardingsphere.database.protocol.mysql.payload.MySQLPacketPayload;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collection;

/**
 * Dispatches MySQL commands to appropriate executors.
 * This is the command layer handler that routes commands to business logic.
 */
public class MilvusCommandDispatcher extends ChannelInboundHandlerAdapter {

  private static final Logger LOGGER = LoggerFactory.getLogger(MilvusCommandDispatcher.class);

  private final SQLExecutor sqlExecutor;

  public MilvusCommandDispatcher(MilvusServerConfig config) {
    this.sqlExecutor =
        new SQLExecutor(config.getMilvusHost(),
            config.getMilvusPort(),
            config.getMilvusDatabase(),
            config.getMilvusUsername(),
            config.getMilvusPassword());
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    LOGGER.debug("[DISPATCH] Received message type: {}", msg.getClass().getName());

    // Handle ByteBuf (raw data from codec)
    if (msg instanceof ByteBuf) {
      ByteBuf buffer = (ByteBuf) msg;

      // Defensive: skip empty buffers (can happen during SSL handshake)
      if (buffer.readableBytes() == 0) {
        buffer.release();
        return;
      }
      LOGGER.debug("[DISPATCH] Received buffer with {} bytes", buffer.readableBytes());
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
          new MySQLPacketPayload(buffer, ctx.channel().attr(
                  org.apache.shardingsphere.database.protocol.constant.CommonConstants.CHARSET_ATTRIBUTE_KEY)
              .get());

      int commandTypeInt = payload.readInt1();
      MySQLCommandPacketType commandType = MySQLCommandPacketType.valueOf(commandTypeInt);
      LOGGER.debug("[DISPATCH] Command type: {} ({})", commandType, commandTypeInt);

      return MilvusCommandPacketFactory.createCommandPacket(commandType, payload);
    } catch (Exception e) {
      LOGGER.warn("[DISPATCH] Failed to parse command packet", e);
      return null;
    }
  }

  /**
   * Handles MySQLCommandPacket execution.
   */
  private void handleCommandPacket(ChannelHandlerContext ctx, MySQLCommandPacket command) {
    try {


      // Execute command
      ConnectionSession session = ctx.channel().attr(MilvusAuthHandler.SESSION_KEY).get();
      CommandExecutor executor =
          MilvusCommandExecutorFactory.createExecutor(command, ctx, session, sqlExecutor);
      Collection<DatabasePacket> response = executor.execute();

      // Write all response packets - use write() to collect, then single flush()
      // This allows Netty to merge packets into fewer TCP segments
      int packetCount = 0;
      for (DatabasePacket packet : response) {
        ctx.write(packet);
        packetCount++;
        LOGGER.debug("[DISPATCH] Queued packet {}: {}", packetCount, packet.getClass().getSimpleName());
      }
      ctx.flush();
    } catch (SQLException e) {
      LOGGER.warn("[DISPATCH] SQL Error", e);
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(e));
    } catch (Exception e) {
      LOGGER.error("[DISPATCH] Unexpected error", e);
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Internal error: " + e.getMessage(), 1105, "HY000"));
    }
  }

}
