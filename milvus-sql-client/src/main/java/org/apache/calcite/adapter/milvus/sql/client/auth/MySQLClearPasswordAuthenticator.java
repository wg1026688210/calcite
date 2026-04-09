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

/**
 * Clear password authenticator for MySQL.
 *
 * @see <a href="https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_connection_phase_authentication_methods_clear_text_password.html">Clear Text Authentication</a>
 */
public final class MySQLClearPasswordAuthenticator implements MilvusAuthenticator {

  @Override public boolean authenticate(String password, byte[] authResponse,
      MySQLAuthenticationPluginData authPluginData) {
    if (password == null || password.isEmpty()) {
      return true;
    }
    if (authResponse == null || authResponse.length == 0) {
      return false;
    }
    byte[] clientPassword = new byte[authResponse.length - 1];
    System.arraycopy(authResponse, 0, clientPassword, 0, authResponse.length - 1);
    return password.equals(new String(clientPassword));
  }

  @Override public String getAuthenticationMethodName() {
    return MySQLAuthenticationMethod.CLEAR_TEXT.getMethodName();
  }
}
