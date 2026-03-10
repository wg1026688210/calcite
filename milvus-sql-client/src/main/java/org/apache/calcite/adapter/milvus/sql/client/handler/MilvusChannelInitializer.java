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

import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;

import org.apache.shardingsphere.database.protocol.codec.PacketCodec;
import org.apache.shardingsphere.database.protocol.codec.PacketCodec;
import org.apache.shardingsphere.database.protocol.constant.CommonConstants;
import org.apache.shardingsphere.database.protocol.mysql.codec.MySQLPacketCodecEngine;
import org.apache.shardingsphere.database.protocol.mysql.constant.MySQLConstants;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Initializes the channel pipeline for MySQL protocol handling.
 * Sets up the 6-layer architecture:
 * 1. Protocol Layer: ShardingSphere codec
 * 2. Authentication Layer: MilvusAuthHandler
 * 3. Command Layer: MilvusCommandDispatcher
 */
public class MilvusChannelInitializer extends ChannelInitializer<SocketChannel> {

  private final MilvusServerConfig config;

  public MilvusChannelInitializer(MilvusServerConfig config) {
    this.config = config;
  }

  @Override
  protected void initChannel(SocketChannel ch) {
    // Set charset attribute required by ShardingSphere codec
    ch.attr(CommonConstants.CHARSET_ATTRIBUTE_KEY).set(StandardCharsets.UTF_8);
    // Set sequence ID attribute for MySQL protocol
    ch.attr(MySQLConstants.SEQUENCE_ID_ATTRIBUTE_KEY).set(new AtomicInteger());

    ch.pipeline()
        // Layer 2: Protocol Layer - ShardingSphere MySQL codec
        .addLast(new PacketCodec(new MySQLPacketCodecEngine()))

        // Layer 3: Authentication Layer
        .addLast(new MilvusAuthHandler(config))

        // Layer 4: Command Dispatch Layer
        .addLast(new MilvusCommandDispatcher(config));
  }
}
