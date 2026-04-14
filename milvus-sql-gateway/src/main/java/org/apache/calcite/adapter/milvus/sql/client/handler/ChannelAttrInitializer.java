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

import org.apache.shardingsphere.database.protocol.constant.CommonConstants;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.Charset;

/**
 * Channel attributes initializer.
 * Replaces ShardingSphere proxy-frontend dependency with inline implementation.
 */
public final class ChannelAttrInitializer extends ChannelInboundHandlerAdapter {

  @Override
  public void channelActive(final ChannelHandlerContext ctx) {
    ctx.channel().attr(CommonConstants.CHARSET_ATTRIBUTE_KEY).setIfAbsent(Charset.defaultCharset());
    ctx.fireChannelActive();
  }
}
