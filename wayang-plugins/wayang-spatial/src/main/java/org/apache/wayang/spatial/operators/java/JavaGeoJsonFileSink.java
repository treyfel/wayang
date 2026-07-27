/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.wayang.spatial.operators.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.wayang.basic.data.Record;
import org.apache.wayang.basic.operators.GeoJsonFileSink;
import org.apache.wayang.core.api.exception.WayangException;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.ChannelDescriptor;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.lineage.ExecutionLineageNode;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.java.channels.JavaChannelInstance;
import org.apache.wayang.java.channels.StreamChannel;
import org.apache.wayang.java.execution.JavaExecutor;
import org.apache.wayang.java.operators.JavaExecutionOperator;
import org.apache.wayang.spatial.data.WayangGeometry;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Java execution operator that writes a stream of {@link Record}s as a GeoJSON FeatureCollection.
 * Each Record must have two fields:
 * <ul>
 *   <li>field[0]: {@link WayangGeometry} – the feature geometry</li>
 *   <li>field[1]: {@link Map}{@code <String, Object>} – the feature properties</li>
 * </ul>
 */
public class JavaGeoJsonFileSink extends GeoJsonFileSink implements JavaExecutionOperator {

    public JavaGeoJsonFileSink(String outputUrl) {
        super(outputUrl);
    }

    public JavaGeoJsonFileSink(GeoJsonFileSink that) {
        super(that);
    }

    @Override
    public Tuple<Collection<ExecutionLineageNode>, Collection<ChannelInstance>> evaluate(
            ChannelInstance[] inputs,
            ChannelInstance[] outputs,
            JavaExecutor javaExecutor,
            OptimizationContext.OperatorContext operatorContext) {

        assert inputs.length == 1;
        assert outputs.length == 0;

        JavaChannelInstance input = (JavaChannelInstance) inputs[0];
        ObjectMapper mapper = new ObjectMapper();

        String path = this.getOutputUrl();
        if (path.startsWith("file:")) {
            path = URI.create(path).getPath();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
            writer.write("{\"type\":\"FeatureCollection\",\"features\":[");

            boolean[] first = {true};
            input.<Record>provideStream().forEach(record -> {
                try {
                    WayangGeometry geom = (WayangGeometry) record.getField(0);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = (Map<String, Object>) record.getField(1);

                    ObjectNode feature = mapper.createObjectNode();
                    feature.put("type", "Feature");
                    feature.set("geometry", mapper.readTree(geom.getGeoJSON()));
                    feature.set("properties", mapper.valueToTree(props));

                    if (!first[0]) writer.write(",");
                    writer.write(mapper.writeValueAsString(feature));
                    first[0] = false;
                } catch (IOException e) {
                    throw new WayangException("Failed to write GeoJSON feature.", e);
                }
            });

            writer.write("]}");
        } catch (IOException e) {
            throw new WayangException("Failed to write GeoJSON file: " + this.getOutputUrl(), e);
        }

        return ExecutionOperator.modelEagerExecution(inputs, outputs, operatorContext);
    }

    @Override
    public String getLoadProfileEstimatorConfigurationKey() {
        return "wayang.java.geojsonfilesink.load";
    }

    @Override
    public List<ChannelDescriptor> getSupportedInputChannels(int index) {
        return Arrays.asList(CollectionChannel.DESCRIPTOR, StreamChannel.DESCRIPTOR);
    }

    @Override
    public List<ChannelDescriptor> getSupportedOutputChannels(int index) {
        throw new UnsupportedOperationException();
    }
}
