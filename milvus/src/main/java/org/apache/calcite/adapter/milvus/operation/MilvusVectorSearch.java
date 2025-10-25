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
import org.apache.calcite.adapter.milvus.util.VectorLogicExtractor;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Relational operator that performs vector similarity search in Milvus.
 * This is a fused operator that combines Sort + Project + Scan for vector search scenarios.
 */
public class MilvusVectorSearch extends AbstractRelNode implements MilvusRel {

  private RelNode input;
  private final List<RexNode> projects;
  private final RelDataType rowType;
  private final RelCollation collation;
  private final RexNode fetch;

  public MilvusVectorSearch(RelOptCluster cluster, RelTraitSet traitSet,
      RelNode input, List<RexNode> projects, RelDataType rowType,
      RelCollation collation,
      RexNode fetch) {
    super(cluster, traitSet);
    this.input = input;
    this.projects = ImmutableList.copyOf(projects);
    this.rowType = rowType;
    this.collation = collation;
    this.fetch = fetch;

  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new MilvusVectorSearch(
        getCluster(),
        traitSet,
        inputs.get(0),
        projects,
        rowType,
        collation,
        fetch);
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    return planner.getCostFactory().makeCost(1, 1, 0);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .input("input", input)
        .item("fetch", fetch)
        .item("collation", collation);
  }

  @Override public void childrenAccept(RelVisitor visitor) {
    visitor.visit(input, 0, this);
  }

  @Override public RelDataType deriveRowType() {
    return rowType;
  }

  @Override public void implement(Implementor implementor) {
    implementor.visitChild(0, input);

    implementor.projectRowType = rowType;
    implementor.projects = projects;

    // Propagate LIMIT (topK) so MilvusToEnumerableConverter can extract it.
    implementor.limit = fetch;

    // Extract the vector-distance expression from the ORDER BY key.
    if (collation == null
        || collation.getFieldCollations() == null
        || collation.getFieldCollations().isEmpty()) {
      return;
    }

    // Currently only supports ordering by the first collation field.
    final RelFieldCollation relFieldCollation = collation.getFieldCollations().get(0);
    final int fieldIndex = relFieldCollation.getFieldIndex();
    if (fieldIndex < 0 || fieldIndex >= projects.size()) {
      return;
    }

    final RexNode rexNode = projects.get(fieldIndex);
    if (VectorLogicExtractor.isVectorDistanceFunction(rexNode)) {
      implementor.vectorDistanceExpr = rexNode;
      implementor.vectorDistanceFieldIndex = fieldIndex;
      implementor.sortOrder = relFieldCollation.getDirection();
    }
  }

  @Override public List<RelNode> getInputs() {
    return java.util.Collections.singletonList(input);
  }

  @Override public RelNode getInput(int i) {
    if (i != 0) {
      throw new IndexOutOfBoundsException("MilvusVectorSearch has a single input; requested " + i);
    }
    return input;
  }

  @Override public void replaceInput(int ordinalInParent, RelNode p) {
    if (ordinalInParent != 0) {
      throw new IndexOutOfBoundsException(
          "MilvusVectorSearch has a single input; requested " + ordinalInParent);
    }
    this.input = p;
  }

}
