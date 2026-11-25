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
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.channels.JavaChannelInstance;
import org.apache.wayang.java.channels.StreamChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * Java implementation of the {@link SpatialKNNSearchOperator}.
 * <p>
 * Scans the input once and keeps the k nearest elements in a bounded heap. Elements whose key is {@code null}
 * or empty are skipped. Among elements at equal distance, which ones are kept depends on input order.
 */
public class JavaSpatialKNNSearchOperator<Type>
        extends SpatialKNNSearchOperator<Type>
        implements JavaExecutionOperator {

    /**
     * Upper bound for the heap's initial capacity, so that a very large {@code k} does not allocate up front.
     */
    private static final int MAX_INITIAL_HEAP_CAPACITY = 1024;

    /**
     * Creates a new instance.
     *
     * @param k the number of nearest elements to return
     */
    public JavaSpatialKNNSearchOperator(Integer k,
                                        FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                        DataSetType<Type> inputClassDatasetType,
                                        SpatialGeometry geometry) {
        super(k, keyExtractor, inputClassDatasetType, geometry);
    }

    public JavaSpatialKNNSearchOperator(SpatialKNNSearchOperator<Type> that) {
        super(that);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs,
            ChannelInstance[] outputs,
            JavaExecutor javaExecutor,
            OptimizationContext.OperatorContext operatorContext) {
        assert inputs.length == this.getNumInputs();
        assert outputs.length == this.getNumOutputs();
        this.validateArguments();

        final Function<Type, ? extends SpatialGeometry> keyExtractor = javaExecutor.getCompiler().compile(this.keyDescriptor);
        final Geometry reference = ((WayangGeometry) this.referenceGeometry).getGeometry();
        final Envelope referenceEnvelope = reference.getEnvelopeInternal();
        final int referenceSrid = reference.getSRID();

        // Max-heap on distance: the head is the farthest of the current k nearest.
        final PriorityQueue<Tuple<Double, Type>> nearest = new PriorityQueue<>(
                Math.max(1, Math.min(this.k, MAX_INITIAL_HEAP_CAPACITY)),
                Comparator.comparingDouble((Tuple<Double, Type> t) -> t.field0).reversed());

        if (this.k > 0) {
            ((JavaChannelInstance) inputs[0]).<Type>provideStream().forEach(element -> {
                final SpatialGeometry key = keyExtractor.apply(element);
                if (key == null) {
                    return; // no geometry, no distance
                }
                final Geometry candidate = ((WayangGeometry) key).getGeometry();
                if (candidate == null || candidate.isEmpty()) {
                    return; // JTS reports distance 0 to an empty geometry, which would rank it first
                }
                final int candidateSrid = candidate.getSRID();
                if (referenceSrid != SpatialGeometry.SRID_UNKNOWN && candidateSrid != SpatialGeometry.SRID_UNKNOWN
                        && referenceSrid != candidateSrid) {
                    throw new WayangException(String.format(
                            "Spatial kNN search cannot compare SRID %d with reference SRID %d; transform one of them first.",
                            candidateSrid, referenceSrid));
                }
                final boolean full = nearest.size() >= this.k;
                // The envelope distance never exceeds the geometry distance, so a candidate whose envelope is
                // not closer than the current k-th nearest cannot replace it.
                if (full && referenceEnvelope.distance(candidate.getEnvelopeInternal()) >= nearest.peek().field0) {
                    return;
                }
                final double distance = reference.distance(candidate);
                if (!full) {
                    nearest.add(new Tuple<>(distance, element));
                } else if (distance < nearest.peek().field0) {
                    nearest.poll();
                    nearest.add(new Tuple<>(distance, element));
                }
            });
        }

        final List<Tuple<Double, Type>> sorted = new ArrayList<>(nearest);
        sorted.sort(Comparator.comparingDouble(t -> t.field0));
        final List<Type> result = new ArrayList<>(sorted.size());
        for (Tuple<Double, Type> t : sorted) {
            result.add(t.field1);
        }
        ((CollectionChannel.Instance) outputs[0]).accept(result);

        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
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
        return Collections.singletonList(CollectionChannel.DESCRIPTOR);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.spatialknnsearch.load";
    }
}
