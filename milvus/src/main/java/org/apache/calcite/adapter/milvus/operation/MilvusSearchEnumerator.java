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

import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.util.Pair;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;

/**
 * Enumerator that performs vector similarity search in Milvus.
 * Handles both vector-only queries and vector+scalar filter queries.
 */
public class MilvusSearchEnumerator implements Enumerator<Object> {
  private final Iterator<Row> iterator;
  private Object current;

  public MilvusSearchEnumerator(
      MilvusClientV2 client,
      String vectorField,
      List<Float> queryVector,
      String metricType,
      Long topK,
      @Nullable String filterExpression,
      String collectionName,
      List<Pair<Integer, MilvusProjectExpression>> projectRowTypeMap,
      @Nullable Map<String, String> milvusOptions) {

    List<String> outputFields = getOutputFields(projectRowTypeMap);

    VectorSearchParam param = VectorSearchParam.builder()
        .collectionName(collectionName)
        .vectorField(vectorField)
        .queryVector(queryVector)
        .metricType(metricType)
        .topK(topK)
        .filterExpression(filterExpression)
        .outputFields(outputFields)
        .milvusOptions(milvusOptions)
        .build();

    this.iterator = createIterator(client, param, projectRowTypeMap);
  }

  private static List<String> getOutputFields(
      List<Pair<Integer, MilvusProjectExpression>> projectRowTypeMap) {
    List<String> outputFields = new ArrayList<>();
    if (projectRowTypeMap != null && !projectRowTypeMap.isEmpty()) {
      for (Pair<Integer, MilvusProjectExpression> pair : projectRowTypeMap) {
        MilvusProjectExpression expr = pair.right;
        if (expr.getType() == MilvusProjectExpression.ExpressionType.INPUT_FIELD
            && expr instanceof MilvusProjectExpression.InputField) {
          String fieldName = ((MilvusProjectExpression.InputField) expr).getFieldName();
          outputFields.add(fieldName);
        }
      }
    }
    return outputFields;
  }

  private Iterator<Row> createIterator(
      MilvusClientV2 client,
      VectorSearchParam param,
      List<Pair<Integer, MilvusProjectExpression>> projectRowTypeMapForEnumerator) {
    SearchReq searchReq = SearchReq.builder()
        .collectionName(param.getCollectionName())
        .data(Collections.singletonList(new FloatVec( param.getQueryVector())))
        .topK(param.getTopK().intValue())
        .outputFields(param.getOutputFields())
        .searchParams(new HashMap<>(param.getMilvusOptions()))
        .metricType(IndexParam.MetricType.valueOf(param.getMetricType()))
        .filter(param.getFilterExpression())
        .build();

    SearchResp response = client.search(searchReq);

    List<Row> rows = parseSearchResults(response, projectRowTypeMapForEnumerator);

    return rows.iterator();
  }


  private static List<Row> parseSearchResults(
      SearchResp searchResponse,
      List<Pair<Integer, MilvusProjectExpression>> projectRowTypeMapForEnumerator) {

    List<Row> rows = new ArrayList<>();

    List<List<SearchResp.SearchResult>> searchResultsList = searchResponse.getSearchResults();

    if (searchResultsList == null || searchResultsList.isEmpty()) {
      return rows;
    }

    List<SearchResp.SearchResult> searchResults = searchResultsList.get(0);

    if (searchResults == null || searchResults.isEmpty()) {
      return rows;
    }

    Map<Integer, MilvusProjectExpression> projectRowTypeMap = new java.util.LinkedHashMap<>();
    if (projectRowTypeMapForEnumerator != null) {
      for (Pair<Integer, MilvusProjectExpression> pair : projectRowTypeMapForEnumerator) {
        projectRowTypeMap.put(pair.left, pair.right);
      }
    }

    for (SearchResp.SearchResult result : searchResults) {
      Object[] rowValues = buildRowFromSearchResult(result, projectRowTypeMap);
      rows.add(new Row(rowValues));
    }

    return rows;
  }

  private static Object[] buildRowFromSearchResult(
      SearchResp.SearchResult result,
      Map<Integer, MilvusProjectExpression> projectRowTypeMap) {

    Map<String, Object> entity = result.getEntity();
    double score = result.getScore();

    return MilvusProjectUtil.fillProjectRow(entity, score, projectRowTypeMap);
  }

  @Override public Object current() {
    if (current == null) {
      throw new IllegalStateException();
    }
    return current;
  }

  @Override public boolean moveNext() {
    if (iterator.hasNext()) {
      Row row = iterator.next();
      if (row.values.length == 1) {
        current = row.values[0];
      } else {
        current = row.values;
      }
      return true;
    } else {
      current = null;
      return false;
    }
  }

  @Override public void reset() {
    throw new UnsupportedOperationException();
  }

  @Override public void close() {
    // No-op for Milvus
  }

}
