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
package org.apache.calcite.adapter.milvus.sql.client;

import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.server.MilvusMySQLServer;

public class MilvusMySQLServerBootstrap {

  public static void main(String[] args) throws Exception {
    MilvusServerConfig config = new MilvusServerConfig();

    if (args.length > 0) {
      config.setPort(Integer.parseInt(args[0]));
    }
    if (args.length > 1) {
      config.setMilvusHost(args[1]);
    }
    if (args.length > 2) {
      config.setMilvusPort(Integer.parseInt(args[2]));
    }

    MilvusMySQLServer server = new MilvusMySQLServer(config);
    server.start();

    System.out.println("Milvus MySQL Server started on port " + config.getPort());
    System.out.println("Connected to Milvus at " + config.getMilvusHost() + ":" + config.getMilvusPort());

    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
  }
}
