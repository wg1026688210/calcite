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
package org.apache.calcite.adapter.milvus.udf;

import org.apache.calcite.schema.Function;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;

public class MilvusVectorFunction extends SqlUserDefinedFunction {
  private final SqlOperandTypeChecker operandTypeChecker;

  public MilvusVectorFunction(String name, SqlReturnTypeInference returnTypeInference,
                             SqlOperandTypeChecker operandTypeChecker, Function function) {
    super(new SqlIdentifier(name, SqlParserPos.ZERO),
          SqlKind.OTHER_FUNCTION,
          returnTypeInference,
          InferTypes.FIRST_KNOWN,
          null,
          function,
          SqlFunctionCategory.USER_DEFINED_FUNCTION,
          SqlSyntax.FUNCTION);
    this.operandTypeChecker = operandTypeChecker;
  }

  @Override public SqlOperandCountRange getOperandCountRange() {
    return operandTypeChecker.getOperandCountRange();
  }

  @Override public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
    return operandTypeChecker.checkOperandTypes(callBinding, throwOnFailure);
  }
}
