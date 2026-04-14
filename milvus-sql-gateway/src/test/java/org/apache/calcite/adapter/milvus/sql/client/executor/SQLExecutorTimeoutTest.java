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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SQLExecutor query timeout behavior.
 * Covers P1-3 query timeout via Statement.setQueryTimeout.
 */
public class SQLExecutorTimeoutTest {

  @Test
  public void testQueryTimeoutIsSet() throws SQLException {
    final AtomicBoolean setQueryTimeoutCalled = new AtomicBoolean(false);
    final AtomicInteger timeoutValue = new AtomicInteger(-1);

    Statement proxyStatement = (Statement) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{Statement.class},
        (obj, method, args) -> {
          String name = method.getName();
          if ("setQueryTimeout".equals(name)) {
            setQueryTimeoutCalled.set(true);
            timeoutValue.set((Integer) args[0]);
            return null;
          }
          if ("execute".equals(name)) {
            return false;
          }
          if ("getUpdateCount".equals(name)) {
            return 0;
          }
          if ("close".equals(name) || "isClosed".equals(name)) {
            return "isClosed".equals(name) ? false : null;
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

    Connection proxyConnection = (Connection) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{Connection.class},
        (obj, method, args) -> {
          String name = method.getName();
          if ("createStatement".equals(name)) {
            return proxyStatement;
          }
          if ("close".equals(name) || "isClosed".equals(name)) {
            return "isClosed".equals(name) ? false : null;
          }
          return null;
        });

    SQLExecutor executor = new SQLExecutor("localhost", 19530, "default", "", "", 15) {
      @Override
      protected Connection createConnection(String currentDatabase) throws SQLException {
        return proxyConnection;
      }
    };

    executor.execute("SELECT 1", "default");

    assertTrue(setQueryTimeoutCalled.get(),
        "setQueryTimeout should be called on Statement");
    assertEquals(15, timeoutValue.get(),
        "setQueryTimeout should be called with configured timeout");
  }

  @Test
  public void testQueryTimeoutSkippedWhenZero() throws SQLException {
    final AtomicBoolean setQueryTimeoutCalled = new AtomicBoolean(false);

    Statement proxyStatement = (Statement) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{Statement.class},
        (obj, method, args) -> {
          if ("setQueryTimeout".equals(method.getName())) {
            setQueryTimeoutCalled.set(true);
            return null;
          }
          if ("execute".equals(method.getName())) {
            return false;
          }
          if ("getUpdateCount".equals(method.getName())) {
            return 0;
          }
          if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
            return "isClosed".equals(method.getName()) ? false : null;
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

    Connection proxyConnection = (Connection) Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{Connection.class},
        (obj, method, args) -> {
          if ("createStatement".equals(method.getName())) {
            return proxyStatement;
          }
          if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
            return "isClosed".equals(method.getName()) ? false : null;
          }
          return null;
        });

    SQLExecutor executor = new SQLExecutor("localhost", 19530, "default", "", "", 0) {
      @Override
      protected Connection createConnection(String currentDatabase) throws SQLException {
        return proxyConnection;
      }
    };

    executor.execute("SELECT 1", "default");

    assertTrue(!setQueryTimeoutCalled.get(),
        "setQueryTimeout should NOT be called when timeout is 0");
  }
}
