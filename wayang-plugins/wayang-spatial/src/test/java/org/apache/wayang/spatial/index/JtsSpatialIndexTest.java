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
package org.apache.wayang.spatial.index;

import org.apache.wayang.core.api.exception.WayangException;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test suite for {@link JtsSpatialIndex}.
 */
class JtsSpatialIndexTest {

    private static JtsSpatialIndex<WayangGeometry> index(List<WayangGeometry> geometries) {
        return JtsSpatialIndex.build(geometries.iterator(), g -> g);
    }

    private static List<WayangGeometry> geometries(String... wkts) {
        List<WayangGeometry> list = new ArrayList<>();
        for (String wkt : wkts) {
            list.add(WayangGeometry.fromStringInput(wkt));
        }
        return list;
    }

    private static List<Double> distances(List<Tuple<Double, WayangGeometry>> neighbours) {
        return neighbours.stream().map(t -> t.field0).collect(Collectors.toList());
    }

    @Test
    void testMatchesBruteForceOnRandomData() {
        Random random = new Random(42);
        List<WayangGeometry> data = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            double x = random.nextDouble() * 1000, y = random.nextDouble() * 1000;
            if (i % 3 == 0) {
                double w = random.nextDouble() * 20, h = random.nextDouble() * 20;
                data.add(WayangGeometry.fromStringInput(String.format(java.util.Locale.ROOT,
                        "POLYGON ((%f %f, %f %f, %f %f, %f %f, %f %f))", x, y, x + w, y, x + w, y + h, x, y + h, x, y)));
            } else {
                data.add(WayangGeometry.fromStringInput(String.format(java.util.Locale.ROOT, "POINT (%f %f)", x, y)));
            }
        }
        JtsSpatialIndex<WayangGeometry> index = index(data);
        assertEquals(data.size(), index.size());

        for (int q = 0; q < 50; q++) {
            WayangGeometry reference = WayangGeometry.fromStringInput(String.format(java.util.Locale.ROOT,
                    "POINT (%f %f)", random.nextDouble() * 1000, random.nextDouble() * 1000));
            int k = 1 + random.nextInt(40);
            List<Double> expected = data.stream()
                    .map(g -> reference.getGeometry().distance(g.getGeometry()))
                    .sorted()
                    .limit(k)
                    .collect(Collectors.toList());
            assertEquals(expected, distances(index.nearestNeighbours(reference, k)));
        }
    }

    @Test
    void testKLargerThanIndexReturnsEverythingSorted() {
        JtsSpatialIndex<WayangGeometry> index = index(geometries("POINT (3 0)", "POINT (1 0)", "POINT (2 0)"));

        List<Tuple<Double, WayangGeometry>> result =
                index.nearestNeighbours(WayangGeometry.fromStringInput("POINT (0 0)"), Integer.MAX_VALUE);

        assertEquals(Arrays.asList(1d, 2d, 3d), distances(result));
    }

    @Test
    void testEmptyIndexAndZeroK() {
        WayangGeometry reference = WayangGeometry.fromStringInput("POINT (0 0)");
        assertTrue(index(new ArrayList<>()).nearestNeighbours(reference, 3).isEmpty());
        assertTrue(index(geometries("POINT (1 0)")).nearestNeighbours(reference, 0).isEmpty());
    }

    @Test
    void testNullAndEmptyGeometriesAreNotIndexed() {
        List<WayangGeometry> data = geometries("POINT EMPTY", "POINT (1 0)", "POINT (2 0)");
        data.add(null);

        JtsSpatialIndex<WayangGeometry> index = index(data);

        assertEquals(2, index.size());
        assertEquals(Arrays.asList(1d, 2d),
                distances(index.nearestNeighbours(WayangGeometry.fromStringInput("POINT (0 0)"), 5)));
    }

    @Test
    void testSrids() {
        JtsSpatialIndex<WayangGeometry> index = index(geometries("SRID=4326;POINT (1 0)", "POINT (2 0)"));
        assertEquals(4326, index.getSrid());
        assertEquals(2, index.nearestNeighbours(WayangGeometry.fromStringInput("POINT (0 0)"), 2).size());
        assertThrows(WayangException.class,
                () -> index.nearestNeighbours(WayangGeometry.fromStringInput("SRID=25832;POINT (0 0)"), 1));
        assertThrows(WayangException.class,
                () -> index(geometries("SRID=4326;POINT (1 0)", "SRID=25832;POINT (2 0)")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSurvivesSerialization() throws Exception {
        JtsSpatialIndex<WayangGeometry> index = index(geometries("POINT (5 0)", "POINT (1 0)", "POINT (3 0)"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(index);
        }
        JtsSpatialIndex<WayangGeometry> copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (JtsSpatialIndex<WayangGeometry>) in.readObject();
        }

        assertEquals(Arrays.asList(1d, 3d),
                distances(copy.nearestNeighbours(WayangGeometry.fromStringInput("POINT (0 0)"), 2)));
    }
}
