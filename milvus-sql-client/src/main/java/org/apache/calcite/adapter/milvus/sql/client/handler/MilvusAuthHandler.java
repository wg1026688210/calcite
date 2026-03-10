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

import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;

import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConstants;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthenticationPluginData;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLHandshakePacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLHandshakeResponse41Packet;
import org.apache.shardingsphere.database.protocol.mysql.payload.MySQLPacketPayload;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles MySQL authentication handshake.
 * This is the authentication layer handler.
 */
public class MilvusAuthHandler extends ChannelInboundHandlerAdapter {

  public static final AttributeKey<ConnectionSession> SESSION_KEY =
      AttributeKey.valueOf("milvus.session");

  private static final AtomicInteger CONNECTION_ID_GENERATOR = new AtomicInteger(1);
  private static final int CHARSET_UTF8 = 33;

  private final MilvusServerConfig config;
  private boolean handshakeComplete;
  private MySQLAuthenticationPluginData authPluginData;
  private int connectionId;

  public MilvusAuthHandler(MilvusServerConfig config) {
    this.config = config;
    this.handshakeComplete = false;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    sendHandshake(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (!handshakeComplete) {
      if (msg instanceof ByteBuf) {
        ByteBuf buffer = (ByteBuf) msg;
        try {
          processHandshakeResponse(ctx, buffer);
        } finally {
          buffer.release();
        }
      }
    } else {
      // Authentication complete, pass to next handler
      ctx.fireChannelRead(msg);
    }
  }

  /**
   * Sends MySQL handshake packet to client.
   */
  private void sendHandshake(ChannelHandlerContext ctx) {
    connectionId = CONNECTION_ID_GENERATOR.getAndIncrement();
    authPluginData = new MySQLAuthenticationPluginData();

    // Create handshake packet with SSL disabled (simpler for now)
    MySQLHandshakePacket handshake = new MySQLHandshakePacket(
        connectionId, false, authPluginData);

    ctx.writeAndFlush(handshake);
  }

  /**
   * Processes handshake response from client.
   */
  private void processHandshakeResponse(ChannelHandlerContext ctx, ByteBuf buffer) {
    try {
      MySQLPacketPayload payload = new MySQLPacketPayload(buffer, StandardCharsets.UTF_8);
      MySQLHandshakeResponse41Packet response = new MySQLHandshakeResponse41Packet(payload);

      // Create connection session and store in channel attribute
      ConnectionSession session = new ConnectionSession(
          connectionId, ctx.channel(), config.getMilvusDatabase());
      session.setAuthenticated(true);
      ctx.channel().attr(SESSION_KEY).set(session);

      // Mark handshake complete
      handshakeComplete = true;

      // Reset sequence ID for command phase (MySQL protocol requirement)
      ctx.channel().attr(MySQLConstants.SEQUENCE_ID_ATTRIBUTE_KEY).set(new AtomicInteger());

      // Send OK packet
      ctx.writeAndFlush(MySQLResponseBuilder.buildOKPacket(0));

    } catch (Exception e) {
      // Send error and close connection
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Access denied: " + e.getMessage(), 1045, "28000"));
      ctx.close();
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.close();
  }
}
