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
import org.apache.shardingsphere.database.protocol.constant.CommonConstants;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLAuthenticationMethod;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCapabilityFlag;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCharacterSets;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConnectionPhase;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConstants;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.MySQLCommandPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.MySQLCommandPacketType;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.initdb.MySQLComInitDbPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.ping.MySQLComPingPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.admin.quit.MySQLComQuitPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.command.query.text.query.MySQLComQueryPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthSwitchRequestPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthSwitchResponsePacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthenticationPluginData;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLHandshakePacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLHandshakeResponse41Packet;
import org.apache.shardingsphere.database.protocol.mysql.payload.MySQLPacketPayload;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified frontend handler that manages both authentication and command dispatch.
 *
 * <p>This design prevents race conditions where the client sends commands immediately
 * after receiving the OK packet but before the server has marked authentication as complete.
 */
public class MilvusFrontendHandler extends ChannelInboundHandlerAdapter {

  public static final AttributeKey<ConnectionSession> SESSION_KEY =
      AttributeKey.valueOf("milvus.session");

  private static final AtomicInteger CONNECTION_ID_GENERATOR = new AtomicInteger(1);

  private final MilvusServerConfig config;
  private final SQLExecutor sqlExecutor;

  // Authentication state
  private final AtomicBoolean authenticated = new AtomicBoolean(false);
  private volatile MySQLConnectionPhase connectionPhase = MySQLConnectionPhase.INITIAL_HANDSHAKE;
  private volatile MySQLAuthenticationPluginData authPluginData = new MySQLAuthenticationPluginData();
  private volatile int connectionId;
  private volatile byte[] authResponse;
  private volatile String currentUsername;
  private volatile String currentDatabase;
  private volatile int clientCapabilityFlags;

  public MilvusFrontendHandler(MilvusServerConfig config, SQLExecutor sqlExecutor) {
    this.config = config;
    this.sqlExecutor = sqlExecutor;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    // MySQLSequenceIdInboundHandler will initialize sequence ID automatically
    sendHandshake(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    // All messages go through this single handler
    // Authentication state determines how we process them
    if (!authenticated.get()) {
      // Still in authentication phase
      handleAuthentication(ctx, msg);
    } else {
      // Authentication complete, handle as command
      handleCommand(ctx, msg);
    }
  }

  /**
   * Handles authentication-phase messages.
   */
  private void handleAuthentication(ChannelHandlerContext ctx, Object msg) {
    if (!(msg instanceof ByteBuf)) {
      // If message is not a ByteBuf, it might be a MySQLCommandPacket that arrived
      // during the race condition window (after OK sent but before authenticated flag set).
      // Re-read the authenticated flag to see if we should process it as a command.
      if (authenticated.get()) {
        // Race condition resolved - authentication is now complete, process as command
        handleCommand(ctx, msg);
      } else {
        // Still in authentication phase but received non-ByteBuf packet
        System.err.println("[AUTH] Warning: Received " + msg.getClass().getSimpleName() +
            " during authentication, but authenticated=" + authenticated.get());
        if (msg instanceof MySQLCommandPacket) {
          System.err.println("[AUTH] Dropping unexpected command packet during authentication");
        }
      }
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
          System.err.println("[AUTH] Unexpected connection phase: " + connectionPhase);
          break;
      }
    } finally {
      if (!authenticated.get()) {
        buffer.release();
      }
    }
  }

  /**
   * Handles post-authentication commands.
   */
  private void handleCommand(ChannelHandlerContext ctx, Object msg) {
    MySQLCommandPacket command;

    if (msg instanceof MySQLCommandPacket) {
      command = (MySQLCommandPacket) msg;
    } else if (msg instanceof ByteBuf) {
      ByteBuf buffer = (ByteBuf) msg;
      try {
        command = parseCommandPacket(buffer, ctx);
        if (command == null) {
          return;
        }
      } finally {
        buffer.release();
      }
    } else {
      System.err.println("[COMMAND] Unknown message type: " + msg.getClass().getName());
      return;
    }

    ConnectionSession session = ctx.channel().attr(SESSION_KEY).get();

    if (session == null) {
      System.err.println("[COMMAND] No session found for authenticated connection!");
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Session not found", 1045, "28000"));
      return;
    }

    try {
      CommandExecutor executor = createExecutor(command, session, ctx);
      Collection<DatabasePacket> response = executor.execute();

      // MySQLSequenceIdInboundHandler manages sequence ID automatically
      // It reads the sequence ID from client request and sets it to +1 for server response

      // Write all response packets
      for (DatabasePacket packet : response) {
        ctx.write(packet);
      }
      ctx.flush();

    } catch (SQLException e) {
      System.err.println("[COMMAND] SQL Error: " + e.getMessage());
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          e.getMessage(), 1064, "42000"));
    } catch (Exception e) {
      System.err.println("[COMMAND] Unexpected error:");
      e.printStackTrace();
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Internal error: " + e.getMessage(), 1105, "HY000"));
    }
  }

  /**
   * Parses ByteBuf into MySQLCommandPacket.
   */
  private MySQLCommandPacket parseCommandPacket(ByteBuf buffer, ChannelHandlerContext ctx) {
    try {
      MySQLPacketPayload payload = new MySQLPacketPayload(buffer,
          ctx.channel().attr(CommonConstants.CHARSET_ATTRIBUTE_KEY).get());

      int commandTypeInt = payload.readInt1();
      MySQLCommandPacketType commandType = MySQLCommandPacketType.valueOf(commandTypeInt);
      System.err.println("[COMMAND] Command type: " + commandType + " (" + commandTypeInt + ")");

      return createCommandPacket(commandType, payload);
    } catch (Exception e) {
      System.err.println("[COMMAND] Failed to parse command packet: " + e.getMessage());
      e.printStackTrace();
      return null;
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
                                         ConnectionSession session,
                                         ChannelHandlerContext ctx) {
    if (command instanceof MySQLComQueryPacket) {
      return new MilvusComQueryExecutor((MySQLComQueryPacket) command, session, sqlExecutor);
    } else if (command instanceof MySQLComPingPacket) {
      return new MilvusComPingExecutor();
    } else if (command instanceof MySQLComInitDbPacket) {
      return new MilvusComInitDbExecutor((MySQLComInitDbPacket) command, session);
    } else if (command instanceof MySQLComQuitPacket) {
      return new MilvusComQuitExecutor(ctx);
    } else {
      return new MilvusComUnsupportedExecutor(command);
    }
  }

  /**
   * Sends MySQL handshake packet to client.
   */
  private void sendHandshake(ChannelHandlerContext ctx) {
    connectionId = CONNECTION_ID_GENERATOR.getAndIncrement();
    connectionPhase = MySQLConnectionPhase.AUTH_PHASE_FAST_PATH;

    MySQLHandshakePacket handshake =
        new MySQLHandshakePacket(connectionId, false, authPluginData);
    MySQLAuthenticationMethod authMethod = resolveAuthMethod(config.getAuthPlugin());
    handshake.setAuthPluginName(authMethod);

    System.err.println("[AUTH] Sending handshake, connectionId=" + connectionId);
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

      System.err.println("[AUTH] Handshake received - user: " + currentUsername
          + ", database: " + currentDatabase
          + ", authResponseLen=" + (authResponse != null ? authResponse.length : 0));

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
    } catch (IndexOutOfBoundsException e) {
      System.err.println("[AUTH] ByteBuf access error - malformed packet or incompatible client:");
      e.printStackTrace();
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Authentication protocol error: malformed handshake response", 1045, "28000"));
      ctx.close();
    } catch (Exception e) {
      System.err.println("[AUTH] Exception during handshake:");
      e.printStackTrace();
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
    } catch (IndexOutOfBoundsException e) {
      System.err.println("[AUTH] ByteBuf access error during auth switch - malformed packet:");
      e.printStackTrace();
      ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
          "Authentication protocol error: malformed auth switch response", 1045, "28000"));
      ctx.close();
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
    // Validate credentials if configured
    String configuredUsername = config.getMysqlUsername();
    String configuredPassword = config.getMysqlPassword();

    if (configuredUsername != null && !configuredUsername.isEmpty()) {
      if (!configuredUsername.equals(currentUsername)) {
        System.err.println("[AUTH] Username mismatch: " + currentUsername);
        ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
            "Access denied for user '" + currentUsername + "'", 1045, "28000"));
        ctx.close();
        return;
      }

      if (!authenticator.authenticate(configuredPassword, authResponse, authPluginData)) {
        System.err.println("[AUTH] Password validation failed");
        ctx.writeAndFlush(MySQLResponseBuilder.buildErrorPacket(
            "Access denied for user '" + currentUsername + "'", 1045, "28000"));
        ctx.close();
        return;
      }
    }

    // Create session
    ConnectionSession session = new ConnectionSession(
        connectionId, ctx.channel(),
        currentDatabase != null ? currentDatabase : config.getMilvusDatabase());
    session.setAuthenticated(true);

    // Mask out unsupported capability flags
    int maskedCapabilityFlags = clientCapabilityFlags
        & ~0x00800000   // CLIENT_SESSION_TRACK
        & ~0x08000000   // CLIENT_QUERY_ATTRIBUTES
        & ~0x10000000;  // MULTI_FACTOR_AUTHENTICATION
    session.setCapabilityFlags(maskedCapabilityFlags);

    ctx.channel().attr(SESSION_KEY).set(session);

    // CRITICAL: Set authenticated flag BEFORE writing OK packet
    // This ensures that if client sends next packet immediately after receiving OK,
    // we will be in the authenticated state
    authenticated.set(true);

    System.err.println("[AUTH] Authentication complete for: " + currentUsername);
    ctx.writeAndFlush(MySQLResponseBuilder.buildOKPacket(0));
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
      ctx.channel().attr(CommonConstants.CHARSET_ATTRIBUTE_KEY).set(characterSet.getCharset());
      ctx.channel().attr(MySQLConstants.CHARACTER_SET_ATTRIBUTE_KEY).set(characterSet);
    } catch (RuntimeException e) {
      ctx.channel().attr(CommonConstants.CHARSET_ATTRIBUTE_KEY).set(StandardCharsets.UTF_8);
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

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.close();
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
    if (event instanceof IdleStateEvent) {
      ConnectionSession session = ctx.channel().attr(SESSION_KEY).get();
      int connId = session != null ? session.getConnectionId() : connectionId;
      String database = session != null ? session.getCurrentDatabase() : "NONE";

      System.err.println("[FrontendHandler] Connection " + connId
          + " idle timeout, closing. database: " + database);
      ctx.close();
      return;
    }
    super.userEventTriggered(ctx, event);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    ConnectionSession session = ctx.channel().attr(SESSION_KEY).get();
    if (session != null) {
      System.err.println("[FrontendHandler] Connection " + session.getConnectionId() + " closed");
      ctx.channel().attr(SESSION_KEY).set(null);
    }
    ctx.fireChannelInactive();
  }
}
