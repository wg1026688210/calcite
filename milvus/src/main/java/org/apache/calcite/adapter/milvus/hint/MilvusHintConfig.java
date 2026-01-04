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
package org.apache.calcite.adapter.milvus.hint;

import org.apache.calcite.rel.hint.HintPredicates;
import org.apache.calcite.rel.hint.HintStrategyTable;

/**
 * Utility class for configuring Milvus SQL hints.
 */
public class MilvusHintConfig {

  /** Hint name for general Milvus options. */
  public static final String MILVUS_OPTIONS = "MILVUS_OPTIONS";

  /**
   * Creates a HintStrategyTable for Milvus SQL hints.
   */
  public static HintStrategyTable createHintStrategyTable() {
    return HintStrategyTable.builder()
        .hintStrategy(MILVUS_OPTIONS, HintPredicates.TABLE_SCAN)
        .build();
  }
}
