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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * SQL hints for vector search optimization.
 * Supports specifying index parameters for different search algorithms.
 *
 * <p>Usage example in SQL:
 * <pre>
 * SELECT /*+ INDEX(vector_idx, nprobe=16) *\/
 *        xid, l2_distance(feature, '[1,1,1,1]') AS dis
 * FROM vector
 * ORDER BY dis
 * LIMIT 3
 * </pre>
 */
public class VectorSearchHint {

  // Common index parameters
  private final Map<String, String> indexParams;

  private VectorSearchHint(Builder builder) {
    this.indexParams = Collections.unmodifiableMap(new HashMap<>(builder.indexParams));
  }

  /**
   * Gets index parameters for search.
   *
   * @return Map of parameter names to values
   */
  public Map<String, String> getIndexParams() {
    return indexParams;
  }

  /**
   * Gets a specific parameter value.
   *
   * @param name Parameter name
   * @return Parameter value, or null if not set
   */
  public String getParam(String name) {
    return indexParams.get(name);
  }

  /**
   * Gets the nprobe parameter for IVF-based indexes.
   * Controls the trade-off between accuracy and performance.
   *
   * @return nprobe value, or 16 (default) if not set
   */
  public int getNprobe() {
    String nprobe = indexParams.get("nprobe");
    return nprobe != null ? Integer.parseInt(nprobe) : 16;
  }

  /**
   * Gets the ef_search parameter for HNSW index.
   * Controls the number of candidates to explore during search.
   *
   * @return ef_search value, or 64 (default) if not set
   */
  public int getEfSearch() {
    String efSearch = indexParams.get("ef_search");
    return efSearch != null ? Integer.parseInt(efSearch) : 64;
  }

  /**
   * Gets the radius parameter for range search.
   * Specifies the search radius for range queries.
   *
   * @return radius value, or null for Top-K search
   */
  public Float getRadius() {
    String radius = indexParams.get("radius");
    return radius != null ? Float.parseFloat(radius) : null;
  }

  /**
   * Gets the range_filter parameter for range search.
   * Specifies the filter threshold for range queries.
   *
   * @return range_filter value, or null for Top-K search
   */
  public Float getRangeFilter() {
    String rangeFilter = indexParams.get("range_filter");
    return rangeFilter != null ? Float.parseFloat(rangeFilter) : null;
  }

  /**
   * Creates a new builder for VectorSearchHint.
   *
   * @return Builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for VectorSearchHint.
   */
  public static class Builder {
    private final Map<String, String> indexParams = new HashMap<>();

    /**
     * Sets a generic parameter.
     *
     * @param name Parameter name
     * @param value Parameter value
     * @return This builder
     */
    public Builder param(String name, String value) {
      this.indexParams.put(name, value);
      return this;
    }

    /**
     * Sets the nprobe parameter for IVF-based indexes.
     *
     * @param nprobe Number of clusters to search
     * @return This builder
     */
    public Builder nprobe(int nprobe) {
      this.indexParams.put("nprobe", String.valueOf(nprobe));
      return this;
    }

    /**
     * Sets the ef_search parameter for HNSW index.
     *
     * @param efSearch Size of the candidate list
     * @return This builder
     */
    public Builder efSearch(int efSearch) {
      this.indexParams.put("ef_search", String.valueOf(efSearch));
      return this;
    }

    /**
     * Sets the radius parameter for range search.
     *
     * @param radius Search radius
     * @return This builder
     */
    public Builder radius(float radius) {
      this.indexParams.put("radius", String.valueOf(radius));
      return this;
    }

    /**
     * Sets the range_filter parameter for range search.
     *
     * @param rangeFilter Filter threshold
     * @return This builder
     */
    public Builder rangeFilter(float rangeFilter) {
      this.indexParams.put("range_filter", String.valueOf(rangeFilter));
      return this;
    }

    /**
     * Builds the VectorSearchHint instance.
     *
     * @return VectorSearchHint instance
     */
    public VectorSearchHint build() {
      return new VectorSearchHint(this);
    }
  }

  /**
   * Parses SQL hints from string.
   *
   * @param hintString SQL hint string (e.g., "nprobe=16,ef_search=64")
   * @return VectorSearchHint instance
   */
  public static VectorSearchHint parse(String hintString) {
    if (hintString == null || hintString.trim().isEmpty()) {
      return builder().build();
    }

    Builder builder = builder();
    String[] pairs = hintString.split(",");

    for (String pair : pairs) {
      String[] keyValue = pair.split("=", 2);
      if (keyValue.length == 2) {
        String key = keyValue[0].trim();
        String value = keyValue[1].trim();
        builder.param(key, value);
      }
    }

    return builder.build();
  }

  @Override public String toString() {
    return "VectorSearchHint{" +
        "indexParams=" + indexParams +
        '}';
  }
}
