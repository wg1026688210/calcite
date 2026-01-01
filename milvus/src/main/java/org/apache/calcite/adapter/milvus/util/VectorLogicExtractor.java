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
package org.apache.calcite.adapter.milvus.util;

import org.apache.calcite.adapter.milvus.operation.MilvusTableScan;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.BuiltInMethod;

import com.google.common.collect.Sets;

import java.util.*;

public class VectorLogicExtractor {

  private static final String ARRAY_OPERATOR_NAME = "ARRAY";

  // Supported vector distance functions
  private static final String L2_DISTANCE = "L2_DISTANCE";
  private static final String COSINE_DISTANCE = "COSINE_DISTANCE";
  private static final String INNER_PRODUCT = "INNER_PRODUCT";

  private static final Set<String> SUPPORTED_DISTANCE_FUNCTIONS =
      Sets.newHashSet(L2_DISTANCE,
      COSINE_DISTANCE,
      INNER_PRODUCT);

  // Mapping from function name to Milvus metric type
  private static final Map<String, String> METRIC_TYPE_MAP = new HashMap<>();

  static {
    METRIC_TYPE_MAP.put(L2_DISTANCE, "L2");
    METRIC_TYPE_MAP.put(COSINE_DISTANCE, "COSINE");
    METRIC_TYPE_MAP.put(INNER_PRODUCT, "IP");
  }

  public static Expression extractVectorField(RexNode vectorDistanceExpr, RelNode input) {
    if (vectorDistanceExpr instanceof RexCall) {
      RexCall call = (RexCall) vectorDistanceExpr;
      RexNode fieldRef = call.operands.get(0);

      if (fieldRef instanceof RexInputRef) {
        int fieldIndex = ((RexInputRef) fieldRef).getIndex();
        RelNode cursor = input;
        RelNode lastNonNull = input;
        while (cursor != null) {
          lastNonNull = cursor;
          if (cursor instanceof MilvusTableScan) {
            return Expressions.constant(cursor.getRowType().getFieldNames().get(fieldIndex));
          }

          if (cursor.getInputs().isEmpty()) {
            break;
          }

          cursor = cursor.getInput(0);
        }
        throw new RuntimeException("Cannot find MilvusTableScan . Last non-null node: " + lastNonNull.getClass().getName());
      }
    }
    throw new RuntimeException("Invalid vector field reference in distance expression");
  }

  /**
   * Extract the query vector value from the distance expression and build Expression.
   */
  public static Expression extractVectorValue(RexNode vectorDistanceExpr) {
    if (!(vectorDistanceExpr instanceof RexCall)) {
      throw new RuntimeException("Vector distance expression must be a function call");
    }

    RexCall call = (RexCall) vectorDistanceExpr;
    if (call.operands.size() < 2) {
      throw new RuntimeException("Distance function requires at least 2 parameters");
    }

    RexNode vectorLiteral = call.operands.get(1);

    // Handle ARRAY() constructor: ARRAY[1.0, 2.0, 3.0]
    if (vectorLiteral instanceof RexCall) {
      RexCall arrayCall = (RexCall) vectorLiteral;
      if (ARRAY_OPERATOR_NAME.equalsIgnoreCase(arrayCall.getOperator().getName())) {
        List<Float> vectorValues = extractFloatValuesFromArrayCall(arrayCall);
        return buildVectorExpression(vectorValues);
      }
      throw new RuntimeException(
          "Unsupported vector expression type: " + arrayCall.getOperator().getName());
    }

    // Handle string literal: '[1.0, 2.0, 3.0]' or '1.0, 2.0, 3.0'
    if (vectorLiteral instanceof RexLiteral) {
      List<Float> vectorValues = extractFloatValuesFromLiteral((RexLiteral) vectorLiteral);
      return buildVectorExpression(vectorValues);
    }

    throw new RuntimeException(
        "Unsupported vector literal type: " + vectorLiteral.getClass().getSimpleName());
  }

  /**
   * Extract float values from ARRAY() RexCall.
   */
  private static List<Float> extractFloatValuesFromArrayCall(RexCall arrayCall) {
    List<Float> values = new ArrayList<>();
    for (RexNode element : arrayCall.operands) {
      if (!(element instanceof RexLiteral)) {
        throw new RuntimeException("Array elements must be literals, got: "
            + element.getClass().getSimpleName());
      }
      values.add(extractFloatFromLiteral((RexLiteral) element));
    }
    return values;
  }

  /**
   * Extract float values from string literal.
   */
  private static List<Float> extractFloatValuesFromLiteral(RexLiteral literal) {
    String value =
        Objects.requireNonNull(literal.getValueAs(String.class), "Vector string literal cannot be null");

    String trimmedValue = value.trim();

    // Handle '[1.0, 2.0, 3.0]' format
    if (trimmedValue.startsWith("[") && trimmedValue.endsWith("]")) {
      trimmedValue = trimmedValue.substring(1, trimmedValue.length() - 1);
    }

    // Handle '1.0, 2.0, 3.0' format
    String[] parts = trimmedValue.split(",");
    if (parts.length == 0) {
      throw new RuntimeException("Vector string literal is empty");
    }

    List<Float> values = new ArrayList<>();
    for (String part : parts) {
      try {
        values.add(Float.parseFloat(part.trim()));
      } catch (NumberFormatException e) {
        throw new RuntimeException("Invalid float value in vector literal: " + part.trim(), e);
      }
    }
    return values;
  }

  /**
   * Extract float from RexLiteral.
   */
  private static float extractFloatFromLiteral(RexLiteral literal) {
    Object value = literal.getValue();
    if (value instanceof Number) {
      return ((Number) value).floatValue();
    }
    if (value == null) {
      throw new RuntimeException("Cannot convert null literal to float");
    }
    try {
      return Float.parseFloat(value.toString());
    } catch (NumberFormatException e) {
      throw new RuntimeException("Cannot convert literal to float: " + value, e);
    }
  }

  /**
   * Build vector expression from float values.
   */
  private static Expression buildVectorExpression(List<Float> values) {
    if (values.isEmpty()) {
      throw new RuntimeException("Vector cannot be empty");
    }

    List<Expression> vectorElements = new ArrayList<>();
    for (Float value : values) {
      vectorElements.add(Expressions.constant(value, float.class));
    }

    return Expressions.call(BuiltInMethod.ARRAYS_AS_LIST.method,
        Expressions.newArrayInit(Float.class, vectorElements));
  }

  /**
   * Extract the metric type from the distance expression.
   */
  public static String extractMetricType(RexNode vectorDistanceExpr) {
    String opName = getFunctionName(vectorDistanceExpr);
    String metricType = METRIC_TYPE_MAP.get(opName);

    if (metricType != null) {
      return metricType;
    }

    throw new RuntimeException("Unsupported distance metric: " + opName);
  }

  /**
   * Check if the expression is a supported vector distance function.
   */
  public static boolean isVectorDistanceFunction(RexNode expr) {
    String opName = getFunctionName(expr);
    return opName != null && SUPPORTED_DISTANCE_FUNCTIONS.contains(opName);
  }

  /**
   * Extract the normalized function name from a RexNode.
   * Returns null if the expression is not a function call.
   */
  private static String getFunctionName(RexNode expr) {
    if (expr instanceof RexCall) {
      RexCall call = (RexCall) expr;
      return call.getOperator().getName().toUpperCase();
    }
    return null;
  }

}
