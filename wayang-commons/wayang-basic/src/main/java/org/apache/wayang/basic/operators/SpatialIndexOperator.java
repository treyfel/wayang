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
import org.apache.wayang.core.api.spatial.SpatialIndex;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.function.TransformationDescriptor;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator;
import org.apache.wayang.core.optimizer.cardinality.DefaultCardinalityEstimator;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Optional;

/**
 * Builds a {@link SpatialIndex} over its input, keyed by a geometry of each element.
 * <p>
 * The output is a dataset of indexes that together hold the input (see {@link SpatialIndex}). Several
 * operators can read the same output, e.g. a number of {@link SpatialIndexKNNSearchOperator}s with different
 * reference geometries, so the index is built once and shared. Elements whose geometry is {@code null} or
 * empty are not indexed.
 *
 * @param <Type> the type of the indexed elements
 */
public class SpatialIndexOperator<Type> extends UnaryToUnaryOperator<Type, SpatialIndex<Type>> {

    protected final TransformationDescriptor<Type, SpatialGeometry> keyDescriptor;

    @SuppressWarnings("unchecked")
    public SpatialIndexOperator(FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                DataSetType<Type> inputType) {
        super(inputType, DataSetType.createDefaultUnchecked(SpatialIndex.class), false);
        this.keyDescriptor = new TransformationDescriptor<>(
                (FunctionDescriptor.SerializableFunction<Type, SpatialGeometry>) (FunctionDescriptor.SerializableFunction) keyExtractor,
                inputType.getDataUnitType().getTypeClass(), SpatialGeometry.class);
    }

    /**
     * Copies an instance (exclusive of broadcasts).
     *
     * @param that that should be copied
     */
    public SpatialIndexOperator(SpatialIndexOperator<Type> that) {
        super(that);
        this.keyDescriptor = that.keyDescriptor;
    }

    public TransformationDescriptor<Type, SpatialGeometry> getKeyDescriptor() {
        return this.keyDescriptor;
    }

    /**
     * The output holds one index per part of the input. That is a single index on single-machine platforms;
     * distributed platforms build one per partition, which is not known before execution.
     */
    @Override
    public Optional<CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        assert outputIndex == 0;
        return Optional.of(new DefaultCardinalityEstimator(0.5d, 1, false, inputCards -> 1L));
    }
}
