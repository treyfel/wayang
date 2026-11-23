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

package org.apache.wayang.spatial.function;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that {@link JtsSpatialPredicate#against(Geometry)}, which prepares the reference geometry where it
 * helps, answers exactly like {@link JtsSpatialPredicate#test(Geometry, Geometry)}.
 */
class JtsSpatialPredicateTest {

    private static final String[] WKTS = {
            // Non-convex reference-like polygon (an L shape).
            "POLYGON ((0 0, 4 0, 4 1, 1 1, 1 4, 0 4, 0 0))",
            "POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0))",
            // Polygons: inside, overlapping, touching, disjoint, equal to the L shape, in the L's notch.
            "POLYGON ((0.2 0.2, 0.8 0.2, 0.8 0.8, 0.2 0.8, 0.2 0.2))",
            "POLYGON ((3 0.5, 5 0.5, 5 2, 3 2, 3 0.5))",
            "POLYGON ((4 0, 5 0, 5 1, 4 1, 4 0))",
            "POLYGON ((10 10, 11 10, 11 11, 10 11, 10 10))",
            "POLYGON ((0 0, 4 0, 4 1, 1 1, 1 4, 0 4, 0 0))",
            "POLYGON ((2 2, 3 2, 3 3, 2 3, 2 2))",
            // Points: interior, boundary, vertex, notch, outside.
            "POINT (0.5 0.5)", "POINT (2 0)", "POINT (4 1)", "POINT (2 2)", "POINT (9 9)",
            // Lines: crossing the boundary, inside, along the boundary, outside.
            "LINESTRING (0.5 0.5, 2 2)", "LINESTRING (0.2 0.5, 0.8 0.5)", "LINESTRING (0 0, 4 0)",
            "LINESTRING (5 5, 6 6)",
    };

    private static List<Geometry> geometries() throws ParseException {
        final WKTReader reader = new WKTReader();
        final List<Geometry> geometries = new ArrayList<>();
        for (String wkt : WKTS) geometries.add(reader.read(wkt));
        return geometries;
    }

    @Test
    void preparedReferenceAnswersLikePlainTest() throws ParseException {
        final List<Geometry> geometries = geometries();
        for (JtsSpatialPredicate predicate : JtsSpatialPredicate.values()) {
            for (Geometry reference : geometries) {
                final Predicate<Geometry> prepared = predicate.against(reference);
                for (Geometry candidate : geometries) {
                    assertEquals(predicate.test(candidate, reference), prepared.test(candidate),
                            () -> predicate + ": " + candidate + " vs reference " + reference);
                }
            }
        }
    }
}
