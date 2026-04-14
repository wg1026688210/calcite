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
import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;
import org.apache.calcite.adapter.milvus.sql.client.handler.MilvusChannelInitializer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Milvus MySQL Server using ShardingSphere db-protocol-mysql.
 */
public class MilvusMySQLServer {
  private final MilvusServerConfig config;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private Channel channel;
  private SQLExecutor sqlExecutor;

  public MilvusMySQLServer(MilvusServerConfig config) {
    this.config = config;
    this.bossGroup = new NioEventLoopGroup(1);
    this.workerGroup = new NioEventLoopGroup();
  }

  public void start() throws InterruptedException {
    sqlExecutor = new SQLExecutor(
        config.getMilvusHost(),
        config.getMilvusPort(),
        config.getMilvusDatabase(),
        config.getMilvusUsername(),
        config.getMilvusPassword(),
        config.getQueryTimeoutSeconds(),
        config.getConnectionPoolSize());

    ServerBootstrap bootstrap = createBootstrap();
    ChannelFuture future = bootstrap.bind(config.getHost(), config.getPort()).sync();
    channel = future.channel();
  }

  ServerBootstrap createBootstrap() {
    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(new MilvusChannelInitializer(config, sqlExecutor))
        .option(ChannelOption.SO_BACKLOG, config.getBacklog())
        .option(ChannelOption.SO_REUSEADDR, true)
        .childOption(ChannelOption.SO_KEEPALIVE, true)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    return bootstrap;
  }

  public int getPort() {
    if (channel != null && channel.localAddress() instanceof java.net.InetSocketAddress) {
      return ((java.net.InetSocketAddress) channel.localAddress()).getPort();
    }
    return config.getPort();
  }

  public void stop() {
    if (channel != null) {
      channel.close();
    }
    if (sqlExecutor != null) {
      sqlExecutor.close();
    }
    if (bossGroup != null) {
      bossGroup.shutdownGracefully();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully();
    }
  }
}
