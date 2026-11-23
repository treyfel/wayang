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

import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public enum JtsSpatialPredicate {

    INTERSECTS("INTERSECTS", "ST_Intersects", Geometry::intersects),
    CONTAINS("CONTAINS", "ST_Contains", Geometry::contains),
    WITHIN("WITHIN", "ST_Within", Geometry::within),
    TOUCHES("TOUCHES", "ST_Touches", Geometry::touches),
    OVERLAPS("OVERLAPS", "ST_Overlaps", Geometry::overlaps),
    CROSSES("CROSSES", "ST_Crosses", Geometry::crosses),
    EQUALS("EQUALS", "ST_Equals", Geometry::equalsTopo);

    private final String opName;
    private final String sqlFunctionName;
    private final BiPredicate<Geometry, Geometry> predicate;

    JtsSpatialPredicate(String opName,
                        String sqlFunctionName,
                        BiPredicate<Geometry, Geometry> predicate) {
        this.opName = opName;
        this.sqlFunctionName = sqlFunctionName;
        this.predicate = predicate;
    }

    public static JtsSpatialPredicate fromString(String opName) {
        return Arrays.stream(values())
                .filter(r -> r.opName.equalsIgnoreCase(opName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported spatial filter type: " + opName));
    }

    /**
     * Convert from the core module's {@link SpatialPredicate} to this enum.
     *
     * @param predicate the spatial predicate
     * @return the corresponding JtsSpatialPredicate
     */
    public static JtsSpatialPredicate of(SpatialPredicate predicate) {
        return switch (predicate) {
            case INTERSECTS -> INTERSECTS;
            case CONTAINS -> CONTAINS;
            case WITHIN -> WITHIN;
            case OVERLAPS -> OVERLAPS;
            case TOUCHES -> TOUCHES;
            case CROSSES -> CROSSES;
            case EQUALS -> EQUALS;
        };
    }

    public boolean test(Geometry candidate, Geometry reference) {
        return predicate.test(candidate, reference);
    }

    /**
     * Fixes the reference geometry of {@link #test(Geometry, Geometry)}, preparing it where JTS can speed up
     * the predicate (it indexes the reference's edges once instead of per candidate). The result is the same
     * as calling {@link #test(Geometry, Geometry)} with that reference.
     *
     * @param reference the geometry that every candidate is tested against
     * @return a predicate on candidates
     */
    public Predicate<Geometry> against(Geometry reference) {
        switch (this) {
            case INTERSECTS: {
                final PreparedGeometry prepared = PreparedGeometryFactory.prepare(reference);
                return prepared::intersects;
            }
            case WITHIN: {
                // candidate.within(reference) is defined as reference.contains(candidate).
                final PreparedGeometry prepared = PreparedGeometryFactory.prepare(reference);
                return prepared::contains;
            }
            default:
                // JTS does not speed up the remaining predicates on a prepared reference.
                return candidate -> this.test(candidate, reference);
        }
    }

    public String toSql(String columnExpr, String geomLiteral) {
        return String.format("%s(%s, %s)", this.sqlFunctionName, columnExpr, geomLiteral);
    }

    public String toSql(String leftTable, String leftKey, String rightTable, String rightKey) {
        return String.format("%s(%s.%s, %s.%s)", this.sqlFunctionName, leftTable, leftKey, rightTable, rightKey);
    }
}
