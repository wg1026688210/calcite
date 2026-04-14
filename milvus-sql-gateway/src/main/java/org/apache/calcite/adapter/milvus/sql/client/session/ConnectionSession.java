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
package org.apache.calcite.adapter.milvus.sql.client.session;

/**
 * Connection session for MySQL client connections.
 * Stores per-connection state including authentication and current database.
 */
public class ConnectionSession {

  private final int connectionId;
  private volatile String currentDatabase;
  private volatile boolean authenticated;
  private volatile int capabilityFlags;

  public ConnectionSession(int connectionId, String defaultDatabase) {
    this.connectionId = connectionId;
    this.currentDatabase = defaultDatabase;
    this.authenticated = false;
    this.capabilityFlags = 0;
  }

  public int getConnectionId() {
    return connectionId;
  }

  public String getCurrentDatabase() {
    return currentDatabase;
  }

  public void setCurrentDatabase(String currentDatabase) {
    this.currentDatabase = currentDatabase;
  }

  public boolean isAuthenticated() {
    return authenticated;
  }

  public void setAuthenticated(boolean authenticated) {
    this.authenticated = authenticated;
  }

  public int getCapabilityFlags() {
    return capabilityFlags;
  }

  public void setCapabilityFlags(int capabilityFlags) {
    this.capabilityFlags = capabilityFlags;
  }
}
