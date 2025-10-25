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
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rex.RexNode;

/**
 * Rule to match and convert vector search patterns:
 * Sort + Project + Scan
 * with conditions:
 * 1. Sort has LIMIT (fetch != null)
 * 2. Project contains vector distance function
 * 3. Sort field references the vector function in Project
 */
@SuppressWarnings("deprecation")
public class MilvusVectorSearchRule extends RelOptRule {

  /**
   * Singleton instance of the rule.
   */
  public static final MilvusVectorSearchRule INSTANCE = new MilvusVectorSearchRule();

  /**
   * Creates a MilvusVectorSearchRule.
   */
  public MilvusVectorSearchRule() {
    super(
        operand(LogicalSort.class,
            operand(LogicalProject.class, any())),
        "MilvusVectorSearchRule");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final LogicalSort sort = call.rel(0);
    final LogicalProject project = call.rel(1);
    final RelNode input = project.getInput();

    if (sort.fetch == null || sort.offset != null) {
      return;
    }

    if (!hasVectorFunction(project)) {
      return;
    }

    if (!validateSortLogic(sort, project)) {
      return;
    }

    final RelNode milvusInput =
        convert(input, input.getTraitSet().replace(MilvusRel.CONVENTION));

    RelNode milvusRel = MilvusRel.findMilvusRel(milvusInput);

    if (!isValidInput(milvusRel)) {
      return;
    }

    call.transformTo(
        new MilvusVectorSearch(
            sort.getCluster(),
            sort.getTraitSet().replace(MilvusRel.CONVENTION),
            milvusInput,
            project.getProjects(),
            project.getRowType(),
            sort.getCollation(),
            sort.fetch));
  }

  /**
   * Check if the project contains vector distance functions.
   */
  private static boolean hasVectorFunction(Project project) {
    for (RexNode expr : project.getProjects()) {
      if (VectorLogicExtractor.isVectorDistanceFunction(expr)) {
        return true;
      }
    }
    return false;
  }


  /**
   * Validate that the Sort field references the vector function in Project.
   */
  private static boolean validateSortLogic(Sort sort, Project project) {
    // 现在只支持单字段检索 todo 后续可以考虑检索支持与多个向量相似的检索（需要考虑传入权重的语法）
    if (sort.getCollation().getFieldCollations().size() != 1) {
      return false;
    }

    RelFieldCollation fieldCollation = sort.getCollation().getFieldCollations().get(0);
    int sortFieldIndex = fieldCollation.getFieldIndex();

    if (sortFieldIndex >= project.getProjects().size()) {
      return false;
    }
    RexNode projectExpr = project.getProjects().get(sortFieldIndex);

    if (VectorLogicExtractor.isVectorDistanceFunction(projectExpr)) {
      RelFieldCollation.Direction direction = fieldCollation.getDirection();

      String metricType = VectorLogicExtractor.extractMetricType(projectExpr);

      //目前向量检索只支持相似性检索，需要检验下排序顺序，L2 得分越小越相似，IP和COSINE得分越大越相似
      switch (metricType) {
      case "L2":
        return direction == RelFieldCollation.Direction.ASCENDING;
      case "IP":
      case "COSINE":
        return direction == RelFieldCollation.Direction.DESCENDING;
      default:
        return false;
      }
    }
    return false;
  }


  private boolean isValidInput(RelNode input) {
    return input instanceof MilvusTableScan || input instanceof MilvusFilter;
  }
}
