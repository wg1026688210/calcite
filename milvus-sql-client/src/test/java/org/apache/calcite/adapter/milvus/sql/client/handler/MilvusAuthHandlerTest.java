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
import org.apache.calcite.adapter.milvus.sql.client.session.ConnectionSession;

import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLCapabilityFlag;
import org.apache.shardingsphere.database.protocol.mysql.packet.generic.MySQLOKPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthSwitchRequestPacket;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthenticationPluginData;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLHandshakePacket;
import org.apache.shardingsphere.database.protocol.mysql.payload.MySQLPacketPayload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MilvusAuthHandler tests")
class MilvusAuthHandlerTest {

  @Test
  @DisplayName("Should authenticate successfully with correct credentials")
  void testSuccessfulAuthentication() {
    MilvusServerConfig config = new MilvusServerConfig();
    config.setMilvusUsername("root");
    config.setMilvusPassword("secret");
    config.setAuthPlugin("mysql_clear_password");

    EmbeddedChannel channel = new EmbeddedChannel(new MilvusAuthHandler(config));

    // Capture handshake packet
    Object handshake = channel.readOutbound();
    assertTrue(handshake instanceof MySQLHandshakePacket);

    // Build handshake response with correct password
    ByteBuf responseBuf = buildClearTextHandshakeResponse(
        "root", "secret", "test_db", "mysql_clear_password");
    channel.writeInbound(responseBuf);

    Object response = channel.readOutbound();
    assertTrue(response instanceof MySQLOKPacket, "Expected OK packet but got: " + response);

    ConnectionSession session = channel.attr(MilvusAuthHandler.SESSION_KEY).get();
    assertNotNull(session);
    assertTrue(session.isAuthenticated());
    assertEquals("test_db", session.getCurrentDatabase());
  }

  @Test
  @DisplayName("Should reject authentication with wrong password")
  void testFailedAuthentication() {
    MilvusServerConfig config = new MilvusServerConfig();
    config.setMilvusUsername("root");
    config.setMilvusPassword("secret");
    config.setAuthPlugin("mysql_clear_password");

    EmbeddedChannel channel = new EmbeddedChannel(new MilvusAuthHandler(config));

    // Consume handshake
    channel.readOutbound();

    // Build handshake response with wrong password
    ByteBuf responseBuf = buildClearTextHandshakeResponse(
        "root", "wrong", "test_db", "mysql_clear_password");
    channel.writeInbound(responseBuf);

    // Expect channel to be closed
    assertFalse(channel.isOpen());
  }

  @Test
  @DisplayName("Should perform auth switch when plugin mismatches")
  void testAuthSwitchOnPluginMismatch() {
    MilvusServerConfig config = new MilvusServerConfig();
    config.setMilvusUsername("root");
    config.setMilvusPassword("secret");
    config.setAuthPlugin("mysql_native_password");

    EmbeddedChannel channel = new EmbeddedChannel(new MilvusAuthHandler(config));

    // Capture handshake packet and auth plugin data
    MySQLHandshakePacket handshake = (MySQLHandshakePacket) channel.readOutbound();
    MySQLAuthenticationPluginData authPluginData = handshake.getAuthPluginData();

    // Build handshake response with mismatched plugin and empty auth response
    ByteBuf responseBuf = buildNativeHandshakeResponse(
        "root", new byte[0], "test_db", "mysql_clear_password");
    channel.writeInbound(responseBuf);

    Object switchPacket = channel.readOutbound();
    assertTrue(switchPacket instanceof MySQLAuthSwitchRequestPacket);
    MySQLAuthSwitchRequestPacket authSwitch = (MySQLAuthSwitchRequestPacket) switchPacket;
    assertEquals("mysql_native_password", authSwitch.getAuthPluginName());

    // Send auth switch response with correct scramble
    byte[] correctScramble = computeNativeScramble("secret", authPluginData.getAuthenticationPluginData());
    ByteBuf switchResponseBuf = Unpooled.wrappedBuffer(correctScramble);
    channel.writeInbound(switchResponseBuf);

    Object okPacket = channel.readOutbound();
    assertTrue(okPacket instanceof MySQLOKPacket, "Expected OK packet after auth switch but got: " + okPacket);

    ConnectionSession session = channel.attr(MilvusAuthHandler.SESSION_KEY).get();
    assertNotNull(session);
    assertTrue(session.isAuthenticated());
  }

  private ByteBuf buildClearTextHandshakeResponse(String username, String password,
      String database, String authPluginName) {
    int capabilityFlags = MySQLCapabilityFlag.CLIENT_LONG_PASSWORD.getValue()
        | MySQLCapabilityFlag.CLIENT_CONNECT_WITH_DB.getValue()
        | MySQLCapabilityFlag.CLIENT_PLUGIN_AUTH.getValue()
        | MySQLCapabilityFlag.CLIENT_SECURE_CONNECTION.getValue();

    byte[] authResponse = new byte[password.length() + 1];
    System.arraycopy(password.getBytes(StandardCharsets.UTF_8), 0, authResponse, 0, password.length());

    return buildHandshakeResponse(capabilityFlags, username, authResponse, database, authPluginName);
  }

  private ByteBuf buildNativeHandshakeResponse(String username, byte[] authResponse,
      String database, String authPluginName) {
    int capabilityFlags = MySQLCapabilityFlag.CLIENT_LONG_PASSWORD.getValue()
        | MySQLCapabilityFlag.CLIENT_CONNECT_WITH_DB.getValue()
        | MySQLCapabilityFlag.CLIENT_PLUGIN_AUTH.getValue()
        | MySQLCapabilityFlag.CLIENT_SECURE_CONNECTION.getValue();

    return buildHandshakeResponse(capabilityFlags, username, authResponse, database, authPluginName);
  }

  private ByteBuf buildHandshakeResponse(int capabilityFlags, String username,
      byte[] authResponse, String database, String authPluginName) {
    ByteBuf buf = Unpooled.buffer();
    MySQLPacketPayload payload = new MySQLPacketPayload(buf, StandardCharsets.UTF_8);
    payload.writeInt4(capabilityFlags);
    payload.writeInt4(16777215);
    payload.writeInt1(33); // UTF8
    payload.writeReserved(23);
    payload.writeStringNul(username);

    if (0 != (capabilityFlags & MySQLCapabilityFlag.CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA.getValue())) {
      payload.writeBytes(authResponse);
    } else if (0 != (capabilityFlags & MySQLCapabilityFlag.CLIENT_SECURE_CONNECTION.getValue())) {
      payload.writeInt1(authResponse.length);
      payload.writeBytes(authResponse);
    } else {
      payload.writeStringNul(new String(authResponse, StandardCharsets.UTF_8));
    }

    if (0 != (capabilityFlags & MySQLCapabilityFlag.CLIENT_CONNECT_WITH_DB.getValue())) {
      payload.writeStringNul(database);
    }
    if (0 != (capabilityFlags & MySQLCapabilityFlag.CLIENT_PLUGIN_AUTH.getValue())) {
      payload.writeStringNul(authPluginName);
    }
    return buf;
  }

  private byte[] computeNativeScramble(String password, byte[] authPluginData) {
    byte[] sha1Password = sha1(password.getBytes(StandardCharsets.UTF_8));
    byte[] doubleSha1Password = sha1(sha1Password);
    byte[] concat = new byte[authPluginData.length + doubleSha1Password.length];
    System.arraycopy(authPluginData, 0, concat, 0, authPluginData.length);
    System.arraycopy(doubleSha1Password, 0, concat, authPluginData.length, doubleSha1Password.length);
    byte[] sha1Concat = sha1(concat);
    byte[] result = new byte[sha1Password.length];
    for (int i = 0; i < result.length; i++) {
      result[i] = (byte) (sha1Password[i] ^ sha1Concat[i]);
    }
    return result;
  }

  private byte[] sha1(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }
}
