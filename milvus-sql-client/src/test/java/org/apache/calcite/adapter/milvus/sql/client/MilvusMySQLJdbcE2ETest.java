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
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JDBC E2E test for Milvus MySQL server using MySQL JDBC driver.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MilvusExtension.class)
public class MilvusMySQLJdbcE2ETest extends MilvusBaseE2ETest {

  private static final int MYSQL_PORT = 13308;
  private static final String TEST_COLLECTION = "test_mysql_jdbc";
  private static MilvusMySQLServer server;
  private Connection jdbcConnection;

  @BeforeAll
  void startServer() throws Exception {
    // Create test collection with data
    TestEnvUtil testEnvUtil = new TestEnvUtil(TEST_COLLECTION, getMilvusServiceClientV1());
    testEnvUtil.createExampleCollection();

    // Get Milvus connection params from extension
    Map<String, Object> params = MilvusExtension.getConnectionParams();

    // Configure and start MySQL server
    MilvusServerConfig config = new MilvusServerConfig();
    config.setPort(MYSQL_PORT);
    config.setHost("127.0.0.1");
    config.setMilvusHost((String) params.get("host"));
    config.setMilvusPort((Integer) params.get("port"));
    config.setMilvusDatabase("default");

    server = new MilvusMySQLServer(config);
    server.start();

    // Wait for server to start
    Thread.sleep(1000);

    // Create JDBC connection
    String url = "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/default?" +
        "connectTimeout=10000&" +
        "socketTimeout=10000&" +
        "autoReconnect=false&" +
        "failOverReadOnly=false&" +
        "maxReconnects=0&" +
        "useSSL=false&" +
        "serverTimezone=UTC&" +
        "allowPublicKeyRetrieval=true";

    jdbcConnection = DriverManager.getConnection(url, "root", "");
  }

  @Test
  void testBasicSelect() throws Exception {
    try (Statement stmt = jdbcConnection.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {

      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void testSelectFromCollection() throws Exception {
    try (Statement stmt = jdbcConnection.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT book_name, book_content FROM " + TEST_COLLECTION + " LIMIT 10")) {

      List<String> results = new ArrayList<>();
      while (rs.next()) {
        results.add(rs.getString("book_name") + ":" + rs.getString("book_content"));
      }

      Assertions.assertFalse(results.isEmpty(), "Should have results from collection");
    }
  }

  @Test
  void testPreparedStatement() throws Exception {
    // Note: Prepared statements require COM_STMT_PREPARE support
    // This test verifies the connection works with prepared statement syntax
    String sql = "SELECT * FROM " + TEST_COLLECTION + " LIMIT 1";
    try (PreparedStatement pstmt = jdbcConnection.prepareStatement(sql);
         ResultSet rs = pstmt.executeQuery()) {

      Assertions.assertTrue(rs.next(), "Should have at least one row");
    }
  }

  @Test
  void testUseDatabase() throws Exception {
    try (Statement stmt = jdbcConnection.createStatement()) {
      // USE command should return OK
      boolean result = stmt.execute("USE default");
      Assertions.assertFalse(result, "USE should not return result set");
    }
  }

  @AfterAll
  void stopServer() throws Exception {
    if (jdbcConnection != null && !jdbcConnection.isClosed()) {
      jdbcConnection.close();
    }
    if (server != null) {
      server.stop();
    }
  }
}
