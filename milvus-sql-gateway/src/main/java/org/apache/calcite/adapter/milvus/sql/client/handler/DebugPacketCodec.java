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

import org.apache.shardingsphere.database.protocol.codec.DatabasePacketCodecEngine;
import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.util.List;

/**
 * Debug packet codec that logs encoded/decoded bytes.
 */
public final class DebugPacketCodec extends ByteToMessageCodec<DatabasePacket> {

  private final DatabasePacketCodecEngine engine;

  public DebugPacketCodec(DatabasePacketCodecEngine engine) {
    this.engine = engine;
  }

  @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    engine.decode(ctx, in, out);
  }

  @Override protected void encode(ChannelHandlerContext ctx, DatabasePacket msg, ByteBuf out) {
    int beforeBytes = out.writerIndex();
    engine.encode(ctx, msg, out);
    int afterBytes = out.writerIndex();
    int encodedBytes = afterBytes - beforeBytes;

    if (encodedBytes > 0) {
      ByteBuf encoded = out.slice(beforeBytes, encodedBytes);
      int payloadLen = encoded.getUnsignedMediumLE(0);
      int seqId = encoded.getUnsignedByte(3);

      System.err.println("[CODEC] Encoded " + msg.getClass().getSimpleName() +
          ": payloadLen=" + payloadLen + ", seqId=" + seqId);

      // Log first byte of payload
      if (payloadLen > 0) {
        int firstByte = encoded.getUnsignedByte(4);
        System.err.println("[CODEC]   First payload byte: 0x" + Integer.toHexString(firstByte));
      }
    } else {
      System.err.println("[CODEC] Encoded " + msg.getClass().getSimpleName() + ": 0 bytes (NOT ENCODED!)");
    }
  }
}
