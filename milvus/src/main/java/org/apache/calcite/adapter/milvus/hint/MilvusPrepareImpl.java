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
package org.apache.calcite.adapter.milvus.hint;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.interpreter.BindableConvention;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;

import java.lang.reflect.Type;

/** CalcitePrepare implementation that enables Milvus SQL hints in JDBC execution path. */
public class MilvusPrepareImpl extends CalcitePrepareImpl {
  @Override protected CalcitePreparingStmt getPreparingStmt(
      CalcitePrepare.Context context,
      Type elementType,
      CalciteCatalogReader catalogReader,
      RelOptPlanner planner) {
    final EnumerableRel.Prefer prefer =
        elementType == Object[].class
            ? EnumerableRel.Prefer.ARRAY
            : EnumerableRel.Prefer.CUSTOM;

    final Convention resultConvention =
        enableBindable ? BindableConvention.INSTANCE : EnumerableConvention.INSTANCE;


    return new CalcitePreparingStmt(this, context, catalogReader, context.getTypeFactory(),
        context.getRootSchema(), prefer,
        createCluster(planner, new RexBuilder(context.getTypeFactory())),
        resultConvention, createConvertletTable()) {
      @Override protected SqlToRelConverter getSqlToRelConverter(
          SqlValidator validator,
          CatalogReader catalogReader,
          SqlToRelConverter.Config config) {
        return super.getSqlToRelConverter(validator, catalogReader,
            config.withHintStrategyTable(MilvusHintConfig.createHintStrategyTable()));
      }
    };
  }
}
