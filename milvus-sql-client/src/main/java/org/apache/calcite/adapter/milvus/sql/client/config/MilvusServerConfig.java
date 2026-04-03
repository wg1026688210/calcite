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

public class MilvusServerConfig {
  private int port = 3307;
  private String host = "0.0.0.0";
  private int maxConnections = 100;
  private String milvusHost = "localhost";
  private int milvusPort = 19530;
  private String milvusDatabase = "default";
  private String milvusUsername = "";
  private String milvusPassword = "";

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

  // SSL configuration (auto-generated self-signed certificate, zero config)
  private boolean sslEnabled = false;

  // Auth plugin configuration
  private String authPlugin = "mysql_native_password";

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
}
