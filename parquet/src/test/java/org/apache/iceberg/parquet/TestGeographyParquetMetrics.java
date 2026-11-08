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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Files;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.geospatial.GeospatialBound;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.EdgeAlgorithm;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class TestGeographyParquetMetrics {
  @TempDir private Path temp;

  @ParameterizedTest
  @CsvSource({"-45, 45, 60", "170, -170, 10"})
  void sphericalBoundsRoundTrip(double west, double east, double latitude) throws IOException {
    Types.GeographyType geographyType = Types.GeographyType.of("EPSG:4326");
    Schema schema = new Schema(optional(1, "geog", geographyType));
    ByteBuffer line = lineStringWkb(west, latitude, east, latitude);
    OutputFile file = Files.localOutput(temp.resolve("geography.parquet").toFile());

    DataFile dataFile = write(file, schema, line, null);

    assertThat(dataFile.recordCount()).isEqualTo(2L);
    assertThat(dataFile.valueCounts()).containsEntry(1, 2L);
    assertThat(dataFile.nullValueCounts()).containsEntry(1, 1L);
    assertThat(dataFile.totalBytes()).containsEntry(1, (long) line.remaining());
    GeospatialBound lower =
        Conversions.fromByteBuffer(geographyType, dataFile.lowerBounds().get(1));
    GeospatialBound upper =
        Conversions.fromByteBuffer(geographyType, dataFile.upperBounds().get(1));
    assertThat(lower.x()).isEqualTo(west);
    assertThat(upper.x()).isEqualTo(east);
    assertThat(lower.y()).isLessThanOrEqualTo(latitude);
    double span = east >= west ? east - west : east - west + 360.0;
    double maximumLatitude =
        Math.toDegrees(
            Math.atan(Math.tan(Math.toRadians(latitude)) / Math.cos(Math.toRadians(span / 2))));
    assertThat(upper.y()).isGreaterThanOrEqualTo(maximumLatitude);
    assertThat(upper.y()).isLessThan(maximumLatitude + 1E-4);

    try (CloseableIterable<Record> reader =
        Parquet.read(file.toInputFile())
            .project(schema)
            .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(schema, fileSchema))
            .build()) {
      List<Record> rows = Lists.newArrayList(reader);
      assertThat(rows).hasSize(2);
      assertThat(rows.get(0).getField("geog")).isEqualTo(line);
      assertThat(rows.get(1).getField("geog")).isNull();
    }
  }

  @ParameterizedTest
  @EnumSource(value = EdgeAlgorithm.class, names = "SPHERICAL", mode = EnumSource.Mode.EXCLUDE)
  void nonSphericalAlgorithmsHaveNoBounds(EdgeAlgorithm algorithm) throws IOException {
    Schema schema = new Schema(optional(1, "geog", Types.GeographyType.of("EPSG:4326", algorithm)));
    ByteBuffer line = lineStringWkb(-45.0, 60.0, 45.0, 60.0);
    OutputFile file = Files.localOutput(temp.resolve("geography.parquet").toFile());

    DataFile dataFile = write(file, schema, line, null);

    assertThat(dataFile.recordCount()).isEqualTo(2L);
    assertThat(dataFile.valueCounts()).containsEntry(1, 2L);
    assertThat(dataFile.nullValueCounts()).containsEntry(1, 1L);
    assertThat(dataFile.totalBytes()).containsEntry(1, (long) line.remaining());
    assertThat(dataFile.lowerBounds()).isEmpty();
    assertThat(dataFile.upperBounds()).isEmpty();
  }

  @Test
  void malformedValueSuppressesFileBounds() throws IOException {
    Schema schema = new Schema(optional(1, "geog", Types.GeographyType.crs84()));
    ByteBuffer line = lineStringWkb(-45.0, 60.0, 45.0, 60.0);
    ByteBuffer malformed = ByteBuffer.wrap(new byte[] {1, 2, 3});
    OutputFile file = Files.localOutput(temp.resolve("geography.parquet").toFile());

    DataFile dataFile = write(file, schema, line, malformed, null);

    assertThat(dataFile.valueCounts()).containsEntry(1, 3L);
    assertThat(dataFile.nullValueCounts()).containsEntry(1, 1L);
    assertThat(dataFile.totalBytes())
        .containsEntry(1, (long) line.remaining() + malformed.remaining());
    assertThat(dataFile.lowerBounds()).isEmpty();
    assertThat(dataFile.upperBounds()).isEmpty();
  }

  @Test
  void optionalParentPreservesGeographyMetrics() throws IOException {
    Types.GeographyType geographyType = Types.GeographyType.crs84();
    Types.StructType locationType = Types.StructType.of(optional(2, "geog", geographyType));
    Schema schema = new Schema(optional(1, "location", locationType));
    ByteBuffer line = lineStringWkb(170.0, 0.0, -170.0, 0.0);
    Record locationWithGeography = GenericRecord.create(locationType);
    locationWithGeography.setField("geog", line);
    OutputFile file = Files.localOutput(temp.resolve("nested-geography.parquet").toFile());
    DataWriter<Record> writer =
        Parquet.writeData(file)
            .schema(schema)
            .createWriterFunc(GenericParquetWriter::create)
            .withSpec(PartitionSpec.unpartitioned())
            .build();

    try (writer) {
      for (Record location :
          new Record[] {locationWithGeography, GenericRecord.create(locationType), null}) {
        Record record = GenericRecord.create(schema);
        record.setField("location", location);
        writer.write(record);
      }
    }

    DataFile dataFile = writer.toDataFile();
    assertThat(dataFile.recordCount()).isEqualTo(3L);
    assertThat(dataFile.valueCounts()).containsEntry(2, 3L);
    assertThat(dataFile.nullValueCounts()).containsEntry(2, 2L);
    assertThat(dataFile.totalBytes()).containsEntry(2, (long) line.remaining());
    assertThat(
            Conversions.<GeospatialBound>fromByteBuffer(
                geographyType, dataFile.lowerBounds().get(2)))
        .isEqualTo(GeospatialBound.createXY(170.0, 0.0));
    assertThat(
            Conversions.<GeospatialBound>fromByteBuffer(
                geographyType, dataFile.upperBounds().get(2)))
        .isEqualTo(GeospatialBound.createXY(-170.0, 0.0));

    try (CloseableIterable<Record> reader =
        Parquet.read(file.toInputFile())
            .project(schema)
            .createReaderFunc(fileSchema -> GenericParquetReaders.buildReader(schema, fileSchema))
            .build()) {
      List<Record> rows = Lists.newArrayList(reader);
      assertThat(rows).hasSize(3);
      assertThat(((Record) rows.get(0).getField("location")).getField("geog")).isEqualTo(line);
      assertThat(((Record) rows.get(1).getField("location")).getField("geog")).isNull();
      assertThat(rows.get(2).getField("location")).isNull();
    }
  }

  private static DataFile write(OutputFile file, Schema schema, ByteBuffer... values)
      throws IOException {
    DataWriter<Record> writer =
        Parquet.writeData(file)
            .schema(schema)
            .createWriterFunc(GenericParquetWriter::create)
            .withSpec(PartitionSpec.unpartitioned())
            .build();
    try (writer) {
      for (ByteBuffer value : values) {
        Record record = GenericRecord.create(schema);
        record.setField("geog", value);
        writer.write(record);
      }
    }

    return writer.toDataFile();
  }

  private static ByteBuffer lineStringWkb(
      double firstX, double firstY, double secondX, double secondY) {
    return ByteBuffer.allocate(41)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put((byte) 1)
        .putInt(2)
        .putInt(2)
        .putDouble(firstX)
        .putDouble(firstY)
        .putDouble(secondX)
        .putDouble(secondY)
        .flip();
  }
}
