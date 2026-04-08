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
package org.apache.calcite.adapter.milvus.sql.client.auth;

import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthenticationPluginData;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MySQLNativePasswordAuthenticator tests")
class MySQLNativePasswordAuthenticatorTest {

  private final MySQLNativePasswordAuthenticator authenticator = new MySQLNativePasswordAuthenticator();

  @Test
  @DisplayName("Should return true for correct password")
  void shouldAuthenticateCorrectPassword() {
    String password = "secret";
    MySQLAuthenticationPluginData authPluginData = new MySQLAuthenticationPluginData();
    byte[] authResponse = computeNativeScramble(password, authPluginData.getAuthenticationPluginData());
    assertTrue(authenticator.authenticate(password, authResponse, authPluginData));
  }

  @Test
  @DisplayName("Should return false for wrong password")
  void shouldRejectWrongPassword() {
    String password = "secret";
    MySQLAuthenticationPluginData authPluginData = new MySQLAuthenticationPluginData();
    byte[] authResponse = computeNativeScramble("wrong", authPluginData.getAuthenticationPluginData());
    assertFalse(authenticator.authenticate(password, authResponse, authPluginData));
  }

  @Test
  @DisplayName("Should return true when configured password is empty")
  void shouldAllowEmptyPassword() {
    MySQLAuthenticationPluginData authPluginData = new MySQLAuthenticationPluginData();
    assertTrue(authenticator.authenticate("", new byte[20], authPluginData));
    assertTrue(authenticator.authenticate(null, new byte[20], authPluginData));
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
