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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.iceberg.geospatial.BoundingBox;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

/**
 * Accumulates conservative XY bounds for spherical geography encoded as ISO WKB.
 *
 * <p>Nonempty polygons use world bounds because their interiors can contain a pole or cover the
 * larger side of a spherical ring. Malformed or unsupported WKB suppresses bounds for the file.
 */
class GeographyBoundsBuilder {
  private static final int TYPE_POINT = 1;
  private static final int TYPE_LINE_STRING = 2;
  private static final int TYPE_POLYGON = 3;
  private static final int TYPE_MULTI_POINT = 4;
  private static final int TYPE_MULTI_LINE_STRING = 5;
  private static final int TYPE_MULTI_POLYGON = 6;
  private static final int TYPE_GEOMETRY_COLLECTION = 7;
  private static final int ANY_GEOMETRY = 0;
  private static final int ANY_DIMENSION = -1;
  private static final int DIMENSION_DIVISOR = 1000;
  private static final int XYZM_GROUP = 3;
  private static final int MIN_RING_POINTS = 4;
  private static final int MAX_NESTING_DEPTH = 64;

  private final SphericalGeographyBoundsBuilder bounds = new SphericalGeographyBoundsBuilder();
  private boolean incomplete = false;

  void addValue(ByteBuffer wkb) {
    Preconditions.checkArgument(wkb != null, "Invalid WKB buffer: null");
    if (incomplete) {
      return;
    }

    ByteBuffer buffer = wkb.duplicate();
    try {
      readGeometry(buffer, ANY_GEOMETRY, ANY_DIMENSION, 0);
      if (buffer.hasRemaining()) {
        incomplete = true;
      }
    } catch (InvalidWkbException e) {
      incomplete = true;
    }
  }

  BoundingBox build() {
    return incomplete ? null : bounds.build();
  }

  private void readGeometry(ByteBuffer buffer, int expectedType, int expectedDimension, int depth) {
    checkWkb(depth <= MAX_NESTING_DEPTH);
    checkRemaining(buffer, Byte.BYTES + Integer.BYTES);
    byte order = buffer.get();
    if (order == 0) {
      buffer.order(ByteOrder.BIG_ENDIAN);
    } else if (order == 1) {
      buffer.order(ByteOrder.LITTLE_ENDIAN);
    } else {
      throw new InvalidWkbException();
    }

    long typeCode = Integer.toUnsignedLong(buffer.getInt());
    int dimensionGroup = (int) (typeCode / DIMENSION_DIVISOR);
    int geometryType = (int) (typeCode % DIMENSION_DIVISOR);
    checkWkb(
        geometryType >= TYPE_POINT
            && geometryType <= TYPE_GEOMETRY_COLLECTION
            && dimensionGroup <= XYZM_GROUP);
    checkWkb(expectedType == ANY_GEOMETRY || geometryType == expectedType);
    checkWkb(expectedDimension == ANY_DIMENSION || dimensionGroup == expectedDimension);
    int numDimensions =
        switch (dimensionGroup) {
          case 0 -> 2;
          case 1, 2 -> 3;
          default -> 4;
        };

    switch (geometryType) {
      case TYPE_POINT -> readPoint(buffer, numDimensions);
      case TYPE_LINE_STRING -> readLine(buffer, numDimensions);
      case TYPE_POLYGON -> readPolygon(buffer, numDimensions);
      case TYPE_MULTI_POINT -> readCollection(buffer, TYPE_POINT, dimensionGroup, depth);
      case TYPE_MULTI_LINE_STRING ->
          readCollection(buffer, TYPE_LINE_STRING, dimensionGroup, depth);
      case TYPE_MULTI_POLYGON -> readCollection(buffer, TYPE_POLYGON, dimensionGroup, depth);
      case TYPE_GEOMETRY_COLLECTION -> readCollection(buffer, ANY_GEOMETRY, dimensionGroup, depth);
    }
  }

  private void readPoint(ByteBuffer buffer, int numDimensions) {
    checkRemaining(buffer, (long) numDimensions * Double.BYTES);
    double longitude = buffer.getDouble();
    double latitude = buffer.getDouble();
    skipExtraOrdinates(buffer, numDimensions);
    bounds.addPoint(longitude, latitude);
  }

  private void readLine(ByteBuffer buffer, int numDimensions) {
    int numPoints = readCount(buffer);
    checkRemaining(buffer, (long) numPoints * numDimensions * Double.BYTES);
    double previousLongitude = Double.NaN;
    double previousLatitude = Double.NaN;
    for (int i = 0; i < numPoints; i += 1) {
      double longitude = buffer.getDouble();
      double latitude = buffer.getDouble();
      skipExtraOrdinates(buffer, numDimensions);
      checkWkb(!Double.isNaN(longitude) && !Double.isNaN(latitude));
      if (i == 0) {
        bounds.addPoint(longitude, latitude);
      } else {
        bounds.addEdge(previousLongitude, previousLatitude, longitude, latitude);
      }

      previousLongitude = longitude;
      previousLatitude = latitude;
    }
  }

  private void readPolygon(ByteBuffer buffer, int numDimensions) {
    int numRings = readCount(buffer);
    for (int i = 0; i < numRings; i += 1) {
      readRing(buffer, numDimensions);
    }
  }

  private void readRing(ByteBuffer buffer, int numDimensions) {
    int numPoints = readCount(buffer);
    checkRemaining(buffer, (long) numPoints * numDimensions * Double.BYTES);
    if (numPoints == 0) {
      return;
    }

    checkWkb(numPoints >= MIN_RING_POINTS);
    bounds.addWorld();
    double firstLongitude = Double.NaN;
    double firstLatitude = Double.NaN;
    double longitude = Double.NaN;
    double latitude = Double.NaN;
    for (int i = 0; i < numPoints; i += 1) {
      longitude = buffer.getDouble();
      latitude = buffer.getDouble();
      skipExtraOrdinates(buffer, numDimensions);
      checkWkb(!Double.isNaN(longitude) && !Double.isNaN(latitude));
      bounds.addPoint(longitude, latitude);
      if (i == 0) {
        firstLongitude = longitude;
        firstLatitude = latitude;
      }
    }

    checkWkb(firstLongitude == longitude && firstLatitude == latitude);
  }

  private void readCollection(
      ByteBuffer buffer, int expectedChildType, int expectedDimension, int depth) {
    int numElements = readCount(buffer);
    for (int i = 0; i < numElements; i += 1) {
      readGeometry(buffer, expectedChildType, expectedDimension, depth + 1);
    }
  }

  private static void skipExtraOrdinates(ByteBuffer buffer, int numDimensions) {
    for (int i = 2; i < numDimensions; i += 1) {
      buffer.getDouble();
    }
  }

  private static int readCount(ByteBuffer buffer) {
    checkRemaining(buffer, Integer.BYTES);
    long count = Integer.toUnsignedLong(buffer.getInt());
    checkWkb(count <= buffer.remaining());
    return (int) count;
  }

  private static void checkRemaining(ByteBuffer buffer, long bytes) {
    checkWkb(buffer.remaining() >= bytes);
  }

  private static void checkWkb(boolean valid) {
    if (!valid) {
      throw new InvalidWkbException();
    }
  }

  private static class InvalidWkbException extends RuntimeException {}
}
