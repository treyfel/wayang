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
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator;
import org.apache.wayang.core.optimizer.cardinality.DefaultCardinalityEstimator;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Optional;

/**
 * Finds the {@code k} elements nearest to a reference geometry in the output of a {@link SpatialIndexOperator}.
 * <p>
 * This is the indexed counterpart of {@link SpatialKNNSearchOperator}: it returns the same elements, but
 * queries an index that other operators can share instead of scanning the data.
 *
 * @param <Type> the type of the indexed elements
 */
public class SpatialIndexKNNSearchOperator<Type> extends UnaryToUnaryOperator<SpatialIndex<Type>, Type> {

    protected final Integer k;
    protected final SpatialGeometry referenceGeometry;

    /**
     * Creates a new instance.
     *
     * @param k                 the number of nearest elements to return
     * @param referenceGeometry the geometry to measure distances from
     * @param outputType        the type of the indexed elements
     */
    public SpatialIndexKNNSearchOperator(Integer k, SpatialGeometry referenceGeometry, DataSetType<Type> outputType) {
        super(DataSetType.createDefaultUnchecked(SpatialIndex.class), outputType, false);
        this.k = k;
        this.referenceGeometry = referenceGeometry;
    }

    /**
     * Copies an instance (exclusive of broadcasts).
     *
     * @param that that should be copied
     */
    public SpatialIndexKNNSearchOperator(SpatialIndexKNNSearchOperator<Type> that) {
        super(that);
        this.k = that.k;
        this.referenceGeometry = that.referenceGeometry;
    }

    public Integer getK() {
        return this.k;
    }

    public SpatialGeometry getReferenceGeometry() {
        return this.referenceGeometry;
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
     * The output holds at most {@code k} elements. The input cardinality counts indexes, not elements,
     * so it cannot tell whether there are fewer than {@code k}.
     */
    @Override
    public Optional<CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        assert outputIndex == 0;
        final long k = this.k;
        return Optional.of(new DefaultCardinalityEstimator(0.5d, 1, false, inputCards -> k));
    }
}
