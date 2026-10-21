/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.wayang.spatial.crs;

import org.apache.wayang.core.api.exception.WayangException;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.util.HashMap;
import java.util.Map;

/**
 * Reprojects JTS geometries between coordinate reference systems.
 *
 * <p>Z and M ordinates pass through untouched: proj4j is 2-D.</p>
 */
public class CrsTransforms {

    private static final CoordinateTransformFactory TRANSFORM_FACTORY = new CoordinateTransformFactory();

    private static final ThreadLocal<Map<Long, CoordinateTransform>> TRANSFORMS =
            ThreadLocal.withInitial(HashMap::new);

    private CrsTransforms() {
    }

    /**
     * Reprojects a geometry, leaving the input untouched.
     *
     * @param geometry   the geometry to reproject, or {@code null}
     * @param sourceSrid the EPSG code of the CRS the geometry's coordinates are in
     * @param targetSrid the EPSG code of the CRS to reproject into
     * @return a new geometry in {@code targetSrid}, or {@code geometry} itself if it is
     * {@code null} or if the two CRSs are the same
     */
    public static Geometry transform(Geometry geometry, int sourceSrid, int targetSrid) {
        if (geometry == null) {
            return null;
        }
        if (sourceSrid == targetSrid) {
            return geometry;
        }

        final CoordinateTransform transform = getTransform(sourceSrid, targetSrid);
        final Geometry result = geometry.copy();
        result.apply(new ReprojectingFilter(transform, sourceSrid, targetSrid));
        result.geometryChanged();
        result.setSRID(targetSrid);
        return result;
    }

    /**
     * @return the transform from {@code sourceSrid} to {@code targetSrid}, cached for this thread
     */
    private static CoordinateTransform getTransform(int sourceSrid, int targetSrid) {
        final long key = (((long) sourceSrid) << 32) | (targetSrid & 0xffffffffL);
        return TRANSFORMS.get().computeIfAbsent(key, ignored ->
                TRANSFORM_FACTORY.createTransform(CrsRegistry.get(sourceSrid), CrsRegistry.get(targetSrid)));
    }

    /**
     * Rewrites every coordinate of a geometry in place. The geometry it is applied to is always a
     * private copy (see {@link #transform}), never user-visible input.
     */
    private static class ReprojectingFilter implements CoordinateSequenceFilter {

        private final CoordinateTransform transform;

        private final int sourceSrid;

        private final int targetSrid;

        private final ProjCoordinate in = new ProjCoordinate();

        private final ProjCoordinate out = new ProjCoordinate();

        ReprojectingFilter(CoordinateTransform transform, int sourceSrid, int targetSrid) {
            this.transform = transform;
            this.sourceSrid = sourceSrid;
            this.targetSrid = targetSrid;
        }

        @Override
        public void filter(CoordinateSequence seq, int i) {
            final double x = seq.getOrdinate(i, CoordinateSequence.X);
            final double y = seq.getOrdinate(i, CoordinateSequence.Y);
            this.in.x = x;
            this.in.y = y;
            this.in.z = Double.NaN;

            this.transform.transform(this.in, this.out);

            if (!Double.isFinite(this.out.x) || !Double.isFinite(this.out.y)) {
                throw new WayangException(String.format(
                        "Cannot transform (%s, %s) from EPSG:%d to EPSG:%d: the result is not a finite "
                                + "coordinate. The point is most likely outside the target CRS's area of use.",
                        x, y, this.sourceSrid, this.targetSrid));
            }

            seq.setOrdinate(i, CoordinateSequence.X, this.out.x);
            seq.setOrdinate(i, CoordinateSequence.Y, this.out.y);
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean isGeometryChanged() {
            return true;
        }
    }
}
