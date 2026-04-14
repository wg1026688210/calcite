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
package org.apache.calcite.adapter.milvus.sql.client.handler;

import org.apache.calcite.adapter.milvus.sql.client.command.MilvusCommandExecutorFactory;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Unified frontend handler that composes authentication and command dispatch.
 * Simplifies the Netty pipeline by exposing a single handler for the frontend layer.
 */
public class MilvusFrontendHandler extends ChannelInboundHandlerAdapter {

  private final MilvusAuthHandler authHandler;
  private final MilvusCommandDispatcher commandDispatcher;

  public MilvusFrontendHandler(MilvusServerConfig config, SQLExecutor sqlExecutor) {
    this.authHandler = new MilvusAuthHandler(config);
    this.commandDispatcher = new MilvusCommandDispatcher(sqlExecutor,
        MilvusCommandExecutorFactory::createExecutor);
  }

  @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
    authHandler.channelActive(ctx);
  }

  @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (authHandler.isHandshakeComplete()) {
      commandDispatcher.channelRead(ctx, msg);
    } else {
      authHandler.channelRead(ctx, msg);
    }
  }

  @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    authHandler.channelInactive(ctx);
  }

  @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    authHandler.exceptionCaught(ctx, cause);
  }

  @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
    authHandler.userEventTriggered(ctx, event);
  }
}
