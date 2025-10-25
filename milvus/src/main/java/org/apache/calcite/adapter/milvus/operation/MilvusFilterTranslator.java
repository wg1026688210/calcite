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

import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;

import java.util.List;

/**
 * Translates Calcite RexNode expressions to Milvus query expressions.
 */
public class MilvusFilterTranslator {

  /**
   * Checks if the given RexNode contains any UDF calls.
   *
   * @param node The RexNode to check
   * @return true if the node contains UDF calls, false otherwise
   */
  public static boolean containsUdfCall(RexNode node) {
    final boolean[] hasUdf = {false};

    node.accept(new RexVisitorImpl<Void>(true) {
      @Override public Void visitCall(RexCall call) {
        SqlKind kind = call.getKind();
        if (kind == SqlKind.AND || kind == SqlKind.OR ||
            kind == SqlKind.EQUALS || kind == SqlKind.NOT_EQUALS ||
            kind == SqlKind.GREATER_THAN || kind == SqlKind.GREATER_THAN_OR_EQUAL ||
            kind == SqlKind.LESS_THAN || kind == SqlKind.LESS_THAN_OR_EQUAL ||
            kind == SqlKind.NOT || kind == SqlKind.LIKE) {
          return super.visitCall(call);
        } else {
          hasUdf[0] = true;
          return super.visitCall(call);
        }
      }
    });

    return hasUdf[0];
  }



  /**
   * Visitor implementation for translating RexNode to Milvus expression.
   */
  private static class FilterTranslatorVisitor extends RexVisitorImpl<String> {
    private final List<String> fieldNames;

    protected FilterTranslatorVisitor(List<String> fieldNames) {
      super(true);
      this.fieldNames = fieldNames;
    }

    @Override public String visitLiteral(RexLiteral literal) {
      Object value = literal.getValue2();

      if (value == null) {
        return null;
      }

      if (value instanceof String) {
        return "\"" + escapeString(value.toString()) + "\"";
      } else if (value instanceof Number) {
        return value.toString();
      } else {
        return null;
      }
    }

    @Override public String visitInputRef(RexInputRef ref) {
      int index = ref.getIndex();
      if (index < 0 || index >= fieldNames.size()) {
        return null;
      }
      return fieldNames.get(index);
    }

    @Override public String visitCall(RexCall call) {
      return translateCall(call);
    }

    private String translateCall(RexCall call) {
      List<RexNode> operands = call.getOperands();
      SqlKind kind = call.getKind();

      switch (kind) {
      case EQUALS:
      case NOT_EQUALS:
      case GREATER_THAN:
      case GREATER_THAN_OR_EQUAL:
      case LESS_THAN:
      case LESS_THAN_OR_EQUAL:
        return translateBinaryOp(getOperatorForKind(kind), operands);
      case LIKE:
        return translateLike(operands);
      case AND:
        return translateLogicalOp("&&", operands);
      case OR:
        return translateLogicalOp("||", operands);
      case NOT:
        return translateNot(operands);
      default:
        return null;
      }
    }

    private String getOperatorForKind(SqlKind kind) {
      switch (kind) {
      case EQUALS: return "==";
      case NOT_EQUALS: return "!=";
      case GREATER_THAN: return ">";
      case GREATER_THAN_OR_EQUAL: return ">=";
      case LESS_THAN: return "<";
      case LESS_THAN_OR_EQUAL: return "<=";
      default: throw new IllegalArgumentException("Not a binary operator: " + kind);
      }
    }

    private String translateBinaryOp(
        String operator,
        List<RexNode> operands) {
      if (operands.size() != 2) {
        return null;
      }

      String left = operands.get(0).accept(this);
      String right = operands.get(1).accept(this);

      if (left == null || right == null) {
        return null;
      }

      return String.format("(%s %s %s)", left, operator, right);
    }

    private String translateLogicalOp(
        String operator,
        List<RexNode> operands) {
      if (operands.size() < 2) {
        return null;
      }

      StringBuilder sb = new StringBuilder("(");
      for (int i = 0; i < operands.size(); i++) {
        String expr = operands.get(i).accept(this);
        if (expr == null) {
          return null;
        }
        sb.append(expr);
        if (i < operands.size() - 1) {
          sb.append(" ").append(operator).append(" ");
        }
      }
      sb.append(")");

      return sb.toString();
    }

    private String translateNot(List<RexNode> operands) {
      if (operands.size() != 1) {
        return null;
      }

      String expr = operands.get(0).accept(this);
      if (expr == null) {
        return null;
      }

      return String.format("(!%s)", expr);
    }

    private String translateLike(List<RexNode> operands) {
      if (operands.size() != 2) {
        return null;
      }

      String field = operands.get(0).accept(this);
      String pattern = operands.get(1).accept(this);

      if (field == null || pattern == null) {
        return null;
      }

      return String.format("%s like %s", field, pattern);
    }
  }

  /**
   * Translates a RexNode to a Milvus expression string.
   * Returns null if the node contains UDF calls or cannot be translated.
   * Vector UDFs (l2_distance, cosine_distance, inner_product) are NOT pushed down to Milvus
   * when used in WHERE clause. They are only supported in ORDER BY with LIMIT (Top-K queries).
   *
   * @param node The RexNode to translate
   * @param fieldNames List of field names in the table
   * @return Milvus expression string, or null if translation failed
   */
  public static String translate(RexNode node, List<String> fieldNames) {
    if (node == null) {
      return null;
    }

    // Check if the node contains any UDF calls (including vector distance functions)
    // If yes, return null to prevent pushing down to Milvus
    if (containsUdfCall(node)) {
      return null;
    }

    FilterTranslatorVisitor visitor = new FilterTranslatorVisitor(fieldNames);
    return node.accept(visitor);
  }

  private static String escapeString(String s) {
    return s.replace("\"", "\\\"");
  }
}
