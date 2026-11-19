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

package org.apache.wayang.basic.operators;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.optimizer.ProbabilisticDoubleInterval;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimate;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator;
import org.apache.wayang.core.types.DataSetType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpatialCardinalityEstimatorTest {

    private final Configuration configuration = new Configuration();

    private static SpatialFilterOperator<SpatialGeometry> filter(SpatialPredicate predicate) {
        return new SpatialFilterOperator<>(predicate, g -> g, DataSetType.createDefault(SpatialGeometry.class), null);
    }

    private static SpatialJoinOperator<SpatialGeometry, SpatialGeometry> join(SpatialPredicate predicate) {
        return new SpatialJoinOperator<>(g -> g, g -> g, SpatialGeometry.class, SpatialGeometry.class, predicate);
    }

    private CardinalityEstimate estimate(org.apache.wayang.core.plan.wayangplan.ElementaryOperator operator,
                                         CardinalityEstimate... inputs) {
        final CardinalityEstimator estimator = operator.createCardinalityEstimator(0, this.configuration)
                .orElseThrow(AssertionError::new);
        return estimator.estimate(null, inputs);
    }

    @Test
    void filterUsesPredicateDefault() {
        final CardinalityEstimate result = this.estimate(filter(SpatialPredicate.INTERSECTS),
                new CardinalityEstimate(1000, 1000, 1d));
        assertEquals(new CardinalityEstimate(1, 500, 0.5), result);
    }

    @Test
    void filterHintOverridesDefaultAndSurvivesCopy() {
        final SpatialFilterOperator<SpatialGeometry> original = filter(SpatialPredicate.CONTAINS)
                .withSelectivity(new ProbabilisticDoubleInterval(0.2, 0.3, 0.9));
        final CardinalityEstimate result = this.estimate(new SpatialFilterOperator<>(original),
                new CardinalityEstimate(1000, 2000, 1d));
        assertEquals(new CardinalityEstimate(200, 600, 0.9), result);
    }

    @Test
    void filterRejectsSelectivityAboveOne() {
        assertThrows(IllegalArgumentException.class,
                () -> filter(SpatialPredicate.WITHIN).withSelectivity(new ProbabilisticDoubleInterval(0.5, 2, 0.9)));
    }

    @Test
    void filterReadsConfiguredDefault() {
        final Configuration fork = this.configuration.fork();
        fork.setProperty("wayang.basic.spatialfilter.selectivity.touches.lower", "0.1");
        fork.setProperty("wayang.basic.spatialfilter.selectivity.touches.upper", "0.2");
        fork.setProperty("wayang.basic.spatialfilter.selectivity.confidence", "0.8");
        final CardinalityEstimate result = filter(SpatialPredicate.TOUCHES)
                .createCardinalityEstimator(0, fork).orElseThrow(AssertionError::new)
                .estimate(null, new CardinalityEstimate(100, 100, 1d));
        assertEquals(new CardinalityEstimate(10, 20, 0.8), result);
    }

    @Test
    void joinScalesWithLargerInput() {
        // 1M points within 1000 polygons: each point lies in at most a couple of polygons.
        final CardinalityEstimate result = this.estimate(join(SpatialPredicate.WITHIN),
                new CardinalityEstimate(1_000_000, 1_000_000, 1d),
                new CardinalityEstimate(1_000, 1_000, 1d));
        assertEquals(new CardinalityEstimate(100_000, 2_000_000, 0.5), result);
    }

    @Test
    void joinIsCappedAtCrossProduct() {
        final CardinalityEstimate result = this.estimate(join(SpatialPredicate.INTERSECTS),
                new CardinalityEstimate(3, 3, 1d),
                new CardinalityEstimate(1, 1, 1d));
        assertEquals(new CardinalityEstimate(0, 3, 0.5), result);
    }

    @Test
    void joinDoesNotOverflow() {
        final CardinalityEstimate result = this.estimate(join(SpatialPredicate.INTERSECTS),
                new CardinalityEstimate(0, Long.MAX_VALUE / 2, 1d),
                new CardinalityEstimate(0, Long.MAX_VALUE / 2, 1d));
        assertEquals(Long.MAX_VALUE, result.getUpperEstimate());
    }

    @Test
    void joinHintSurvivesCopy() {
        final SpatialJoinOperator<SpatialGeometry, SpatialGeometry> original = join(SpatialPredicate.INTERSECTS)
                .withMatchFactor(new ProbabilisticDoubleInterval(1, 1, 0.9));
        final CardinalityEstimate result = this.estimate(new SpatialJoinOperator<>(original),
                new CardinalityEstimate(500, 500, 1d),
                new CardinalityEstimate(800, 800, 1d));
        assertEquals(new CardinalityEstimate(800, 800, 0.9), result);
    }

    @Test
    void patternOperatorsWithoutPredicateStillEstimate() {
        // Mappings build pattern operators with a null predicate.
        final SpatialJoinOperator<?, ?> pattern =
                new SpatialJoinOperator<>(null, null, DataSetType.none(), DataSetType.none(), null);
        final CardinalityEstimate result = this.estimate(pattern,
                new CardinalityEstimate(10, 10, 1d), new CardinalityEstimate(10, 10, 1d));
        assertEquals(new CardinalityEstimate(1, 100, 0.5), result);
    }
}
