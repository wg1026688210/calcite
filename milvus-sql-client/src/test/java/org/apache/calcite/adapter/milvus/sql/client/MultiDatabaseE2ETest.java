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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E2E test for multi-database support.
 * Creates multiple databases (db1, db2) with collections in each.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MilvusExtension.class)
public class MultiDatabaseE2ETest extends MilvusBaseE2ETest {

  private static final int MYSQL_PORT = 13309;
  private static final String DB1_NAME = "db1";
  private static final String DB2_NAME = "db2";
  private static final String DB1_COLLECTION = "db1_books";
  private static final String DB2_COLLECTION = "db2_products";
  private static MilvusMySQLServer server;

  @BeforeAll
  void startServer() throws Exception {
    // Create test collection in default database
    new TestEnvUtil("default_collection", getMilvusServiceClientV1())
        .createExampleCollection();

    // Create database db1 with collection
    new TestEnvUtil(DB1_COLLECTION, DB1_NAME, getMilvusServiceClientV1())
        .createExampleCollection();

    // Create database db2 with collection
    new TestEnvUtil(DB2_COLLECTION, DB2_NAME, getMilvusServiceClientV1())
        .createExampleCollection();

    Map<String, Object> params = MilvusExtension.getConnectionParams();

    MilvusServerConfig config = new MilvusServerConfig();
    config.setPort(MYSQL_PORT);
    config.setHost("127.0.0.1");
    config.setMilvusHost((String) params.get("host"));
    config.setMilvusPort((Integer) params.get("port"));
    config.setMilvusDatabase("default");

    server = new MilvusMySQLServer(config);
    server.start();

    Thread.sleep(1000);
  }

  @Test void testShowDatabases() throws Exception {
    String url = buildJdbcUrl();
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {

      List<String> databases = new ArrayList<>();
      while (rs.next()) {
        databases.add(rs.getString(1));
      }

      System.out.println("[MULTI-DB TEST] SHOW DATABASES result: " + databases);

      // Should contain default, db1, and db2
      Assertions.assertTrue(databases.contains("default"),
          "SHOW DATABASES should include 'default' database");
      Assertions.assertTrue(databases.contains(DB1_NAME),
          "SHOW DATABASES should include '" + DB1_NAME + "' database");
      Assertions.assertTrue(databases.contains(DB2_NAME),
          "SHOW DATABASES should include '" + DB2_NAME + "' database");
    }
  }

  @Test void testShowTablesInDefaultDb() throws Exception {
    String url = buildJdbcUrl();
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SHOW TABLES")) {

      List<String> tables = new ArrayList<>();
      while (rs.next()) {
        tables.add(rs.getString(1));
      }

      System.out.println("[MULTI-DB TEST] SHOW TABLES in default: " + tables);

      Assertions.assertTrue(tables.contains("default_collection"),
          "default database should have 'default_collection'");
    }
  }

  @Test void testShowTablesInDb1() throws Exception {
    String url = buildJdbcUrl(DB1_NAME);
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SHOW TABLES")) {

      List<String> tables = new ArrayList<>();
      while (rs.next()) {
        tables.add(rs.getString(1));
      }

      System.out.println("[MULTI-DB TEST] SHOW TABLES in db1: " + tables);

      Assertions.assertTrue(tables.contains(DB1_COLLECTION),
          "db1 should have '" + DB1_COLLECTION + "'");
    }
  }

  @Test void testShowTablesInDb2() throws Exception {
    String url = buildJdbcUrl(DB2_NAME);
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SHOW TABLES")) {

      List<String> tables = new ArrayList<>();
      while (rs.next()) {
        tables.add(rs.getString(1));
      }

      System.out.println("[MULTI-DB TEST] SHOW TABLES in db2: " + tables);

      Assertions.assertTrue(tables.contains(DB2_COLLECTION),
          "db2 should have '" + DB2_COLLECTION + "'");
    }
  }

  @Test void testUseDatabase() throws Exception {
    String url = buildJdbcUrl();
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement()) {

      // USE db1 command should succeed
      boolean result = stmt.execute("USE " + DB1_NAME);
      Assertions.assertFalse(result, "USE should not return result set");
      System.out.println("[MULTI-DB TEST] USE " + DB1_NAME + " succeeded");

      // After USE db1, query should work on db1's collection
      try (ResultSet rs = stmt.executeQuery("SELECT book_name FROM " + DB1_COLLECTION + " LIMIT 3")) {
        int count = 0;
        while (rs.next()) {
          count++;
          Assertions.assertNotNull(rs.getString("book_name"));
        }
        Assertions.assertTrue(count > 0, "Should have results from db1 after USE db1");
        System.out.println("[MULTI-DB TEST] Query after USE " + DB1_NAME + " succeeded, rows: " + count);
      }

      // Switch to db2
      stmt.execute("USE " + DB2_NAME);
      System.out.println("[MULTI-DB TEST] USE " + DB2_NAME + " succeeded");

      // After USE db2, query should work on db2's collection
      try (ResultSet rs = stmt.executeQuery("SELECT book_name FROM " + DB2_COLLECTION + " LIMIT 3")) {
        int count = 0;
        while (rs.next()) {
          count++;
          Assertions.assertNotNull(rs.getString("book_name"));
        }
        Assertions.assertTrue(count > 0, "Should have results from db2 after USE db2");
        System.out.println("[MULTI-DB TEST] Query after USE " + DB2_NAME + " succeeded, rows: " + count);
      }
    }
  }

  @Test void testUseNonExistentDatabase() throws Exception {
    String url = buildJdbcUrl();
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement()) {

      // USE non_existent_db should fail with error 1049
      SQLException exception = Assertions.assertThrows(SQLException.class, () -> {
        stmt.execute("USE non_existent_db");
      });

      Assertions.assertTrue(exception.getMessage().contains("Unknown database"),
          "Error message should indicate unknown database");
      System.out.println("[MULTI-DB TEST] USE non_existent_db correctly failed: " + exception.getMessage());
    }
  }

  @Test void testFullyQualifiedTableName() throws Exception {
    String url = buildJdbcUrl();
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement()) {

      // Query db1.collection from default connection
      try (ResultSet rs =
          stmt.executeQuery("SELECT book_name FROM db1." + DB1_COLLECTION + " LIMIT 3")) {
        int count = 0;
        while (rs.next()) {
          count++;
          Assertions.assertNotNull(rs.getString("book_name"));
        }
        Assertions.assertTrue(count > 0, "Should query db1.collection using fully qualified name");
        System.out.println("[MULTI-DB TEST] Query db1." + DB1_COLLECTION + " succeeded, rows: " + count);
      }

      // Query db2.collection from default connection
      try (ResultSet rs =
          stmt.executeQuery("SELECT book_name FROM db2." + DB2_COLLECTION + " LIMIT 3")) {
        int count = 0;
        while (rs.next()) {
          count++;
          Assertions.assertNotNull(rs.getString("book_name"));
        }
        Assertions.assertTrue(count > 0, "Should query db2.collection using fully qualified name");
        System.out.println("[MULTI-DB TEST] Query db2." + DB2_COLLECTION + " succeeded, rows: " + count);
      }
    }
  }

  private String buildJdbcUrl() {
    return buildJdbcUrl("default");
  }

  private String buildJdbcUrl(String database) {
    return "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/" + database + "?" +
        "connectTimeout=10000&" +
        "socketTimeout=10000&" +
        "autoReconnect=false&" +
        "failOverReadOnly=false&" +
        "useSSL=false&" +
        "serverTimezone=UTC&" +
        "allowPublicKeyRetrieval=true";
  }

  @AfterAll
  void stopServer() {
    if (server != null) {
      server.stop();
    }
  }
}
