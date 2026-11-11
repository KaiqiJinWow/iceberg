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

import org.apache.iceberg.geospatial.BoundingBox;
import org.apache.iceberg.geospatial.GeospatialBound;

/**
 * Builds an XY bounding box from geography points and minor great-circle edges on a sphere.
 *
 * <p>Bounds may cross the antimeridian, with west greater than east. Pole coordinates contribute
 * only latitude. Without a longitude interval, no bounds are produced. Edges between non-pole
 * points on the antimeridian use -180 degrees. Computed latitude extrema are widened to avoid
 * undercoverage.
 *
 * <p>Points with both ordinates NaN are empty. Incomplete, out-of-range or infinite coordinates,
 * including NaN edge endpoints, permanently suppress bounds. Callers must retain this builder for
 * all values contributing to the bounds. Numerically unresolved near-antipodal edges produce world
 * bounds.
 *
 * <p>Callers must bound polygon interiors separately from their edges.
 */
class SphericalGeographyBoundsBuilder {
  // Azimuths are poorly conditioned as the central angle approaches pi.
  private static final double MIN_NORMAL_LENGTH = 1e-6;

  // A relative margin alone cannot cover underflow when subnormal degrees are converted to radians.
  private static final double LATITUDE_MARGIN = Math.ulp(GeographyConstants.MAX_LATITUDE);
  private static final double LATITUDE_SCALING_FACTOR = 1.0000001;

  private final LongitudeBoundsBuilder longitudes = new LongitudeBoundsBuilder();
  private double minLatitude = Double.POSITIVE_INFINITY;
  private double maxLatitude = Double.NEGATIVE_INFINITY;
  private State state = State.EMPTY;

  void addPoint(double longitude, double latitude) {
    if (Double.isNaN(longitude) && Double.isNaN(latitude)) {
      return;
    }

    includeCoordinate(longitude, latitude);
  }

  void addWorld() {
    if (state != State.INVALID) {
      includeFullWorld();
    }
  }

  void addEdge(double longitude1, double latitude1, double longitude2, double latitude2) {
    boolean onAntimeridian =
        !isPole(latitude1)
            && !isPole(latitude2)
            && Math.abs(longitude1) == GeographyConstants.MAX_LONGITUDE
            && Math.abs(longitude2) == GeographyConstants.MAX_LONGITUDE;
    double startLongitude = onAntimeridian ? GeographyConstants.MIN_LONGITUDE : longitude1;
    double endLongitude = onAntimeridian ? GeographyConstants.MIN_LONGITUDE : longitude2;
    boolean firstIsComplete = includeCoordinate(startLongitude, latitude1);
    boolean secondIsComplete = includeCoordinate(endLongitude, latitude2);
    if (!firstIsComplete || !secondIsComplete || state != State.ACTIVE) {
      return;
    }

    if (addEdgeWithPole(latitude1, latitude2)) {
      return;
    }

    addMinimumLongitudeInterval(startLongitude, endLongitude);
    addInteriorLatitudeExtrema(startLongitude, latitude1, endLongitude, latitude2);
  }

  private boolean addEdgeWithPole(double latitude1, double latitude2) {
    boolean firstIsPole = isPole(latitude1);
    boolean secondIsPole = isPole(latitude2);
    if (firstIsPole && secondIsPole) {
      if (latitude1 != latitude2) {
        includeFullWorld();
      }

      return true;
    }

    return firstIsPole || secondIsPole;
  }

  /*
   * The azimuth and vertex calculation below is adapted from Boost.Geometry 1.82.0:
   * https://github.com/boostorg/geometry/tree/boost-1.82.0/include/boost/geometry
   * strategy/spherical/envelope_segment.hpp, formulas/spherical.hpp,
   * and formulas/vertex_latitude.hpp.
   *
   * Copyright (c) 2017-2020 Oracle and/or its affiliates. (envelope_segment.hpp)
   * Copyright (c) 2016-2020 Oracle and/or its affiliates. (spherical.hpp, vertex_latitude.hpp)
   * Contributed and/or modified by Vissarion Fisikopoulos, on behalf of Oracle
   * Contributed and/or modified by Vissarion Fysikopoulos, on behalf of Oracle
   * Contributed and/or modified by Adam Wulkiewicz, on behalf of Oracle
   *
   * Boost Software License - Version 1.0 - August 17th, 2003
   *
   * Permission is hereby granted, free of charge, to any person or organization
   * obtaining a copy of the software and accompanying documentation covered by
   * this license (the "Software") to use, reproduce, display, distribute,
   * execute, and transmit the Software, and to prepare derivative works of the
   * Software, and to permit third-parties to whom the Software is furnished to
   * do so, all subject to the following:
   *
   * The copyright notices in the Software and this entire statement, including
   * the above license grant, this restriction and the following disclaimer,
   * must be included in all copies of the Software, in whole or in part, and
   * all derivative works of the Software, unless such copies or derivative
   * works are solely in the form of machine-executable object code generated by
   * a source language processor.
   *
   * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
   * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
   * FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT
   * SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE
   * FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE,
   * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
   * DEALINGS IN THE SOFTWARE.
   */
  private void addInteriorLatitudeExtrema(
      double longitude1, double latitude1, double longitude2, double latitude2) {
    if (latitude1 == latitude2
        && (longitude1 == longitude2
            || Math.abs(longitude1 - longitude2) == GeographyConstants.LONGITUDE_SPAN)) {
      return;
    }

    // Order the endpoints along the eastward minor arc. Only these local copies are reordered.
    double startLatitude = latitude1;
    double endLatitude = latitude2;
    double longitudeSpan = longitude2 - longitude1;
    if (longitudeSpan < 0.0) {
      longitudeSpan = -longitudeSpan;
      double temporary = startLatitude;
      startLatitude = endLatitude;
      endLatitude = temporary;
    }
    if (longitudeSpan > GeographyConstants.MAX_LONGITUDE) {
      longitudeSpan = GeographyConstants.LONGITUDE_SPAN - longitudeSpan;
      double temporary = startLatitude;
      startLatitude = endLatitude;
      endLatitude = temporary;
    }

    double startLatitudeRadians = Math.toRadians(startLatitude);
    double endLatitudeRadians = Math.toRadians(endLatitude);
    double sinLatitude1 = Math.sin(startLatitudeRadians);
    double cosLatitude1 = Math.cos(startLatitudeRadians);
    double sinLatitude2 = Math.sin(endLatitudeRadians);
    double cosLatitude2 = Math.cos(endLatitudeRadians);
    double longitudeSpanRadians = Math.toRadians(longitudeSpan);
    double sinLongitudeSpan = Math.sin(longitudeSpanRadians);
    double cosLongitudeSpan = Math.cos(longitudeSpanRadians);
    double azimuthY1 = sinLongitudeSpan * cosLatitude2;
    double azimuthX1 = cosLatitude1 * sinLatitude2 - sinLatitude1 * cosLatitude2 * cosLongitudeSpan;

    // The azimuth vector has length sin(theta). Only a negative dot product identifies
    // nearly antipodal endpoints; short arcs can use the ordinary vertex calculation.
    double normalLength = Math.hypot(azimuthX1, azimuthY1);
    if (normalLength <= MIN_NORMAL_LENGTH) {
      double cosineCentralAngle =
          sinLatitude1 * sinLatitude2 + cosLatitude1 * cosLatitude2 * cosLongitudeSpan;
      if (cosineCentralAngle < 0.0) {
        includeFullWorld();
        return;
      }
    }

    double azimuthY2 = sinLongitudeSpan * cosLatitude1;
    double azimuthX2 = sinLatitude2 * cosLatitude1 * cosLongitudeSpan - cosLatitude2 * sinLatitude1;
    if (!containsLatitudeExtremum(azimuthX1, azimuthX2, startLatitude, endLatitude)) {
      return;
    }

    // This is equivalent to acos(abs(cos(latitude) * sin(azimuth))). The atan2 form
    // retains the small sine of the vertex latitude when its cosine rounds to one.
    double vertexLatitude =
        startLatitude <= endLatitude
            ? vertexLatitude(azimuthX1, azimuthY1, sinLatitude1, cosLatitude1)
            : vertexLatitude(azimuthX2, azimuthY2, sinLatitude2, cosLatitude2);
    // An interior extremum can round to an endpoint, so pad before merging with endpoints.
    double conservativeVertexLatitude =
        Math.min(
            Math.max(LATITUDE_SCALING_FACTOR * vertexLatitude, vertexLatitude + LATITUDE_MARGIN),
            GeographyConstants.MAX_LATITUDE);
    if (startLatitude + endLatitude < 0.0) {
      minLatitude = Math.min(minLatitude, -conservativeVertexLatitude);
    } else {
      maxLatitude = Math.max(maxLatitude, conservativeVertexLatitude);
    }

    if (conservativeVertexLatitude == GeographyConstants.MAX_LATITUDE) {
      // A longitude difference can round to 180 degrees and select the wrong side of a pole.
      longitudes.add(GeographyConstants.MIN_LONGITUDE, GeographyConstants.MAX_LONGITUDE);
    }
  }

  private static double vertexLatitude(
      double azimuthX, double azimuthY, double sinLatitude, double cosLatitude) {
    return Math.toDegrees(
        Math.atan2(Math.hypot(azimuthX, sinLatitude * azimuthY), Math.abs(cosLatitude * azimuthY)));
  }

  BoundingBox build() {
    if (state == State.EMPTY || state == State.INVALID) {
      return null;
    }

    if (state == State.FULL_WORLD) {
      return worldBounds();
    }

    LongitudeBoundsBuilder.Interval longitudeBounds = longitudes.build();
    if (longitudeBounds == null) {
      return null;
    }

    return new BoundingBox(
        GeospatialBound.createXY(longitudeBounds.west(), minLatitude),
        GeospatialBound.createXY(longitudeBounds.east(), maxLatitude));
  }

  private void includeLatitude(double latitude) {
    minLatitude = Math.min(minLatitude, latitude);
    maxLatitude = Math.max(maxLatitude, latitude);
  }

  private void includeFullWorld() {
    minLatitude = GeographyConstants.MIN_LATITUDE;
    maxLatitude = GeographyConstants.MAX_LATITUDE;
    longitudes.add(GeographyConstants.MIN_LONGITUDE, GeographyConstants.MAX_LONGITUDE);
    state = State.FULL_WORLD;
  }

  private static BoundingBox worldBounds() {
    return new BoundingBox(
        GeospatialBound.createXY(GeographyConstants.MIN_LONGITUDE, GeographyConstants.MIN_LATITUDE),
        GeospatialBound.createXY(
            GeographyConstants.MAX_LONGITUDE, GeographyConstants.MAX_LATITUDE));
  }

  private boolean includeCoordinate(double longitude, double latitude) {
    if (state == State.INVALID) {
      return false;
    }

    if (!isInLongitudeRange(longitude) || !isInLatitudeRange(latitude)) {
      state = State.INVALID;
      return false;
    }

    if (state == State.FULL_WORLD) {
      return false;
    }

    includeLatitude(latitude);
    if (!isPole(latitude)) {
      longitudes.add(longitude, longitude);
    }

    state = State.ACTIVE;
    return true;
  }

  private void addMinimumLongitudeInterval(double longitude1, double longitude2) {
    double west = Math.min(longitude1, longitude2);
    double east = Math.max(longitude1, longitude2);
    double directGap = east - west;
    double antimeridianGap = GeographyConstants.LONGITUDE_SPAN - directGap;
    if (antimeridianGap >= directGap) {
      longitudes.add(west, east);
    } else {
      longitudes.add(east, west);
    }
  }

  private static boolean isInLongitudeRange(double longitude) {
    return Double.isFinite(longitude)
        && longitude >= GeographyConstants.MIN_LONGITUDE
        && longitude <= GeographyConstants.MAX_LONGITUDE;
  }

  private static boolean isInLatitudeRange(double latitude) {
    return Double.isFinite(latitude)
        && latitude >= GeographyConstants.MIN_LATITUDE
        && latitude <= GeographyConstants.MAX_LATITUDE;
  }

  private static boolean isPole(double latitude) {
    return Math.abs(latitude) == GeographyConstants.MAX_LATITUDE;
  }

  private static boolean containsLatitudeExtremum(
      double azimuthX1, double azimuthX2, double latitude1, double latitude2) {
    if (latitude1 == 0.0 && latitude2 == 0.0) {
      return false;
    }

    // On an eastward arc, the azimuth crosses pi/2 where its X component crosses zero.
    // Include equality because an interior extremum can round to an endpoint.
    return (azimuthX1 <= 0.0 && azimuthX2 >= 0.0) || (azimuthX2 <= 0.0 && azimuthX1 >= 0.0);
  }

  private enum State {
    EMPTY,
    ACTIVE,
    FULL_WORLD,
    INVALID
  }
}
