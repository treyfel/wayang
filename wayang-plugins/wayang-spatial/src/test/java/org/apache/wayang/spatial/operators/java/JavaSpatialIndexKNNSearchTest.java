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
package org.apache.wayang.spatial.operators.java;

import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.SpatialIndexKNNSearchOperator;
import org.apache.wayang.basic.operators.SpatialIndexOperator;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialIndex;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.java.Java;
import org.apache.wayang.spatial.Spatial;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.apache.wayang.spatial.index.JtsSpatialIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs plans with {@link SpatialIndexOperator} and {@link SpatialIndexKNNSearchOperator} on the Java platform.
 */
class JavaSpatialIndexKNNSearchTest {

    /**
     * Counts key extractions, to check that a shared index is built only once.
     */
    private static final AtomicInteger KEY_EXTRACTIONS = new AtomicInteger();

    private static WayangContext context() {
        return new WayangContext().with(Java.basicPlugin()).with(Spatial.javaPlugin());
    }

    private static List<WayangGeometry> geometries(String... wkts) {
        return Arrays.stream(wkts).map(WayangGeometry::fromStringInput).collect(Collectors.toList());
    }

    private static List<String> wkts(List<WayangGeometry> geometries) {
        return geometries.stream().map(WayangGeometry::getWKT).collect(Collectors.toList());
    }

    @Test
    void testOneIndexServesSeveralQueries() {
        List<WayangGeometry> points = geometries(
                "POINT (0 0)", "POINT (1 0)", "POINT (2 0)", "POINT (10 0)", "POINT (11 0)", "POINT (12 0)");

        CollectionSource<WayangGeometry> source = new CollectionSource<>(points, WayangGeometry.class);
        SpatialIndexOperator<WayangGeometry> index =
                new SpatialIndexOperator<>(g -> {
                    KEY_EXTRACTIONS.incrementAndGet();
                    return g;
                }, DataSetType.createDefault(WayangGeometry.class));
        SpatialIndexKNNSearchOperator<WayangGeometry> nearLeft = new SpatialIndexKNNSearchOperator<>(
                2, WayangGeometry.fromStringInput("POINT (-1 0)"), DataSetType.createDefault(WayangGeometry.class));
        SpatialIndexKNNSearchOperator<WayangGeometry> nearRight = new SpatialIndexKNNSearchOperator<>(
                2, WayangGeometry.fromStringInput("POINT (13 0)"), DataSetType.createDefault(WayangGeometry.class));
        List<WayangGeometry> left = new ArrayList<>();
        List<WayangGeometry> right = new ArrayList<>();
        LocalCallbackSink<WayangGeometry> leftSink = LocalCallbackSink.createCollectingSink(left, WayangGeometry.class);
        LocalCallbackSink<WayangGeometry> rightSink = LocalCallbackSink.createCollectingSink(right, WayangGeometry.class);

        source.connectTo(0, index, 0);
        index.connectTo(0, nearLeft, 0);
        index.connectTo(0, nearRight, 0);
        nearLeft.connectTo(0, leftSink, 0);
        nearRight.connectTo(0, rightSink, 0);
        KEY_EXTRACTIONS.set(0);
        context().execute(new WayangPlan(leftSink, rightSink));

        assertEquals(points.size(), KEY_EXTRACTIONS.get(), "the index should be built once for both queries");

        assertEquals(Arrays.asList("POINT (0 0)", "POINT (1 0)"), wkts(left));
        assertEquals(Arrays.asList("POINT (12 0)", "POINT (11 0)"), wkts(right));
    }

    @Test
    void testMergesAnswersOfSeveralIndexes() {
        // Two indexes, as a distributed platform would build one per partition.
        List<SpatialIndex<WayangGeometry>> parts = Arrays.asList(
                JtsSpatialIndex.build(geometries("POINT (1 0)", "POINT (4 0)", "POINT (6 0)").iterator(), g -> g),
                JtsSpatialIndex.build(geometries("POINT (2 0)", "POINT (3 0)", "POINT (5 0)").iterator(), g -> g));

        CollectionSource<SpatialIndex<WayangGeometry>> source =
                new CollectionSource<>(parts, DataSetType.createDefaultUnchecked(SpatialIndex.class));
        SpatialIndexKNNSearchOperator<WayangGeometry> knn = new SpatialIndexKNNSearchOperator<>(
                4, WayangGeometry.fromStringInput("POINT (0 0)"), DataSetType.createDefault(WayangGeometry.class));
        List<WayangGeometry> result = new ArrayList<>();
        LocalCallbackSink<WayangGeometry> sink = LocalCallbackSink.createCollectingSink(result, WayangGeometry.class);

        source.connectTo(0, knn, 0);
        knn.connectTo(0, sink, 0);
        context().execute(new WayangPlan(sink));

        assertEquals(Arrays.asList("POINT (1 0)", "POINT (2 0)", "POINT (3 0)", "POINT (4 0)"), wkts(result));
    }
}
