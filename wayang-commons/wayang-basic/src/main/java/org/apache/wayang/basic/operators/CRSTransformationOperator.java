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
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.function.TransformationDescriptor;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator;
import org.apache.wayang.core.optimizer.cardinality.DefaultCardinalityEstimator;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Optional;

/**
 * This operator returns a new dataset in which the geometry of every element has been reprojected
 * from one coordinate reference system into another.
 */
public class CRSTransformationOperator<Type> extends UnaryToUnaryOperator<Type, Type> {

    protected final TransformationDescriptor<Type, SpatialGeometry> keyDescriptor;

    protected final FunctionDescriptor.SerializableBiFunction<Type, SpatialGeometry, Type> geometryUpdater;

    protected final int sourceSrid;

    protected final int targetSrid;

    /**
     * Creates a new instance.
     */
    @SuppressWarnings("unchecked")
    public CRSTransformationOperator(
            FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
            FunctionDescriptor.SerializableBiFunction<Type, SpatialGeometry, Type> geometryUpdater,
            DataSetType<Type> inputClassDatasetType,
            int sourceSrid,
            int targetSrid) {
        super(inputClassDatasetType, inputClassDatasetType, true);
        this.keyDescriptor = new TransformationDescriptor<>(
                (FunctionDescriptor.SerializableFunction<Type, SpatialGeometry>) (FunctionDescriptor.SerializableFunction) keyExtractor,
                inputClassDatasetType.getDataUnitType().getTypeClass(), SpatialGeometry.class);
        this.geometryUpdater = geometryUpdater;
        this.sourceSrid = sourceSrid;
        this.targetSrid = targetSrid;
    }

    /**
     * Copies an instance (exclusive of broadcasts).
     *
     * @param that that should be copied
     */
    public CRSTransformationOperator(CRSTransformationOperator<Type> that) {
        super(that);
        this.keyDescriptor = that.keyDescriptor;
        this.geometryUpdater = that.geometryUpdater;
        this.sourceSrid = that.sourceSrid;
        this.targetSrid = that.targetSrid;
    }

    public TransformationDescriptor<Type, SpatialGeometry> getKeyDescriptor() {
        return this.keyDescriptor;
    }

    public FunctionDescriptor.SerializableBiFunction<Type, SpatialGeometry, Type> getGeometryUpdater() {
        return this.geometryUpdater;
    }

    public int getSourceSrid() {
        return this.sourceSrid;
    }

    public int getTargetSrid() {
        return this.targetSrid;
    }

    @Override
    public Optional<CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        Validate.inclusiveBetween(0, this.getNumOutputs() - 1, outputIndex);
        // A CRS transformation is 1:1: it moves coordinates, it never adds or drops elements.
        return Optional.of(new DefaultCardinalityEstimator(1d, 1, this.isSupportingBroadcastInputs(),
                inputCards -> inputCards[0]));
    }
}
