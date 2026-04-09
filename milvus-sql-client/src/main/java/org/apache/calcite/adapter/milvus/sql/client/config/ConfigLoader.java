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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads configuration from YAML file.
 * Supports loading from file system or classpath.
 */
public class ConfigLoader {

  private static final String DEFAULT_CONFIG_FILE = "milvus-server.yml";

  /**
   * Loads configuration from default location.
   *
   * @return configuration object
   */
  public static MilvusServerConfig load() {
    return load(DEFAULT_CONFIG_FILE);
  }

  /**
   * Loads configuration from specified file path.
   *
   * @param configFile path to YAML configuration file
   * @return configuration object
   */
  public static MilvusServerConfig load(String configFile) {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    try {
      // 1. Try to load from file system
      File file = new File(configFile);
      if (file.exists()) {
        System.out.println("[ConfigLoader] Loading config from file: " + file.getAbsolutePath());
        return mapper.readValue(file, MilvusServerConfig.class);
      }

      // 2. Try to load from classpath
      InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(configFile);
      if (is != null) {
        System.out.println("[ConfigLoader] Loading config from classpath: " + configFile);
        return mapper.readValue(is, MilvusServerConfig.class);
      }

      // 3. Return default configuration
      System.out.println("[ConfigLoader] No config file found, using default settings");
      return new MilvusServerConfig();
    } catch (IOException e) {
      System.err.println("[ConfigLoader] Failed to load config: " + e.getMessage());
      System.out.println("[ConfigLoader] Using default settings");
      return new MilvusServerConfig();
    }
  }
}
