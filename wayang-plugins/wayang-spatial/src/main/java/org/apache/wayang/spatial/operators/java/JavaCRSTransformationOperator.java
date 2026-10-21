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

import org.apache.wayang.basic.operators.CRSTransformationOperator;
import org.apache.wayang.core.api.exception.WayangException;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.channels.JavaChannelInstance;
import org.apache.wayang.java.channels.StreamChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.apache.wayang.spatial.crs.CrsTransforms;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.locationtech.jts.geom.Geometry;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Java implementation of the {@link CRSTransformationOperator}.
 */
public class JavaCRSTransformationOperator<Type>
        extends CRSTransformationOperator<Type>
        implements JavaExecutionOperator {

    public JavaCRSTransformationOperator(
            FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
            FunctionDescriptor.SerializableBiFunction<Type, SpatialGeometry, Type> geometryUpdater,
            DataSetType<Type> inputClassDatasetType,
            int sourceSrid,
            int targetSrid) {
        super(keyExtractor, geometryUpdater, inputClassDatasetType, sourceSrid, targetSrid);
    }

    public JavaCRSTransformationOperator(CRSTransformationOperator<Type> that) {
        super(that);
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs,
            ChannelInstance[] outputs,
            JavaExecutor javaExecutor,
            OptimizationContext.OperatorContext operatorContext) {

        final Function<Type, Type> reproject = this.buildReprojection(javaExecutor);
        ((StreamChannel.Instance) outputs[0]).accept(
                ((JavaChannelInstance) inputs[0]).<Type>provideStream().map(reproject)
        );

        return ExecutionOperator.modelLazyExecution(inputs, outputs, operatorContext);
    }

    /**
     * @return a function that reprojects the geometry of one element, reading it in the declared
     * source CRS or, if there is none, in the SRID the geometry carries
     */
    private Function<Type, Type> buildReprojection(JavaExecutor javaExecutor) {
        final Function<Type, ? extends SpatialGeometry> keyExtractor = javaExecutor.getCompiler().compile(this.keyDescriptor);
        final BiFunction<Type, SpatialGeometry, Type> geometryUpdater = this.geometryUpdater;
        final int declaredSourceSrid = this.sourceSrid;
        final int targetSrid = this.targetSrid;

        return input -> {
            final SpatialGeometry key = keyExtractor.apply(input);
            if (key == null) {
                return input;
            }
            final Geometry geometry = ((WayangGeometry) key).getGeometry();
            final int sourceSrid = declaredSourceSrid != SpatialGeometry.SRID_UNKNOWN
                    ? declaredSourceSrid
                    : geometry.getSRID();
            if (sourceSrid == SpatialGeometry.SRID_UNKNOWN) {
                throw new WayangException(String.format(
                        "Cannot reproject %s into EPSG:%d: it declares no SRID and the operator was given "
                                + "no source CRS. Pass an explicit source CRS.",
                        geometry, targetSrid));
            }
            final Geometry transformed = CrsTransforms.transform(geometry, sourceSrid, targetSrid);
            return geometryUpdater.apply(input, WayangGeometry.fromGeometry(transformed));
        };
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        assert index <= this.getNumInputs() || (index == 0 && this.getNumInputs() == 0);
        if (this.getInput(index).isBroadcast()) return Collections.singletonList(CollectionChannel.DESCRIPTOR);
        return Arrays.asList(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        assert index <= this.getNumOutputs() || (index == 0 && this.getNumOutputs() == 0);
        return Collections.singletonList(StreamChannel.DESCRIPTOR);
    }
}
