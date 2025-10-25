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
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.util.BuiltInMethod;

import java.util.ArrayList;
import java.util.List;

public class VectorLogicExtractor {

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

          if (cursor.getInputs() == null || cursor.getInputs().isEmpty()) {
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
    if (vectorDistanceExpr instanceof RexCall) {
      RexCall call = (RexCall) vectorDistanceExpr;
      RexNode vectorLiteral = call.operands.get(1);

      if (vectorLiteral instanceof RexCall &&
          ((RexCall) vectorLiteral).getOperator().getName().equalsIgnoreCase("ARRAY")) {
        RexCall arrayCall = (RexCall) vectorLiteral;
        List<Expression> vectorElements = new ArrayList<>();
        for (RexNode element : arrayCall.operands) {
          if (element instanceof RexLiteral) {
            Object value = ((RexLiteral) element).getValue();
            float floatValue = value instanceof Number ? ((Number) value).floatValue() :
                             Float.parseFloat(value.toString());
            vectorElements.add(Expressions.constant(floatValue, float.class));
          }
        }
        return Expressions.call(BuiltInMethod.ARRAYS_AS_LIST.method,
            Expressions.newArrayInit(Float.class, vectorElements));
      }

      if (vectorLiteral instanceof RexLiteral) {
        String value = ((RexLiteral) vectorLiteral).getValueAs(String.class);

        if (value != null) {
          value = value.trim();
        }

        if (value.startsWith("[") && value.endsWith("]")) {
          value = value.substring(1, value.length() - 1);
        }

        String[] parts = value.split(",");
        List<Expression> vectorElements = new ArrayList<>();
        for (String part : parts) {
          float floatValue = Float.parseFloat(part.trim());
          vectorElements.add(Expressions.constant(floatValue, float.class));
        }
        return Expressions.call(BuiltInMethod.ARRAYS_AS_LIST.method,
            Expressions.newArrayInit(Float.class, vectorElements));
      }
    }
    throw new RuntimeException("Invalid vector literal in distance expression");
  }

  /**
   * Extract the metric type from the distance expression.
   */
  public static String extractMetricType(RexNode vectorDistanceExpr) {
    if (vectorDistanceExpr instanceof RexCall) {
      RexCall call = (RexCall) vectorDistanceExpr;
      String opName = call.getOperator().getName().toUpperCase();

      switch (opName) {
      case "L2_DISTANCE":
        return "L2";
      case "COSINE_DISTANCE":
        return "COSINE";
      case "INNER_PRODUCT":
        return "IP";
      default:
        throw new RuntimeException("Unsupported distance metric: " + opName);
      }
    }
    throw new RuntimeException("Invalid distance expression");
  }

  public static boolean isVectorDistanceFunction(RexNode expr) {
    if (expr instanceof RexCall) {
      RexCall call = (RexCall) expr;
      SqlOperator operator = call.getOperator();
      String opName = operator.getName().toUpperCase();

      // Check if it's a supported distance function
      return opName.equals("L2_DISTANCE")
          || opName.equals("COSINE_DISTANCE")
          || opName.equals("INNER_PRODUCT");
    }
    return false;
  }

}
