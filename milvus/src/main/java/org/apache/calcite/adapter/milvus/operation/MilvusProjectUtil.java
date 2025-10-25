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

import org.apache.calcite.linq4j.tree.Primitive;

import io.milvus.grpc.FieldData;
import io.milvus.response.FieldDataWrapper;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Utility class for common projection operations shared between
 * MilvusEnumerator and MilvusVectorEnumerator.
 * Provides shared implementations for field value extraction and conversion.
 */
public final class MilvusProjectUtil {

  private MilvusProjectUtil() {
    // Utility class, prevent instantiation
  }




  /**
   * fill project info.
   *
   * @param entity              the entity map from V2 response
   * @param score               the score value for vector search (null for scan)
   * @param projectRowTypeMap   the projection map defining field order
   * @return the projected row values
   */
  public static Object[] fillProjectRow(
      Map<String, Object> entity,
      Double score,
      Map<Integer, MilvusProjectExpression> projectRowTypeMap) {

    Object[] rowValues = new Object[projectRowTypeMap.size()];
    for (Map.Entry<Integer, MilvusProjectExpression> entry : projectRowTypeMap.entrySet()) {
      Integer position = entry.getKey();
      MilvusProjectExpression expr = entry.getValue();

      if (expr.getType() == MilvusProjectExpression.ExpressionType.VECTOR_SCORE
          && score != null) {
        // Set score value for VECTOR_SCORE expressions (vector search only)
        rowValues[position] = score;
      } else if (expr.getType() == MilvusProjectExpression.ExpressionType.CONSTANT) {
        // Set constant value
        MilvusProjectExpression.Constant constantExpr =
            (MilvusProjectExpression.Constant) expr;
        rowValues[position] = constantExpr.getValue();
      } else if (expr.getType() == MilvusProjectExpression.ExpressionType.INPUT_FIELD
          && expr instanceof MilvusProjectExpression.InputField) {
        String fieldName = ((MilvusProjectExpression.InputField) expr).getFieldName();
        rowValues[position] = entity.get(fieldName);
      }else {
        // Unsupported expression type
        rowValues[position] = null;

      }
    }
    return rowValues;
  }



  /**
   * Gets a field value at the specified index from Milvus field data using a pre-built map.
   * This is more efficient than the plain getFieldValue method for multiple lookups.
   *
   * @param fieldDataMap pre-built map from field name to FieldData
   * @param fieldName    the name of the field to retrieve
   * @param fieldClass   the expected class of the field
   * @param rowIndex     the row index to get the value from
   * @return the field value, or null if not found
   */
  public static Object getFieldValue(
      Map<String, FieldData> fieldDataMap,
      String fieldName,
      Class<?> fieldClass,
      int rowIndex) {
    FieldData fieldData = fieldDataMap.get(fieldName);
    if (fieldData != null) {
      FieldDataWrapper wrapper = new FieldDataWrapper(fieldData);
      List<?> fieldDataList = wrapper.getFieldData();
      if (rowIndex < fieldDataList.size()) {
        Object value = fieldDataList.get(rowIndex);
        return convertValue(value, fieldClass);
      }
    }
    return null;
  }

  /**
   * Builds a map from field name to FieldData for efficient lookups.
   *
   * @param fieldsDataList the list of field data from Milvus
   * @return a map from field name to FieldData
   */
  public static Map<String, FieldData> buildFieldDataMap(List<FieldData> fieldsDataList) {
    Map<String, FieldData> fieldDataMap = new java.util.HashMap<>(fieldsDataList.size());
    for (FieldData fieldData : fieldsDataList) {
      fieldDataMap.put(fieldData.getFieldName(), fieldData);
    }
    return fieldDataMap;
  }

  /**
   * Converts a value to the target class, handling type conversions.
   * Supports ByteBuffer to byte[] conversion and numeric type conversions.
   * Also supports List to array conversion for vector fields.
   *
   * @param value      the value to convert
   * @param fieldClass the target class
   * @return the converted value
   */
  public static Object convertValue(Object value, Class<?> fieldClass) {
    if (value == null) {
      return null;
    }

    if (fieldClass.isInstance(value)) {
      return value;
    }

    if (value instanceof ByteBuffer) {
      ByteBuffer byteBuffer = (ByteBuffer) value;
      byte[] bytes = new byte[byteBuffer.remaining()];
      byteBuffer.get(bytes);
      return bytes;
    }

    Primitive primitive = Primitive.of(fieldClass);
    if (primitive != null) {
      fieldClass = primitive.boxClass;
    } else {
      primitive = Primitive.ofBox(fieldClass);
    }

    if (primitive != null && value instanceof Number) {
      return primitive.number((Number) value);
    }

    if (value instanceof List) {
      if (fieldClass.isArray()) {
        return convertListToArray((List<?>) value, fieldClass);
      }
    }

    return value;
  }



  /**
   * Converts a List to an array for vector field support.
   *
   * @param list       the list to convert
   * @param arrayClass the target array class (e.g., float[].class)
   * @return the converted array
   */
  private static Object convertListToArray(List<?> list, Class<?> arrayClass) {
    if (arrayClass == byte[].class) {
      byte[] array = new byte[list.size()];
      for (int i = 0; i < list.size(); i++) {
        Object item = list.get(i);
        if (item instanceof Number) {
          array[i] = ((Number) item).byteValue();
        } else if (item instanceof Byte) {
          array[i] = (Byte) item;
        }
      }
      return array;
    } else if (arrayClass == float[].class) {
      float[] array = new float[list.size()];
      for (int i = 0; i < list.size(); i++) {
        Object item = list.get(i);
        if (item instanceof Number) {
          array[i] = ((Number) item).floatValue();
        }
      }
      return array;
    } else if (arrayClass == double[].class) {
      double[] array = new double[list.size()];
      for (int i = 0; i < list.size(); i++) {
        Object item = list.get(i);
        if (item instanceof Number) {
          array[i] = ((Number) item).doubleValue();
        }
      }
      return array;
    }

    return list;
  }
}
