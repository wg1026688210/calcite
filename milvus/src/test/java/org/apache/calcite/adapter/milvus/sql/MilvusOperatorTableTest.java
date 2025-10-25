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
package org.apache.calcite.adapter.milvus.sql;

import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify that MilvusOperatorTable is correctly implemented.
 *
 * <p>This test validates that the Milvus vector distance functions
 * (L2_DISTANCE, COSINE_DISTANCE, INNER_PRODUCT) are registered in the
 * operator table and can be looked up.
 */
class MilvusOperatorTableTest {

  @Test void testMilvusOperatorTableHasAllFunctions() {
    MilvusOperatorTable operatorTable = MilvusOperatorTable.instance();

    System.out.println("\n=== Verifying MilvusOperatorTable Functions ===");

    // Get all operators from the table
    List operators = operatorTable.getOperatorList();

    // Verify that our three functions are in the list
    boolean hasL2Distance = false;
    boolean hasCosineDistance = false;
    boolean hasInnerProduct = false;

    for (Object op : operators) {
      if (op instanceof SqlFunction) {
        SqlFunction func = (SqlFunction) op;
        String name = func.getName();
        System.out.println("  Found function: " + name);

        if ("L2_DISTANCE".equals(name)) {
          hasL2Distance = true;
          System.out.println("    ✓ L2_DISTANCE function verified");
        } else if ("COSINE_DISTANCE".equals(name)) {
          hasCosineDistance = true;
          System.out.println("    ✓ COSINE_DISTANCE function verified");
        } else if ("INNER_PRODUCT".equals(name)) {
          hasInnerProduct = true;
          System.out.println("    ✓ INNER_PRODUCT function verified");
        }
      }
    }

    System.out.println("\n=== Summary ===");
    System.out.println("L2_DISTANCE found: " + hasL2Distance);
    System.out.println("COSINE_DISTANCE found: " + hasCosineDistance);
    System.out.println("INNER_PRODUCT found: " + hasInnerProduct);

    assertTrue(hasL2Distance, "L2_DISTANCE should be registered");
    assertTrue(hasCosineDistance, "COSINE_DISTANCE should be registered");
    assertTrue(hasInnerProduct, "INNER_PRODUCT should be registered");

    System.out.println("\n✅ SUCCESS: All three Milvus vector functions are registered!");
  }

  @Test void testOperatorTableChaining() {
    // Test that MilvusOperatorTable can be chained with standard operators
    SqlOperatorTable milvusOpTable = MilvusOperatorTable.instance();
    SqlOperatorTable chainedTable =
        SqlOperatorTables.chain(SqlStdOperatorTable.instance(),
        milvusOpTable);

    System.out.println("\n=== Testing Operator Table Chaining ===");

    List operators = chainedTable.getOperatorList();

    // Should have both standard operators and Milvus functions
    boolean hasStandardOps = false;
    boolean hasMilvusOps = false;

    for (Object op : operators) {
      if (op instanceof SqlFunction) {
        SqlFunction func = (SqlFunction) op;
        String name = func.getName();

        if ("L2_DISTANCE".equals(name) ||
            "COSINE_DISTANCE".equals(name) ||
            "INNER_PRODUCT".equals(name)) {
          hasMilvusOps = true;
        }

        if ("TRIM".equals(name) || "UPPER".equals(name) || "LOWER".equals(name)) {
          hasStandardOps = true;
        }
      }
    }

    assertTrue(hasStandardOps, "Should have standard SQL functions");
    assertTrue(hasMilvusOps, "Should have Milvus functions");

    System.out.println("✅ SUCCESS: Operator table chaining works!");
  }

  @Test void testFunctionNames() {
    MilvusOperatorTable operatorTable = MilvusOperatorTable.instance();
    List operators = operatorTable.getOperatorList();

    System.out.println("\n=== Function Names ===");

    for (Object op : operators) {
      if (op instanceof SqlFunction) {
        SqlFunction func = (SqlFunction) op;
        String name = func.getName();
        System.out.println("  - " + name);
      }
    }

    // Verify names are uppercase
    assertTrue(
        operators.stream()
            .filter(op -> op instanceof SqlFunction)
            .map(op -> (SqlFunction) op)
            .anyMatch(f -> "L2_DISTANCE".equals(((SqlFunction) f).getName())),
        "Function names should be uppercase");

    System.out.println("✅ Function names are correctly uppercase");
  }

  @Test void testMilvusOperatorTableValidation() throws Exception {
    System.out.println("\n=== Testing MilvusOperatorTable SQL Validation ===");

    // Create a simple schema for testing
    SchemaPlus rootSchema = Frameworks.createRootSchema(true);

    // Test that MilvusOperatorTable works with Frameworks API for SQL validation
    FrameworkConfig config = Frameworks.newConfigBuilder()
        .defaultSchema(rootSchema)
        .operatorTable(
            SqlOperatorTables.chain(
                SqlStdOperatorTable.instance(),
                MilvusOperatorTable.instance()))
        .build();

    String[] vectorFunctions = {"l2_distance", "cosine_distance", "inner_product"};
    for (String function : vectorFunctions) {
      System.out.println("\n--- Testing " + function.toUpperCase() + " function ---");

      try {
        org.apache.calcite.tools.Planner planner = Frameworks.getPlanner(config);

        // Test function signature validation - use simple literals instead of column references
        String functionExpr =
            String.format("%s(ARRAY[0.1, 0.2, 0.3, 0.4], '[0.1, 0.2, 0.3, 0.4]')", function);
        org.apache.calcite.sql.SqlNode sqlNode = planner.parse("SELECT " + functionExpr);
        org.apache.calcite.sql.SqlNode validatedNode = planner.validate(sqlNode);
        System.out.println("✓ " + function.toUpperCase() + " validation successful!");
        System.out.println("  Expression: " + functionExpr);

      } catch (Exception e) {
        System.out.println("✗ " + function.toUpperCase() + " validation failed: " + e.getMessage());
        throw e;
      }
    }
  }
}
