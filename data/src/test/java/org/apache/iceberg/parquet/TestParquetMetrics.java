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
package org.apache.iceberg.parquet;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Files;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.ParameterizedTestExtension;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TestMetrics;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.geospatial.GeospatialBound;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Types;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Test Metrics for Parquet. */
@ExtendWith(ParameterizedTestExtension.class)
public class TestParquetMetrics extends TestMetrics {
  private static final Map<String, String> SMALL_ROW_GROUP_CONFIG =
      ImmutableMap.of(TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES, "1600");

  @Test
  void geometryMetricsIncludeOptionalBounds() throws IOException {
    Types.GeometryType geometryType = Types.GeometryType.crs84();
    Schema schema =
        new Schema(optional(1, "geom_m", geometryType), optional(2, "geom_zm", geometryType));
    Record first = GenericRecord.create(schema);
    first.setField("geom_m", wkbPoint(2001, 1, 2, 7));
    first.setField("geom_zm", wkbPoint(3001, 1, 2, 100, 10));
    Record second = GenericRecord.create(schema);
    second.setField("geom_m", wkbPoint(2001, 3, 4, -2));
    second.setField("geom_zm", wkbPoint(3001, 3, 4, -50, 20));

    Metrics metrics = getMetrics(schema, first, second, GenericRecord.create(schema));

    assertThat(
            Conversions.<GeospatialBound>fromByteBuffer(geometryType, metrics.lowerBounds().get(1)))
        .isEqualTo(GeospatialBound.createXYM(1, 2, -2));
    assertThat(
            Conversions.<GeospatialBound>fromByteBuffer(geometryType, metrics.upperBounds().get(1)))
        .isEqualTo(GeospatialBound.createXYM(3, 4, 7));
    assertThat(
            Conversions.<GeospatialBound>fromByteBuffer(geometryType, metrics.lowerBounds().get(2)))
        .isEqualTo(GeospatialBound.createXYZM(1, 2, -50, 10));
    assertThat(
            Conversions.<GeospatialBound>fromByteBuffer(geometryType, metrics.upperBounds().get(2)))
        .isEqualTo(GeospatialBound.createXYZM(3, 4, 100, 20));
  }

  private static ByteBuffer wkbPoint(int typeCode, double... coordinates) {
    ByteBuffer buffer =
        ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + coordinates.length * Double.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    buffer.put((byte) 1);
    buffer.putInt(typeCode);
    for (double coordinate : coordinates) {
      buffer.putDouble(coordinate);
    }

    return buffer.flip();
  }

  @Override
  public FileFormat fileFormat() {
    return FileFormat.PARQUET;
  }

  @Override
  protected OutputFile createOutputFile() throws IOException {
    File tmpFolder = java.nio.file.Files.createTempDirectory(temp, "parquet").toFile();
    String filename = UUID.randomUUID().toString();
    return Files.localOutput(new File(tmpFolder, FileFormat.PARQUET.addExtension(filename)));
  }

  @Override
  public Metrics getMetrics(Schema schema, Record... records) throws IOException {
    return getMetrics(schema, MetricsConfig.getDefault(), records);
  }

  @Override
  public Metrics getMetrics(Schema schema, MetricsConfig metricsConfig, Record... records)
      throws IOException {
    return getMetrics(schema, createOutputFile(), ImmutableMap.of(), metricsConfig, records);
  }

  private Metrics getMetrics(
      Schema schema,
      OutputFile file,
      Map<String, String> properties,
      MetricsConfig metricsConfig,
      Record... records)
      throws IOException {
    FileAppender<Record> writer =
        Parquet.write(file)
            .schema(schema)
            .setAll(properties)
            .createWriterFunc(GenericParquetWriter::create)
            .metricsConfig(metricsConfig)
            .build();
    try (FileAppender<Record> appender = writer) {
      appender.addAll(Lists.newArrayList(records));
    }
    return writer.metrics();
  }

  @Override
  protected Metrics getMetricsForRecordsWithSmallRowGroups(
      Schema schema, OutputFile outputFile, Record... records) throws IOException {
    return getMetrics(
        schema, outputFile, SMALL_ROW_GROUP_CONFIG, MetricsConfig.getDefault(), records);
  }

  @Override
  public int splitCount(InputFile inputFile) throws IOException {
    try (ParquetFileReader reader = ParquetFileReader.open(ParquetIO.file(inputFile))) {
      return reader.getRowGroups().size();
    }
  }

  @Override
  public boolean supportsSmallRowGroups() {
    return true;
  }
}
