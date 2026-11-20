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

import org.apache.wayang.basic.operators.SpatialIndexKNNSearchOperator;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialIndex;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Java implementation of the {@link SpatialIndexKNNSearchOperator}: queries every index of the input and
 * merges their answers.
 */
public class JavaSpatialIndexKNNSearchOperator<Type>
        extends SpatialIndexKNNSearchOperator<Type>
        implements JavaExecutionOperator {

    public JavaSpatialIndexKNNSearchOperator(Integer k, SpatialGeometry referenceGeometry, DataSetType<Type> outputType) {
        super(k, referenceGeometry, outputType);
    }

    public JavaSpatialIndexKNNSearchOperator(SpatialIndexKNNSearchOperator<Type> that) {
        super(that);
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs,
            ChannelInstance[] outputs,
            JavaExecutor javaExecutor,
            OptimizationContext.OperatorContext operatorContext) {
        assert inputs.length == this.getNumInputs();
        assert outputs.length == this.getNumOutputs();
        this.validateArguments();

        final List<Tuple<Double, Type>> candidates = new ArrayList<>();
        ((JavaChannelInstance) inputs[0]).<SpatialIndex<Type>>provideStream()
                .forEach(index -> candidates.addAll(index.nearestNeighbours(this.referenceGeometry, this.k)));
        final List<Type> result = candidates.stream()
                .sorted(Comparator.comparingDouble(t -> t.field0))
                .limit(this.k)
                .map(t -> t.field1)
                .collect(Collectors.toList());
        ((CollectionChannel.Instance) outputs[0]).accept(result);

        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        assert index <= this.getNumInputs() || (index == 0 && this.getNumInputs() == 0);
        return Arrays.asList(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        assert index <= this.getNumOutputs() || (index == 0 && this.getNumOutputs() == 0);
        return Collections.singletonList(CollectionChannel.DESCRIPTOR);
    }
}
