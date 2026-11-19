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

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.Job;
import org.apache.wayang.core.api.exception.WayangException;
import org.apache.wayang.core.optimizer.DefaultOptimizationContext;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimate;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.platform.CrossPlatformExecutor;
import org.apache.wayang.core.profiling.NoInstrumentationStrategy;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.channels.JavaChannelInstance;
import org.apache.wayang.java.channels.StreamChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.platform.JavaPlatform;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test suite for {@link JavaSpatialKNNSearchOperator}.
 */
class JavaSpatialKNNSearchOperatorTest {

    private static Configuration configuration;
    private static Job job;

    @BeforeAll
    static void init() {
        configuration = new Configuration();
        job = mock(Job.class);
        when(job.getConfiguration()).thenReturn(configuration);
        DefaultOptimizationContext optimizationContext = new DefaultOptimizationContext(job);
        when(job.getCrossPlatformExecutor()).thenReturn(new CrossPlatformExecutor(job, new NoInstrumentationStrategy()));
        when(job.getOptimizationContext()).thenReturn(optimizationContext);
    }

    private static List<String> knn(Integer k, String referenceWkt, List<WayangGeometry> input) {
        JavaSpatialKNNSearchOperator<WayangGeometry> op = new JavaSpatialKNNSearchOperator<>(
                k,
                w -> w,
                DataSetType.createDefault(WayangGeometry.class),
                referenceWkt == null ? null : WayangGeometry.fromStringInput(referenceWkt)
        );

        StreamChannel.Instance in = (StreamChannel.Instance) StreamChannel.DESCRIPTOR
                .createChannel(null, configuration)
                .createInstance(mock(JavaExecutor.class), null, -1);
        in.accept(input.stream());
        CollectionChannel.Instance out = (CollectionChannel.Instance) CollectionChannel.DESCRIPTOR
                .createChannel(null, configuration)
                .createInstance(mock(JavaExecutor.class), null, -1);

        OptimizationContext.OperatorContext operatorContext = job.getOptimizationContext().addOneTimeOperator(op);
        operatorContext.setInputCardinality(0, new CardinalityEstimate(100, 10000, 0.1));
        operatorContext.setOutputCardinality(0, new CardinalityEstimate(100, 10000, 0.1));

        op.evaluate(new JavaChannelInstance[]{in}, new JavaChannelInstance[]{out},
                new JavaExecutor(JavaPlatform.getInstance(), job), operatorContext);

        Collection<WayangGeometry> result = out.provideCollection();
        return result.stream().map(WayangGeometry::getWKT).collect(Collectors.toList());
    }

    private static List<WayangGeometry> geometries(String... wkts) {
        List<WayangGeometry> list = new ArrayList<>();
        for (String wkt : wkts) {
            list.add(WayangGeometry.fromStringInput(wkt));
        }
        return list;
    }

    @Test
    void testReturnsKNearestNearestFirst() {
        List<WayangGeometry> input = geometries(
                "POINT (10 0)", "POINT (1 0)", "POINT (5 0)", "POINT (3 0)", "POINT (7 0)", "POINT (2 0)");

        List<String> result = knn(3, "POINT (0 0)", input);

        assertEquals(Arrays.asList("POINT (1 0)", "POINT (2 0)", "POINT (3 0)"), result);
    }

    @Test
    void testUsesGeometryDistanceNotEnvelopeDistance() {
        // The L-shaped line's envelope contains the reference, but the line itself is 4 away.
        List<WayangGeometry> input = geometries(
                "LINESTRING (-5 -5, 5 -5, 5 5)", "POINT (2 0)", "POINT (0 3)");

        List<String> result = knn(2, "POINT (0 0)", input);

        assertEquals(Arrays.asList("POINT (2 0)", "POINT (0 3)"), result);
    }

    @Test
    void testKLargerThanInputReturnsEverythingSorted() {
        List<WayangGeometry> input = geometries("POINT (3 0)", "POINT (1 0)", "POINT (2 0)");

        List<String> result = knn(Integer.MAX_VALUE, "POINT (0 0)", input);

        assertEquals(Arrays.asList("POINT (1 0)", "POINT (2 0)", "POINT (3 0)"), result);
    }

    @Test
    void testKZeroReturnsNothing() {
        assertTrue(knn(0, "POINT (0 0)", geometries("POINT (1 0)")).isEmpty());
    }

    @Test
    void testEmptyAndNullKeysAreSkippedRegardlessOfOrder() {
        List<WayangGeometry> emptyLast = geometries("POINT (1 0)", "POINT (2 0)", "POINT EMPTY");
        List<WayangGeometry> emptyFirst = geometries("POINT EMPTY", "POINT (1 0)", "POINT (2 0)");
        List<WayangGeometry> withNull = geometries("POINT (1 0)", "POINT (2 0)");
        withNull.add(0, null);

        List<String> expected = Arrays.asList("POINT (1 0)", "POINT (2 0)");
        assertEquals(expected, knn(2, "POINT (0 0)", emptyLast));
        assertEquals(expected, knn(2, "POINT (0 0)", emptyFirst));
        assertEquals(expected, knn(2, "POINT (0 0)", withNull));
    }

    @Test
    void testMismatchedSridsAreRejected() {
        List<WayangGeometry> input = geometries("SRID=4326;POINT (1 0)");

        assertThrows(WayangException.class, () -> knn(1, "SRID=25832;POINT (0 0)", input));
    }

    @Test
    void testUnknownSridIsComparedWithAnySrid() {
        List<WayangGeometry> input = geometries("SRID=4326;POINT (1 0)", "POINT (2 0)");

        assertEquals(2, knn(2, "POINT (0 0)", input).size());
    }

    @Test
    void testInvalidArgumentsAreRejected() {
        List<WayangGeometry> input = Collections.emptyList();
        assertThrows(IllegalStateException.class, () -> knn(null, "POINT (0 0)", input));
        assertThrows(IllegalStateException.class, () -> knn(-1, "POINT (0 0)", input));
        assertThrows(IllegalStateException.class, () -> knn(1, null, input));
    }
}
