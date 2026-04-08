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
package org.apache.calcite.adapter.milvus.sql.client;

import org.apache.calcite.adapter.milvus.MilvusBaseE2ETest;
import org.apache.calcite.adapter.milvus.extension.MilvusExtension;
import org.apache.calcite.adapter.milvus.sql.client.config.MilvusServerConfig;
import org.apache.calcite.adapter.milvus.sql.client.server.MilvusMySQLServer;
import org.apache.calcite.adapter.milvus.util.TestEnvUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Test with raw socket to debug protocol issues.
 */
@ExtendWith(MilvusExtension.class)
public class RawSocketTest extends MilvusBaseE2ETest {
  private static final int MYSQL_PORT = 13308;
  private static MilvusMySQLServer server;

  @BeforeAll
  static void setupServer() throws Exception {
    Map<String, Object> params = MilvusExtension.getConnectionParams();
    MilvusServerConfig config = new MilvusServerConfig();
    config.setPort(MYSQL_PORT);
    config.setHost("127.0.0.1");
    config.setMilvusHost((String) params.get("host"));
    config.setMilvusPort((Integer) params.get("port"));
    config.setMilvusDatabase("default");

    server = new MilvusMySQLServer(config);
    server.start();

    Thread.sleep(1000);
  }

  @Test
  @Disabled("Debug test - may timeout due to socket read")
  public void testRawSocket() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", MYSQL_PORT)) {
      socket.setSoTimeout(10000);
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();

      // Read handshake packet
      byte[] header = new byte[4];
      int read = in.read(header);
      System.err.println("[RAW] Handshake header read: " + read);
      if (read == 4) {
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        int seq = header[3] & 0xFF;
        System.err.println("[RAW] Handshake packet length: " + length + ", seq: " + seq);

        byte[] payload = new byte[length];
        read = in.read(payload);
        System.err.println("[RAW] Handshake payload read: " + read);
      }

      // Send handshake response (minimal)
      // capability flags: 0x0001a685 (CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | etc)
      byte[] response = buildHandshakeResponse();
      out.write(response);
      out.flush();
      System.err.println("[RAW] Sent handshake response: " + response.length + " bytes");

      // Read OK packet
      read = in.read(header);
      System.err.println("[RAW] OK packet header read: " + read);
      if (read == 4) {
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        int seq = header[3] & 0xFF;
        System.err.println("[RAW] OK packet length: " + length + ", seq: " + seq);

        byte[] payload = new byte[length];
        read = in.read(payload);
        System.err.println("[RAW] OK payload read: " + read + ", first byte: " + (payload.length > 0 ? (payload[0] & 0xFF) : "N/A"));
      }

      // Send COM_QUERY for "SHOW VARIABLES" (what JDBC driver sends)
      byte[] query = buildComQuery("SHOW VARIABLES WHERE Variable_name ='character_set_client'");
      out.write(query);
      out.flush();
      System.err.println("[RAW] Sent COM_QUERY for SHOW VARIABLES");

      // Read result set
      // 1. Field count
      read = in.read(header);
      System.err.println("[RAW] Field count header read: " + read);
      if (read == 4) {
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        int seq = header[3] & 0xFF;
        System.err.println("[RAW] Field count packet length: " + length + ", seq: " + seq);

        byte[] payload = new byte[length];
        read = in.read(payload);
        System.err.println("[RAW] Field count payload read: " + read + ", field count: " + (payload.length > 0 ? (payload[0] & 0xFF) : "N/A"));
      }

      // 2. Column definition (first column)
      read = in.read(header);
      System.err.println("[RAW] Column def 1 header read: " + read);
      if (read == 4) {
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        int seq = header[3] & 0xFF;
        System.err.println("[RAW] Column def 1 packet length: " + length + ", seq: " + seq);

        byte[] payload = new byte[length];
        read = in.read(payload);
        System.err.println("[RAW] Column def 1 payload read: " + read);

        // Dump hex
        StringBuilder hex = new StringBuilder();
        for (byte b : payload) {
          hex.append(String.format("%02x ", b & 0xFF));
        }
        System.err.println("[RAW] Column def 1 hex: " + hex.toString());

        // Try to parse the column definition
        parseColumnDefinition(payload);
      }

      // 3. Column definition (second column) or EOF
      read = in.read(header);
      System.err.println("[RAW] Column def 2 / EOF header read: " + read);
      if (read == 4) {
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        int seq = header[3] & 0xFF;
        System.err.println("[RAW] Column def 2 / EOF packet length: " + length + ", seq: " + seq);

        byte[] payload = new byte[length];
        read = in.read(payload);
        System.err.println("[RAW] Column def 2 / EOF payload read: " + read + ", first byte: " + (payload.length > 0 ? String.format("0x%02x", payload[0] & 0xFF) : "N/A"));

        // Check if it's EOF (0xfe) or column def
        if (payload.length > 0 && (payload[0] & 0xFF) == 0xfe) {
          System.err.println("[RAW] -> This is EOF packet");
        } else {
          System.err.println("[RAW] -> This is column definition (NOT EOF!)");
          parseColumnDefinition(payload);
        }
      }

      // 4. Row data
      read = in.read(header);
      System.err.println("[RAW] Row data header read: " + read);
      if (read == 4) {
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        System.err.println("[RAW] Row data packet length: " + length);

        byte[] payload = new byte[length];
        read = in.read(payload);
        System.err.println("[RAW] Row data payload read: " + read);
      }

      // 5. Final OK/EOF
      read = in.read(header);
      System.err.println("[RAW] Final OK header read: " + read);
      if (read == 4) {
        int length = (header[0] & 0xFF) | ((header[1] & 0xFF) << 8) | ((header[2] & 0xFF) << 16);
        System.err.println("[RAW] Final OK packet length: " + length);

        byte[] payload = new byte[length];
        read = in.read(payload);
        System.err.println("[RAW] Final OK payload read: " + read + ", first byte: " + (payload.length > 0 ? String.format("0x%02x", payload[0] & 0xFF) : "N/A"));
      }

      System.err.println("[RAW] Test completed successfully!");
    }
  }

  private byte[] buildHandshakeResponse() {
    // Minimal handshake response for MySQL 4.1+
    // Format: 4 bytes capability, 4 bytes max packet size, 1 byte charset, 23 bytes reserved, username\0

    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

    // Payload
    // Capability flags (4 bytes): CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH
    int cap = 0x0001a685;  // Basic capabilities
    baos.write(cap & 0xFF);
    baos.write((cap >> 8) & 0xFF);
    baos.write((cap >> 16) & 0xFF);
    baos.write((cap >> 24) & 0xFF);

    // Max packet size (4 bytes)
    baos.write(0x00);
    baos.write(0x00);
    baos.write(0x00);
    baos.write(0x01);

    // Charset (1 byte) - UTF8 = 33
    baos.write(33);

    // Reserved (23 bytes)
    for (int i = 0; i < 23; i++) {
      baos.write(0x00);
    }

    // Username
    byte[] usernameBytes = "root".getBytes(StandardCharsets.UTF_8);
    for (byte b : usernameBytes) {
      baos.write(b);
    }
    baos.write(0x00);  // null terminator

    // Auth response (empty for now)
    baos.write(0x00);  // length 0

    byte[] payload = baos.toByteArray();

    // Build packet with header
    byte[] packet = new byte[4 + payload.length];
    packet[0] = (byte) (payload.length & 0xFF);
    packet[1] = (byte) ((payload.length >> 8) & 0xFF);
    packet[2] = (byte) ((payload.length >> 16) & 0xFF);
    packet[3] = 1;  // sequence ID

    System.arraycopy(payload, 0, packet, 4, payload.length);

    return packet;
  }

  private byte[] buildComQuery(String sql) {
    byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
    byte[] packet = new byte[4 + 1 + sqlBytes.length];

    int payloadLen = 1 + sqlBytes.length;
    packet[0] = (byte) (payloadLen & 0xFF);
    packet[1] = (byte) ((payloadLen >> 8) & 0xFF);
    packet[2] = (byte) ((payloadLen >> 16) & 0xFF);
    packet[3] = 0;  // sequence ID (reset after auth)

    packet[4] = 0x03;  // COM_QUERY command
    System.arraycopy(sqlBytes, 0, packet, 5, sqlBytes.length);

    return packet;
  }

  @AfterAll
  static void tearDownServer() {
    if (server != null) {
      server.stop();
    }
  }

  private void parseColumnDefinition(byte[] payload) {
    int pos = 0;
    System.err.println("[RAW] Parsing column definition:");

    // Catalog (length-encoded string)
    int catalogLen = payload[pos++] & 0xFF;
    if (catalogLen <= 250) {
      String catalog = new String(payload, pos, catalogLen, StandardCharsets.UTF_8);
      System.err.println("[RAW]   Catalog: " + catalog);
      pos += catalogLen;
    } else if (catalogLen == 0xFB) {
      System.err.println("[RAW]   Catalog: NULL");
    }

    // Schema (length-encoded string)
    int schemaLen = payload[pos++] & 0xFF;
    if (schemaLen <= 250) {
      String schema = new String(payload, pos, schemaLen, StandardCharsets.UTF_8);
      System.err.println("[RAW]   Schema: " + schema);
      pos += schemaLen;
    }

    // Table (length-encoded string)
    int tableLen = payload[pos++] & 0xFF;
    if (tableLen <= 250) {
      String table = new String(payload, pos, tableLen, StandardCharsets.UTF_8);
      System.err.println("[RAW]   Table: " + table);
      pos += tableLen;
    }

    // OrgTable (length-encoded string)
    int orgTableLen = payload[pos++] & 0xFF;
    if (orgTableLen <= 250) {
      String orgTable = new String(payload, pos, orgTableLen, StandardCharsets.UTF_8);
      System.err.println("[RAW]   OrgTable: " + orgTable);
      pos += orgTableLen;
    }

    // Name (length-encoded string)
    int nameLen = payload[pos++] & 0xFF;
    if (nameLen <= 250) {
      String name = new String(payload, pos, nameLen, StandardCharsets.UTF_8);
      System.err.println("[RAW]   Name: " + name);
      pos += nameLen;
    }

    // OrgName (length-encoded string)
    int orgNameLen = payload[pos++] & 0xFF;
    if (orgNameLen <= 250) {
      String orgName = new String(payload, pos, orgNameLen, StandardCharsets.UTF_8);
      System.err.println("[RAW]   OrgName: " + orgName);
      pos += orgNameLen;
    }

    // Next length (should be 0x0c)
    if (pos < payload.length) {
      int nextLen = payload[pos++] & 0xFF;
      System.err.println("[RAW]   Next length: " + nextLen);
    }

    // Character set (2 bytes)
    if (pos + 2 <= payload.length) {
      int charset = (payload[pos] & 0xFF) | ((payload[pos + 1] & 0xFF) << 8);
      pos += 2;
      System.err.println("[RAW]   Character set: " + charset);
    }

    // Column length (4 bytes)
    if (pos + 4 <= payload.length) {
      int colLen = (payload[pos] & 0xFF) | ((payload[pos + 1] & 0xFF) << 8) |
          ((payload[pos + 2] & 0xFF) << 16) | ((payload[pos + 3] & 0xFF) << 24);
      pos += 4;
      System.err.println("[RAW]   Column length: " + colLen);
    }

    // Type (1 byte)
    if (pos < payload.length) {
      int type = payload[pos++] & 0xFF;
      System.err.println("[RAW]   Type: " + type);
    }

    // Flags (2 bytes)
    if (pos + 2 <= payload.length) {
      int flags = (payload[pos] & 0xFF) | ((payload[pos + 1] & 0xFF) << 8);
      pos += 2;
      System.err.println("[RAW]   Flags: " + flags);
    }

    // Decimals (1 byte)
    if (pos < payload.length) {
      int decimals = payload[pos++] & 0xFF;
      System.err.println("[RAW]   Decimals: " + decimals);
    }

    System.err.println("[RAW]   Final position: " + pos + " / " + payload.length);
  }
}
