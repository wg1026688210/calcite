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
import org.apache.calcite.adapter.milvus.util.TestEnvUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.shaded.com.google.common.collect.Lists;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for "SELECT * FROM table WHERE condition" query on Milvus.
 * This test:
 * 1. Starts a Milvus Docker container
 * 2. Creates a collection with sample data
 * 3. Configures Calcite connection with Milvus schema
 * 4. Executes queries with WHERE clauses
 * 5. Verifies filtering works correctly
 */
@ExtendWith(MilvusExtension.class)
public class MilvusFilterQueryTest extends MilvusBaseE2ETest {
  private static final String COLLECTION_NAME = "MilvusQueryFilterTest";
  private Connection connection;

  @BeforeEach
  public void setUp() {
    TestEnvUtil testEnvUtil =
        new TestEnvUtil(COLLECTION_NAME, getMilvusServiceClientV1());
    testEnvUtil.createExampleCollection();
    try {
      connection = setupCalciteConnection();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }


  @Test public void testFilterEqualString() throws Exception {
    String sql = "SELECT book_name, book_content FROM milvus." + COLLECTION_NAME + " WHERE book_name = '三体'";

    String executionPlan = getExecutionPlan(sql, connection);
    assertTrue(containsMilvusOperator(executionPlan, MILVUS_FILTER), "Filter should be pushed down to Milvus");

    List<String> result = getSqlResult(sql, connection);

    // Expected: exactly one book with name '三体'
    List<String> expected =
        Lists.newArrayList("三体,三体文明的到来改变了人类对宇宙的认知。");

    assertEquals(expected, result, "Should return exactly '三体' with its content");
  }

  @Test public void testFilterNotEqualString() throws Exception {
    String sql = "SELECT book_name FROM milvus." + COLLECTION_NAME + " WHERE book_name <> '三体'";

    String executionPlan = getExecutionPlan(sql, connection);
    assertTrue(containsMilvusOperator(executionPlan, MILVUS_FILTER), "Filter should be pushed down to Milvus");

    List<String> result = getSqlResult(sql, connection);

    // Expected: all books except '三体'
    List<String> expected =
        Lists.newArrayList("围城",
        "小王子",
        "平凡的世界",
        "挪威的森林",
        "时间简史",
        "活着",
        "百年孤独",
        "红楼梦",
        "追风筝的人");

    assertEquals(expected, result, "Should return all books except '三体'");
  }

  @Test public void testFilterLikeOperator() throws Exception {
    String sql = "SELECT book_name FROM milvus." + COLLECTION_NAME + " WHERE book_name LIKE '三%'";

    String executionPlan = getExecutionPlan(sql, connection);
    assertTrue(containsMilvusOperator(executionPlan, MILVUS_FILTER), "Filter should be pushed down to Milvus");

    List<String> result = getSqlResult(sql, connection);

    List<String> expected =
        Lists.newArrayList("三体");

    assertEquals(expected, result, "Should return books starting with '三'");
  }

  @Test public void testFilterAndCondition() throws Exception {
    String sql = "SELECT book_name FROM milvus." + COLLECTION_NAME + " WHERE book_name = '三体' AND book_name <> '小王子'";

    String executionPlan = getExecutionPlan(sql, connection);
    assertTrue(containsMilvusOperator(executionPlan, MILVUS_FILTER), "Filter should be pushed down to Milvus");

    List<String> result = getSqlResult(sql, connection);

    List<String> expected =
        Lists.newArrayList("三体");

    assertEquals(expected, result, "Should return '三体'");
  }


  @Test public void testNotPushDown() throws SQLException {
    {
      String sql = "SELECT book_name FROM milvus." + COLLECTION_NAME + " WHERE CHAR_LENGTH(book_name) > 4";

      String executionPlan = getExecutionPlan(sql, connection);
      assertFalse(containsMilvusOperator(executionPlan,MILVUS_FILTER));

      List<String> result = getSqlResult(sql, connection);

      List<String> expected =
          Lists.newArrayList("平凡的世界",
          "挪威的森林",
          "追风筝的人");

      assertEquals(expected, result, "Should return books with more than 4 characters");
    }
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }
  }
}
