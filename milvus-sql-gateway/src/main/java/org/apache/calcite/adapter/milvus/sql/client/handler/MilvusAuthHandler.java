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
import org.apache.calcite.adapter.milvus.sql.client.auth.MySQLCachingSha2PasswordAuthenticator;
import org.apache.calcite.adapter.milvus.sql.client.auth.MySQLClearPasswordAuthenticator;
import org.apache.calcite.adapter.milvus.sql.client.auth.MySQLNativePasswordAuthenticator;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.response.MySQLResponseBuilder;
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;
import org.apache.calcite.adapter.milvus.sql.client.ssl.MilvusSSLRequestHandler;

import org.apache.shardingsphere.database.protocol.constant.CommonConstants;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLAuthenticationMethod;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCapabilityFlag;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCharacterSets;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConnectionPhase;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConstants;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthSwitchRequestPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthSwitchResponsePacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthenticationPluginData;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLHandshakePacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLHandshakeResponse41Packet;
import org.apache.shardingsphere.database.protocol.mysql.payload.MySQLPacketPayload;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles MySQL authentication handshake with state machine,
 * real password validation, SSL upgrade, and auth switch support.
 */
public class MilvusAuthHandler extends ChannelInboundHandlerAdapter {

  private static final Logger LOGGER = LoggerFactory.getLogger(MilvusAuthHandler.class);

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
  private int clientCapabilityFlags;

  // MySQL capability flags not exposed by ShardingSphere MySQLCapabilityFlag enum
  private static final int CLIENT_QUERY_ATTRIBUTES = 0x08000000;
  private static final int MULTI_FACTOR_AUTHENTICATION = 0x10000000;

  public MilvusAuthHandler(MilvusServerConfig config) {
    this.config = config;
    this.handshakeComplete = false;
  }

  @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
    sendHandshake(ctx);
  }

  @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (handshakeComplete) {
      // Unified frontend handler routes to dispatcher; do not forward
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

    MySQLHandshakePacket handshake =
        new MySQLHandshakePacket(connectionId, sslEnabled, authPluginData);
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
      clientCapabilityFlags = response.getCapabilityFlags();
      LOGGER.debug("[AUTH] Handshake received - user: {}, database: {}, authResponse length: {}, capabilityFlags: 0x{}",
          currentUsername, currentDatabase,
          authResponse != null ? authResponse.length : 0,
          Integer.toHexString(clientCapabilityFlags));

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
      LOGGER.warn("[AUTH] Exception during handshake processing", e);
      sendAuthErrorAndClose(ctx, "Access denied: " + e.getMessage());
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
      sendAuthErrorAndClose(ctx, "Access denied: " + e.getMessage());
    }
  }

  /**
   * Completes authentication after credentials are available.
   */
  private void completeAuthentication(ChannelHandlerContext ctx,
      MilvusAuthenticator authenticator) {
    // MySQL protocol authentication (separate from Milvus backend credentials)
    String configuredUsername = config.getMysqlUsername();
    String configuredPassword = config.getMysqlPassword();

    LOGGER.debug("[AUTH] Authenticating user: {}, expected: {}", currentUsername, configuredUsername);
    if (configuredUsername != null && !configuredUsername.isEmpty()) {
      if (!configuredUsername.equals(currentUsername)) {
        LOGGER.warn("[AUTH] Username mismatch: {} != {}", currentUsername, configuredUsername);
        sendAuthErrorAndClose(ctx, "Access denied for user '" + currentUsername + "'");
        return;
      }
      LOGGER.debug("[AUTH] Validating password...");
      if (!authenticator.authenticate(configuredPassword, authResponse, authPluginData)) {
        LOGGER.warn("[AUTH] Password validation failed");
        sendAuthErrorAndClose(ctx, "Access denied for user '" + currentUsername + "'");
        return;
      }
      LOGGER.debug("[AUTH] Password validated successfully");
    }

    ConnectionSession session =
        new ConnectionSession(connectionId,
        currentDatabase != null ? currentDatabase : config.getMilvusDatabase());
    session.setAuthenticated(true);
    // Mask out unsupported capability flags to prevent protocol issues
    // CLIENT_SESSION_TRACK requires special OK packet format
    // CLIENT_QUERY_ATTRIBUTES and MULTI_FACTOR_AUTHENTICATION are not supported
    // Note: Keep CLIENT_DEPRECATE_EOF as-is to respect client preference
    int maskedCapabilityFlags = clientCapabilityFlags
        & ~MySQLCapabilityFlag.CLIENT_SESSION_TRACK.getValue()
        & ~CLIENT_QUERY_ATTRIBUTES
        & ~MULTI_FACTOR_AUTHENTICATION;
    session.setCapabilityFlags(maskedCapabilityFlags);
    ctx.channel().attr(SESSION_KEY).set(session);
    LOGGER.debug("[AUTH] Authentication complete, session created for: {}", currentUsername);

    // Set handshakeComplete BEFORE write to avoid race condition
    // Client may send next packet immediately after receiving OK
    handshakeComplete = true;
    ctx.writeAndFlush(MySQLResponseBuilder.buildOKPacket());
    LOGGER.debug("[AUTH] OK packet sent, handshake complete");
  }

  public boolean isHandshakeComplete() {
    return handshakeComplete;
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
      case CACHING_SHA2_PASSWORD:
        return new MySQLCachingSha2PasswordAuthenticator();
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

  /**
   * Sends an authentication error packet and closes the connection.
   */
  private void sendAuthErrorAndClose(ChannelHandlerContext ctx, String message) {
    ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(message, 1045, "28000"));
    ctx.close();
  }

  /**
   * Gets the connection session from a channel.
   */
  public static ConnectionSession getSession(io.netty.channel.Channel channel) {
    return channel.attr(SESSION_KEY).get();
  }

  @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOGGER.error("[AUTH] Unhandled exception", cause);
    ctx.close();
  }

  @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
    if (event instanceof IdleStateEvent) {
      ConnectionSession session = ctx.channel().attr(SESSION_KEY).get();
      int connectionId = session != null ? session.getConnectionId() : -1;
      String database = session != null ? session.getCurrentDatabase() : "NONE";

      LOGGER.warn("[MilvusAuthHandler] Connection {} idle timeout after {}s, closing. database: {}", connectionId, config.getIdleTimeoutSeconds(), database);
      ctx.close();
      return;
    }
    super.userEventTriggered(ctx, event);
  }

  @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    ConnectionSession session = ctx.channel().attr(SESSION_KEY).get();
    if (session != null) {
      LOGGER.debug("[MilvusAuthHandler] Connection {} closed", session.getConnectionId());
      ctx.channel().attr(SESSION_KEY).set(null);
    }
    ctx.fireChannelInactive();
  }
}
