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

import io.milvus.pool.MilvusClientV2Pool;
import io.milvus.pool.PoolConfig;
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

  private final String host;
  private final Integer port;
  private final String databaseName;
  private final String user;
  private final String password;

  private final String poolKey;
  private final MilvusClientV2Pool clientPool;

  public MilvusSchema(String host, Integer port, String databaseName, String user,
      String password) {
    super();
    this.host = host;
    this.port = port;
    this.databaseName = databaseName;
    this.user = user;
    this.password = password;

    // A stable identifier for a group of pooled clients (keyed pool).
    this.poolKey = buildPoolKey(host, port, databaseName, user);

    try {
      this.clientPool = new MilvusClientV2Pool(
          PoolConfig.builder().build(),
          buildConnectConfig());
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize MilvusClientV2Pool", e);
    }
  }

  private static String buildPoolKey(String host, Integer port, String databaseName, String user) {
    StringBuilder sb = new StringBuilder();
    sb.append(host).append(':').append(port);
    if (databaseName != null) {
      sb.append("/").append(databaseName);
    }
    if (user != null) {
      sb.append("?user=").append(user);
    }
    return sb.toString();
  }

  private ConnectConfig buildConnectConfig() {
    ConnectConfig.ConnectConfigBuilder uri = ConnectConfig.builder()
        .uri("http://" + host + ":" + port);

    if (user != null) {
      uri.username(user);
    }

    if (password != null) {
      uri.password(password);
    }

    if (databaseName != null) {
      uri.dbName(databaseName);
    }

    return uri.build();
  }

  /** Borrow a MilvusClientV2 from the SDK pool. Caller must return it. */
  public MilvusClientV2 borrowClient() {
    return clientPool.getClient(poolKey);
  }

  /** Return a MilvusClientV2 back to the SDK pool. */
  public void returnClient(MilvusClientV2 client) {
    if (client == null) {
      return;
    }
    clientPool.returnClient(poolKey, client);
  }

  @Override
  protected synchronized Map<String, Table> getTableMap() {
    MilvusClientV2 client = borrowClient();
    try {
      ListCollectionsResp list = client.listCollections();
      if (list.getCollectionNames() != null) {
        for (String name : list.getCollectionNames()) {
          tableMap.computeIfAbsent(name, n ->
              new MilvusTranslatableTable(this, n, getCollectionSchema(n, client)));
        }
      }
    } finally {
      returnClient(client);
    }
    return tableMap;
  }

  /**
   * Compatibility escape hatch: create a standalone client.
   *
   * <p>Prefer {@link #borrowClient()} / {@link #returnClient(MilvusClientV2)} for pooling.
   */
  public MilvusClientV2 createClient() {
    return new MilvusClientV2(buildConnectConfig());
  }

  private CreateCollectionReq.CollectionSchema getCollectionSchema(String collectionName,
      MilvusClientV2 milvusClient) {
    DescribeCollectionReq req =
        DescribeCollectionReq.builder().collectionName(collectionName).build();
    DescribeCollectionResp describeCollectionResp =
        milvusClient.describeCollection(req);
    return describeCollectionResp.getCollectionSchema();
  }
}
