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

import org.apache.calcite.adapter.milvus.sql.client.command.CommandExecutor;
import org.apache.calcite.adapter.milvus.sql.client.executor.SQLExecutor;

import org.apache.shardingsphere.database.protocol.packet.DatabasePacket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for MilvusCommandDispatcher.
 * Covers P0-4 autoRead backpressure, P1-7 executor close lifecycle, P2-5 exception classification.
 */
public class MilvusCommandDispatcherTest {

  private static SQLExecutor dummySqlExecutor() {
    return new SQLExecutor("localhost", 19530, "default", "", "", 0);
  }

  private static EmbeddedChannel createChannel() {
    EmbeddedChannel channel = new EmbeddedChannel();
    channel.attr(org.apache.shardingsphere.database.protocol.constant.CommonConstants.CHARSET_ATTRIBUTE_KEY)
        .set(StandardCharsets.UTF_8);
    return channel;
  }

  @Test
  public void testAutoReadBackpressure() throws Exception {
    SQLExecutor sqlExecutor = dummySqlExecutor();
    AtomicBoolean autoReadFalse = new AtomicBoolean(false);
    AtomicBoolean autoReadTrue = new AtomicBoolean(false);

    EmbeddedChannel channel = createChannel();
    channel.config().setAutoRead(true);

    CommandExecutor executor = new CommandExecutor() {
      @Override
      public Collection<DatabasePacket> execute() throws SQLException {
        autoReadFalse.set(!channel.config().isAutoRead());
        return Collections.emptyList();
      }
    };

    MilvusCommandDispatcher dispatcher = new MilvusCommandDispatcher(sqlExecutor,
        (cmd, ctx, session, sqlExec) -> executor);
    channel.pipeline().addLast(dispatcher);

    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{0x0E}); // COM_PING
    channel.writeInbound(buffer);

    autoReadTrue.set(channel.config().isAutoRead());

    assertTrue(autoReadFalse.get(), "AutoRead should be false during execution");
    assertTrue(autoReadTrue.get(), "AutoRead should be restored to true after execution");
  }

  @Test
  public void testExecutorCloseInFinally() throws Exception {
    SQLExecutor sqlExecutor = dummySqlExecutor();
    AtomicBoolean closed = new AtomicBoolean(false);

    CommandExecutor throwingExecutor = new CommandExecutor() {
      @Override
      public Collection<DatabasePacket> execute() throws SQLException {
        throw new SQLException("expected failure", "HY000");
      }

      @Override
      public void close() throws SQLException {
        closed.set(true);
      }
    };

    MilvusCommandDispatcher dispatcher = new MilvusCommandDispatcher(sqlExecutor,
        (cmd, ctx, session, sqlExec) -> throwingExecutor);

    EmbeddedChannel channel = createChannel();
    channel.pipeline().addLast(dispatcher);
    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{0x0E}); // COM_PING
    channel.writeInbound(buffer);

    assertTrue(closed.get(), "Executor.close() should be called in finally block");
  }

  @Test
  public void testExpectedSqlExceptionHandledGracefully() throws Exception {
    SQLExecutor sqlExecutor = dummySqlExecutor();

    CommandExecutor syntaxErrorExecutor = new CommandExecutor() {
      @Override
      public Collection<DatabasePacket> execute() throws SQLException {
        throw new SQLException("Syntax error", "42000", 1064);
      }
    };

    MilvusCommandDispatcher dispatcher = new MilvusCommandDispatcher(sqlExecutor,
        (cmd, ctx, session, sqlExec) -> syntaxErrorExecutor);

    EmbeddedChannel channel = createChannel();
    channel.pipeline().addLast(dispatcher);
    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{0x0E}); // COM_PING
    channel.writeInbound(buffer);

    assertFalse(channel.outboundMessages().isEmpty(),
        "Error packet should be written for expected SQL exception");
  }

  @Test
  public void testUnexpectedRuntimeExceptionHandledGracefully() throws Exception {
    SQLExecutor sqlExecutor = dummySqlExecutor();

    CommandExecutor runtimeErrorExecutor = new CommandExecutor() {
      @Override
      public Collection<DatabasePacket> execute() throws SQLException {
        throw new RuntimeException("boom");
      }
    };

    MilvusCommandDispatcher dispatcher = new MilvusCommandDispatcher(sqlExecutor,
        (cmd, ctx, session, sqlExec) -> runtimeErrorExecutor);

    EmbeddedChannel channel = createChannel();
    channel.pipeline().addLast(dispatcher);
    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{0x0E}); // COM_PING
    channel.writeInbound(buffer);

    assertFalse(channel.outboundMessages().isEmpty(),
        "Error packet should be written for unexpected runtime exception");
  }

  @Test
  public void testInboundBufferReleasedOnParseError() {
    SQLExecutor sqlExecutor = dummySqlExecutor();
    MilvusCommandDispatcher dispatcher = new MilvusCommandDispatcher(sqlExecutor,
        (cmd, ctx, session, sqlExec) -> Collections::emptyList);

    EmbeddedChannel channel = createChannel();
    channel.pipeline().addLast(dispatcher);

    // Invalid command type (0xFF is not a valid MySQL command type)
    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{(byte) 0xFF});
    assertEquals(1, buffer.refCnt(), "Buffer should start with refCnt 1");

    channel.writeInbound(buffer);

    assertEquals(0, buffer.refCnt(), "Buffer should be released in finally block even on parse error");
  }

  @Test
  public void testSafeReleaseCompositeByteBuf() {
    SQLExecutor sqlExecutor = dummySqlExecutor();
    MilvusCommandDispatcher dispatcher = new MilvusCommandDispatcher(sqlExecutor,
        (cmd, ctx, session, sqlExec) -> Collections::emptyList);

    EmbeddedChannel channel = createChannel();
    channel.pipeline().addLast(dispatcher);

    ByteBuf inner = Unpooled.wrappedBuffer(new byte[]{(byte) 0xFF});
    CompositeByteBuf composite = channel.alloc().compositeBuffer(1);
    composite.addComponent(true, inner);

    assertEquals(1, composite.refCnt(), "Composite should start with refCnt 1");

    channel.writeInbound(composite);

    assertEquals(0, composite.refCnt(),
        "CompositeByteBuf should be released by safeRelease in finally block");
  }
}
