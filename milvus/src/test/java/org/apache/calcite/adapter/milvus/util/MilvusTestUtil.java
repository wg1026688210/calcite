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
package org.apache.calcite.adapter.milvus.util;

import com.google.common.collect.Lists;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;

import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MilvusTestUtil {
  private static final Logger logger = LoggerFactory.getLogger(MilvusTestUtil.class);
  private String collectionName;
  private MilvusServiceClient milvusServiceClient;

  public MilvusTestUtil(String collectionName, MilvusServiceClient milvusServiceClient) {
    this.collectionName = collectionName;
    this.milvusServiceClient = milvusServiceClient;
  }

  public void createStringPKAndBinaryCollection() {
    List<FieldType> fieldsSchema = new ArrayList<>();
    fieldsSchema.add(
        FieldType.newBuilder()
            .withName("book_name")
            .withDataType(DataType.VarChar)
            .withMaxLength(200)
            .withPrimaryKey(true)
            .withAutoID(false)
            .build());

    fieldsSchema.add(
        FieldType.newBuilder()
            .withName("book_content")
            .withDataType(DataType.VarChar)
            .withMaxLength(200)
            .build());
    fieldsSchema.add(
        FieldType.newBuilder()
            .withName(CommonData.defaultBinaryVectorField)
            .withDataType(DataType.BinaryVector)
            .withDimension(128)
            .build());
    CollectionSchemaParam schemaParam = CollectionSchemaParam.newBuilder()
        .withFieldTypes(fieldsSchema)
        .build();
    CreateCollectionParam createCollectionReq =
        CreateCollectionParam.newBuilder()
            .withCollectionName(collectionName)
            .withDescription("Test" + collectionName + "search")
            .withShardsNum(2)
            .withSchema(schemaParam)
            .build();
    R<RpcStatus> collection = milvusServiceClient.createCollection(createCollectionReq);
    logger.info("Create String pk and binary vector collection:" + collectionName);
  }

  public void createIndex(String vectorField, String indexName) {
    CreateIndexParam indexParam = CreateIndexParam.newBuilder()
        .withCollectionName(collectionName)
        .withFieldName(vectorField)
        .withIndexName(indexName)
        .withIndexType(IndexType.BIN_IVF_FLAT)  // 二进制向量专用索引
        .withMetricType(MetricType.JACCARD)     // 杰卡德距离
        .withExtraParam("{\"nlist\":1024}") // 索引参数
        .withSyncMode(Boolean.TRUE)         // 同步模式
        .build();
    R<RpcStatus> indexR = milvusServiceClient.createIndex(indexParam);
    Assertions.assertEquals(0, indexR.getStatus());
  }
  public void load(String collectionName) {
    LoadCollectionParam loadCollectionParam =
        LoadCollectionParam.newBuilder().withCollectionName(collectionName).withSyncLoad(true)
            .build();
    milvusServiceClient.loadCollection(loadCollectionParam);
  }

  public List<InsertParam.Field> generateStringPKBinaryData(int num) {
    List<String> book_name_array = new ArrayList<>();
    List<String> book_content_array = new ArrayList<>();

    for (long i = 0L; i < num; ++i) {
      book_name_array.add(MathUtil.genRandomStringAndChinese(10) + "-" + i);
      book_content_array.add(i + "-" + MathUtil.genRandomStringAndChinese(10));
    }

    List<ByteBuffer> book_intro_array = generateBinaryVectors(num, 128);
    List<InsertParam.Field> fields = new ArrayList<>();
    fields.add(new InsertParam.Field("book_name", book_name_array));
    fields.add(new InsertParam.Field("book_content", book_content_array));
    fields.add(new InsertParam.Field(CommonData.defaultBinaryVectorField, book_intro_array));
    return fields;
  }

  public static List<ByteBuffer> generateBinaryVectors(int count, int dimension) {
    Random ran = new Random();
    List<ByteBuffer> vectors = new ArrayList<>();
    int byteCount = dimension / 8;
    for (int n = 0; n < count; ++n) {
      ByteBuffer vector = ByteBuffer.allocate(byteCount);
      // logger.info("generate No."+n+" binary vector");
      for (int i = 0; i < byteCount; ++i) {
        vector.put((byte) ran.nextInt(Byte.MAX_VALUE));
        // logger.info("generateBinaryVector:"+(byte) ran.nextInt(Byte.MAX_VALUE));
      }
      vectors.add(vector);
    }
    return vectors;
  }

  public void generateExampleData() {
    List<String> bookIntroArray =
        Lists.newArrayList("小王子",
            "时间简史",
            "百年孤独",
            "活着",
            "围城",
            "平凡的世界",
            "红楼梦",
            "三体",
            "挪威的森林",
            "追风筝的人");
    List<String> bookContentArray =
        Lists.newArrayList("从前有个小王子住在一颗很小的星球上，那里有一朵他非常珍爱的玫瑰花。",
            "时间是一种神秘的现象，它既无处不在，又难以捉摸。",
            "马孔多是一个充满魔幻色彩的小镇，那里发生了许多不可思议的故事。",
            "人生如戏，我们都是这场戏中的演员，经历着喜怒哀乐。",
            "围城里的人想出去，城外的人想进来，这就是人生的矛盾。",
            "生活虽然平凡，但每个人都有自己的梦想和追求。",
            "红楼梦是一部描写封建社会兴衰的伟大作品。",
            "三体文明的到来改变了人类对宇宙的认知。",
            "挪威的森林中充满了青春的迷茫与彷徨。",
            "追风筝的人讲述了一个关于友谊与救赎的动人故事。");

    List<ByteBuffer> book_intro_array = generateBinaryVectors(10, 128);
    List<InsertParam.Field> fields = new ArrayList<>();
    fields.add(new InsertParam.Field("book_name", bookIntroArray));
    fields.add(new InsertParam.Field("book_content", bookContentArray));
    fields.add(new InsertParam.Field(CommonData.defaultBinaryVectorField, book_intro_array));
    InsertParam insertParam =
        InsertParam.newBuilder().withCollectionName(collectionName).withFields(fields).build();
    milvusServiceClient.insert(insertParam);

  }
}
