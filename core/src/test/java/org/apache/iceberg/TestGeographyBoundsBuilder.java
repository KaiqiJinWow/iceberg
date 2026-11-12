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
import java.util.Arrays;
import java.util.stream.Stream;
import org.apache.iceberg.geospatial.BoundingBox;
import org.apache.iceberg.geospatial.GeospatialBound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TestGeographyBoundsBuilder {
  private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
  private static final ByteOrder BE = ByteOrder.BIG_ENDIAN;

  @ParameterizedTest(name = "{0}")
  @MethodSource("shapeCases")
  void boundsAcrossSupportedShapes(String description, ByteBuffer wkb, BoundingBox expected) {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(wkb);

    assertThat(builder.build()).as(description).isEqualTo(expected);
  }

  @Test
  void coversInteriorLatitudeOfGreatCircleArc() {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(line(0, -45, 60, 45, 60));
    double vertexLatitude =
        Math.toDegrees(Math.atan(Math.tan(Math.toRadians(60)) / Math.cos(Math.toRadians(45))));

    BoundingBox box = builder.build();
    assertThat(box.min()).isEqualTo(GeospatialBound.createXY(-45, 60));
    assertThat(box.max().x()).isEqualTo(45);
    assertThat(box.max().y()).isBetween(vertexLatitude, vertexLatitude + 0.0001);
  }

  @Test
  void boundsAcrossPointsWithIndependentMissingDimensions() {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(point(LE, 0, 12, Double.NaN));
    builder.addValue(point(LE, 0, Double.NaN, 34));
    builder.addValue(point(LE, 0, Double.NaN, Double.NaN));

    assertThat(builder.build()).isEqualTo(box(12, 34, 12, 34));
  }

  @Test
  void noBoundsWhenOneDimensionIsMissing() {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(point(LE, 0, 12, Double.NaN));

    assertThat(builder.build()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void readsIsoDimensionsWithinNestedGeographies(int group) {
    int dimensions = group == 0 ? 2 : group == 3 ? 4 : 3;
    double[] points = new double[dimensions * 2];
    Arrays.fill(points, Double.NaN);
    points[0] = 170;
    points[1] = 0;
    points[dimensions] = -170;
    points[dimensions + 1] = 0;
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(
        collection(
            LE,
            7 + 1000 * group,
            point(BE, group, Arrays.copyOf(points, dimensions)),
            collection(BE, 5 + 1000 * group, line(group, points))));

    BoundingBox result = builder.build();
    assertThat(result).isEqualTo(box(170, 0, -170, 0));
    assertThat(result.min().hasZ()).isFalse();
    assertThat(result.min().hasM()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void polygonRingClosesOnXYWithDifferentExtraOrdinates(int group) {
    int dimensions = group == 3 ? 4 : 3;
    double[] ring = new double[4 * dimensions];
    ring[dimensions + 1] = 10;
    ring[2 * dimensions] = 10;
    ring[3 * dimensions + 2] = 123;
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(polygon(group, ring));

    assertThat(builder.build()).isEqualTo(box(-180, -90, 180, 90));
  }

  @Test
  void preservesInputBufferStateForSlicedDirectReadOnlyBuffer() {
    ByteBuffer wkb = point(BE, 0, 12, 34);
    ByteBuffer direct = ByteBuffer.allocateDirect(wkb.remaining() + 13).order(LE);
    direct.position(7);
    direct.put(wkb);
    direct.flip();
    direct.position(7);
    ByteBuffer input = direct.asReadOnlyBuffer().order(LE);
    int position = input.position();
    int limit = input.limit();
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();

    builder.addValue(input);

    assertThat(builder.build()).isEqualTo(box(12, 34, 12, 34));
    assertThat(input.position()).isEqualTo(position);
    assertThat(input.limit()).isEqualTo(limit);
    assertThat(input.order()).isEqualTo(LE);
  }

  @Test
  void preservesInputBufferStateWhenMalformedWkbSuppressesBounds() {
    ByteBuffer truncated = point(BE, 0, 12, 34).limit(8);
    ByteBuffer direct = ByteBuffer.allocateDirect(truncated.remaining() + 13).order(LE);
    direct.position(7);
    direct.put(truncated);
    direct.flip();
    direct.position(7);
    ByteBuffer input = direct.asReadOnlyBuffer().order(LE);
    int position = input.position();
    int limit = input.limit();
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();

    builder.addValue(input);

    assertThat(builder.build()).isNull();
    assertThat(input.position()).isEqualTo(position);
    assertThat(input.limit()).isEqualTo(limit);
    assertThat(input.order()).isEqualTo(LE);
  }

  @Test
  void truncatedNestedChildSuppressesBoundsFromValidSibling() {
    ByteBuffer truncatedLine = line(3, 10, 20, 30, 40, 11, 21, 31, 41);
    truncatedLine.limit(truncatedLine.limit() - Double.BYTES);
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(collection(BE, 3007, point(LE, 3, 1, 2, 3, 4), truncatedLine));

    assertThat(builder.build()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unboundableCases")
  void unboundableValueSuppressesFileBounds(String description, ByteBuffer value) {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(point(LE, 0, 1, 2));
    builder.addValue(value);
    builder.addValue(point(LE, 0, 3, 4));

    assertThat(builder.build()).as(description).isNull();
  }

  @Test
  void malformedValueSuppressesBoundsAfterWorldFallback() {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(polygon(0, new double[] {0, 0, 0, 10, 10, 0, 0, 0}));
    builder.addValue(ByteBuffer.wrap(new byte[] {1, 1}));

    assertThat(builder.build()).isNull();
  }

  @Test
  void invalidCoordinateCannotBeRevivedByPolygonWorldFallback() {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(polygon(0, new double[] {181, 0, 0, 10, 10, 0, 181, 0}));
    builder.addValue(polygon(0, new double[] {0, 0, 0, 10, 10, 0, 0, 0}));

    assertThat(builder.build()).isNull();
  }

  @Test
  void invalidCoordinateAfterWorldFallbackSuppressesBounds() {
    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(polygon(0, new double[] {0, 0, 0, 10, 10, 0, 0, 0}));
    builder.addValue(point(LE, 0, 0, 91));

    assertThat(builder.build()).isNull();
  }

  @Test
  void deeplyNestedCollectionSuppressesOptionalBounds() {
    ByteBuffer value = point(LE, 0, 1, 2);
    for (int i = 0; i < 100; i += 1) {
      value = collection(LE, 7, value);
    }

    GeographyBoundsBuilder builder = new GeographyBoundsBuilder();
    builder.addValue(value);

    assertThat(builder.build()).isNull();
  }

  private static Stream<Arguments> shapeCases() {
    BoundingBox world = box(-180, -90, 180, 90);
    return Stream.of(
        Arguments.of("point", point(LE, 0, 1, 2), box(1, 2, 1, 2)),
        Arguments.of("big-endian point", point(BE, 0, 1, 2), box(1, 2, 1, 2)),
        Arguments.of("empty point", point(LE, 0, Double.NaN, Double.NaN), null),
        Arguments.of("line crossing antimeridian", line(0, 170, 0, -170, 0), box(170, 0, -170, 0)),
        Arguments.of("empty line", line(0), null),
        Arguments.of("polygon", polygon(0, new double[] {0, 0, 0, 10, 10, 0, 0, 0}), world),
        Arguments.of(
            "polygon containing a pole",
            polygon(0, new double[] {-120, 80, 0, 80, 120, 80, -120, 80}),
            world),
        Arguments.of("empty polygon", polygon(0), null),
        Arguments.of("empty polygon ring", polygon(0, new double[0]), null),
        Arguments.of(
            "mixed-endian multipoint",
            collection(BE, 4, point(LE, 0, 170, 0), point(BE, 0, -170, 0)),
            box(170, 0, -170, 0)),
        Arguments.of(
            "multiline",
            collection(LE, 5, line(0, 170, 0, 175, 0), line(0, -175, 0, -170, 0)),
            box(170, 0, -170, 0)),
        Arguments.of(
            "multipolygon",
            collection(LE, 6, polygon(0), polygon(0, new double[] {0, 0, 0, 10, 10, 0, 0, 0})),
            world),
        Arguments.of(
            "nested collection",
            collection(
                LE,
                7,
                point(LE, 0, 170, 0),
                collection(BE, 7, line(0, 175, 0, -170, 0), polygon(0))),
            box(170, 0, -170, 0)),
        Arguments.of(
            "collection with polygon",
            collection(
                LE, 7, point(LE, 0, 1, 2), polygon(0, new double[] {0, 0, 0, 10, 10, 0, 0, 0})),
            world));
  }

  private static Stream<Arguments> unboundableCases() {
    ByteBuffer invalidOrder = point(LE, 0, 1, 2);
    invalidOrder.put(0, (byte) 2);
    ByteBuffer unsupported = ByteBuffer.allocate(5).order(LE).put((byte) 1).putInt(15).flip();
    ByteBuffer ewkb = ByteBuffer.allocate(5).order(LE).put((byte) 1).putInt(0x20000001).flip();
    ByteBuffer hugeCount =
        ByteBuffer.allocate(9).order(LE).put((byte) 1).putInt(2).putInt(-1).flip();
    ByteBuffer trailing = ByteBuffer.allocate(22).put(point(LE, 0, 1, 2)).put((byte) 0).flip();
    return Stream.of(
        Arguments.of("invalid byte order", invalidOrder),
        Arguments.of("unsupported geometry", unsupported),
        Arguments.of("EWKB", ewkb),
        Arguments.of("truncated point", point(LE, 0, 1, 2).limit(8)),
        Arguments.of("impossible element count", hugeCount),
        Arguments.of("trailing bytes", trailing),
        Arguments.of("wrong multi child type", collection(LE, 4, line(0, 1, 2, 3, 4))),
        Arguments.of("wrong child dimensions", collection(LE, 1004, point(LE, 0, 1, 2))),
        Arguments.of("open polygon ring", polygon(0, new double[] {0, 0, 0, 10, 10, 0, 1, 1})),
        Arguments.of("short polygon ring", polygon(0, new double[] {0, 0, 0, 10, 0, 0})),
        Arguments.of("incomplete line coordinate", line(0, 1, 2, 3, Double.NaN)),
        Arguments.of("empty point in line", line(0, 1, 2, Double.NaN, Double.NaN, 3, 4)),
        Arguments.of(
            "incomplete ring coordinate",
            polygon(0, new double[] {0, 0, Double.NaN, 10, 10, 0, 0, 0})),
        Arguments.of("invalid longitude", point(LE, 0, 181, 0)),
        Arguments.of("infinite latitude", point(LE, 0, 0, Double.POSITIVE_INFINITY)));
  }

  private static BoundingBox box(double west, double south, double east, double north) {
    return new BoundingBox(
        GeospatialBound.createXY(west, south), GeospatialBound.createXY(east, north));
  }

  private static ByteBuffer point(ByteOrder order, int group, double... coordinates) {
    ByteBuffer result = header(order, 1 + 1000 * group, coordinates.length * Double.BYTES);
    for (double coordinate : coordinates) {
      result.putDouble(coordinate);
    }
    return result.flip();
  }

  private static ByteBuffer line(int group, double... coordinates) {
    int dimensions = group == 0 ? 2 : group == 3 ? 4 : 3;
    ByteBuffer result =
        header(LE, 2 + 1000 * group, Integer.BYTES + coordinates.length * Double.BYTES);
    result.putInt(coordinates.length / dimensions);
    for (double coordinate : coordinates) {
      result.putDouble(coordinate);
    }
    return result.flip();
  }

  private static ByteBuffer polygon(int group, double[]... rings) {
    int dimensions = group == 0 ? 2 : group == 3 ? 4 : 3;
    int size = Integer.BYTES;
    for (double[] ring : rings) {
      size += Integer.BYTES + ring.length * Double.BYTES;
    }
    ByteBuffer result = header(LE, 3 + 1000 * group, size);
    result.putInt(rings.length);
    for (double[] ring : rings) {
      result.putInt(ring.length / dimensions);
      for (double coordinate : ring) {
        result.putDouble(coordinate);
      }
    }
    return result.flip();
  }

  private static ByteBuffer collection(ByteOrder order, int type, ByteBuffer... children) {
    int size = Integer.BYTES;
    for (ByteBuffer child : children) {
      size += child.remaining();
    }
    ByteBuffer result = header(order, type, size);
    result.putInt(children.length);
    for (ByteBuffer child : children) {
      result.put(child.duplicate());
    }
    return result.flip();
  }

  private static ByteBuffer header(ByteOrder order, int type, int size) {
    return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + size)
        .order(order)
        .put((byte) (order == LE ? 1 : 0))
        .putInt(type);
  }
}
