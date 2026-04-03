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

import org.apache.calcite.adapter.milvus.sql.client.auth.MilvusAuthenticator;
import org.apache.calcite.adapter.milvus.sql.client.auth.MySQLClearPasswordAuthenticator;
import org.apache.calcite.adapter.milvus.sql.client.auth.MySQLNativePasswordAuthenticator;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;
import org.apache.calcite.adapter.milvus.sql.client.ssl.MilvusSSLRequestHandler;

import org.apache.shardingsphere.database.protocol.codec.PacketCodec;
import org.apache.shardingsphere.database.protocol.constant.CommonConstants;
import org.apache.shardingsphere.database.protocol.mysql.codec.MySQLPacketCodecEngine;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLAuthenticationMethod;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCapabilityFlag;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCharacterSets;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConnectionPhase;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConstants;
import org.apache.shardingsphere.database.protocol.mysql.netty.MySQLSequenceIdInboundHandler;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthenticationPluginData;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthSwitchRequestPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthSwitchResponsePacket;
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
 * Handles MySQL authentication handshake with state machine,
 * real password validation, SSL upgrade, and auth switch support.
 */
public class MilvusAuthHandler extends ChannelInboundHandlerAdapter {

  public static final AttributeKey<ConnectionSession> SESSION_KEY =
      AttributeKey.valueOf("milvus.session");

  private static final AtomicInteger CONNECTION_ID_GENERATOR = new AtomicInteger(1);

  private final MilvusServerConfig config;

  private boolean handshakeComplete;
  private MySQLConnectionPhase connectionPhase = MySQLConnectionPhase.INITIAL_HANDSHAKE;
  private MySQLAuthenticationPluginData authPluginData = new MySQLAuthenticationPluginData();
  private int connectionId;

  private byte[] authResponse;
  private String currentUsername;
  private String currentDatabase;

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
    if (handshakeComplete) {
      ctx.fireChannelRead(msg);
      return;
    }
    if (!(msg instanceof ByteBuf)) {
      return;
    }
    ByteBuf buffer = (ByteBuf) msg;
    try {
      MySQLPacketPayload payload = new MySQLPacketPayload(buffer, StandardCharsets.UTF_8);
      switch (connectionPhase) {
        case AUTH_PHASE_FAST_PATH:
          processFastPath(ctx, payload);
          break;
        case AUTHENTICATION_METHOD_MISMATCH:
          processAuthSwitch(ctx, payload);
          break;
        default:
          break;
      }
    } finally {
      buffer.release();
    }
  }

  /**
   * Sends MySQL handshake packet to client.
   */
  private void sendHandshake(ChannelHandlerContext ctx) {
    connectionId = CONNECTION_ID_GENERATOR.getAndIncrement();
    connectionPhase = MySQLConnectionPhase.AUTH_PHASE_FAST_PATH;

    boolean sslEnabled = config.isSslEnabled();
    if (sslEnabled) {
      ctx.pipeline().addFirst(
          MilvusSSLRequestHandler.class.getSimpleName(),
          new MilvusSSLRequestHandler());
    }

    MySQLHandshakePacket handshake = new MySQLHandshakePacket(
        connectionId, sslEnabled, authPluginData);
    MySQLAuthenticationMethod authMethod = resolveAuthMethod(config.getAuthPlugin());
    handshake.setAuthPluginName(authMethod);

    ctx.writeAndFlush(handshake);
  }

  /**
   * Processes the fast-path handshake response.
   */
  private void processFastPath(ChannelHandlerContext ctx, MySQLPacketPayload payload) {
    try {
      MySQLHandshakeResponse41Packet response = new MySQLHandshakeResponse41Packet(payload);
      authResponse = response.getAuthResponse();
      currentUsername = response.getUsername();
      currentDatabase = response.getDatabase();

      setMultiStatementsOption(ctx, response);
      setCharacterSet(ctx, response);

      MilvusAuthenticator authenticator = createAuthenticator(config.getAuthPlugin());
      String expectedPlugin = authenticator.getAuthenticationMethodName();

      if (shouldAuthSwitch(response, expectedPlugin)) {
        connectionPhase = MySQLConnectionPhase.AUTHENTICATION_METHOD_MISMATCH;
        ctx.writeAndFlush(new MySQLAuthSwitchRequestPacket(expectedPlugin, authPluginData));
        return;
      }

      completeAuthentication(ctx, authenticator);
    } catch (Exception e) {
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Access denied: " + e.getMessage(), 1045, "28000"));
      ctx.close();
    }
  }

  /**
   * Processes auth switch response.
   */
  private void processAuthSwitch(ChannelHandlerContext ctx, MySQLPacketPayload payload) {
    try {
      MySQLAuthSwitchResponsePacket response = new MySQLAuthSwitchResponsePacket(payload);
      authResponse = response.getAuthPluginResponse();
      completeAuthentication(ctx, createAuthenticator(config.getAuthPlugin()));
    } catch (Exception e) {
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Access denied: " + e.getMessage(), 1045, "28000"));
      ctx.close();
    }
  }

  /**
   * Completes authentication after credentials are available.
   */
  private void completeAuthentication(ChannelHandlerContext ctx,
      MilvusAuthenticator authenticator) {
    String configuredUsername = config.getMilvusUsername();
    String configuredPassword = config.getMilvusPassword();

    if (configuredUsername != null && !configuredUsername.isEmpty()) {
      if (!configuredUsername.equals(currentUsername)) {
        ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
            "Access denied for user '" + currentUsername + "'", 1045, "28000"));
        ctx.close();
        return;
      }
      if (!authenticator.authenticate(configuredPassword, authResponse, authPluginData)) {
        ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
            "Access denied for user '" + currentUsername + "'", 1045, "28000"));
        ctx.close();
        return;
      }
    }

    ConnectionSession session = new ConnectionSession(
        connectionId, ctx.channel(),
        currentDatabase != null ? currentDatabase : config.getMilvusDatabase());
    session.setAuthenticated(true);
    ctx.channel().attr(SESSION_KEY).set(session);

    ctx.writeAndFlush(MySQLResponseBuilder.buildOKPacket(0));
    handshakeComplete = true;
  }

  private boolean shouldAuthSwitch(MySQLHandshakeResponse41Packet response,
      String expectedPlugin) {
    if (authResponse == null || authResponse.length == 0) {
      return true;
    }
    return isClientPluginAuth(response)
        && !expectedPlugin.equals(response.getAuthPluginName());
  }

  private boolean isClientPluginAuth(MySQLHandshakeResponse41Packet response) {
    return 0 != (response.getCapabilityFlags()
        & MySQLCapabilityFlag.CLIENT_PLUGIN_AUTH.getValue());
  }

  private void setMultiStatementsOption(ChannelHandlerContext ctx,
      MySQLHandshakeResponse41Packet response) {
    ctx.channel().attr(MySQLConstants.OPTION_MULTI_STATEMENTS_ATTRIBUTE_KEY)
        .set(response.getMultiStatementsOption());
  }

  private void setCharacterSet(ChannelHandlerContext ctx,
      MySQLHandshakeResponse41Packet response) {
    try {
      MySQLCharacterSets characterSet = MySQLCharacterSets.findById(response.getCharacterSet());
      ctx.channel().attr(CommonConstants.CHARSET_ATTRIBUTE_KEY)
          .set(characterSet.getCharset());
      ctx.channel().attr(MySQLConstants.CHARACTER_SET_ATTRIBUTE_KEY)
          .set(characterSet);
    } catch (RuntimeException e) {
      ctx.channel().attr(CommonConstants.CHARSET_ATTRIBUTE_KEY)
          .set(StandardCharsets.UTF_8);
    }
  }

  private MilvusAuthenticator createAuthenticator(String authPlugin) {
    MySQLAuthenticationMethod method = resolveAuthMethod(authPlugin);
    switch (method) {
      case NATIVE:
        return new MySQLNativePasswordAuthenticator();
      case CLEAR_TEXT:
        return new MySQLClearPasswordAuthenticator();
      default:
        return new MySQLNativePasswordAuthenticator();
    }
  }

  private MySQLAuthenticationMethod resolveAuthMethod(String authPlugin) {
    if (authPlugin == null || authPlugin.isEmpty()) {
      return MySQLAuthenticationMethod.NATIVE;
    }
    for (MySQLAuthenticationMethod method : MySQLAuthenticationMethod.values()) {
      if (method.getMethodName().equalsIgnoreCase(authPlugin)) {
        return method;
      }
    }
    return MySQLAuthenticationMethod.NATIVE;
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.close();
  }
}
