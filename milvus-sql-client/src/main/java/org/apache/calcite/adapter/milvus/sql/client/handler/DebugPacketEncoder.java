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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Debug encoder that logs all outgoing bytes.
 */
public class DebugPacketEncoder extends MessageToByteEncoder<ByteBuf> {

  @Override
  protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
    // Copy the message to output
    out.writeBytes(msg);

    // Log the bytes
    int readable = msg.readableBytes();
    if (readable >= 4) {
      int readerIndex = msg.readerIndex();
      int payloadLen = msg.getUnsignedMediumLE(readerIndex);
      int seqId = msg.getUnsignedByte(readerIndex + 3);

      System.err.println("[ENCODE] Outgoing packet: payloadLen=" + payloadLen + ", seqId=" + seqId + ", totalBytes=" + readable);

      // Log first byte of payload
      if (readable > 4) {
        int firstPayloadByte = msg.getUnsignedByte(readerIndex + 4);
        System.err.println("[ENCODE]   First payload byte: 0x" + Integer.toHexString(firstPayloadByte));
      }
    } else {
      System.err.println("[ENCODE] Outgoing packet: " + readable + " bytes (too short)");
    }
  }
}
