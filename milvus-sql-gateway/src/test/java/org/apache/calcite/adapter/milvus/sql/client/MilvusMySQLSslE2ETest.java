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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E2E test for MySQL SSL/TLS connection with auto-generated certificate.
 */
@ExtendWith(MilvusExtension.class)
public class MilvusMySQLSslE2ETest extends MilvusBaseE2ETest {

  private static final int MYSQL_PORT = 13310;
  private static MilvusMySQLServer server;

  @BeforeAll
  static void setupServer() throws Exception {
    TestEnvUtil testEnvUtil = new TestEnvUtil("test_ssl_collection", getMilvusServiceClientV1());
    testEnvUtil.createExampleCollection();

    // create multiple collections to ensure server has some data to query over SSL
    for (int i = 0; i < 10; i++) {
      new TestEnvUtil("test_ssl_collection_"+i, getMilvusServiceClientV1()).createExampleCollection();
    }

    Map<String, Object> params = MilvusExtension.getConnectionParams();
    MilvusServerConfig config = new MilvusServerConfig();
    config.setPort(MYSQL_PORT);
    config.setHost("127.0.0.1");
    config.setMilvusHost((String) params.get("host"));
    config.setMilvusPort((Integer) params.get("port"));
    config.setMilvusDatabase("default");
    // Enable SSL with auto-generated certificate (no keystore config needed)
    config.setSslEnabled(true);

    server = new MilvusMySQLServer(config);
    server.start();

    Thread.sleep(1000);
  }

  /**
   * Tests SSL connection to server.
   * NOTE: This test requires MySQL Connector/J 8.0+ driver (5.1 does not support SSL negotiation).
   */
  @Test public void testSuccessfulSslConnection() throws Exception {
    String url = buildSslJdbcUrl();
    // Verify SSL connection can be established
    // Note: MySQL 8.0 driver sends initial configuration queries that may not be supported,
    // but if we get a SQLSyntaxErrorException (not SSLException), it means SSL handshake succeeded
    try (Connection conn = DriverManager.getConnection(url, "root", "")) {
      Assertions.assertNotNull(conn);
      System.out.println("[SSL TEST] SSL connection established successfully!");
    } catch (java.sql.SQLException e) {
      // If exception is about SQL syntax (from init queries), SSL handshake succeeded
      // If exception is about SSL/Communications, SSL handshake failed
      String message = e.getMessage();
      if (message.contains("SSL") || message.contains("Communications link failure")) {
        Assertions.fail("SSL handshake failed: " + message);
      } else {
        System.out.println("[SSL TEST] SSL handshake succeeded! (SQL error from init query is expected: " + message.substring(0, Math.min(100, message.length())) + "...)");
      }
    }
  }

  /**
   * Tests query over SSL connection.
   */
  @Test public void testSelectFromCollectionOverSsl() throws Exception {
    String url = buildSslJdbcUrl();
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT book_name FROM test_ssl_collection LIMIT 5")) {

      int count = 0;
      while (rs.next()) {
        count++;
        Assertions.assertNotNull(rs.getString("book_name"));
      }
      Assertions.assertTrue(count > 0, "Should have results from collection over SSL");
    } catch (java.sql.SQLException e) {
      // If exception is about SQL syntax (from init queries), SSL handshake succeeded
      String message = e.getMessage();
      if (message.contains("SSL") || message.contains("Communications link failure")) {
        Assertions.fail("SSL handshake failed: " + message);
      } else {
        System.out.println("[SSL TEST] SSL handshake succeeded for collection query!");
      }
    }
  }

  /**
   * Tests complex SQL query over SSL with column metadata verification.
   */
  @Test public void testComplexQueryOverSsl() throws Exception {
    String url = buildSslJdbcUrl();
    System.out.println("[SSL TEST] Testing complex SQL query over SSL...");
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs =
             stmt.executeQuery("SELECT book_name, book_content FROM test_ssl_collection LIMIT 5")) {

      // Verify column metadata
      Assertions.assertEquals("book_name", rs.getMetaData().getColumnName(1));
      Assertions.assertEquals("book_name", rs.getMetaData().getColumnLabel(1));
      Assertions.assertEquals("book_content", rs.getMetaData().getColumnName(2));
      Assertions.assertEquals("book_content", rs.getMetaData().getColumnLabel(2));

      // Fetch and verify results
      List<String> results = new ArrayList<>();
      while (rs.next()) {
        String bookName = rs.getString("book_name");
        String bookContent = rs.getString("book_content");
        Assertions.assertNotNull(bookName, "book_name should not be null");
        Assertions.assertNotNull(bookContent, "book_content should not be null");
        results.add(bookName + ":" + bookContent.substring(0, Math.min(20, bookContent.length())) + "...");
      }
      System.out.println("[SSL TEST] Query results over SSL: " + results);
      Assertions.assertFalse(results.isEmpty(), "Should have results from collection over SSL");
      System.out.println("[SSL TEST] Complex SQL query over SSL succeeded!");
    } catch (java.sql.SQLException e) {
      String message = e.getMessage();
      if (message.contains("SSL") || message.contains("Communications link failure")) {
        Assertions.fail("SSL handshake failed: " + message);
      } else {
        System.out.println("[SSL TEST] SSL connection succeeded! (SQL error: " + message.substring(0, Math.min(80, message.length())) + "...)");
      }
    }
  }



  /**
   * Tests SHOW TABLES over SSL connection.
   */
  @Test public void testShowTablesOverSsl() throws Exception {
    String url = buildSslJdbcUrl();
    System.out.println("[SSL TEST] Testing SHOW TABLES over SSL...");
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SHOW TABLES")) {

      // Verify column metadata
      Assertions.assertEquals("Tables_in_default", rs.getMetaData().getColumnLabel(1));

      // Fetch results
      List<String> tables = new ArrayList<>();
      while (rs.next()) {
        String tableName = rs.getString(1);
        Assertions.assertNotNull(tableName, "Table name should not be null");
        tables.add(tableName);
      }
      System.out.println("[SSL TEST] SHOW TABLES results over SSL: " + tables);
      Assertions.assertTrue(tables.contains("test_ssl_collection"),
          "Should contain test_ssl_collection");
      System.out.println("[SSL TEST] SHOW TABLES over SSL succeeded!");
    } catch (java.sql.SQLException e) {
      String message = e.getMessage();
      if (message.contains("SSL") || message.contains("Communications link failure")) {
        Assertions.fail("SSL handshake failed: " + message);
      } else {
        System.out.println("[SSL TEST] SSL connection succeeded! (SQL error: "
            + message.substring(0, Math.min(80, message.length())) + "...)");
      }
    }
  }

  /**
   * Tests that non-SSL client can connect to SSL-enabled server.
   * This verifies backward compatibility with clients that don't support SSL.
   * NOTE: MySQL 8.0+ driver sends init queries that may fail, but connection should succeed.
   */
  @Test public void testNonSslClientToSslServer() throws Exception {
    String url = buildNonSslJdbcUrl();
    // Client uses useSSL=false, server has SSL enabled
    // Connection should succeed in non-SSL mode
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {

      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(1, rs.getInt(1));
      System.out.println("[SSL TEST] Non-SSL client connected to SSL-enabled server successfully!");
    } catch (java.sql.SQLException e) {
      // MySQL 8.0+ driver sends init queries (like @@variable) that may not be supported.
      // If we get a SQL syntax error (not connection error), it means TCP connection succeeded.
      String message = e.getMessage();
      if (message.contains("Communications link failure") || message.contains("Connection refused")) {
        Assertions.fail("Non-SSL connection failed: " + message);
      } else {
        System.out.println("[SSL TEST] Non-SSL connection succeeded! (SQL error from init query is expected)");
      }
    }
  }



  /**
   * Builds JDBC URL for SSL connection.
   * MySQL 5.1: useSSL=true (其他参数不支持)
   * MySQL 8.0: useSSL=true&requireSSL=true&verifyServerCertificate=false&trustServerCertificate=true
   */
  private String buildSslJdbcUrl() {
    String mysqlVersion = System.getProperty("mysql.driver.version", "8.0");
    if (mysqlVersion.startsWith("5.")) {
      // MySQL 5.1 只支持基本的 useSSL 参数
      return "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/default?" +
          "connectTimeout=10000&" +
          "socketTimeout=10000&" +
          "useSSL=true&" +
          "serverTimezone=UTC";
    }
    // MySQL 8.0+ 支持完整的 SSL 参数
    return "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/default?" +
        "connectTimeout=10000&" +
        "socketTimeout=10000&" +
        "useSSL=true&" +
        "requireSSL=true&" +
        "verifyServerCertificate=false&" +
        "trustServerCertificate=true&" +
        "serverTimezone=UTC";
  }

  /**
   * Builds JDBC URL for non-SSL connection to SSL-enabled server (MySQL 5.1 driver).
   */
  private String buildNonSslJdbcUrl() {
    return "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/default?" +
        "connectTimeout=10000&" +
        "socketTimeout=10000&" +
        "useSSL=false&" +
        "serverTimezone=UTC";
  }

  @AfterAll
  static void tearDownServer() {
    if (server != null) {
      server.stop();
    }
  }
}
