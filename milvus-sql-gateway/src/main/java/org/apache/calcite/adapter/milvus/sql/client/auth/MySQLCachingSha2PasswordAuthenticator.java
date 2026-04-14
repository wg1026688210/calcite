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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Caching SHA-2 password authenticator for MySQL 8.0+.
 *
 * <p>Implements the caching_sha2_password authentication plugin which is
 * the default for MySQL 8.0 and later.
 *
 * @see <a href="https://dev.mysql.com/doc/refman/8.0/en/caching-sha2-pluggable-authentication.html">Caching SHA-2 Pluggable Authentication</a>
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_connection_phase_authentication_methods_caching_sha2_password.html">Caching SHA-2 Authentication</a>
 */
public final class MySQLCachingSha2PasswordAuthenticator implements MilvusAuthenticator {

  private static final String AUTH_PLUGIN_NAME = "caching_sha2_password";

  @Override
  public boolean authenticate(String password, byte[] authResponse,
      MySQLAuthenticationPluginData authPluginData) {
    if (password == null || password.isEmpty()) {
      return true;
    }

    byte[] expected = getAuthCipherBytes(password, authPluginData.getAuthenticationPluginData());
    return Arrays.equals(expected, authResponse);
  }

  /**
   * Computes the caching_sha2_password auth cipher bytes.
   *
   * <p>The algorithm:
   * <pre>
   *   SHA256(password) XOR SHA256(SHA256(SHA256(password)) + scramble)
   * </pre>
   *
   * @param password the plain text password
   * @param scramble the server-provided scramble (challenge)
   * @return the expected auth response
   */
  private byte[] getAuthCipherBytes(String password, byte[] scramble) {
    byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

    // SHA256(password)
    byte[] sha256Password = sha256(passwordBytes);

    // SHA256(SHA256(password))
    byte[] doubleSha256Password = sha256(sha256Password);

    // SHA256(SHA256(SHA256(password)) + scramble)
    byte[] concatBytes = new byte[doubleSha256Password.length + scramble.length];
    System.arraycopy(doubleSha256Password, 0, concatBytes, 0, doubleSha256Password.length);
    System.arraycopy(scramble, 0, concatBytes, doubleSha256Password.length, scramble.length);
    byte[] sha256Concat = sha256(concatBytes);

    // SHA256(password) XOR SHA256(SHA256(SHA256(password)) + scramble)
    return xor(sha256Password, sha256Concat);
  }

  private byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }

  private byte[] xor(byte[] input, byte[] secret) {
    byte[] result = new byte[input.length];
    for (int i = 0; i < input.length; i++) {
      result[i] = (byte) (input[i] ^ secret[i]);
    }
    return result;
  }

  @Override
  public String getAuthenticationMethodName() {
    return AUTH_PLUGIN_NAME;
  }
}
