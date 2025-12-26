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
package org.apache.calcite.adapter.milvus.convention;

import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.adapter.milvus.factory.MilvusTranslatableTable;
import org.apache.calcite.adapter.milvus.operation.MilvusFilterTranslator;
import org.apache.calcite.adapter.milvus.operation.MilvusProjectExpression;
import org.apache.calcite.adapter.milvus.util.VectorLogicExtractor;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterImpl;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;


/**
 * MilvusToEnumerableConverter converts a relational expression
 * from Milvus calling convention to Enumerable calling convention.
 */
public class MilvusToEnumerableConverter
    extends ConverterImpl
    implements EnumerableRel {
  protected MilvusToEnumerableConverter(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input) {
    super(cluster, ConventionTraitDef.INSTANCE, traits, input);
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new MilvusToEnumerableConverter(
        getCluster(), traitSet, sole(inputs));
  }

  @Override public @Nullable RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    return super.computeSelfCost(planner, mq).multiplyBy(.1);
  }

  @Override public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    final BlockBuilder list = new BlockBuilder();
    final MilvusRel.Implementor milvusImplementor =
        new MilvusRel.Implementor(getCluster().getRexBuilder());
    milvusImplementor.visitChild(0, getInput());

    final Expression root = implementor.getRootExpression();
    final Expression schema =
        Expressions.call(root, BuiltInMethod.DATA_CONTEXT_GET_ROOT_SCHEMA.method);

    //scan
    final List<String> qualifiedTableName = milvusImplementor.table.getQualifiedName();
    final Expression table = getScanInfo(qualifiedTableName, schema);
    final Expression tableExpr =
        Expressions.convert_(table, MilvusTranslatableTable.class);

    //project
    final RelDataType rowType = milvusImplementor.projectRowType != null
        ? milvusImplementor.projectRowType
        : getRowType();

    final PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(), rowType,
            pref.prefer(JavaRowFormat.ARRAY));

    final List<RexNode> projects = milvusImplementor.projects;
    List<Pair<Integer, MilvusProjectExpression>>
        projectInfo = getProjectInfo(projects, rowType, physType);
    final Expression projectInfoExpr =
        list.append("projectRowTypeMapForEnumerator", expressionForProjectPairs(projectInfo));

    //filter
    Expression filterExpr = Expressions.constant("");
    if (milvusImplementor.filterCondition != null) {
      String filterExpression =
          MilvusFilterTranslator.translate(milvusImplementor.filterCondition,
              milvusImplementor.rowType.getFieldNames());
      filterExpr = Expressions.constant(filterExpression);
    }

    Expression enumerable;
    if (milvusImplementor.vectorDistanceExpr == null) {
      enumerable =
          list.append(
              "enumerable", Expressions.call(tableExpr,
                  "scan",
                  filterExpr,
                  projectInfoExpr));
    } else {
      // with vector search
      Expression vectorField = VectorLogicExtractor.extractVectorField(milvusImplementor.vectorDistanceExpr, getInput());
      Expression vectorValueExpr = VectorLogicExtractor.extractVectorValue(milvusImplementor.vectorDistanceExpr);
      Expression metricType = Expressions.constant(VectorLogicExtractor.extractMetricType(milvusImplementor.vectorDistanceExpr));

      enumerable =
          list.append(
              "enumerable", Expressions.call(tableExpr,
                  "vectorSearch",
                  vectorField,
                  vectorValueExpr,
                  metricType,
                  Expressions.box(Expressions.constant(getTopK(milvusImplementor.limit)), Primitive.LONG),
                  filterExpr,
                  projectInfoExpr));
    }

    list.add(Expressions.return_(null, enumerable));
    return implementor.result(physType, list.toBlock());
  }

  private static Expression getScanInfo(List<String> qualifiedName,
      Expression schema) {
    final String schemaName = qualifiedName.size() > 1 ? qualifiedName.get(0) : null;
    final String tableName = qualifiedName.get(qualifiedName.size() - 1);

    Expression current = schema;

    if (schemaName != null) {
      current =
          Expressions.call(current, BuiltInMethod.SCHEMA_GET_SUB_SCHEMA.method,
              Expressions.constant(schemaName));
      current = Expressions.convert_(current, Schema.class);
    }

    return Expressions.call(current,
        BuiltInMethod.SCHEMA_GET_TABLE.method,
        Expressions.constant(tableName));
  }

  private static List<Pair<Integer, MilvusProjectExpression>> getProjectInfo(List<RexNode> projects,
      RelDataType rowType, PhysType physType) {
    List<Pair<Integer, MilvusProjectExpression>> projectInfo = new ArrayList<>();
    if (projects != null) {
      // 如果有project ,则构建project的填充逻辑,支持表字段，常量和向量函数
      List<RelDataTypeField> rowTypeFields = rowType.getFieldList();

      for (int i = 0; i < projects.size(); i++) {
        RexNode project = projects.get(i);
        RelDataTypeField field = rowTypeFields.get(i);
        Class<?> fieldClass = physType.fieldClass(i);
        MilvusProjectExpression expr;

        if (project instanceof RexInputRef) {
          expr = new MilvusProjectExpression.InputField(field.getName(), fieldClass);
        } else if (project instanceof RexCall) {
          //todo 这里只能传向量函数
          expr = new MilvusProjectExpression.VectorScore(fieldClass);
        } else if (project instanceof RexLiteral) {
          RexLiteral literal = (RexLiteral) project;
          // Get value with target class and convert if needed
          Object value = getLiteralValueWithConversion(literal, fieldClass);
          expr = new MilvusProjectExpression.Constant(fieldClass, value);
        } else {
          throw new UnsupportedOperationException("Unsupported project type");
        }
        projectInfo.add(Pair.of(i, expr));
      }
    } else {
      //如果没有project，则按原始字段顺序返回
      List<String> inputFields = rowType.getFieldNames();
      for (int i = 0; i < inputFields.size(); i++) {
        String fieldName = inputFields.get(i);
        Class<?> fieldClass = physType.fieldClass(i);
        projectInfo.add(
            Pair.of(i,
                new MilvusProjectExpression.InputField(fieldName, fieldClass)));
      }
    }
    return projectInfo;
  }

  /**
   * Extract Top-K value from LIMIT expression.
   * Default to 5 if not specified.
   */
  private Long getTopK(RexNode limit) {
    Long value = null;
    if (limit instanceof RexLiteral) {
      value = ((RexLiteral) limit).getValueAs(Long.class);
    }
    return value;
  }

  private static Object getLiteralValueWithConversion(RexLiteral literal, Class<?> fieldClass) {
    try {
      // Try Calcite's native conversion first
      Object value = literal.getValueAs(fieldClass);
      if (value != null && !fieldClass.isInstance(value)) {
        return convertValueToTargetType(value, fieldClass);
      }
      return value;
    } catch (Throwable e) {
      Object rawValue = literal.getValue3();
      return convertValueToTargetType(rawValue, fieldClass);
    }
  }

  private static Object convertValueToTargetType(Object value, Class<?> targetClass) {
    if (value == null) {
      return null;
    }

    if (targetClass.isInstance(value)) {
      return value;
    }

    if (value instanceof java.math.BigDecimal) {
      java.math.BigDecimal bd = (java.math.BigDecimal) value;
      if (targetClass == int.class || targetClass == Integer.class) {
        return bd.intValue();
      } else if (targetClass == long.class || targetClass == Long.class) {
        return bd.longValue();
      } else if (targetClass == float.class || targetClass == Float.class) {
        return bd.floatValue();
      } else if (targetClass == double.class || targetClass == Double.class) {
        return bd.doubleValue();
      } else if (targetClass == short.class || targetClass == Short.class) {
        return bd.shortValue();
      } else if (targetClass == byte.class || targetClass == Byte.class) {
        return bd.byteValue();
      }
      return value;
    }

    if (value instanceof org.apache.calcite.util.NlsString && targetClass == String.class) {
      return ((org.apache.calcite.util.NlsString) value).getValue();
    }

    return value;
  }

  private Expression expressionForProjectExpression(MilvusProjectExpression expr) {
    if (expr instanceof MilvusProjectExpression.InputField) {
      String fieldName = ((MilvusProjectExpression.InputField) expr).getFieldName();
      return Expressions.new_(MilvusProjectExpression.InputField.class,
          Expressions.constant(fieldName),
          Expressions.constant(expr.getClazz(), Class.class));
    } else if (expr instanceof MilvusProjectExpression.Constant) {
      Object value = ((MilvusProjectExpression.Constant) expr).getValue();
      return Expressions.new_(MilvusProjectExpression.Constant.class,
          Expressions.constant(expr.getClazz(), Class.class),
          Expressions.constant(value));
    } else if (expr instanceof MilvusProjectExpression.VectorScore) {
      return Expressions.new_(MilvusProjectExpression.VectorScore.class,
          Expressions.constant(expr.getClazz(), Class.class));
    } else {
      throw new AssertionError("Unknown expression type: " + expr);
    }
  }

  private Expression expressionForProjectPairs(List<Pair<Integer, MilvusProjectExpression>> pairs) {
    List<Expression> pairExpressions = new ArrayList<>();

    for (Pair<Integer, MilvusProjectExpression> pair : pairs) {
      Expression first = Expressions.constant(pair.left, Integer.class);
      Expression second = expressionForProjectExpression(pair.right);
      Type pairType = Types.of(Pair.class, Integer.class, MilvusProjectExpression.class);
      Expression pairExpr = Expressions.new_(pairType, first, second);
      pairExpressions.add(pairExpr);
    }
    return Expressions.call(BuiltInMethod.ARRAYS_AS_LIST.method,
        Expressions.newArrayInit(Pair.class, pairExpressions));
  }

}
