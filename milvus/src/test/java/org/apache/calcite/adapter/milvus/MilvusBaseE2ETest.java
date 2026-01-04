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
package org.apache.calcite.adapter.milvus;

import org.apache.calcite.adapter.milvus.extension.MilvusExtension;
import org.apache.calcite.adapter.milvus.factory.MilvusSchemaFactory;
import org.apache.calcite.adapter.milvus.hint.MilvusPrepareImpl;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Base class for Milvus E2E tests providing utility methods and constants.
 *
 * <p>Tests extending this class should also use
 * {@code @ExtendWith(MilvusExtension.class)} to ensure Milvus containers
 * are properly initialized.
 */
public class MilvusBaseE2ETest {

  public static final String MILVUS_CONVERTER = "MilvusToEnumerableConverter";
  public static final String MILVUS_FILTER = "MilvusFilter";
  public static final String MILVUS_PROJECT = "MilvusProject";
  public static final String MILVUS_SCAN = "MilvusTableScan";
  public static final String MILVUS_VECTOR_SEARCH = "MilvusVectorSearch";

  private static final String[] MILVUS_OPERATOR_PATTERNS = {
      MILVUS_CONVERTER.toLowerCase(),
      MILVUS_FILTER.toLowerCase(),
      MILVUS_PROJECT.toLowerCase(),
      MILVUS_SCAN.toLowerCase(),
      MILVUS_VECTOR_SEARCH.toLowerCase()
  };


  public static MilvusServiceClient getMilvusServiceClientV1() {
    return MilvusExtension.getMilvusClientV1();
  }


  public static MilvusClientV2 getMilvusServiceClientV2() {
    Map<String, Object> params = MilvusExtension.getConnectionParams();
    String host = (String) params.get("host");
    Integer port = (Integer) params.get("port");

    ConnectConfig connectConfig = ConnectConfig.builder()
        .uri("http://" + host + ":" + port)
        .build();
    return new MilvusClientV2(connectConfig);
  }


  /**
   * Check if the execution plan contains a specific Milvus operator.
   *
   * @param executionPlan the execution plan string
   * @param operator the operator name to search for
   * @return true if the operator is present
   */
  protected boolean containsMilvusOperator(String executionPlan, String operator) {
    for (String plan : executionPlan.split("\n")) {
      if (plan.contains(operator)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get the execution plan for a SQL query.
   *
   * @param sql the SQL query
   * @param connection the Calcite connection
   * @return the execution plan string
   * @throws SQLException if query execution fails
   */
  public String getExecutionPlan(String sql, Connection connection) throws SQLException {
    String explainSql = String.format("EXPLAIN PLAN FOR %s", sql);

    String executionPlan = "";
    try (Statement statement = connection.createStatement()) {
      ResultSet resultSet = statement.executeQuery(explainSql);
      while (resultSet.next()) {
        executionPlan = resultSet.getString(1);
      }
    }
    System.out.println("\n=== Execution Plan for SQL ===");
    System.out.println(executionPlan);
    return executionPlan;
  }

  /**
   * Set up a Calcite connection with Milvus schema.
   * <p>
   * We use this method to register hint strategies so that
   * context.getTableHints() can return hints.
   *
   * @return Connection to Calcite with Milvus schema
   * @throws Exception if setup fails
   */
  public static Connection setupCalciteConnection() throws Exception {
    // 启用Janino源码打印
//    System.setProperty("calcite.debug", "true");
//    System.setProperty("calcite.debug.janino", "true");

    Map<String, Object> params = MilvusExtension.getConnectionParams();
    String host = (String) params.get("host");
    Integer port = (Integer) params.get("port");

    System.setProperty("calcite.default.charset", "UTF-8");
    System.setProperty("calcite.default.nationalcharset", "UTF-8");
    System.setProperty("file.encoding", "UTF-8");

    // Create connection config with hint support
    Properties info = new Properties();
    info.setProperty("lex", "JAVA");
    info.setProperty("fun", "milvus");

    // Enable Milvus hint strategies in the JDBC execution path.
    final Driver driver = new Driver().withPrepareFactory(MilvusPrepareImpl::new);
    Connection connection = driver.connect("jdbc:calcite:", info);
    CalciteConnection calciteConnection = connection.unwrap(CalciteConnection.class);
    SchemaPlus rootSchema = calciteConnection.getRootSchema();

    Map<String, Object> operands = new HashMap<>();
    operands.put("host", host);
    operands.put("port", port);
    operands.put("databaseName", "default");

    MilvusSchemaFactory schemaFactory = new MilvusSchemaFactory();
    // Create schema
    Schema milvusSchema = schemaFactory.create(rootSchema, "milvus", operands);
    // Add to root
    rootSchema.add("milvus", milvusSchema);

    System.out.println("[setupCalciteConnection] Milvus hint strategies enabled for JDBC execution");
    return connection;
  }



  protected List<String> getSqlResult(String sql, Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      ResultSet resultSet = statement.executeQuery(sql);
      List<String> actual = new ArrayList<>();
      int columnCount = resultSet.getMetaData().getColumnCount();
      while (resultSet.next()) {
        StringBuilder rowValue = new StringBuilder();
        for (int i = 1; i <= columnCount; i++) {
          String value = resultSet.getString(i);
          if (i > 1) {
            rowValue.append(",");
          }
          rowValue.append(value);
        }
        actual.add(rowValue.toString());
      }
      return actual;
    }
  }


  protected List<String> getSqlResult(String sql, Connection connection, int distanceScale)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      ResultSet resultSet = statement.executeQuery(sql);
      List<String> actual = new ArrayList<>();
      while (resultSet.next()) {
        String bookName = resultSet.getString(1);
        String distanceStr = resultSet.getString(2);

        String formattedDistance = distanceStr;
        if (distanceStr != null && !distanceStr.isEmpty()) {
          try {
            java.math.BigDecimal bd = new java.math.BigDecimal(distanceStr);
            formattedDistance = bd.setScale(distanceScale, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
          } catch (NumberFormatException ignore) {
          }
        }

        actual.add(String.format("%s,%s", bookName, formattedDistance));
      }
      return actual;
    }
  }
}
