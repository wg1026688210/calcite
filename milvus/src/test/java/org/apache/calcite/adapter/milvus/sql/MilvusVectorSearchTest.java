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

import com.google.common.collect.Lists;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MilvusExtension.class)
public class MilvusVectorSearchTest extends MilvusBaseE2ETest {
  private static final String FLOAT_VECTOR_COLLECTION_NAME = "test_vector_search";
  private Connection connection;

  @BeforeEach
  void setup() throws Exception {
    TestEnvUtil testEnvUtil =
        new TestEnvUtil(FLOAT_VECTOR_COLLECTION_NAME, getMilvusServiceClientV1());
    testEnvUtil.createExampleCollection();
    this.connection = setupCalciteConnection();
  }

  @Test void testVectorSimilaritySearch() throws Exception {
    String queryVector = "[0.1, 0.2, 0.3, 0.4]";

    {
      // test vector distance function in SELECT clause
      String sql =
          String.format(
              "SELECT book_name, l2_distance(%s , CAST('[0.1, 0.2, 0.3, 0" +
                  ".4]' " +
                  "AS VARCHAR)) AS d\n"
                  + "FROM milvus.%s\n"
                  + "ORDER BY 2\n"
                  + "LIMIT 5",
              CommonData.defaultVectorField,
              FLOAT_VECTOR_COLLECTION_NAME);
      String executionPlan = getExecutionPlan(sql, connection);
      assertTrue(containsMilvusOperator(executionPlan, MILVUS_VECTOR_SEARCH));
      assertEquals(
          Lists.newArrayList(
          "小王子,0",
          "时间简史,0.3",
          "百年孤独,1.2",
          "活着,2.7",
          "围城,4.8"), checkSqlResult(sql, connection, 2));
    }

    {
      // test with constant project
      String sql =
          String.format(
              "SELECT 'test' , l2_distance(%s , CAST('[0.1, 0.2, 0.3, 0" +
                  ".4]' " +
                  "AS VARCHAR)) AS d\n"
                  + "FROM milvus.%s\n"
                  + "ORDER BY 2\n"
                  + "LIMIT 5",
              CommonData.defaultVectorField,
              FLOAT_VECTOR_COLLECTION_NAME);
      String executionPlan = getExecutionPlan(sql, connection);
      assertTrue(containsMilvusOperator(executionPlan, MILVUS_VECTOR_SEARCH));
      assertEquals(
          Lists.newArrayList(
              "test,0",
              "test,0.3",
              "test,1.2",
              "test,2.7",
              "test,4.8"), checkSqlResult(sql, connection, 2));
    }



    {

      // test vector search with filter
      String sql =
          String.format("SELECT book_name, l2_distance(%s, '%s')  d  \n"
                  + "FROM milvus.%s\n"
                  + "Where book_name <> '小王子'\n"
                  + "ORDER BY 2\n"
                  + "LIMIT 5",
              CommonData.defaultVectorField,
              queryVector,
              FLOAT_VECTOR_COLLECTION_NAME);
      String executionPlan = getExecutionPlan(sql, connection);

      assertTrue(containsMilvusOperator(executionPlan, MILVUS_VECTOR_SEARCH));
      assertEquals(
          Lists.newArrayList(
              "时间简史,0.3",
              "百年孤独,1.2",
              "活着,2.7",
              "围城,4.8",
              "平凡的世界,7.5"),
          checkSqlResult(sql, connection, 2));

      String sql1 =
          String.format("SELECT book_name, l2_distance(%s, '%s') AS d " +
                  "FROM milvus.%s " +
                  "WHERE book_name LIKE '%%三体%%' " +
                  "ORDER BY 2 " +
                  "LIMIT 2",
              CommonData.defaultVectorField,
              queryVector,
              FLOAT_VECTOR_COLLECTION_NAME);
      String executionPlan1 = getExecutionPlan(sql, connection);
      assertTrue(containsMilvusOperator(executionPlan1, MILVUS_FILTER));
      assertTrue(containsMilvusOperator(executionPlan1, MILVUS_SCAN));
      assertEquals(Lists.newArrayList("三体,14.7"), checkSqlResult(sql1, connection, 2));
    }

  }

  @Test public void testNotPushDown() throws SQLException {
    String queryVector = "[0.1, 0.2, 0.3, 0.4]";
    ArrayList<String> expected =
        Lists.newArrayList("小王子,0",
        "时间简史,0.55",
        "百年孤独,1.1",
        "活着,1.64",
        "围城,2.19");
    {
      String sql =
          String.format("SELECT  book_name, l2_distance(%s, '%s') AS distance " +
              "FROM milvus.%s " +
              "WHERE  CHAR_LENGTH(%s) < 9999 " +
              "ORDER BY 2 " +
              "LIMIT 5",
          CommonData.defaultVectorField, queryVector,
          FLOAT_VECTOR_COLLECTION_NAME,
          "book_name");
      String executionPlan = getExecutionPlan(sql, connection);
      assertTrue(containsMilvusOperator(executionPlan, MILVUS_SCAN));
      assertFalse(containsMilvusOperator(executionPlan, MILVUS_VECTOR_SEARCH),
          "Execution plan should not contain MilvusFilter for vector UDF filter");

    }

    {
      String sql =
          String.format("SELECT book_name, distance FROM ( " +
              "SELECT book_name, l2_distance(%s, '%s') AS distance " +
              "FROM milvus.%s " +
              ") AS subquery " +
              "WHERE distance < 10 " +
              "ORDER BY distance " +
              "LIMIT 5",
          CommonData.defaultVectorField, queryVector,
          FLOAT_VECTOR_COLLECTION_NAME);

      String executionPlan = getExecutionPlan(sql, connection);
      assertTrue(containsMilvusOperator(executionPlan, MILVUS_SCAN));
      assertFalse(containsMilvusOperator(executionPlan, MILVUS_VECTOR_SEARCH),
          "Execution plan should not contain MilvusFilter for vector UDF filter");
      assertEquals(
          expected,
          checkSqlResult(sql, connection, 2));

    }
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }
  }
}
