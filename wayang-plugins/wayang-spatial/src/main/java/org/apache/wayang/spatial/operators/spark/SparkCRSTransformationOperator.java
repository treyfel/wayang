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

package org.apache.wayang.spatial.operators.spark;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
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
import org.apache.wayang.core.util.ReflectionUtils;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.spark.channels.RddChannel;
import org.apache.wayang.spark.execution.SparkExecutor;
import org.apache.wayang.spark.operators.SparkExecutionOperator;
import org.apache.wayang.spatial.crs.CrsTransforms;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.proj4j.CoordinateTransform;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Spark implementation of the {@link CRSTransformationOperator}
 */
public class SparkCRSTransformationOperator<Type> extends CRSTransformationOperator<Type> implements SparkExecutionOperator {
    public SparkCRSTransformationOperator(
            FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
            FunctionDescriptor.SerializableBiFunction<Type, SpatialGeometry, Type> geometryUpdater,
            DataSetType<Type> inputClassDatasetType,
            int sourceSrid,
            int targetSrid) {
        super(keyExtractor, geometryUpdater, inputClassDatasetType, sourceSrid, targetSrid);
    }

    public SparkCRSTransformationOperator(CRSTransformationOperator<Type> that) {
        super(that);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs,
            ChannelInstance[] outputs,
            SparkExecutor sparkExecutor,
            OptimizationContext.OperatorContext operatorContext) {
        assert inputs.length == this.getNumInputs();
        assert outputs.length == this.getNumOutputs();

        // Register the proj4j JAR with Spark executors if running in cluster mode.
        if (!sparkExecutor.sc.isLocal()) {
            String proj4jJar = ReflectionUtils.getDeclaringJar(CoordinateTransform.class);
            if (proj4jJar != null) {
                sparkExecutor.sc.addJar(proj4jJar);
            }
        }

        final JavaRDD<Type> inputRdd = ((RddChannel.Instance) inputs[0]).provideRdd();
        final JavaRDD<Type> outputRdd = inputRdd.map(this.buildReprojection());
        this.name(outputRdd);
        ((RddChannel.Instance) outputs[0]).accept(outputRdd, sparkExecutor);

        return ExecutionOperator.modelLazyExecution(inputs, outputs, operatorContext);
    }

    /**
     * @return a function that reprojects the geometry of one element, reading it in the declared
     * source CRS or, if there is none, in the SRID the geometry carries
     */
    @SuppressWarnings("unchecked")
    private Function<Type, Type> buildReprojection() {
        // Copied into locals so that the closure Spark ships to its executors does not capture
        // this operator. The proj4j transforms are not shipped either: CrsTransforms builds them
        // per thread where the closure runs.
        final FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor =
                (FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry>) this.keyDescriptor.getJavaImplementation();
        final FunctionDescriptor.SerializableBiFunction<Type, SpatialGeometry, Type> geometryUpdater = this.geometryUpdater;
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
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.spark.crstransform.load";
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return Arrays.asList(RddChannel.UNCACHED_DESCRIPTOR, RddChannel.CACHED_DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        return Collections.singletonList(RddChannel.UNCACHED_DESCRIPTOR);
    }

    @Override
    public boolean containsAction() {
        return false;
    }

}
