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

import org.apache.wayang.basic.data.Record;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.jdbc.platform.JdbcPlatformTemplate;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.apache.wayang.spatial.test.HsqldbPlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test suite for {@link JdbcSpatialKNNSearchOperator}.
 * Verifies the generated {@code ORDER BY ... <-> ... LIMIT k} clause.
 */
class JdbcSpatialKNNSearchOperatorTest {

    /**
     * Concrete subclass for testing against HSQLDB.
     */
    private static class TestJdbcSpatialKNNSearchOperator<Type> extends JdbcSpatialKNNSearchOperator<Type> {

        TestJdbcSpatialKNNSearchOperator(Integer k,
                                         FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                         DataSetType<Type> inputClassDatasetType,
                                         SpatialGeometry geometry) {
            super(k, keyExtractor, inputClassDatasetType, geometry);
        }

        @Override
        public JdbcPlatformTemplate getPlatform() {
            return HsqldbPlatform.getInstance();
        }
    }

    @Test
    void testPlainWktUsesGeomFromText() {
        String clause = createClause(3, new WayangGeometry("POINT (583571.9 4506714.3)"));

        assertEquals(" ORDER BY geom <-> ST_GeomFromText('POINT (583571.9 4506714.3)') LIMIT 3", clause);
    }

    @Test
    void testEwktKeepsItsSrid() {
        String clause = createClause(5, WayangGeometry.fromStringInput("SRID=26918;POINT (583571.9 4506714.3)"));

        assertEquals(" ORDER BY geom <-> ST_GeomFromEWKT('SRID=26918;POINT (583571.9 4506714.3)') LIMIT 5", clause);
    }

    @Test
    void testMissingGeometryIsRejected() {
        assertThrows(IllegalStateException.class, () -> createClause(3, null));
    }

    @Test
    void testNegativeKIsRejected() {
        assertThrows(IllegalStateException.class, () -> createClause(-1, new WayangGeometry("POINT (0 0)")));
    }

    private String createClause(Integer k, WayangGeometry referenceGeometry) {
        TestJdbcSpatialKNNSearchOperator<Record> knnOp = new TestJdbcSpatialKNNSearchOperator<>(
                k,
                record -> WayangGeometry.fromStringInput((String) record.getField(1)),
                DataSetType.createDefault(Record.class),
                referenceGeometry
        );
        knnOp.getKeyDescriptor().withSqlImplementation("testGeom", "geom");
        return knnOp.createSqlClause(null, null);
    }
}
