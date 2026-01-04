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
package org.apache.calcite.adapter.milvus.sql;

import org.apache.calcite.adapter.milvus.MilvusBaseE2ETest;
import org.apache.calcite.adapter.milvus.extension.MilvusExtension;
import org.apache.calcite.adapter.milvus.util.CommonData;
import org.apache.calcite.adapter.milvus.util.TestEnvUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.shaded.com.google.common.collect.Lists;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for "SELECT column1, column2 FROM table" query on Milvus.
 * This test:
 * 1. Starts a Milvus Docker container
 * 2. Creates a collection with sample data
 * 3. Configures Calcite connection with Milvus schema
 * 4. Executes "SELECT specific columns" query
 * 5. Verifies results and column filtering
 */
@ExtendWith(MilvusExtension.class)
public class MilvusProjectQueryTest extends MilvusBaseE2ETest {
  private static final String COLLECTION_NAME = "MilvusProjectQueryTest";
  private Connection connection;

  @BeforeAll
  static void setupOnce() {
    TestEnvUtil testEnvUtil =
        new TestEnvUtil(COLLECTION_NAME, getMilvusServiceClientV1());
    testEnvUtil.createExampleCollection();
  }

  @BeforeEach
  public void setUp() throws Exception {
    connection = setupCalciteConnection();
  }


  @Test public void testStringConstant() throws SQLException {
    String sql = String.format("select book_name ,'xxx' from milvus.%s ", COLLECTION_NAME);
    String executionPlan = getExecutionPlan(sql, connection);
    assertTrue(containsMilvusOperator(executionPlan, MILVUS_PROJECT));
    List<String> result = getSqlResult(sql, connection);
    System.out.println(result);
  }

  @Test public void testNumberConstant() throws SQLException {
    {
      String sql = String.format("select book_name ,1 from milvus.%s ", COLLECTION_NAME);
      String executionPlan = getExecutionPlan(sql, connection);
      assertTrue(containsMilvusOperator(executionPlan, MILVUS_PROJECT));
      List<String> result = getSqlResult(sql, connection);
      System.out.println(result);
    }

    {
      String sql = String.format("select book_name ,1.1 from milvus.%s ", COLLECTION_NAME);
      String executionPlan = getExecutionPlan(sql, connection);
      assertTrue(containsMilvusOperator(executionPlan, MILVUS_PROJECT));
      List<String> result = getSqlResult(sql, connection);
      System.out.println(result);
    }
  }

  @Test public void testSelectSingleColumn() throws Exception {
    {
      String sql = String.format("SELECT book_name FROM milvus.%s ", COLLECTION_NAME);

      assertTrue(containsMilvusOperator(sql, MILVUS_PROJECT));

      // Execute query and get result
      List<String> actual = new ArrayList<>();
      try (Statement statement = connection.createStatement()) {
        ResultSet resultSet = statement.executeQuery(sql);
        while (resultSet.next()) {
          String bookName = resultSet.getString(1);
          actual.add(bookName);
        }
      }

      List<String> expected =
          Lists.newArrayList("三体",
          "围城",
          "小王子",
          "平凡的世界",
          "挪威的森林",
          "时间简史",
          "活着",
          "百年孤独",
          "红楼梦",
          "追风筝的人");
      assertEquals(expected, actual, "Single column projection should return book names");
    }

  }

  @Test public void testSelectMultiColumns() throws Exception {
    String sql =
        String.format("SELECT book_name, book_content, %s FROM milvus.%s", CommonData.defaultVectorField, COLLECTION_NAME);
    assertTrue(containsMilvusOperator(sql, MILVUS_PROJECT));

    List<String> actual = new ArrayList<>();
    try (Statement statement = connection.createStatement()) {
      ResultSet resultSet = statement.executeQuery(sql);
      while (resultSet.next()) {
        String bookName = resultSet.getString(1);
        String bookContent = resultSet.getString(2);
        String vector = resultSet.getString(3);
        actual.add(String.format("%s,%s,%s", bookName, bookContent, vector));
      }
    }

    List<String> expected =
        Lists.newArrayList("三体,三体文明的到来改变了人类对宇宙的认知。,[0.8, 1.6, 2.4, 3.2]",
        "围城,围城里的人想出去，城外的人想进来，这就是人生的矛盾。,[0.5, 1.0, 1.5, 2.0]",
        "小王子,从前有个小王子住在一颗很小的星球上，那里有一朵他非常珍爱的玫瑰花。,[0.1, 0.2, 0.3, 0.4]",
        "平凡的世界,生活虽然平凡，但每个人都有自己的梦想和追求。,[0.6, 1.2, 1.8000001, 2.4]",
        "挪威的森林,挪威的森林中充满了青春的迷茫与彷徨。,[0.90000004, 1.8000001, 2.7, 3.6000001]",
        "时间简史,时间是一种神秘的现象，它既无处不在，又难以捉摸。,[0.2, 0.4, 0.6, 0.8]",
        "活着,人生如戏，我们都是这场戏中的演员，经历着喜怒哀乐。,[0.4, 0.8, 1.2, 1.6]",
        "百年孤独,马孔多是一个充满魔幻色彩的小镇，那里发生了许多不可思议的故事。,[0.3, 0.6, 0.90000004, 1.2]",
        "红楼梦,红楼梦是一部描写封建社会兴衰的伟大作品。,[0.7, 1.4, 2.1, 2.8]",
        "追风筝的人,追风筝的人讲述了一个关于友谊与救赎的动人故事。,[1.0, 2.0, 3.0, 4.0]");

    System.out.println("Actual result: " + actual);
    System.out.println("Expected result: " + expected);
    assertEquals(expected, actual, "Multiple column projection should return all columns " +
        "correctly");
  }

  @Test public void testSelectVectorFieldOnly() throws Exception {
    // Execute query with only vector field
    String sql =
        String.format("SELECT %s FROM milvus.%s", CommonData.defaultVectorField, COLLECTION_NAME);
    assertTrue(containsMilvusOperator(sql, MILVUS_PROJECT));

    // Execute query and get only vector field
    List<String> actual = new ArrayList<>();
    try (Statement statement = connection.createStatement()) {
      ResultSet resultSet = statement.executeQuery(sql);
      while (resultSet.next()) {
        String vector = resultSet.getString(1);
        actual.add(vector);
      }
    }

    // Expected results (only vector_field)
    List<String> expected =
        Lists.newArrayList("[0.8, 1.6, 2.4, 3.2]",
        "[0.5, 1.0, 1.5, 2.0]",
        "[0.1, 0.2, 0.3, 0.4]",
        "[0.6, 1.2, 1.8000001, 2.4]",
        "[0.90000004, 1.8000001, 2.7, 3.6000001]",
        "[0.2, 0.4, 0.6, 0.8]",
        "[0.4, 0.8, 1.2, 1.6]",
        "[0.3, 0.6, 0.90000004, 1.2]",
        "[0.7, 1.4, 2.1, 2.8]",
        "[1.0, 2.0, 3.0, 4.0]");

    System.out.println("Actual result: " + actual);
    System.out.println("Expected result: " + expected);
    assertEquals(expected, actual, "Vector field only projection should return vector values");
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }
  }
}
