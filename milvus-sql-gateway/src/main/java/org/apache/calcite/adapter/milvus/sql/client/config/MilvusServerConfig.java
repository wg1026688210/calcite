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
package org.apache.calcite.adapter.milvus.sql.client.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configuration for Milvus MySQL Server.
 * Supports YAML configuration file loading via Jackson.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MilvusServerConfig {
  private int port = 3307;
  private String host = "0.0.0.0";
  private int maxConnections = 100;
  private String milvusHost = "localhost";
  private int milvusPort = 19530;
  private String milvusDatabase = "default";
  private String milvusUsername = "";
  private String milvusPassword = "";

  // MySQL protocol authentication (defaults to empty, allowing any connection)
  private String mysqlUsername = "";
  private String mysqlPassword = "";

  // Idle connection timeout (seconds), default 30 minutes
  private int idleTimeoutSeconds = 1800;

  // Query timeout in seconds (default 30, 0 means no timeout)
  private int queryTimeoutSeconds = 30;

  // TCP accept backlog
  private int backlog = 1024;

  // CalciteConnection pool size per database (default 5, min 1, max 20)
  private int connectionPoolSize = 5;

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  public void setMaxConnections(int maxConnections) {
    this.maxConnections = maxConnections;
  }

  public String getMilvusHost() {
    return milvusHost;
  }

  public void setMilvusHost(String milvusHost) {
    this.milvusHost = milvusHost;
  }

  public int getMilvusPort() {
    return milvusPort;
  }

  public void setMilvusPort(int milvusPort) {
    this.milvusPort = milvusPort;
  }

  public String getMilvusDatabase() {
    return milvusDatabase;
  }

  public void setMilvusDatabase(String milvusDatabase) {
    this.milvusDatabase = milvusDatabase;
  }

  public String getMilvusUsername() {
    return milvusUsername;
  }

  public void setMilvusUsername(String milvusUsername) {
    this.milvusUsername = milvusUsername;
  }

  public String getMilvusPassword() {
    return milvusPassword;
  }

  public void setMilvusPassword(String milvusPassword) {
    this.milvusPassword = milvusPassword;
  }

  public String getMysqlUsername() {
    return mysqlUsername;
  }

  public void setMysqlUsername(String mysqlUsername) {
    this.mysqlUsername = mysqlUsername;
  }

  public String getMysqlPassword() {
    return mysqlPassword;
  }

  public void setMysqlPassword(String mysqlPassword) {
    this.mysqlPassword = mysqlPassword;
  }

  // SSL configuration (auto-generated self-signed certificate, zero config)
  private boolean sslEnabled = false;

  // Auth plugin configuration - default to caching_sha2_password for MySQL 8.0+ compatibility
  private String authPlugin = "caching_sha2_password";

  public boolean isSslEnabled() {
    return sslEnabled;
  }

  public void setSslEnabled(boolean sslEnabled) {
    this.sslEnabled = sslEnabled;
  }

  public String getAuthPlugin() {
    return authPlugin;
  }

  public void setAuthPlugin(String authPlugin) {
    this.authPlugin = authPlugin;
  }

  public int getIdleTimeoutSeconds() {
    return idleTimeoutSeconds;
  }

  public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
    this.idleTimeoutSeconds = idleTimeoutSeconds;
  }

  public int getQueryTimeoutSeconds() {
    return queryTimeoutSeconds;
  }

  public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
    this.queryTimeoutSeconds = queryTimeoutSeconds;
  }

  public int getBacklog() {
    return backlog;
  }

  public void setBacklog(int backlog) {
    this.backlog = backlog;
  }

  public int getConnectionPoolSize() {
    return connectionPoolSize;
  }

  public void setConnectionPoolSize(int connectionPoolSize) {
    this.connectionPoolSize = Math.max(1, Math.min(20, connectionPoolSize));
  }
}
