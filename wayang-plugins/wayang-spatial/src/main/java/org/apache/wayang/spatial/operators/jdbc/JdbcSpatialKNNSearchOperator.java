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

package org.apache.wayang.spatial.operators.jdbc;

import org.apache.wayang.basic.operators.SpatialKNNSearchOperator;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.jdbc.compiler.FunctionCompiler;
import org.apache.wayang.jdbc.operators.JdbcExecutionOperator;

import java.sql.Connection;


/**
 * Template for JDBC-based {@link SpatialKNNSearchOperator}.
 * <p>
 * Produces an {@code ORDER BY <column> <-> <reference> LIMIT k} clause. Keeping the reference geometry a
 * constant lets PostGIS answer the query from a GiST index on the column, walking it nearest-first.
 */
public abstract class JdbcSpatialKNNSearchOperator<Type> extends SpatialKNNSearchOperator<Type> implements JdbcExecutionOperator {

    /**
     * Creates a new instance.
     *
     * @param k the number of nearest elements to return
     */
    public JdbcSpatialKNNSearchOperator(Integer k,
                                        FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                        DataSetType<Type> inputClassDatasetType,
                                        SpatialGeometry geometry) {
        super(k, keyExtractor, inputClassDatasetType, geometry);
    }

    public JdbcSpatialKNNSearchOperator(SpatialKNNSearchOperator<Type> that) {
        super(that);
    }

    @Override
    public String createSqlClause(Connection connection, FunctionCompiler compiler) {
        if (this.referenceGeometry == null) {
            throw new IllegalStateException("Geometry for spatial kNN search must not be null.");
        }
        if (this.k == null || this.k < 0) {
            throw new IllegalStateException("k for spatial kNN search must be non-negative, but was " + this.k + ".");
        }

        // Column expression (e.g. "geom" or "t.geom")
        final String columnExpr = this.keyDescriptor.getSqlImplementation().getField1();

        // The reference must carry the column's SRID, or PostGIS rejects the mixed-SRID comparison.
        // toWKT() already returns EWKT ("SRID=...;...") for geometries that have an SRID.
        final String wkt = this.referenceGeometry.toWKT();
        final int srid = this.referenceGeometry.getSrid();
        final String geomLiteral;
        if (wkt.regionMatches(true, 0, "SRID=", 0, 5)) {
            geomLiteral = String.format("ST_GeomFromEWKT('%s')", wkt);
        } else if (srid > 0) {
            geomLiteral = String.format("ST_GeomFromText('%s', %d)", wkt, srid);
        } else {
            geomLiteral = String.format("ST_GeomFromText('%s')", wkt);
        }

        return String.format(" ORDER BY %s <-> %s LIMIT %d", columnExpr, geomLiteral, this.k);
    }
}
