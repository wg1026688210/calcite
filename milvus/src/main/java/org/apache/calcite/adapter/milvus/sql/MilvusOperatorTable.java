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

import org.apache.calcite.adapter.milvus.udf.MilvusVectorFunction;
import org.apache.calcite.adapter.milvus.udf.MilvusVectorUdfs;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.ReflectiveSqlOperatorTable;

public class MilvusOperatorTable extends ReflectiveSqlOperatorTable {

  private static final MilvusOperatorTable INSTANCE = new MilvusOperatorTable();

  public static final SqlFunction L2_DISTANCE =
      new MilvusVectorFunction(
          "l2_distance",
          ReturnTypes.explicit(SqlTypeName.DOUBLE),
          OperandTypes.sequence(
              "l2_distance(ARRAY, ARRAY|CHARACTER)",
              OperandTypes.family(SqlTypeFamily.ARRAY),
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.ARRAY),
                  OperandTypes.family(SqlTypeFamily.CHARACTER))),
          ScalarFunctionImpl.create(MilvusVectorUdfs.class, "l2_distance"));

  // Cosine distance function
  // Signature: cosine_distance(ARRAY, ARRAY|CHARACTER) -> DOUBLE
  public static final SqlFunction COSINE_DISTANCE =
      new MilvusVectorFunction(
          "cosine_distance",
          ReturnTypes.explicit(SqlTypeName.DOUBLE),
          OperandTypes.sequence(
              "cosine_distance(ARRAY, ARRAY|CHARACTER)",
              OperandTypes.family(SqlTypeFamily.ARRAY),
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.ARRAY),
                  OperandTypes.family(SqlTypeFamily.CHARACTER))),
          ScalarFunctionImpl.create(MilvusVectorUdfs.class, "cosine_distance"));

  // Inner product function
  // Signature: inner_product(ARRAY, ARRAY|CHARACTER) -> DOUBLE
  public static final SqlFunction INNER_PRODUCT =
      new MilvusVectorFunction(
          "inner_product",
          ReturnTypes.explicit(SqlTypeName.DOUBLE),
          OperandTypes.sequence(
              "inner_product(ARRAY, ARRAY|CHARACTER)",
              OperandTypes.family(SqlTypeFamily.ARRAY),
              OperandTypes.or(
                  OperandTypes.family(SqlTypeFamily.ARRAY),
                  OperandTypes.family(SqlTypeFamily.CHARACTER))),
          ScalarFunctionImpl.create(MilvusVectorUdfs.class, "inner_product"));

  static {
    INSTANCE.init();
  }

  private MilvusOperatorTable() {
    super();
  }

  public static MilvusOperatorTable instance() {
    return INSTANCE;
  }
}
