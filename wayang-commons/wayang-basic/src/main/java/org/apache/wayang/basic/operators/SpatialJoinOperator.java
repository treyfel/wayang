/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.wayang.basic.operators;

import org.apache.commons.lang3.Validate;
import org.apache.wayang.basic.data.Tuple2;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.function.TransformationDescriptor;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.optimizer.ProbabilisticDoubleInterval;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimate;
import org.apache.wayang.core.plan.wayangplan.BinaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Optional;

/**
 * This operator returns a new dataset after joining the input tables using the predicate.
 */
public class SpatialJoinOperator<InputType0, InputType1> extends BinaryToUnaryOperator<InputType0, InputType1, Tuple2<InputType0, InputType1>> {

    private static <InputType0, InputType1> DataSetType<Tuple2<InputType0, InputType1>> createOutputDataSetType() {
        return DataSetType.createDefaultUnchecked(Tuple2.class);
    }

    protected final TransformationDescriptor<InputType0, ? extends SpatialGeometry> keyDescriptor0;

    protected final TransformationDescriptor<InputType1, ? extends SpatialGeometry> keyDescriptor1;

    protected final SpatialPredicate predicateType;

    /**
     * Optional user-provided match factor; overrides the configured per-predicate default.
     */
    private ProbabilisticDoubleInterval matchFactor;

    public SpatialJoinOperator(TransformationDescriptor<InputType0, ? extends SpatialGeometry> keyDescriptor0,
                               TransformationDescriptor<InputType1, ? extends SpatialGeometry> keyDescriptor1,
                               SpatialPredicate predicateType) {
        super(DataSetType.createDefault(keyDescriptor0.getInputType()),
                DataSetType.createDefault(keyDescriptor1.getInputType()),
                SpatialJoinOperator.createOutputDataSetType(),
                true);
        this.keyDescriptor0 = keyDescriptor0;
        this.keyDescriptor1 = keyDescriptor1;
        this.predicateType = predicateType;
    }

    public SpatialJoinOperator(TransformationDescriptor<InputType0, ? extends SpatialGeometry> keyDescriptor0,
                               TransformationDescriptor<InputType1, ? extends SpatialGeometry> keyDescriptor1,
                               DataSetType<InputType0> inputType0,
                               DataSetType<InputType1> inputType1,
                               SpatialPredicate predicateType) {
        super(inputType0, inputType1, SpatialJoinOperator.createOutputDataSetType(), true);
        this.keyDescriptor0 = keyDescriptor0;
        this.keyDescriptor1 = keyDescriptor1;
        this.predicateType = predicateType;
    }

    public SpatialJoinOperator(SpatialJoinOperator<InputType0, InputType1> that) {
        super(that);
        this.keyDescriptor0 = that.keyDescriptor0;
        this.keyDescriptor1 = that.keyDescriptor1;
        this.predicateType = that.predicateType;
        this.matchFactor = that.matchFactor;
    }

    @SuppressWarnings("unchecked")
    public SpatialJoinOperator(
            FunctionDescriptor.SerializableFunction<InputType0, ? extends SpatialGeometry> keyExtractor0,
            FunctionDescriptor.SerializableFunction<InputType1, ? extends SpatialGeometry> keyExtractor1,
            Class<InputType0> input0Class,
            Class<InputType1> input1Class,
            SpatialPredicate predicateType) {
        this(
                new TransformationDescriptor<>(
                        (FunctionDescriptor.SerializableFunction<InputType0, SpatialGeometry>) keyExtractor0,
                        input0Class, SpatialGeometry.class),
                new TransformationDescriptor<>(
                        (FunctionDescriptor.SerializableFunction<InputType1, SpatialGeometry>) keyExtractor1,
                        input1Class, SpatialGeometry.class),
                predicateType
        );
    }

    public TransformationDescriptor<InputType0, ? extends SpatialGeometry> getKeyDescriptor0() {
        return this.keyDescriptor0;
    }

    public TransformationDescriptor<InputType1, ? extends SpatialGeometry> getKeyDescriptor1() {
        return this.keyDescriptor1;
    }

    public SpatialPredicate getPredicateType() {
        return this.predicateType;
    }

    /**
     * Sets the expected output size as a multiple of the larger input, i.e., the average number of
     * matches per element. Without it, the optimizer uses a per-predicate default
     * (see {@link SpatialCardinalityEstimates}).
     *
     * @param matchFactor the match factor, non-negative
     * @return this instance
     */
    public SpatialJoinOperator<InputType0, InputType1> withMatchFactor(ProbabilisticDoubleInterval matchFactor) {
        Validate.isTrue(matchFactor.getLowerEstimate() >= 0, "Match factor must be non-negative, got %s.", matchFactor);
        this.matchFactor = matchFactor;
        return this;
    }

    public ProbabilisticDoubleInterval getMatchFactor() {
        return this.matchFactor;
    }

    @Override
    public Optional<org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        Validate.inclusiveBetween(0, this.getNumOutputs() - 1, outputIndex);
        final ProbabilisticDoubleInterval matchFactor = this.matchFactor != null
                ? this.matchFactor
                : SpatialCardinalityEstimates.joinMatchFactor(this.predicateType, configuration);
        return Optional.of(new CardinalityEstimator(matchFactor));
    }

    /**
     * Estimates the output as {@code matchFactor * max(in0, in1)}, capped at the cross product.
     */
    private class CardinalityEstimator implements org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator {

        private final ProbabilisticDoubleInterval matchFactor;

        CardinalityEstimator(ProbabilisticDoubleInterval matchFactor) {
            this.matchFactor = matchFactor;
        }

        @Override
        public CardinalityEstimate estimate(OptimizationContext optimizationContext, CardinalityEstimate... inputEstimates) {
            Validate.isTrue(inputEstimates.length == SpatialJoinOperator.this.getNumInputs());
            final CardinalityEstimate in0 = inputEstimates[0], in1 = inputEstimates[1];
            final long lower = Math.min(
                    (long) (Math.max(in0.getLowerEstimate(), in1.getLowerEstimate()) * this.matchFactor.getLowerEstimate()),
                    SpatialCardinalityEstimates.saturatedProduct(in0.getLowerEstimate(), in1.getLowerEstimate()));
            final long upper = Math.min(
                    (long) Math.ceil(Math.max(in0.getUpperEstimate(), in1.getUpperEstimate()) * this.matchFactor.getUpperEstimate()),
                    SpatialCardinalityEstimates.saturatedProduct(in0.getUpperEstimate(), in1.getUpperEstimate()));
            return new CardinalityEstimate(
                    lower,
                    upper,
                    in0.getCorrectnessProbability() * in1.getCorrectnessProbability()
                            * this.matchFactor.getCorrectnessProbability()
            );
        }
    }
}
