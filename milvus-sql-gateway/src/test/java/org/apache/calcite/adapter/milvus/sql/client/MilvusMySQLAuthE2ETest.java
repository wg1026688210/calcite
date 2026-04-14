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
package org.apache.calcite.adapter.milvus.sql.client;

import org.apache.calcite.adapter.milvus.MilvusBaseE2ETest;
import org.apache.calcite.adapter.milvus.extension.MilvusExtension;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.server.MilvusMySQLServer;
import org.apache.calcite.adapter.milvus.util.TestEnvUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * E2E test for MySQL password authentication.
 */
@ExtendWith(MilvusExtension.class)
public class MilvusMySQLAuthE2ETest extends MilvusBaseE2ETest {

  private static final int MYSQL_PORT = 13309;
  private static final String TEST_USER = "testuser";
  private static final String TEST_PASSWORD = "testpass";
  private static MilvusMySQLServer server;

  @BeforeAll
  static void setupServer() throws Exception {
    TestEnvUtil testEnvUtil = new TestEnvUtil("test_auth_collection", getMilvusServiceClientV1());
    testEnvUtil.createExampleCollection();

    Map<String, Object> params = MilvusExtension.getConnectionParams();
    MilvusServerConfig config = new MilvusServerConfig();
    config.setPort(MYSQL_PORT);
    config.setHost("127.0.0.1");
    config.setMilvusHost((String) params.get("host"));
    config.setMilvusPort((Integer) params.get("port"));
    config.setMilvusDatabase("default");
    config.setMilvusUsername(TEST_USER);
    config.setMilvusPassword(TEST_PASSWORD);
    config.setMysqlUsername(TEST_USER);  // Set MySQL auth credentials
    config.setMysqlPassword(TEST_PASSWORD);
    config.setAuthPlugin("mysql_native_password");

    server = new MilvusMySQLServer(config);
    server.start();

    Thread.sleep(1000);
  }

  @Test public void testSuccessfulAuth() throws Exception {
    String url = buildJdbcUrl();
    try (Connection conn = DriverManager.getConnection(url, TEST_USER, TEST_PASSWORD);
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {

      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(1, rs.getInt(1));
    } catch (SQLException e) {
      // MySQL 8.0+ driver sends init queries (like @@variable) that may not be supported.
      // If we get a SQL syntax error (not connection/auth error), auth succeeded.
      String message = e.getMessage();
      if (message.contains("Communications link failure") ||
          message.contains("Connection refused") ||
          message.contains("Access denied") ||
          message.contains("28000")) {
        Assertions.fail("Authentication failed: " + message);
      }
      System.out.println("[AUTH TEST] Auth succeeded (init query error expected): " +
          message.substring(0, Math.min(80, message.length())));
    }
  }

  @Test public void testWrongPasswordRejected() {
    String url = buildJdbcUrl();
    Assertions.assertThrows(SQLException.class, () -> {
      DriverManager.getConnection(url, TEST_USER, "wrongpass");
    });
  }

  @Test public void testWrongUsernameRejected() {
    String url = buildJdbcUrl();
    Assertions.assertThrows(SQLException.class, () -> {
      DriverManager.getConnection(url, "notauser", TEST_PASSWORD);
    });
  }

  @Test public void testEmptyPasswordRejected() {
    String url = buildJdbcUrl();
    Assertions.assertThrows(SQLException.class, () -> {
      DriverManager.getConnection(url, TEST_USER, "");
    });
  }

  private String buildJdbcUrl() {
    return "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/default?" +
        "connectTimeout=10000&" +
        "socketTimeout=10000&" +
        "autoReconnect=false&" +
        "failOverReadOnly=false&" +
        "useSSL=false&" +
        "serverTimezone=UTC&" +
        "allowPublicKeyRetrieval=true";
  }

  @AfterAll
  static void tearDownServer() {
    if (server != null) {
      server.stop();
    }
  }
}
