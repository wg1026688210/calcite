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

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Test class for Milvus collection operations.
 *
 * <p>This test demonstrates how to:
 * <ul>
 *   <li>Use the {@link MilvusExtension} to manage Milvus container lifecycle</li>
 *   <li>Extend {@link MilvusBaseE2ETest} for utility methods</li>
 *   <li>Create and inspect Milvus collections</li>
 * </ul>
 */
@ExtendWith(MilvusExtension.class)
public class CollectionTest extends MilvusBaseE2ETest {

  private static final String COLLECTION = "CollectionTest";

  /**
   * Initializes the collection used for testing before all tests run.
   * Note: The {@link MilvusExtension} handles Milvus container lifecycle separately.
   */
  @BeforeAll
  public static void setupCollection() {
    MilvusTestUtil milvusTestUtil = new MilvusTestUtil(COLLECTION, getMilvusServiceClientV1());
    milvusTestUtil.createStringPKAndBinaryCollection();
  }

  /**
   * Test describing a collection and inspecting its schema.
   */
  @Test public void testShowCollection() {
    MilvusClientV2 milvusServiceClientV2 = getMilvusServiceClientV2();

    DescribeCollectionResp describeCollectionResp =
        milvusServiceClientV2.describeCollection(
            DescribeCollectionReq.builder()
                .collectionName(COLLECTION)
                .build());

    for (CreateCollectionReq.FieldSchema fieldSchema : describeCollectionResp.getCollectionSchema()
        .getFieldSchemaList()) {
      String name = fieldSchema.getName();
      DataType dataType = fieldSchema.getDataType();
      System.out.println(name + " : " + dataType);
    }
  }

}
