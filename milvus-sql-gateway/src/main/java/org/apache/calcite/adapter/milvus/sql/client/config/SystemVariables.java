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
package org.apache.calcite.adapter.milvus.sql.client.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized MySQL system variable definitions for JDBC compatibility.
 * All mock system variable values are defined here to ensure consistency.
 */
public final class SystemVariables {

  private SystemVariables() {
    // utility class
  }

  // Connection and charset variables
  public static final String AUTO_INCREMENT_INCREMENT = "1";
  public static final String CHARACTER_SET_CLIENT = "utf8mb4";
  public static final String CHARACTER_SET_CONNECTION = "utf8mb4";
  public static final String CHARACTER_SET_RESULTS = "utf8mb4";
  public static final String CHARACTER_SET_SERVER = "utf8mb4";
  public static final String COLLATION_SERVER = "utf8mb4_general_ci";
  public static final String COLLATION_CONNECTION = "utf8mb4_general_ci";
  public static final String INIT_CONNECT = "";
  public static final String INTERACTIVE_TIMEOUT = "28800";
  public static final String WAIT_TIMEOUT = "28800";

  // System info variables
  public static final String LICENSE = "GPL";
  public static final String LOWER_CASE_TABLE_NAMES = "0";
  public static final String MAX_ALLOWED_PACKET = "4194304";
  public static final String NET_WRITE_TIMEOUT = "60";
  public static final String NET_BUFFER_LENGTH = "16384";
  public static final String PERFORMANCE_SCHEMA = "OFF";
  public static final String VERSION_COMMENT = "MySQL Community Server - GPL";

  // Cache and mode variables
  public static final String QUERY_CACHE_SIZE = "0";
  public static final String QUERY_CACHE_TYPE = "OFF";
  public static final String SQL_MODE = "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES";

  // Timezone variables
  public static final String SYSTEM_TIME_ZONE = "UTC";
  public static final String TIME_ZONE = "SYSTEM";
  public static final String TIMEZONE = "SYSTEM";

  // Transaction variables
  public static final String TRANSACTION_ISOLATION = "REPEATABLE-READ";
  public static final String TX_ISOLATION = "REPEATABLE-READ";

  // Character set aliases
  public static final String CHARACTER_SET = "utf8mb4";

  /**
   * Immutable map of all mock system variables.
   */
  public static final Map<String, String> VARIABLE_VALUES;

  static {
    Map<String, String> map = new HashMap<>();
    map.put("auto_increment_increment", AUTO_INCREMENT_INCREMENT);
    map.put("character_set_client", CHARACTER_SET_CLIENT);
    map.put("character_set_connection", CHARACTER_SET_CONNECTION);
    map.put("character_set_results", CHARACTER_SET_RESULTS);
    map.put("character_set_server", CHARACTER_SET_SERVER);
    map.put("collation_server", COLLATION_SERVER);
    map.put("collation_connection", COLLATION_CONNECTION);
    map.put("init_connect", INIT_CONNECT);
    map.put("interactive_timeout", INTERACTIVE_TIMEOUT);
    map.put("wait_timeout", WAIT_TIMEOUT);
    map.put("license", LICENSE);
    map.put("lower_case_table_names", LOWER_CASE_TABLE_NAMES);
    map.put("max_allowed_packet", MAX_ALLOWED_PACKET);
    map.put("net_write_timeout", NET_WRITE_TIMEOUT);
    map.put("net_buffer_length", NET_BUFFER_LENGTH);
    map.put("performance_schema", PERFORMANCE_SCHEMA);
    map.put("version_comment", VERSION_COMMENT);
    map.put("query_cache_size", QUERY_CACHE_SIZE);
    map.put("query_cache_type", QUERY_CACHE_TYPE);
    map.put("sql_mode", SQL_MODE);
    map.put("system_time_zone", SYSTEM_TIME_ZONE);
    map.put("time_zone", TIME_ZONE);
    map.put("timezone", TIMEZONE);
    map.put("transaction_isolation", TRANSACTION_ISOLATION);
    map.put("tx_isolation", TX_ISOLATION);
    map.put("character_set", CHARACTER_SET);
    VARIABLE_VALUES = Collections.unmodifiableMap(map);
  }
}
