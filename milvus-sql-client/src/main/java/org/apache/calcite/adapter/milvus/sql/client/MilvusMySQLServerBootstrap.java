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

import org.apache.calcite.adapter.milvus.sql.client.config.ConfigLoader;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.server.MilvusMySQLServer;
import org.apache.shardingsphere.database.protocol.constant.DatabaseProtocolServerInfo;

public class MilvusMySQLServerBootstrap {

  public static void main(String[] args) throws Exception {
    // Set MySQL protocol version to 8.0.30 for MySQL CLI 8.0+ compatibility
    // This prevents CLI from entering 5.7 compatibility mode and sending incompatible queries
    DatabaseProtocolServerInfo.setProtocolVersion("MySQL", "8.0.30");

    // Load configuration from YAML file or use defaults
    MilvusServerConfig config;
    if (args.length > 0) {
      config = ConfigLoader.load(args[0]);
    } else {
      config = ConfigLoader.load();
    }

    // Print startup configuration
    System.out.println("[Bootstrap] Starting Milvus MySQL Server");
    System.out.println("[Bootstrap] Configuration:");
    System.out.println("  MySQL Protocol: " + config.getHost() + ":" + config.getPort());
    System.out.println("  MySQL Auth: " + (config.getMysqlUsername().isEmpty() ? "ANY" : config.getMysqlUsername()));
    System.out.println("  Milvus Backend: " + config.getMilvusHost() + ":" + config.getMilvusPort());
    System.out.println("  Milvus Auth: " + (config.getMilvusUsername().isEmpty() ? "NONE" : config.getMilvusUsername()));
    System.out.println("  Database: " + config.getMilvusDatabase());
    System.out.println("  Idle Timeout: " + config.getIdleTimeoutSeconds() + "s");
    System.out.println("  SSL Enabled: " + config.isSslEnabled());
    System.out.println("  Auth Plugin: " + config.getAuthPlugin());

    MilvusMySQLServer server = new MilvusMySQLServer(config);
    server.start();

    System.out.println("[Bootstrap] Server started successfully");

    Runtime.getRuntime().addShutdownHook(
        new Thread(() -> {
      System.out.println("[Bootstrap] Shutting down server...");
      server.stop();
      System.out.println("[Bootstrap] Server stopped");
    }));
  }
}
