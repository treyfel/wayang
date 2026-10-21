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

    INTERSECTS("INTERSECTS", "ST_Intersects", Geometry::intersects, PreparedGeometry::intersects),
    CONTAINS("CONTAINS", "ST_Contains", Geometry::contains, PreparedGeometry::within),
    WITHIN("WITHIN", "ST_Within", Geometry::within, PreparedGeometry::contains),
    TOUCHES("TOUCHES", "ST_Touches", Geometry::touches, PreparedGeometry::touches),
    OVERLAPS("OVERLAPS", "ST_Overlaps", Geometry::overlaps, PreparedGeometry::overlaps),
    CROSSES("CROSSES", "ST_Crosses", Geometry::crosses, PreparedGeometry::crosses),
    EQUALS("EQUALS", "ST_Equals", Geometry::equalsTopo, null);

    private final String opName;
    private final String sqlFunctionName;
    private final BiPredicate<Geometry, Geometry> predicate;
    private final BiPredicate<PreparedGeometry, Geometry> preparedPredicate;

    JtsSpatialPredicate(String opName,
                        String sqlFunctionName,
                        BiPredicate<Geometry, Geometry> predicate,
                        BiPredicate<PreparedGeometry, Geometry> preparedPredicate) {
        this.opName = opName;
        this.sqlFunctionName = sqlFunctionName;
        this.predicate = predicate;
        this.preparedPredicate = preparedPredicate;
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
     * Fixes the reference geometry and prepares it once, so that testing many candidates against
     * it does not re-process its edges on every call. Equivalent to {@link #test} with that
     * reference.
     *
     * @param reference the reference geometry
     * @return a predicate over candidate geometries
     */
    public Predicate<Geometry> against(Geometry reference) {
        if (preparedPredicate == null) {
            return candidate -> predicate.test(candidate, reference);
        }
        final PreparedGeometry prepared = PreparedGeometryFactory.prepare(reference);
        return candidate -> preparedPredicate.test(prepared, candidate);
    }

    public String toSql(String columnExpr, String geomLiteral) {
        return String.format("%s(%s, %s)", this.sqlFunctionName, columnExpr, geomLiteral);
    }

    public String toSql(String leftTable, String leftKey, String rightTable, String rightKey) {
        return String.format("%s(%s.%s, %s.%s)", this.sqlFunctionName, leftTable, leftKey, rightTable, rightKey);
    }
}
