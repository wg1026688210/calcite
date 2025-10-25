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
package org.apache.calcite.adapter.milvus.udf.back;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

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
 * by {@link org.apache.calcite.adapter.milvus.operation.MilvusVectorEnumerator}
 * using Milvus's native search capabilities.
 */
public class MilvusVectorUdfs {

  private static final Gson GSON = new Gson();

  private MilvusVectorUdfs() {
    // Utility class
  }

  /**
   * Parses a query vector from JSON string to List<Float>.
   *
   * @param queryVector the query vector as a JSON string (e.g., "[0.1, 0.2, 0.3]")
   * @return parsed query vector as List<Float>
   */
  private static List<Float> parseQueryVector(String queryVector) {
    try {
      // Parse JSON array string to List<Float>
      return GSON.fromJson(queryVector, new TypeToken<ArrayList<Float>>() { }.getType());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid query vector format. Expected a JSON array of floats, e.g., \"[0.1, 0.2, 0.3]\"", e);
    }
  }

  /**
   * Validates that two vectors have the same dimension.
   *
   * @param vector1 first vector
   * @param vector2 second vector
   * @throws IllegalArgumentException if vectors have different dimensions
   */
  private static void validateDimensions(List<Float> vector1, List<Float> vector2) {
    if (vector1 == null || vector2 == null) {
      throw new IllegalArgumentException("Vectors cannot be null");
    }
    if (vector1.size() != vector2.size()) {
      throw new IllegalArgumentException(
          "Vector dimension mismatch: " + vector1.size() + " != " + vector2.size());
    }
  }

  /**
   * Converts a list-like vector to List<Float>.
   *
   * <p>Calcite may represent ARRAY elements as different boxed numeric types
   * depending on casts (e.g. FLOAT ARRAY vs DOUBLE ARRAY). We normalize to
   * float values for distance computation.
   */
  private static List<Float> toFloatVector(List<?> vector) {
    if (vector == null) {
      return null;
    }
    List<Float> out = new ArrayList<>(vector.size());
    for (Object o : vector) {
      if (o == null) {
        out.add(0f);
      } else if (o instanceof Number) {
        out.add(((Number) o).floatValue());
      } else {
        throw new IllegalArgumentException(
            "Vector element is not a number: " + o.getClass().getName());
      }
    }
    return out;
  }

  /**
   * Computes L2 distance (Euclidean distance) between two vectors.
   *
   * <p>Signature: L2_DISTANCE(vector_field_array, query_vector_string) -> double
   *
   * @param vectorField the vector field from the table (as List of Numbers)
   * @param queryVector the query vector as a JSON string (e.g., "[0.1, 0.2, 0.3]")
   * @return L2 distance between the vectors (smaller values indicate higher similarity)
   */
  public static double l2_distance(List<?> vectorField, String queryVector) {
    List<Float> query = parseQueryVector(queryVector);
    List<Float> vector = toFloatVector(vectorField);

    if (vector == null) {
      return 0.0;
    }

    validateDimensions(vector, query);

    double sum = 0.0;
    int size = vector.size();
    System.out.println("[MilvusVectorUdfs.l2_distance] Calculating L2 distance...");

    for (int i = 0; i < size; i++) {
      double diff = vector.get(i) - query.get(i);
      sum += diff * diff;
      System.out.println("[MilvusVectorUdfs.l2_distance]   i=" + i + ", vector=" + vector.get(i)
          + ", query=" + query.get(i) + ", diff=" + diff);
    }

    double result = Math.sqrt(sum);
    System.out.println("[MilvusVectorUdfs.l2_distance] Final result=" + result);
    return result;
  }

  /**
   * Overload for ARRAY literal query vectors.
   *
   * <p>When SQL uses {@code ARRAY[...]} as the query vector, Calcite generates
   * code that passes a {@code java.util.List} (often of BigDecimal). This
   * overload keeps the function callable and normalizes values.
   */
  public static double l2_distance(List<?> vectorField, List<?> queryVector) {
    List<Float> vector = toFloatVector(vectorField);
    List<Float> query = toFloatVector(queryVector);

    if (vector == null) {
      return 0.0;
    }

    validateDimensions(vector, query);

    double sum = 0.0;
    int size = vector.size();
    for (int i = 0; i < size; i++) {
      double diff = vector.get(i) - query.get(i);
      sum += diff * diff;
    }
    return Math.sqrt(sum);
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
  public static double cosine_distance(List<?> vectorField, String queryVector) {
    List<Float> query = parseQueryVector(queryVector);
    List<Float> vector = toFloatVector(vectorField);
    validateDimensions(vector, query);

    double dotProduct = 0.0;
    double norm1 = 0.0;
    double norm2 = 0.0;

    int size = vector.size();

    for (int i = 0; i < size; i++) {
      float a = vector.get(i);
      float b = query.get(i);
      dotProduct += a * b;
      norm1 += a * a;
      norm2 += b * b;
    }

    double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);

    if (denominator == 0.0) {
      // Both vectors are zero vectors
      return 0.0;
    }

    double cosineSimilarity = dotProduct / denominator;

    // Convert cosine similarity to cosine distance: distance = 1 - similarity
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
  public static double inner_product(List<?> vectorField, String queryVector) {
    List<Float> query = parseQueryVector(queryVector);
    List<Float> vector = toFloatVector(vectorField);
    validateDimensions(vector, query);

    double sum = 0.0;
    int size = vector.size();

    for (int i = 0; i < size; i++) {
      sum += vector.get(i) * query.get(i);
    }

    return sum;
  }

}
