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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A simple per-database {@link Connection} pool implemented with
 * {@code java.util.concurrent} primitives only.
 *
 * <p>Each database has its own {@link CalciteConnectionPool} instance.
 * Connections are created lazily up to {@code maxSize} and reused across
 * queries. When the pool is at capacity, {@link #borrow()} blocks until a
 * connection is returned.</p>
 */
public final class CalciteConnectionPool {

  private final String databaseName;
  private final int maxSize;
  private final BlockingQueue<Connection> pool;
  private final AtomicInteger activeCount = new AtomicInteger(0);
  private final Function<String, Connection> factory;
  private volatile boolean closed = false;

  public CalciteConnectionPool(String databaseName, int maxSize,
      Function<String, Connection> factory) {
    this.databaseName = databaseName;
    this.maxSize = maxSize;
    this.pool = new ArrayBlockingQueue<>(maxSize);
    this.factory = factory;
  }

  /**
   * Borrows a connection from the pool. Creates a new one if the pool
   * has not reached {@code maxSize}; otherwise blocks until a connection
   * is returned.
   */
  public Connection borrow() throws SQLException {
    if (closed) {
      throw new SQLException("Pool closed for database: " + databaseName);
    }

    Connection conn = pool.poll();
    if (conn != null) {
      return conn;
    }

    synchronized (this) {
      if (!closed && activeCount.get() < maxSize) {
        activeCount.incrementAndGet();
        try {
          return factory.apply(databaseName);
        } catch (RuntimeException e) {
          activeCount.decrementAndGet();
          if (e.getCause() instanceof SQLException) {
            throw (SQLException) e.getCause();
          }
          throw e;
        }
      }
    }

    try {
      return pool.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException(
          "Interrupted while waiting for connection to database: " + databaseName, e);
    }
  }

  /**
   * Returns a connection to the pool. If the pool is closed or full,
   * the connection is closed instead.
   */
  public void returnConnection(Connection conn) {
    if (conn == null) {
      return;
    }
    if (closed || !pool.offer(conn)) {
      activeCount.decrementAndGet();
      try {
        conn.close();
      } catch (SQLException ignored) {
        // ignore close errors
      }
    }
  }

  /**
   * Closes the pool and all idle connections held in it.
   * Connections still checked out are not affected.
   */
  public void close() {
    closed = true;
    Connection conn;
    while ((conn = pool.poll()) != null) {
      try {
        conn.close();
      } catch (SQLException ignored) {
        // ignore close errors
      }
      activeCount.decrementAndGet();
    }
  }
}

/**
 * Manages a collection of {@link CalciteConnectionPool} instances,
 * one per database name.
 */
final class ConnectionPoolManager {

  private final ConcurrentHashMap<String, CalciteConnectionPool> pools =
      new ConcurrentHashMap<>();
  private final int maxPoolSize;
  private final Function<String, Connection> factory;

  ConnectionPoolManager(int maxPoolSize, Function<String, Connection> factory) {
    this.maxPoolSize = maxPoolSize;
    this.factory = factory;
  }

  Connection borrow(String database) throws SQLException {
    CalciteConnectionPool pool = pools.computeIfAbsent(database,
        db -> new CalciteConnectionPool(db, maxPoolSize, factory));
    return pool.borrow();
  }

  void returnConnection(String database, Connection conn) {
    CalciteConnectionPool pool = pools.get(database);
    if (pool != null) {
      pool.returnConnection(conn);
    } else if (conn != null) {
      try {
        conn.close();
      } catch (SQLException ignored) {
        // ignore close errors
      }
    }
  }

  void close() {
    for (CalciteConnectionPool pool : pools.values()) {
      pool.close();
    }
    pools.clear();
  }
}
