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
import org.apache.iceberg.geospatial.BoundingBox;
import org.apache.iceberg.geospatial.GeospatialBound;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.types.EdgeAlgorithm;
import org.apache.iceberg.types.Types;

/** Field-level counts, WKB size, and conservative XY bounds for a geography column. */
public class GeographyFieldMetrics extends FieldMetrics<GeospatialBound> {
  private GeographyFieldMetrics(
      int id,
      long valueCount,
      GeospatialBound lowerBound,
      GeospatialBound upperBound,
      Types.GeographyType type,
      long totalBytes) {
    super(id, valueCount, 0L, -1L, lowerBound, upperBound, type, totalBytes);
  }

  /** Builds geography metrics, omitting bounds for unsupported edge-interpolation algorithms. */
  public static class Builder {
    private final int id;
    private final Types.GeographyType geographyType;
    private final GeographyBoundsBuilder boundsBuilder;
    private long valueCount = 0;
    private long totalBytes = 0;

    /** Creates a collector for the declared geography type of a field. */
    public Builder(int id, Types.GeographyType geographyType) {
      Preconditions.checkArgument(geographyType != null, "Invalid geography type: null");
      this.id = id;
      this.geographyType = geographyType;
      this.boundsBuilder =
          geographyType.algorithm() == EdgeAlgorithm.SPHERICAL
              ? new GeographyBoundsBuilder()
              : null;
    }

    /** Adds a non-null WKB value without changing the buffer's position or limit. */
    public void addValue(ByteBuffer wkb) {
      Preconditions.checkArgument(wkb != null, "Invalid WKB buffer: null");
      valueCount += 1;
      totalBytes += wkb.remaining();
      if (boundsBuilder != null) {
        boundsBuilder.addValue(wkb);
      }
    }

    /**
     * Returns the accumulated metrics, retaining counts and size when bounds cannot be computed.
     */
    public GeographyFieldMetrics build() {
      BoundingBox box = boundsBuilder == null ? null : boundsBuilder.build();
      return new GeographyFieldMetrics(
          id,
          valueCount,
          box == null ? null : box.min(),
          box == null ? null : box.max(),
          geographyType,
          totalBytes);
    }
  }
}
