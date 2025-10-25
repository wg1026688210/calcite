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

import org.apache.calcite.adapter.milvus.convention.MilvusRel;
import org.apache.calcite.adapter.milvus.operation.MilvusVectorSearchRule;
import org.apache.calcite.adapter.milvus.udf.MilvusVectorUdfs;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.test.CalciteAssert;

/** Helper utilities for Milvus rule unit tests. */
final class MilvusVectorSearchRuleFixture {
  private MilvusVectorSearchRuleFixture() {
  }

  static RelNode optimize(RelNode input) {
    HepProgram program = new HepProgramBuilder()
        .addRuleInstance(MilvusVectorSearchRule.INSTANCE)
        .build();

    HepPlanner planner = new HepPlanner(program);
    planner.setRoot(input);
    return planner.findBestExp();
  }

  static String plan(RelNode node) {
    return RelOptUtil.toString(node, SqlExplainLevel.ALL_ATTRIBUTES);
  }

  static boolean hasMilvusConvention(RelNode node) {
    return node.getTraitSet().contains(MilvusRel.CONVENTION);
  }

  /**
   * Returns a CalciteAssert fixture configured so queries using Milvus vector functions
   * (e.g. l2_distance) validate.
   *
   * <p>We register the scalar UDFs using Calcite JDBC connection property "fun".
   */
  static CalciteAssert.AssertThat milvusAssert() {
    return CalciteAssert.that()
        .with("fun", MilvusVectorUdfs.class.getName());
  }
}
