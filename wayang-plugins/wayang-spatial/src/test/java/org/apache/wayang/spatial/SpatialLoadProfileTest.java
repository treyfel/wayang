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

import org.apache.wayang.basic.operators.SpatialFilterOperator;
import org.apache.wayang.basic.operators.SpatialJoinOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimate;
import org.apache.wayang.core.optimizer.costs.LoadProfile;
import org.apache.wayang.core.optimizer.costs.LoadProfileEstimator;
import org.apache.wayang.core.optimizer.costs.SimpleEstimationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.spatial.operators.java.JavaGeoJsonFileSink;
import org.apache.wayang.spatial.operators.java.JavaGeoJsonFileSource;
import org.apache.wayang.spatial.operators.java.JavaSpatialFilterOperator;
import org.apache.wayang.spatial.operators.java.JavaSpatialJoinOperator;
import org.apache.wayang.spatial.operators.postgres.PostgresSpatialFilterOperator;
import org.apache.wayang.spatial.operators.postgres.PostgresSpatialJoinOperator;
import org.apache.wayang.spatial.operators.spark.SparkSpatialFilterOperator;
import org.apache.wayang.spatial.operators.spark.SparkSpatialJoinOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every spatial execution operator resolves a load profile from
 * {@code wayang-spatial-defaults.properties} instead of the generic fallback.
 */
class SpatialLoadProfileTest {

    private static Configuration configuration;

    private static final SpatialFilterOperator<SpatialGeometry> FILTER = new SpatialFilterOperator<>(
            SpatialPredicate.INTERSECTS, g -> g, DataSetType.createDefault(SpatialGeometry.class), null);

    private static final SpatialJoinOperator<SpatialGeometry, SpatialGeometry> JOIN = new SpatialJoinOperator<>(
            g -> g, g -> g, SpatialGeometry.class, SpatialGeometry.class, SpatialPredicate.INTERSECTS);

    @BeforeAll
    static void setUp() {
        configuration = new Configuration();
        Spatial.plugin().configure(configuration);
    }

    private static LoadProfile estimate(ExecutionOperator operator, long[] in, long[] out) {
        final Optional<LoadProfileEstimator> estimator = operator.createLoadProfileEstimator(configuration);
        assertTrue(estimator.isPresent(), "No load profile for " + operator);
        final CardinalityEstimate[] inCards = new CardinalityEstimate[in.length];
        for (int i = 0; i < in.length; i++) inCards[i] = new CardinalityEstimate(in[i], in[i], 1d);
        final CardinalityEstimate[] outCards = new CardinalityEstimate[out.length];
        for (int i = 0; i < out.length; i++) outCards[i] = new CardinalityEstimate(out[i], out[i], 1d);
        return estimator.get().estimate(new SimpleEstimationContext(inCards, outCards, new HashMap<>(), 1));
    }

    private static long cpu(LoadProfile profile) {
        return profile.getCpuUsage().getLowerEstimate();
    }

    @Test
    void filtersHaveLoadProfiles() {
        final long[] in = {1_000_000}, out = {10_000};
        assertEquals(8_000L * 1_000_000 + 350_000,
                cpu(estimate(new JavaSpatialFilterOperator<>(FILTER), in, out)));
        assertEquals(10_000L * 1_000_000 + 56_789,
                cpu(estimate(new SparkSpatialFilterOperator(FILTER), in, out)));
        assertEquals(1_500L * 1_000_000 + 380_000,
                cpu(estimate(new PostgresSpatialFilterOperator<>(FILTER), in, out)));
    }

    @Test
    void joinsHaveLoadProfiles() {
        final long[] in = {1_000_000, 1_000}, out = {1_000_000};
        final LoadProfile java = estimate(new JavaSpatialJoinOperator<>(JOIN), in, out);
        final LoadProfile spark = estimate(new SparkSpatialJoinOperator<>(JOIN), in, out);
        final LoadProfile postgres = estimate(new PostgresSpatialJoinOperator<>(JOIN), in, out);

        final double lnRight = Math.log(1_002);
        assertEquals(Math.round(3000 * 1_000 * lnRight + 2000 * 1_000_000 * lnRight + 20_000d * 1_000_000 + 1_000_000),
                cpu(java));
        assertEquals(400L * 1_000, java.getRamUsage().getLowerEstimate());
        assertTrue(spark.getNetworkUsage().getLowerEstimate() > 0, "Spark join should model its shuffle");
        assertTrue(cpu(postgres) < cpu(java), "Indexed PostGIS join should be cheaper per element than JTS");
    }

    @Test
    void joinCostGrowsSuperlinearlyWithIndexedSide() {
        final JavaSpatialJoinOperator<SpatialGeometry, SpatialGeometry> join = new JavaSpatialJoinOperator<>(JOIN);
        final long small = cpu(estimate(join, new long[]{1_000, 1_000}, new long[]{0}));
        final long large = cpu(estimate(join, new long[]{1_000, 1_000_000}, new long[]{0}));
        assertTrue(large > 1_000 * (small - 1_000_000), "Index build should be n log n in the indexed input");
    }

    @Test
    void geoJsonSourceAndSinkHaveLoadProfiles() {
        assertEquals(20_000L * 500 + 1_400_000,
                cpu(estimate(new JavaGeoJsonFileSource("file:///dev/null"), new long[0], new long[]{500})));
        final LoadProfile sink = estimate(new JavaGeoJsonFileSink("file:///dev/null"), new long[]{500}, new long[0]);
        assertEquals(10_000L * 500 + 810_000, cpu(sink));
        assertEquals(500L * 500, sink.getDiskUsage().getLowerEstimate());
    }
}
