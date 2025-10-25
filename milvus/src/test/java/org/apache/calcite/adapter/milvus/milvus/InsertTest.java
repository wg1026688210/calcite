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
import org.apache.calcite.adapter.milvus.util.MilvusTestUtil;

import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.response.GetCollStatResponseWrapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

/**
 * Test class for Milvus insert operations.
 *
 * <p>This test demonstrates:
 * <ul>
 *   <li>Using {@link MilvusExtension} for container management</li>
 *   <li>Inserting data into Milvus collections</li>
 *   <li>Verifying collection statistics after insert</li>
 * </ul>
 */
@ExtendWith(MilvusExtension.class)
public class InsertTest extends MilvusBaseE2ETest {

  private static final String RANDOM_COLLECTION = "InsertTest";

  private static MilvusTestUtil MILVUS_TEST_UTIL;


  @BeforeAll
  public static void beforeAll() {
    MILVUS_TEST_UTIL = new MilvusTestUtil(RANDOM_COLLECTION, getMilvusServiceClientV1());
    MILVUS_TEST_UTIL.createStringPKAndBinaryCollection();
  }

  /**
   * Test inserting data into a collection and verifying statistics.
   */
  @Test public void insertTest() {
    // Insert 2000 records
    List<InsertParam.Field> fields = MILVUS_TEST_UTIL.generateStringPKBinaryData(2000);
    R<MutationResult> mutationResultR =
        getMilvusServiceClientV1()
            .insert(
                InsertParam.newBuilder()
                    .withCollectionName(RANDOM_COLLECTION)
                    .withFields(fields)
                    .build());

    Assertions.assertEquals(0, mutationResultR.getStatus().intValue());
    Assertions.assertEquals(2000, mutationResultR.getData().getSuccIndexCount());
    Assertions.assertEquals(0, mutationResultR.getData().getDeleteCnt());

    // Verify collection statistics
    R<GetCollectionStatisticsResponse> respCollectionStatistics =
        getMilvusServiceClientV1()
            .getCollectionStatistics(
                GetCollectionStatisticsParam.newBuilder()
                    .withCollectionName(RANDOM_COLLECTION)
                    .withFlush(true)
                    .build());

    Assertions.assertEquals(0, respCollectionStatistics.getStatus().intValue());

    GetCollStatResponseWrapper wrapperCollectionStatistics =
        new GetCollStatResponseWrapper(respCollectionStatistics.getData());
    Assertions.assertEquals(2000, wrapperCollectionStatistics.getRowCount());
  }

}
