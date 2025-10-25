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
package org.apache.calcite.adapter.milvus.milvus;

import org.apache.calcite.adapter.milvus.MilvusBaseE2ETest;
import org.apache.calcite.adapter.milvus.extension.MilvusExtension;
import org.apache.calcite.adapter.milvus.util.CommonData;
import org.apache.calcite.adapter.milvus.util.MilvusTestUtil;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.FieldDataWrapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.List;

/**
 * Test class for Milvus select/search operations.
 *
 * <p>This test demonstrates:
 * <ul>
 *   <li>Using {@link MilvusExtension} for container management</li>
 *   <li>Vector similarity search operations</li>
 *   <li>Query operations with pagination</li>
 * </ul>
 */
@ExtendWith(MilvusExtension.class)
public class SelectTest extends MilvusBaseE2ETest {

  private static final String RANDOM_COLLECTION = "InsertTest";
  private static MilvusTestUtil MILVUS_TEST_UTIL;

  /**
   * Set up test fixtures before all tests.
   * Creates collection, inserts data, creates index, and loads collection.
   */
  @BeforeAll
  public static void setup() {
    MILVUS_TEST_UTIL = new MilvusTestUtil(RANDOM_COLLECTION, getMilvusServiceClientV1());
    MILVUS_TEST_UTIL.createStringPKAndBinaryCollection();
    MILVUS_TEST_UTIL.generateExampleData();
    MILVUS_TEST_UTIL.createIndex(CommonData.defaultBinaryVectorField, "xxx");
    MILVUS_TEST_UTIL.load(RANDOM_COLLECTION);
  }

  /**
   * Test basic vector similarity search.
   */
  @Test public void testSearchAll() {
    MilvusServiceClient milvusServiceClient = getMilvusServiceClientV1();

    SearchParam searchParam = SearchParam.newBuilder()
        .withCollectionName(RANDOM_COLLECTION)
        .withBinaryVectors(MILVUS_TEST_UTIL.generateBinaryVectors(1, 128))
        .addOutField("book_name")
        .addOutField("book_content")
        .withVectorFieldName(CommonData.defaultBinaryVectorField)
        .withLimit(1L)
        .build();

    R<SearchResults> search = milvusServiceClient.search(searchParam);
    Assertions.assertEquals(0, search.getStatus());
  }

  /**
   * Test vector search with limit parameter.
   */
  @Test public void testSearchAllWithLimit() {
    SearchParam param = SearchParam.newBuilder()
        .withCollectionName(RANDOM_COLLECTION)
        .withBinaryVectors(MILVUS_TEST_UTIL.generateBinaryVectors(1, 128))
        .addOutField("book_name")
        .addOutField("book_content")
        .withVectorFieldName(CommonData.defaultBinaryVectorField)
        .withLimit(1L)
        .build();
    R<SearchResults> search = getMilvusServiceClientV1().search(param);
    Assertions.assertEquals(0, search.getStatus());
  }

  /**
   * Test query with pagination.
   */
  @Test public void testQueryAll() {
    long batchSize = 2; // 每批查询数量
    long offset = 0;
    boolean hasMoreData = true;

    while (hasMoreData) {
      // 分页查询
      QueryParam queryParam = QueryParam.newBuilder()
          .withCollectionName(RANDOM_COLLECTION)
          .withExpr("")  // 查询所有
          .withOutFields(Arrays.asList("book_name", "book_content")) // 指定输出字段
          .withOffset(offset)
          .withLimit(batchSize)
          .build();

      R<QueryResults> response = getMilvusServiceClientV1().query(queryParam);

      if (response.getStatus() == R.Status.Success.getCode()) {
        QueryResults results = response.getData();
        List<FieldData> fieldsDataList = results.getFieldsDataList();
        boolean needAddOffset = true;
        for (FieldData fieldData : fieldsDataList) {
          FieldDataWrapper fieldDataWrapper = new FieldDataWrapper(fieldData);
          if (fieldDataWrapper.getRowCount() == 0) {
            hasMoreData = false;
          } else if (needAddOffset) {
            offset += fieldDataWrapper.getRowCount();
            List<?> fieldData1 = fieldDataWrapper.getFieldData();
            for (Object o : fieldData1) {
              System.out.println("xxx" + o);
            }
            needAddOffset = false;
          }
        }
      } else {
        hasMoreData = false;
      }
    }
    Assertions.assertEquals(10, offset);
  }

}
