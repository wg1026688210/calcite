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
package org.apache.calcite.adapter.milvus.sql.client.executor;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SQLExecutor connection pooling behavior.
 */
public class SQLExecutorPoolTest {

  private static Statement createProxyStatement() {
    return (Statement) Proxy.newProxyInstance(
        SQLExecutorPoolTest.class.getClassLoader(),
        new Class<?>[]{Statement.class},
        (obj, method, args) -> {
          String name = method.getName();
          if ("close".equals(name) || "isClosed".equals(name)) {
            return "isClosed".equals(name) ? false : null;
          }
          if ("execute".equals(name)) {
            return false;
          }
          if ("getUpdateCount".equals(name)) {
            return 0;
          }
          Class<?> returnType = method.getReturnType();
          if (returnType.equals(boolean.class)) {
            return false;
          }
          if (returnType.equals(int.class)) {
            return 0;
          }
          return null;
        });
  }

  private static Connection createProxyConnection() {
    return (Connection) Proxy.newProxyInstance(
        SQLExecutorPoolTest.class.getClassLoader(),
        new Class<?>[]{Connection.class},
        (obj, method, args) -> {
          String name = method.getName();
          if ("createStatement".equals(name)) {
            return createProxyStatement();
          }
          if ("close".equals(name) || "isClosed".equals(name)) {
            return "isClosed".equals(name) ? false : null;
          }
          if ("unwrap".equals(name)) {
            return null;
          }
          if ("getSchema".equals(name)) {
            return "default";
          }
          if ("setSchema".equals(name)) {
            return null;
          }
          Class<?> returnType = method.getReturnType();
          if (returnType.equals(boolean.class)) {
            return false;
          }
          if (returnType.equals(int.class)) {
            return 0;
          }
          return null;
        });
  }

  @Test
  public void testConnectionReuseWithPool() throws SQLException {
    AtomicInteger createCount = new AtomicInteger(0);

    SQLExecutor executor = new SQLExecutor(
        "localhost", 19530, "default", "", "", 0, 2) {
      @Override
      protected Connection createConnection(String currentDatabase) throws SQLException {
        createCount.incrementAndGet();
        return createProxyConnection();
      }
    };

    executor.execute("SELECT 1", "default");
    executor.execute("SELECT 2", "default");

    assertEquals(1, createCount.get(),
        "Connection should be reused when pool is enabled");

    executor.close();
  }

  @Test
  public void testNoPoolingWhenSizeIsZero() throws SQLException {
    AtomicInteger createCount = new AtomicInteger(0);

    SQLExecutor executor = new SQLExecutor(
        "localhost", 19530, "default", "", "", 0, 0) {
      @Override
      protected Connection createConnection(String currentDatabase) throws SQLException {
        createCount.incrementAndGet();
        return createProxyConnection();
      }
    };

    executor.execute("SELECT 1", "default");
    executor.execute("SELECT 2", "default");

    assertEquals(2, createCount.get(),
        "Each query should create a new connection when pool is disabled");

    executor.close();
  }

  @Test
  public void testCloseReleasesConnections() throws SQLException {
    AtomicInteger closeCount = new AtomicInteger(0);

    Connection proxyConnection = (Connection) Proxy.newProxyInstance(
        SQLExecutorPoolTest.class.getClassLoader(),
        new Class<?>[]{Connection.class},
        (obj, method, args) -> {
          String name = method.getName();
          if ("close".equals(name)) {
            closeCount.incrementAndGet();
            return null;
          }
          if ("isClosed".equals(name)) {
            return false;
          }
          if ("createStatement".equals(name)) {
            return createProxyStatement();
          }
          if ("unwrap".equals(name) || "setSchema".equals(name)) {
            return null;
          }
          if ("getSchema".equals(name)) {
            return "default";
          }
          Class<?> returnType = method.getReturnType();
          if (returnType.equals(boolean.class)) {
            return false;
          }
          if (returnType.equals(int.class)) {
            return 0;
          }
          return null;
        });

    SQLExecutor executor = new SQLExecutor(
        "localhost", 19530, "default", "", "", 0, 1) {
      @Override
      protected Connection createConnection(String currentDatabase) throws SQLException {
        return proxyConnection;
      }
    };

    executor.execute("SELECT 1", "default");
    executor.close();

    assertTrue(closeCount.get() >= 1,
        "Closing executor should close pooled connections");
  }
}
