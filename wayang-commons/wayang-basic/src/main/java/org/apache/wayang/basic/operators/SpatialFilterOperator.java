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

import org.apache.commons.lang3.Validate;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.function.TransformationDescriptor;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.optimizer.ProbabilisticDoubleInterval;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimate;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Optional;


/**
 * This operator returns a new dataset after filtering by applying a spatial predicate.
 */
public class SpatialFilterOperator<Type> extends UnaryToUnaryOperator<Type, Type> {

    protected final SpatialPredicate predicateType;
    protected final TransformationDescriptor<Type, SpatialGeometry> keyDescriptor;
    protected final SpatialGeometry referenceGeometry;

    /**
     * Optional user-provided selectivity; overrides the configured per-predicate default.
     */
    private ProbabilisticDoubleInterval selectivity;

    @SuppressWarnings("unchecked")
    public SpatialFilterOperator(SpatialPredicate predicateType,
                                 FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                 DataSetType<Type> inputClassDatasetType,
                                 SpatialGeometry geometry) {
        super(inputClassDatasetType, inputClassDatasetType, true);
        this.predicateType = predicateType;
        this.keyDescriptor = new TransformationDescriptor<>(
                (FunctionDescriptor.SerializableFunction<Type, SpatialGeometry>) (FunctionDescriptor.SerializableFunction) keyExtractor,
                inputClassDatasetType.getDataUnitType().getTypeClass(), SpatialGeometry.class);
        this.referenceGeometry = geometry;
    }

    /**
     * Copies an instance (exclusive of broadcasts).
     *
     * @param that that should be copied
     */
    public SpatialFilterOperator(SpatialFilterOperator<Type> that) {
        super(that);
        this.predicateType = that.predicateType;
        this.keyDescriptor = that.keyDescriptor;
        this.referenceGeometry = that.referenceGeometry;
        this.selectivity = that.selectivity;
    }

    public SpatialPredicate getPredicateType() {
        return this.predicateType;
    }

    public SpatialGeometry getReferenceGeometry() {
        return this.referenceGeometry;
    }

    public TransformationDescriptor<Type, SpatialGeometry> getKeyDescriptor() {
        return this.keyDescriptor;
    }

    /**
     * Sets the expected fraction of the input that satisfies the predicate. Without it, the optimizer
     * uses a per-predicate default (see {@link SpatialCardinalityEstimates}).
     *
     * @param selectivity the selectivity, within {@code [0, 1]}
     * @return this instance
     */
    public SpatialFilterOperator<Type> withSelectivity(ProbabilisticDoubleInterval selectivity) {
        Validate.isTrue(selectivity.getLowerEstimate() >= 0 && selectivity.getUpperEstimate() <= 1,
                "Selectivity must lie within [0, 1], got %s.", selectivity);
        this.selectivity = selectivity;
        return this;
    }

    public ProbabilisticDoubleInterval getSelectivity() {
        return this.selectivity;
    }

    @Override
    public Optional<org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        Validate.inclusiveBetween(0, this.getNumOutputs() - 1, outputIndex);
        final ProbabilisticDoubleInterval selectivity = this.selectivity != null
                ? this.selectivity
                : SpatialCardinalityEstimates.filterSelectivity(this.predicateType, configuration);
        return Optional.of(new CardinalityEstimator(selectivity));
    }

    /**
     * Scales the input cardinality by the selectivity of the spatial predicate.
     */
    private class CardinalityEstimator implements org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator {

        private final ProbabilisticDoubleInterval selectivity;

        CardinalityEstimator(ProbabilisticDoubleInterval selectivity) {
            this.selectivity = selectivity;
        }

        @Override
        public CardinalityEstimate estimate(OptimizationContext optimizationContext, CardinalityEstimate... inputEstimates) {
            Validate.isTrue(inputEstimates.length == SpatialFilterOperator.this.getNumInputs());
            final CardinalityEstimate input = inputEstimates[0];
            return new CardinalityEstimate(
                    (long) (input.getLowerEstimate() * this.selectivity.getLowerEstimate()),
                    (long) Math.ceil(input.getUpperEstimate() * this.selectivity.getUpperEstimate()),
                    input.getCorrectnessProbability() * this.selectivity.getCorrectnessProbability()
            );
        }
    }
}
