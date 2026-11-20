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

package org.apache.wayang.spatial;

import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.SpatialFilterOperator;
import org.apache.wayang.basic.operators.SpatialJoinOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.optimizer.cardinality.FixedSizeCardinalityEstimator;
import org.apache.wayang.core.plan.executionplan.ExecutionPlan;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.java.Java;
import org.apache.wayang.spark.Spark;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sanity checks that the cost model sends small spatial workloads to Java and large ones to Spark.
 * Only the optimizer runs; input sizes are pinned with {@link FixedSizeCardinalityEstimator}s.
 */
class SpatialPlatformChoiceTest {

    private static CollectionSource<SpatialGeometry> source(long cardinality) {
        final CollectionSource<SpatialGeometry> source =
                new CollectionSource<>(Collections.emptyList(), SpatialGeometry.class);
        source.setCardinalityEstimator(0, new FixedSizeCardinalityEstimator(cardinality, true));
        return source;
    }

    private static Platform platformOf(ExecutionPlan plan, Class<? extends Operator> logicalType) {
        return plan.collectAllTasks().stream()
                .map(task -> task.getOperator())
                .filter(logicalType::isInstance)
                .map(ExecutionOperator::getPlatform)
                .findAny()
                .orElseThrow(() -> new AssertionError("No " + logicalType.getSimpleName() + " in " + plan));
    }

    private static ExecutionPlan optimize(WayangPlan plan) {
        final WayangContext context = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Spatial.plugin());
        return context.buildInitialExecutionPlan("spatial-platform-choice", plan);
    }

    private static Platform joinPlatform(long left, long right) {
        final SpatialJoinOperator<SpatialGeometry, SpatialGeometry> join = new SpatialJoinOperator<>(
                g -> g, g -> g, SpatialGeometry.class, SpatialGeometry.class, SpatialPredicate.INTERSECTS);
        final LocalCallbackSink<?> sink = LocalCallbackSink.createStdoutSink(DataSetType.createDefaultUnchecked(Object.class));
        source(left).connectTo(0, join, 0);
        source(right).connectTo(0, join, 1);
        join.connectTo(0, sink, 0);
        return platformOf(optimize(new WayangPlan(sink)), SpatialJoinOperator.class);
    }

    private static Platform filterPlatform(long size) {
        final SpatialFilterOperator<SpatialGeometry> filter = new SpatialFilterOperator<>(
                SpatialPredicate.INTERSECTS, g -> g, DataSetType.createDefault(SpatialGeometry.class), null);
        final LocalCallbackSink<SpatialGeometry> sink =
                LocalCallbackSink.createStdoutSink(DataSetType.createDefault(SpatialGeometry.class));
        source(size).connectTo(0, filter, 0);
        filter.connectTo(0, sink, 0);
        return platformOf(optimize(new WayangPlan(sink)), SpatialFilterOperator.class);
    }

    /*
     * With the current defaults the join moves to Spark between 100k and 1M left-side elements
     * (right side = 1% of left) and the filter between 1M and 10M elements. The sizes below stay
     * at least an order of magnitude away from those crossovers so that recalibration does not
     * flip them, while still failing without spatial load profiles: with the generic fallback
     * estimators, the filter stays on Java even at 10^10 elements and the join only moves at 10^8.
     */

    @Test
    void smallJoinRunsOnJava() {
        assertEquals(Java.platform(), joinPlatform(1_000, 10));
    }

    @Test
    void largeJoinRunsOnSpark() {
        assertEquals(Spark.platform(), joinPlatform(10_000_000, 100_000));
    }

    @Test
    void smallFilterRunsOnJava() {
        assertEquals(Java.platform(), filterPlatform(10_000));
    }

    @Test
    void largeFilterRunsOnSpark() {
        assertEquals(Spark.platform(), filterPlatform(1_000_000_000));
    }
}
