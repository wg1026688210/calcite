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

import java.util.List;
import java.util.Map;

/**
 * Parameters for Milvus vector similarity search.
 * Encapsulates all parameters needed for performing vector search,
 * including vector field, query vector, metric type, filters, and options.
 */
public class VectorSearchParam {
  private final String collectionName;
  private final String vectorField;
  private final List<Float> queryVector;
  private final String metricType;
  private final Long topK;
  private final String filterExpression;
  private final List<String> outputFields;
  private final Map<String, String> milvusOptions;

  private VectorSearchParam(Builder builder) {
    this.collectionName = builder.collectionName;
    this.vectorField = builder.vectorField;
    this.queryVector = builder.queryVector;
    this.metricType = builder.metricType;
    this.topK = builder.topK;
    this.filterExpression = builder.filterExpression;
    this.outputFields = builder.outputFields;
    this.milvusOptions = builder.milvusOptions;
  }

  public String getCollectionName() {
    return collectionName;
  }

  public String getVectorField() {
    return vectorField;
  }

  public List<Float> getQueryVector() {
    return queryVector;
  }

  public String getMetricType() {
    return metricType;
  }

  public Long getTopK() {
    return topK;
  }

  public String getFilterExpression() {
    return filterExpression;
  }

  public List<String> getOutputFields() {
    return outputFields;
  }

  public Map<String, String> getMilvusOptions() {
    return milvusOptions;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for VectorSearchParam.
   */
  public static class Builder {
    private String collectionName;
    private String vectorField;
    private List<Float> queryVector;
    private String metricType;
    private Long topK;
    private String filterExpression;
    private List<String> outputFields;
    private Map<String, String> milvusOptions;

    public Builder collectionName(String collectionName) {
      this.collectionName = collectionName;
      return this;
    }

    public Builder vectorField(String vectorField) {
      this.vectorField = vectorField;
      return this;
    }

    public Builder queryVector(List<Float> queryVector) {
      this.queryVector = queryVector;
      return this;
    }

    public Builder metricType(String metricType) {
      this.metricType = metricType;
      return this;
    }

    public Builder topK(Long topK) {
      this.topK = topK;
      return this;
    }

    public Builder filterExpression(String filterExpression) {
      this.filterExpression = filterExpression;
      return this;
    }

    public Builder outputFields(List<String> outputFields) {
      this.outputFields = outputFields;
      return this;
    }

    public Builder milvusOptions(Map<String, String> milvusOptions) {
      this.milvusOptions = milvusOptions;
      return this;
    }

    public VectorSearchParam build() {
      return new VectorSearchParam(this);
    }
  }
}
