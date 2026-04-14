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
package org.apache.calcite.adapter.milvus.sql.client.server;

import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MilvusMySQLServer bootstrap options.
 * Covers P0-6 Netty parameters verification.
 */
public class MilvusMySQLServerTest {

  @SuppressWarnings("unchecked")
  private static <T> T getField(Object target, Class<?> clazz, String fieldName) throws Exception {
    Field field = clazz.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (T) field.get(target);
  }

  @Test
  public void testBootstrapNettyOptions() throws Exception {
    MilvusServerConfig config = new MilvusServerConfig();
    config.setBacklog(2048);

    MilvusMySQLServer server = new MilvusMySQLServer(config);
    ServerBootstrap bootstrap = server.createBootstrap();

    Map<ChannelOption<?>, Object> options = getField(
        bootstrap, ServerBootstrap.class.getSuperclass(), "options");
    Map<ChannelOption<?>, Object> childOptions = getField(
        bootstrap, ServerBootstrap.class, "childOptions");

    assertEquals(2048, options.get(ChannelOption.SO_BACKLOG),
        "SO_BACKLOG should match config");
    assertTrue((Boolean) options.get(ChannelOption.SO_REUSEADDR),
        "SO_REUSEADDR should be true");
    assertTrue((Boolean) childOptions.get(ChannelOption.SO_KEEPALIVE),
        "SO_KEEPALIVE should be true");
    assertTrue((Boolean) childOptions.get(ChannelOption.TCP_NODELAY),
        "TCP_NODELAY should be true");
    assertEquals(PooledByteBufAllocator.DEFAULT, childOptions.get(ChannelOption.ALLOCATOR),
        "ALLOCATOR should be PooledByteBufAllocator.DEFAULT");
  }
}
