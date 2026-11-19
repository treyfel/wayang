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

import org.apache.sedona.core.enums.IndexType;
import org.apache.sedona.core.knnJudgement.GeometryDistanceComparator;
import org.apache.sedona.core.knnJudgement.KnnJudgementUsingIndex;
import org.apache.sedona.core.spatialRDD.SpatialRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.wayang.basic.operators.SpatialKNNSearchOperator;
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
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.spark.channels.RddChannel;
import org.apache.wayang.spark.execution.SparkExecutor;
import org.apache.wayang.spark.operators.SparkExecutionOperator;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Spark implementation of the {@link SpatialKNNSearchOperator}.
 */
public class SparkSpatialKNNSearchOperator<Type>
        extends SpatialKNNSearchOperator<Type>
        implements SparkExecutionOperator {

    /**
     * Creates a new instance.
     *
     * @param k the number of nearest elements to return
     */
    public SparkSpatialKNNSearchOperator(Integer k,
                                         FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                         DataSetType<Type> inputClassDatasetType,
                                         SpatialGeometry geometry) {
        super(k, keyExtractor, inputClassDatasetType, geometry);
    }

    /**
     * Copies an instance (exclusive of broadcasts).
     *
     * @param that that should be copied
     */
    public SparkSpatialKNNSearchOperator(SpatialKNNSearchOperator<Type> that) {
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
        this.validateArguments();

        // Register Sedona JAR with Spark executors if running in cluster mode.
        if (!sparkExecutor.sc.isLocal()) {
            String sedonaJar = ReflectionUtils.getDeclaringJar(SpatialRDD.class);
            if (sedonaJar != null) {
                sparkExecutor.sc.addJar(sedonaJar);
            }
        }

        final Geometry reference = ((WayangGeometry) this.referenceGeometry).getGeometry();

        final List<Type> result = new ArrayList<>();
        if (this.k > 0) {
            final JavaRDD<Type> inputRdd = ((RddChannel.Instance) inputs[0]).provideRdd();

            final FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor =
                    (FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry>) this.keyDescriptor.getJavaImplementation();

            final JavaRDD<Geometry> geometryRdd = inputRdd
                    .map((Type value) -> {
                        final Geometry geom = ((WayangGeometry) keyExtractor.apply(value)).getGeometry();
                        geom.setUserData(value);
                        return geom;
                    });

            final SpatialRDD<Geometry> spatialRDD = new SpatialRDD<>();
            spatialRDD.setRawSpatialRDD(geometryRdd);
            try {
                spatialRDD.buildIndex(IndexType.RTREE, false);
            } catch (Exception e) {
                throw new WayangException(e);
            }

            final List<Geometry> nearest = spatialRDD.indexedRawRDD
                    .mapPartitions(new KnnJudgementUsingIndex<>(reference, this.k))
                    .takeOrdered(this.k, new GeometryDistanceComparator<>(reference, true));
            for (Geometry geom : nearest) {
                result.add((Type) geom.getUserData());
            }
        }
        ((CollectionChannel.Instance) outputs[0]).accept(result);

        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.spark.spatialknnsearch.load";
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return Arrays.asList(RddChannel.UNCACHED_DESCRIPTOR, RddChannel.CACHED_DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        return Collections.singletonList(CollectionChannel.DESCRIPTOR);
    }

    @Override
    public boolean containsAction() {
        return true;
    }

}
