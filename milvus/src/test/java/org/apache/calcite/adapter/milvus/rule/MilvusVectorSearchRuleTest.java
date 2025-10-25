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
package org.apache.calcite.adapter.milvus.rule;

import org.apache.calcite.rel.RelNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Milvus vector-search pushdown rules.
 *
 * <p>These tests don't require a real Milvus instance; they only verify rule matching
 * and conversion in the planner.
 */
public class MilvusVectorSearchRuleTest {

  @Test public void convertsSortOnProjectVectorDistanceWithLimit() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, l2_distance(ARRAY[0.1E0, 0.2E0], '[1,2]') as d) "
            + "order by 2 limit 5")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertTrue(s.contains("MilvusVectorSearch"), s);
          assertTrue(MilvusVectorSearchRuleFixture.hasMilvusConvention(optimized), s);
        });
  }

  @Test public void doesNotConvertWithoutLimit() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, l2_distance(ARRAY[0.1E0, 0.2E0], '[1,2]') as d) "
            + "order by 2")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertFalse(s.contains("MilvusVectorSearch"), s);
        });
  }

  @Test public void doesNotConvertWhenOrderingByNonDistanceColumn() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, l2_distance(ARRAY[0.1E0, 0.2E0], '[1,2]') as d) "
            + "order by 1 limit 5")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertFalse(s.contains("MilvusVectorSearch"), s);
        });
  }

  @Test public void convertsWithScalarFilterPushedDown() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, l2_distance(ARRAY[0.1E0, 0.2E0], '[1,2]') as d) t "
            + "where id = 1 order by 2 limit 3")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertTrue(s.contains("MilvusVectorSearch"), s);
          // Filter should be converted.
          assertTrue(s.contains("MilvusFilter"), s);
        });
  }

  @Test public void convertsEvenIfFilterHasUdfButDoesNotPushDownFilter() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, l2_distance(ARRAY[0.1E0, 0.2E0], '[1,2]') as d) t "
            + "where CHAR_LENGTH(CAST(id AS VARCHAR)) > 0 order by 2 limit 3")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertTrue(s.contains("MilvusVectorSearch"), s);
          // UDF filter condition should prevent MilvusFilter.
          assertFalse(s.contains("MilvusFilter"), s);
        });
  }

  @Test public void convertsLogicalCalcShape() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, (l2_distance(ARRAY[0.1E0, 0.2E0], '[1,2]') + 0) as d) t "
            + "order by 2 limit 5")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertTrue(s.contains("MilvusVectorSearch"), s);
        });
  }

  @Test public void doesNotConvertWithOffset() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, l2_distance(ARRAY[0.1E0, 0.2E0], '[1,2]') as d) "
            + "order by 2 limit 5 offset 1")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertFalse(s.contains("MilvusVectorSearch"), s);
        });
  }

  @Test public void doesNotConvertWithoutVectorFunction() {
    MilvusVectorSearchRuleFixture.milvusAssert()
        .withSchema("TEST", new org.apache.calcite.schema.impl.AbstractSchema())
        .query("select id, d from (select 1 as id, 2 as d) order by 2 limit 5")
        .convertMatches(rel -> {
          RelNode optimized = MilvusVectorSearchRuleFixture.optimize(rel);
          String s = MilvusVectorSearchRuleFixture.plan(optimized);
          assertFalse(s.contains("MilvusVectorSearch"), s);
        });
  }
}
