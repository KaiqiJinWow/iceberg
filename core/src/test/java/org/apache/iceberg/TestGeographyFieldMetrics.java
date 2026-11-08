/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.iceberg.geospatial.GeospatialBound;
import org.apache.iceberg.types.EdgeAlgorithm;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TestGeographyFieldMetrics {
  private static final Types.GeographyType GEOGRAPHY = Types.GeographyType.crs84();

  @Test
  void includesBoundsCountsAndWkbSize() {
    GeographyFieldMetrics.Builder builder = new GeographyFieldMetrics.Builder(2, GEOGRAPHY);
    ByteBuffer west = point(170, 10);
    ByteBuffer east = point(-170, 20);
    long totalBytes = west.remaining() + east.remaining();
    builder.addValue(west);
    builder.addValue(east);

    GeographyFieldMetrics metrics = builder.build();
    assertThat(metrics.id()).isEqualTo(2);
    assertThat(metrics.valueCount()).isEqualTo(2);
    assertThat(metrics.nullValueCount()).isZero();
    assertThat(metrics.nanValueCount()).isEqualTo(-1);
    assertThat(metrics.originalType()).isEqualTo(GEOGRAPHY);
    assertThat(metrics.lowerBound()).isEqualTo(GeospatialBound.createXY(170, 10));
    assertThat(metrics.upperBound()).isEqualTo(GeospatialBound.createXY(-170, 20));
    assertThat(metrics.totalBytes()).isEqualTo(totalBytes);
    assertThat(metrics.avgValueSizeInBytes()).isEqualTo((int) (totalBytes / 2));
  }

  @Test
  void emptyValuesKeepSizeWithoutBounds() {
    GeographyFieldMetrics.Builder builder = new GeographyFieldMetrics.Builder(2, GEOGRAPHY);
    ByteBuffer empty = point(Double.NaN, Double.NaN);
    int bytes = empty.remaining();
    builder.addValue(empty);

    GeographyFieldMetrics metrics = builder.build();
    assertThat(metrics.valueCount()).isEqualTo(1);
    assertThat(metrics.totalBytes()).isEqualTo(bytes);
    assertThat(metrics.lowerBound()).isNull();
    assertThat(metrics.upperBound()).isNull();
  }

  @Test
  void malformedValueKeepsAllCountsAndBytes() {
    GeographyFieldMetrics.Builder builder = new GeographyFieldMetrics.Builder(2, GEOGRAPHY);
    ByteBuffer valid = point(12, 34);
    ByteBuffer invalid = ByteBuffer.wrap(new byte[] {1});
    long totalBytes = 2L * valid.remaining() + invalid.remaining();
    builder.addValue(valid);
    builder.addValue(invalid);
    builder.addValue(valid);

    GeographyFieldMetrics metrics = builder.build();
    assertThat(metrics.valueCount()).isEqualTo(3);
    assertThat(metrics.totalBytes()).isEqualTo(totalBytes);
    assertThat(metrics.lowerBound()).isNull();
    assertThat(metrics.upperBound()).isNull();
    assertThat(valid.position()).isZero();
    assertThat(invalid.position()).isZero();
  }

  @ParameterizedTest
  @EnumSource(value = EdgeAlgorithm.class, names = "SPHERICAL", mode = EnumSource.Mode.EXCLUDE)
  void unsupportedAlgorithmKeepsCountsAndSize(EdgeAlgorithm algorithm) {
    Types.GeographyType type = Types.GeographyType.of("EPSG:4326", algorithm);
    GeographyFieldMetrics.Builder builder = new GeographyFieldMetrics.Builder(2, type);
    ByteBuffer value = point(12, 34);
    int bytes = value.remaining();
    builder.addValue(value);

    GeographyFieldMetrics metrics = builder.build();
    assertThat(metrics.originalType()).isEqualTo(type);
    assertThat(metrics.valueCount()).isEqualTo(1);
    assertThat(metrics.totalBytes()).isEqualTo(bytes);
    assertThat(metrics.lowerBound()).isNull();
    assertThat(metrics.upperBound()).isNull();
  }

  @Test
  void noValuesProducesNoBoundsOrAverageSize() {
    GeographyFieldMetrics metrics = new GeographyFieldMetrics.Builder(2, GEOGRAPHY).build();

    assertThat(metrics.valueCount()).isZero();
    assertThat(metrics.totalBytes()).isZero();
    assertThat(metrics.avgValueSizeInBytes()).isNull();
    assertThat(metrics.lowerBound()).isNull();
    assertThat(metrics.upperBound()).isNull();
  }

  private static ByteBuffer point(double longitude, double latitude) {
    return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + 2 * Double.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put((byte) 1)
        .putInt(1)
        .putDouble(longitude)
        .putDouble(latitude)
        .flip();
  }
}
