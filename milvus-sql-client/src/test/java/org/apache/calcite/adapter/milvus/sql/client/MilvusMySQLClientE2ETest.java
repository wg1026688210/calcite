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

@ExtendWith(MilvusExtension.class)
public class MilvusMySQLClientE2ETest extends MilvusBaseE2ETest {
  private static final int MYSQL_PORT = 13307;
  private static final String TEST_COLLECTION = "test_mysql_client";
  private static MilvusMySQLServer server;

  @BeforeAll
  static void setupServer() throws Exception {
    TestEnvUtil testEnvUtil = new TestEnvUtil(TEST_COLLECTION, getMilvusServiceClientV1());
    testEnvUtil.createExampleCollection();

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

  @Test
  public void testMySQLJDBCConnection() throws Exception {
    // Add connection timeout and disable certain features that may cause issues
    String url = "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/default?" +
        "connectTimeout=10000&" +
        "socketTimeout=10000&" +
        "autoReconnect=false&" +
        "failOverReadOnly=false&" +
        "maxReconnects=0&" +
        "useSSL=false&" +
        "serverTimezone=UTC&" +
        "allowPublicKeyRetrieval=true";

    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {

      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  public void testSelectFromCollection() throws Exception {
    String url = "jdbc:mysql://127.0.0.1:" + MYSQL_PORT + "/default?" +
        "connectTimeout=10000&" +
        "socketTimeout=10000&" +
        "autoReconnect=false&" +
        "failOverReadOnly=false&" +
        "maxReconnects=0&" +
        "useSSL=false&" +
        "serverTimezone=UTC&" +
        "allowPublicKeyRetrieval=true";
    try (Connection conn = DriverManager.getConnection(url, "root", "");
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT book_name, book_content FROM " + TEST_COLLECTION + " LIMIT 10")) {

      List<String> actual = new ArrayList<>();
      while (rs.next()) {
        actual.add(rs.getString(1) + "," + rs.getString(2));
      }
      Assertions.assertFalse(actual.isEmpty(), "Should have results");
    }
  }

  @AfterAll
  static void tearDownServer() {
    if (server != null) {
      server.stop();
    }
  }
}
