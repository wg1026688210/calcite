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
package org.apache.calcite.adapter.milvus.factory;

import org.apache.calcite.adapter.milvus.operation.MilvusProjectExpression;
import org.apache.calcite.adapter.milvus.operation.MilvusQueryEnumerator;
import org.apache.calcite.adapter.milvus.operation.MilvusSearchEnumerator;
import org.apache.calcite.adapter.milvus.operation.MilvusTableScan;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.QueryableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.Pair;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MilvusTranslatableTable extends AbstractTable
    implements QueryableTable, TranslatableTable {
  private final MilvusClientV2 milvusClient;
  private final String collectionName;
  private final CreateCollectionReq.CollectionSchema collectionSchema;

  public MilvusTranslatableTable(MilvusClientV2 milvusClient, String collectionName,CreateCollectionReq.CollectionSchema collectionSchema) {
    this.collectionName = collectionName;
    this.milvusClient = milvusClient;
    this.collectionSchema = collectionSchema;

  }

  @Override public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
    final RelOptCluster cluster = context.getCluster();
    return new MilvusTableScan(
        cluster,
        cluster.traitSetOf(org.apache.calcite.adapter.milvus.convention.MilvusRel.CONVENTION),
        context.getTableHints(),
        relOptTable,
        this);
  }

  @Override public RelDataType getRowType(RelDataTypeFactory typeFactory) {

    List<RelDataType> dataTypes = new ArrayList<>();
    List<String> fieldNames = new ArrayList<>();
    for (CreateCollectionReq.FieldSchema fieldSchema : collectionSchema
        .getFieldSchemaList()) {
      DataType dataType = fieldSchema.getDataType();
      fieldNames.add(fieldSchema.getName());
      switch (dataType) {
      case Int8:
        dataTypes.add(typeFactory.createSqlType(SqlTypeName.SMALLINT));
      case Int16:
        dataTypes.add(
            typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.SMALLINT),
                true));
        break;
      case Int32:
        dataTypes.add(
            typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.INTEGER),
                true));
        break;
      case BinaryVector:
        dataTypes.add(
            typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.VARBINARY),
                true))
        ;
        break;
      case FloatVector:
        dataTypes.add(
            typeFactory.createTypeWithNullability(
                typeFactory.createArrayType(
                    typeFactory.createSqlType(SqlTypeName.REAL), -1),
                true));
        break;
      case Float:
        dataTypes.add(
            typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.REAL),
                true));
        break;
      case Double:
        dataTypes.add(
            typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.DOUBLE),
                true));
        break;
      case Bool:
        dataTypes.add(
            typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.BOOLEAN),
                true));
        break;
      default:
        dataTypes.add(
            typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.VARCHAR),
                true));
        break;
      }
    }
    return typeFactory.createStructType(Pair.zip(fieldNames, dataTypes));
  }

  public Enumerable<Object> scan(
      String filterExpression,
      List<Pair<Integer, MilvusProjectExpression>> projectRowTypeMapForEnumerator) {
    return new AbstractEnumerable<Object>() {
      @Override public Enumerator<Object> enumerator() {
        return new MilvusQueryEnumerator(
            milvusClient,
            collectionName,
            filterExpression,
            projectRowTypeMapForEnumerator);
      }
    };
  }

  public Enumerable<Object> vectorSearch(
      String vectorField,
      List<Float> queryVector,
      String metricType,
      Long topK,
      String filterExpression,
      List<Pair<Integer, MilvusProjectExpression>> projectRowTypeMapForEnumerator,
      Map<String,String> milvusOptions) {

    return new AbstractEnumerable<Object>() {
      @Override public Enumerator<Object> enumerator() {
        return new MilvusSearchEnumerator(
            milvusClient,
            vectorField,
            queryVector,
            metricType,
            topK,
            filterExpression,
            collectionName,
            projectRowTypeMapForEnumerator,
            milvusOptions);
      }
    };
  }

  @Override public Expression getExpression(SchemaPlus schema, String tableName, Class clazz) {
    return Schemas.tableExpression(schema, getElementType(), tableName, clazz);
  }

  @Override public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema,
      String tableName) {
    throw new UnsupportedOperationException();
  }

  @Override public Type getElementType() {
    return Object[].class;
  }
}
