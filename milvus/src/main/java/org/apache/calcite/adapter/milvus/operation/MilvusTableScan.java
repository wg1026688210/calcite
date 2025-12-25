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
package org.apache.calcite.adapter.milvus.operation;

import org.apache.calcite.adapter.milvus.convention.MilvusRel;
import org.apache.calcite.adapter.milvus.convention.MilvusToEnumerableConverterRule;
import org.apache.calcite.adapter.milvus.factory.MilvusTranslatableTable;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/**
 * Relational expression representing a scan of a Milvus collection.
 *
 * <p> Additional operations might be applied,
 * using query methods.
 */
public class MilvusTableScan extends TableScan implements MilvusRel {
  final MilvusTranslatableTable milvusTable;

  /**
   * Creates a MilvusTableScan.
   *
   * @param cluster        Cluster
   * @param traitSet       Traits
   * @param table          Table
   * @param milvusTable    Milvus table
   */
  public MilvusTableScan(RelOptCluster cluster, RelTraitSet traitSet,
      RelOptTable table, MilvusTranslatableTable milvusTable) {
    super(cluster, traitSet, ImmutableList.of(), table);
    this.milvusTable = requireNonNull(milvusTable, "milvusTable");
    checkArgument(getConvention() == MilvusRel.CONVENTION);
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    assert inputs.isEmpty();
    return this;
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    final RelOptCost cost = requireNonNull(super.computeSelfCost(planner, mq));
    final int fieldCount = getRowType().getFieldCount();
    return cost.multiplyBy(0.01 * fieldCount);
  }

  @Override public void register(RelOptPlanner planner) {
    planner.addRule(MilvusFilterRule.DEFAULT_CONFIG.toRule());
    planner.addRule(MilvusProjectRule.DEFAULT_CONFIG.toRule());
    planner.addRule(MilvusToEnumerableConverterRule.INSTANCE);
    planner.addRule(MilvusVectorSearchRule.INSTANCE);
  }

  @Override public void implement(Implementor implementor) {
    implementor.table = table;
    implementor.milvusTable = milvusTable;
    implementor.rowType = getRowType();
  }

  /**
   * Gets the field names from the scan's row type.
   * This is used by MilvusFilter for translating filter expressions.
   *
   * @return list of field names
   */
  public List<String> getFieldNames() {
    return getRowType().getFieldNames();
  }

}
