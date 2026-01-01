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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * UDFs for Milvus vector distance functions.
 *
 * <p>These functions provide vector distance computations for vector similarity search.
 * They are:
 * 1. Registered in the schema to enable SQL parsing
 * 2. Recognized by MilvusFilterTranslator and converted to Milvus search parameters
 * 3. Can be executed when vector search is not push down to milvus
 *
 * <p>The actual vector similarity search can be performed by either local computation or
 * by {@link org.apache.calcite.adapter.milvus.operation.MilvusSearchEnumerator}
 * using Milvus's native search capabilities.
 */
public class MilvusVectorUdfs {

  private MilvusVectorUdfs() {
  }

  /**
   * Parses a query vector from JSON string to List<Float>.
   *
   * @param queryVector the query vector as a JSON string (e.g., "[0.1, 0.2, 0.3]")
   * @return parsed query vector as List<Float>
   */
  private static List<Float> parseQueryVector(String queryVector) {
    if (queryVector == null) {
      throw new IllegalArgumentException("Query vector cannot be null");
    }

    String trimmed = queryVector.trim();
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
      throw new IllegalArgumentException(
          "Invalid query vector format. Expected a JSON array of floats, e.g., \"[0.1, 0.2, 0.3]\"");
    }

    String content = trimmed.substring(1, trimmed.length() - 1).trim();
    if (content.isEmpty()) {
      return new ArrayList<>();
    }

    List<Float> result = new ArrayList<>();
    String[] parts = content.split(",");

    for (String part : parts) {
      try {
        String numStr = part.trim();

        result.add(parseFloat(numStr));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Invalid query vector format. Expected a JSON array of floats, e.g., \"[0.1, 0.2, 0.3]\"",
            e);
      }
    }

    return result;
  }

  /**
   * Validates that two vectors have the same dimension.
   *
   * @param vector1 first vector
   * @param vector2 second vector
   * @throws IllegalArgumentException if vectors have different dimensions
   */
  private static void validateDimensions(List<Number> vector1, List<Float> vector2) {
    if (vector1 == null || vector2 == null) {
      throw new IllegalArgumentException("Vectors cannot be null");
    }
    if (vector1.size() != vector2.size()) {
      throw new IllegalArgumentException(
          "Vector dimension mismatch: " + vector1.size() + " != " + vector2.size());
    }
  }

  /**
   * Computes L2 distance (Euclidean distance) between two vectors.
   *
   * <p>Signature: L2_DISTANCE(vector_field_array, query_vector_string) -> double
   *
   * @param vectorField the vector field from the table (as List<Float>)
   * @param queryVector the query vector as a JSON string (e.g., "[0.1, 0.2, 0.3]")
   * @return L2 distance between the vectors (smaller values indicate higher similarity)
   */
  public static double l2_distance(List<Number> vectorField, String queryVector) {
    if (vectorField == null) {
      return 0.0;
    }

    List<Float> query = parseQueryVector(queryVector);

    validateDimensions(vectorField, query);

    List<Float> floatVectorField =
        vectorField.stream().map(Number::floatValue).collect(Collectors.toList());

    double sum = 0.0;
    int size = floatVectorField.size();

    for (int i = 0; i < size; i++) {
      double diff = floatVectorField.get(i) - query.get(i);
      sum += diff * diff;
    }

    double result = Math.sqrt(sum);
    return result;
  }

  /**
   * Computes cosine distance between two vectors.
   *
   * <p>Signature: COSINE_DISTANCE(vector_field_array, query_vector_string) -> double
   *
   * @param vectorField the vector field from the table (as List<Float>)
   * @param queryVector the query vector as a JSON string (e.g., "[0.1, 0.2, 0.3]")
   * @return cosine distance between the vectors (range: [0, 2], where 0 means identical direction)
   */
  public static double cosine_distance(List<Number> vectorField, String queryVector) {
    List<Float> query = parseQueryVector(queryVector);
    validateDimensions(vectorField, query);

    List<Float> floatVectorField =
        vectorField.stream().map(Number::floatValue).collect(Collectors.toList());

    double dotProduct = 0.0;
    double norm1 = 0.0;
    double norm2 = 0.0;

    int size = floatVectorField.size();

    for (int i = 0; i < size; i++) {
      float a = floatVectorField.get(i);
      float b = query.get(i);
      dotProduct += a * b;
      norm1 += a * a;
      norm2 += b * b;
    }

    double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);

    if (denominator == 0.0) {
      return 0.0;
    }

    double cosineSimilarity = dotProduct / denominator;

    return 1.0 - cosineSimilarity;
  }

  /**
   * Computes inner product between two vectors.
   *
   * <p>Signature: INNER_PRODUCT(vector_field_array, query_vector_string) -> double
   *
   * @param vectorField the vector field from the table (as List<Float>)
   * @param queryVector the query vector as a JSON string (e.g., "[0.1, 0.2, 0.3]")
   * @return inner product between the vectors (larger values indicate higher similarity)
   */
  public static double inner_product(List<Number> vectorField, String queryVector) {
    List<Float> query = parseQueryVector(queryVector);
    validateDimensions(vectorField, query);


    List<Float> floatVectorField =
        vectorField.stream().map(Number::floatValue).collect(Collectors.toList());


    double sum = 0.0;
    int size = floatVectorField.size();

    for (int i = 0; i < size; i++) {
      sum += floatVectorField.get(i) * query.get(i);
    }

    return sum;
  }

}
