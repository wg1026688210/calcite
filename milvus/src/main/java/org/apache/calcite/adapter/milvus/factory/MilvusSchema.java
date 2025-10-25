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
package org.apache.calcite.adapter.milvus.factory;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.collection.response.ListCollectionsResp;

import java.util.HashMap;
import java.util.Map;

public class MilvusSchema extends AbstractSchema {
  private final Map<String, Table> tableMap = new HashMap<>();

  public MilvusSchema(String host, Integer port, String databaseName, String user,
      String password) {
    super();
    ConnectConfig.ConnectConfigBuilder uri = ConnectConfig.builder()
        .uri("http://" + host + ":" + port);

    if (user != null) {
      uri.username(user);
    }

    if (password != null) {
      uri.password(password);
    }

    MilvusClientV2 milvusClient = new MilvusClientV2(uri.build());

    ListCollectionsResp listCollectionsResponse = milvusClient.listCollections();

    for (String collectionName : listCollectionsResponse.getCollectionNames()) {

      tableMap.put(collectionName,
          new MilvusTranslatableTable(milvusClient, collectionName,
              getCollectionSchema(collectionName, milvusClient)));
    }

  }

  private CreateCollectionReq.CollectionSchema getCollectionSchema(String collectionName,
      MilvusClientV2 milvusClient) {
    DescribeCollectionReq req =
        DescribeCollectionReq.builder().collectionName(collectionName).build();
    DescribeCollectionResp describeCollectionResp =
        milvusClient.describeCollection(req);
    return describeCollectionResp.getCollectionSchema();
  }

  @Override
  protected Map<String, Table> getTableMap() {
    return tableMap;
  }
}
