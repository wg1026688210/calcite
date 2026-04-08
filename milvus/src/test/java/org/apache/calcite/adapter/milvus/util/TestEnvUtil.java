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
import io.milvus.grpc.MutationResult;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.collection.CreateDatabaseParam;
import io.milvus.param.collection.DropDatabaseParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;

import java.util.ArrayList;
import java.util.List;

public class TestEnvUtil {
  private String collectionName;
  private String databaseName;
  private MilvusServiceClient milvusServiceClient;
  private final MetricType metricType;

  public TestEnvUtil(String collectionName, MilvusServiceClient milvusServiceClient) {
    this(collectionName, "default", milvusServiceClient, MetricType.L2);
  }

  public TestEnvUtil(String collectionName, String databaseName,
      MilvusServiceClient milvusServiceClient) {
    this(collectionName, databaseName, milvusServiceClient, MetricType.L2);
  }

  public TestEnvUtil(String collectionName, MilvusServiceClient milvusServiceClient,
      MetricType metricType) {
    this(collectionName, "default", milvusServiceClient, metricType);
  }

  public TestEnvUtil(String collectionName, String databaseName,
      MilvusServiceClient milvusServiceClient, MetricType metricType) {
    this.collectionName = collectionName;
    this.databaseName = databaseName != null ? databaseName : "default";
    this.milvusServiceClient = milvusServiceClient;
    this.metricType = metricType;
  }

  public void createExampleCollection() {
    ensureDatabaseExists();
    dropCollectionIfExists();
    createFloatVectorCollection();
    generateVectorExampleData();
  }

  /**
   * Creates a new database if it doesn't exist.
   */
  public void ensureDatabaseExists() {
    if (databaseName == null || "default".equals(databaseName)) {
      return; // default database always exists
    }

    try {
      // Check if database exists by listing all databases
      boolean exists = false;
      R<io.milvus.grpc.ListDatabasesResponse> listResponse = milvusServiceClient.listDatabases();
      if (listResponse.getStatus() == 0 && listResponse.getData() != null) {
        for (String dbName : listResponse.getData().getDbNamesList()) {
          if (databaseName.equals(dbName)) {
            exists = true;
            break;
          }
        }
      }

      if (!exists) {
        // Create database
        CreateDatabaseParam createDbParam = CreateDatabaseParam.newBuilder()
            .withDatabaseName(databaseName)
            .build();
        R<RpcStatus> response = milvusServiceClient.createDatabase(createDbParam);
        if (response.getStatus() != 0) {
          throw new RuntimeException("Failed to create database: " + response.getMessage());
        }
        System.out.println("[TestEnvUtil] Created database: " + databaseName);
      }
    } catch (Exception e) {
      System.out.println("[TestEnvUtil] Warning: Database check/create failed: " + e.getMessage());
    }
  }

  /**
   * Drops the database and all its collections.
   */
  public void dropDatabase() {
    if (databaseName == null || "default".equals(databaseName)) {
      return; // don't drop default database
    }

    try {
      DropDatabaseParam dropDbParam = DropDatabaseParam.newBuilder()
          .withDatabaseName(databaseName)
          .build();
      milvusServiceClient.dropDatabase(dropDbParam);
      System.out.println("[TestEnvUtil] Dropped database: " + databaseName);
    } catch (Exception e) {
      System.out.println("[TestEnvUtil] Warning: Failed to drop database: " + e.getMessage());
    }
  }

  private void generateVectorExampleData() {
    List<String> bookNames =
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

    List<String> bookContents =
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

    // Generate float vectors (4 dimensions for testing)
    List<List<Float>> vectors = generateDeterministicVectors(bookNames.size(), 4, 0.1f);

    // Create fields for insertion
    List<InsertParam.Field> fields = new ArrayList<>();
    fields.add(new InsertParam.Field("book_name", bookNames));
    fields.add(new InsertParam.Field("book_content", bookContents));
    fields.add(new InsertParam.Field(CommonData.defaultVectorField, vectors));

    // Insert data
    InsertParam insertParam = InsertParam.newBuilder()
        .withCollectionName(collectionName)
        .withDatabaseName(databaseName)
        .withFields(fields)
        .build();

    R<MutationResult> response =
        milvusServiceClient.insert(insertParam);
    if (response.getStatus() != 0) {
      throw new RuntimeException("Failed to insert data: " + response.getMessage());
    }

    CreateIndexParam indexParam = CreateIndexParam.newBuilder()
        .withCollectionName(collectionName)
        .withDatabaseName(databaseName)
        .withFieldName(CommonData.defaultVectorField)
        .withIndexName("float_vector_idx")
        .withIndexType(IndexType.IVF_FLAT)
        .withMetricType(metricType)
        .withExtraParam("{\"nlist\":1024}")
        .withSyncMode(Boolean.TRUE)
        .build();

    R<RpcStatus> indexResponse = milvusServiceClient.createIndex(indexParam);
    if (indexResponse.getStatus() != 0) {
      System.out.println("Warning: Failed to create index: " + indexResponse.getMessage());
    }

    // Load collection
    LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
        .withCollectionName(collectionName)
        .withDatabaseName(databaseName)
        .withSyncLoad(Boolean.TRUE)
        .build();

    R<?> loadResponse = milvusServiceClient.loadCollection(loadParam);
    if (loadResponse.getStatus() != R.Status.Success.getCode()) {
      System.out.println("Warning: Failed to load collection: " + loadResponse.getMessage());
    }
  }


  private List<List<Float>> generateDeterministicVectors(int count, int dimension, float scale) {
    List<List<Float>> vectors = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      List<Float> vector = new ArrayList<>(dimension);
      float base = (i + 1) * scale;
      for (int j = 0; j < dimension; j++) {
        vector.add(base * (j + 1));
      }
      vectors.add(vector);
    }
    return vectors;
  }

  private void dropCollectionIfExists() {
    if (collectionExists(collectionName)) {
      dropCollection();
    }
  }


  private boolean collectionExists(String collectionName) {
    try {
      HasCollectionParam.Builder paramBuilder = HasCollectionParam.newBuilder()
          .withCollectionName(collectionName);
      if (databaseName != null && !"default".equals(databaseName)) {
        paramBuilder.withDatabaseName(databaseName);
      }
      R<Boolean> response = milvusServiceClient.hasCollection(paramBuilder.build());
      return response.getData();
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Creates a collection with FloatVector field specifically for vector search testing.
   * This does not modify the base test class and is specific to vector search scenarios.
   */
  private void createFloatVectorCollection() {
    List<FieldType> fieldsSchema = new ArrayList<>();

    // Primary key field
    fieldsSchema.add(
        FieldType.newBuilder()
            .withName("book_name")
            .withDataType(DataType.VarChar)
            .withMaxLength(200)
            .withPrimaryKey(true)
            .withAutoID(false)
            .build());

    // Content field
    fieldsSchema.add(
        FieldType.newBuilder()
            .withName("book_content")
            .withDataType(DataType.VarChar)
            .withMaxLength(200)
            .build());

    // Float vector field for L2 distance testing
    fieldsSchema.add(
        FieldType.newBuilder()
            .withName(CommonData.defaultVectorField)
            .withDataType(DataType.FloatVector)
            .withDimension(4)  // Small dimension for ease of testing
            .build());

    CollectionSchemaParam schemaParam = CollectionSchemaParam.newBuilder()
        .withFieldTypes(fieldsSchema)
        .build();

    CreateCollectionParam.Builder createCollectionReqBuilder = CreateCollectionParam.newBuilder()
        .withCollectionName(collectionName)
        .withDescription("Collection for vector search testing")
        .withShardsNum(2)
        .withSchema(schemaParam);
    if (databaseName != null && !"default".equals(databaseName)) {
      createCollectionReqBuilder.withDatabaseName(databaseName);
    }
    CreateCollectionParam createCollectionReq = createCollectionReqBuilder.build();

    R<RpcStatus> response = milvusServiceClient.createCollection(createCollectionReq);
    if (response.getStatus() != 0) {
      throw new RuntimeException("Failed to create collection: " + response.getMessage());
    }
  }

  private void dropCollection() {
    DropCollectionParam.Builder dropParamBuilder = DropCollectionParam.newBuilder()
        .withCollectionName(collectionName);
    if (databaseName != null && !"default".equals(databaseName)) {
      dropParamBuilder.withDatabaseName(databaseName);
    }
    milvusServiceClient.dropCollection(dropParamBuilder.build());
  }
}
