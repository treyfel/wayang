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
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.function.TransformationDescriptor;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator;
import org.apache.wayang.core.optimizer.cardinality.DefaultCardinalityEstimator;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Optional;

/**
 * This operator returns a new dataset after filtering by applying a knn-search operation.
 */
public class SpatialKNNSearchOperator<Type> extends UnaryToUnaryOperator<Type, Type> {
    protected final Integer k;
    protected final TransformationDescriptor<Type, SpatialGeometry> keyDescriptor;
    protected final SpatialGeometry referenceGeometry;

    @SuppressWarnings("unchecked")
    public SpatialKNNSearchOperator(Integer k,
                                    FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                    DataSetType<Type> inputClassDatasetType,
                                    SpatialGeometry geometry) {
        super(inputClassDatasetType, inputClassDatasetType, true);
        this.k = k;
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
    public SpatialKNNSearchOperator(SpatialKNNSearchOperator<Type> that) {
        super(that);
        this.k = that.k;
        this.keyDescriptor = that.keyDescriptor;
        this.referenceGeometry = that.referenceGeometry;
    }

    public SpatialGeometry getReferenceGeometry() {
        return this.referenceGeometry;
    }

    public TransformationDescriptor<Type, SpatialGeometry> getKeyDescriptor() {
        return this.keyDescriptor;
    }

    public Integer getK() {
        return this.k;
    }

    /**
     * Checks the arguments before execution. The constructor cannot do this, because mappings build
     * pattern instances with {@code null} arguments.
     *
     * @throws IllegalStateException if {@code k} is {@code null} or negative, or the reference geometry is {@code null}
     */
    protected void validateArguments() {
        if (this.referenceGeometry == null) {
            throw new IllegalStateException("Geometry for spatial kNN search must not be null.");
        }
        if (this.k == null || this.k < 0) {
            throw new IllegalStateException("k for spatial kNN search must be non-negative, but was " + this.k + ".");
        }
    }

    /**
     * The output holds the k nearest elements, or the whole input if it has fewer than k.
     */
    @Override
    public Optional<CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        assert outputIndex == 0;
        final long k = this.k;
        return Optional.of(new DefaultCardinalityEstimator(1d, 1, false, inputCards -> Math.min(k, inputCards[0])));
    }

}
