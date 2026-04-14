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
import org.apache.calcite.adapter.milvus.sql.client.handler.MilvusChannelInitializer;

import io.netty.bootstrap.ServerBootstrap;
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

  public MilvusMySQLServer(MilvusServerConfig config) {
    this.config = config;
  }

  public void start() throws InterruptedException {
    bossGroup = new NioEventLoopGroup(1);
    workerGroup = new NioEventLoopGroup();

    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(new MilvusChannelInitializer(config))
        .option(ChannelOption.SO_BACKLOG, 128)
        .childOption(ChannelOption.SO_KEEPALIVE, true);

    ChannelFuture future = bootstrap.bind(config.getHost(), config.getPort()).sync();
    channel = future.channel();
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
    if (bossGroup != null) {
      bossGroup.shutdownGracefully();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully();
    }
  }
}
