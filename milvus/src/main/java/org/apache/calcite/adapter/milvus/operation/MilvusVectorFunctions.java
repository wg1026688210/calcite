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

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Definitions of vector distance functions for Milvus.
 * These functions enable vector similarity search in SQL queries.
 */
public class MilvusVectorFunctions {
  private MilvusVectorFunctions() {
  }

  /**
   * L2 Distance (Euclidean distance) function.
   * Calculates the Euclidean distance between two vectors.
   * Signature: l2_distance(vector_field, query_vector_literal) -> DOUBLE
   */
  public static final SqlFunction L2_DISTANCE =
      new SqlFunction("L2_DISTANCE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.DOUBLE),
          null,
          OperandTypes.family(
              SqlTypeFamily.ARRAY,
              SqlTypeFamily.CHARACTER),
          SqlFunctionCategory.USER_DEFINED_FUNCTION);

  /**
   * Cosine Distance function.
   * Calculates the cosine distance between two vectors.
   * Signature: cosine_distance(vector_field, query_vector_literal) -> DOUBLE
   */
  public static final SqlFunction COSINE_DISTANCE =
      new SqlFunction("COSINE_DISTANCE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.DOUBLE),
          null,
          OperandTypes.family(
              SqlTypeFamily.ARRAY,
              SqlTypeFamily.CHARACTER),
          SqlFunctionCategory.USER_DEFINED_FUNCTION);

  /**
   * Inner Product distance function.
   * Calculates the inner product between two vectors.
   * Signature: inner_product(vector_field, query_vector_literal) -> DOUBLE
   */
  public static final SqlFunction INNER_PRODUCT =
      new SqlFunction("INNER_PRODUCT",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.DOUBLE),
          null,
          OperandTypes.family(
              SqlTypeFamily.ARRAY,
              SqlTypeFamily.CHARACTER),
          SqlFunctionCategory.USER_DEFINED_FUNCTION);
}
