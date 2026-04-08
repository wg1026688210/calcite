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

import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLAuthenticationMethod;
import org.apache.shardingsphere.database.protocol.mysql.packet.handshake.MySQLAuthenticationPluginData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Native password authenticator for MySQL (SHA1 scramble).
 *
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_connection_phase_authentication_methods_native_password_authentication.html">Native Authentication</a>
 */
public final class MySQLNativePasswordAuthenticator implements MilvusAuthenticator {

  @Override
  public boolean authenticate(String password, byte[] authResponse,
      MySQLAuthenticationPluginData authPluginData) {
    if (password == null || password.isEmpty()) {
      return true;
    }
    byte[] expected = getAuthCipherBytes(password, authPluginData.getAuthenticationPluginData());
    return Arrays.equals(expected, authResponse);
  }

  private byte[] getAuthCipherBytes(String password, byte[] authenticationPluginData) {
    byte[] sha1Password = sha1(password.getBytes(StandardCharsets.UTF_8));
    byte[] doubleSha1Password = sha1(sha1Password);
    byte[] concatBytes = new byte[authenticationPluginData.length + doubleSha1Password.length];
    System.arraycopy(authenticationPluginData, 0, concatBytes, 0, authenticationPluginData.length);
    System.arraycopy(doubleSha1Password, 0, concatBytes, authenticationPluginData.length,
        doubleSha1Password.length);
    byte[] sha1ConcatBytes = sha1(concatBytes);
    return xor(sha1Password, sha1ConcatBytes);
  }

  private byte[] sha1(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-1 algorithm not available", e);
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
    return MySQLAuthenticationMethod.NATIVE.getMethodName();
  }
}
