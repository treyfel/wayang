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

package org.apache.wayang.apps.spatial;

import org.apache.wayang.basic.operators.CountOperator;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.SpatialIndexKNNSearchOperator;
import org.apache.wayang.basic.operators.SpatialIndexOperator;
import org.apache.wayang.basic.operators.SpatialKNNSearchOperator;
import org.apache.wayang.basic.operators.TextFileSource;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.plan.wayangplan.OutputSlot;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.core.util.ReflectionUtils;
import org.apache.wayang.java.Java;
import org.apache.wayang.spark.Spark;
import org.apache.wayang.spatial.Spatial;
import org.apache.wayang.spatial.data.WayangGeometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

/**
 * k-nearest-neighbour search over a file of geometries.
 * <p>
 * Args: {@code <ignored> <file-url> <java|spark|javaindex> <k> [reference WKT, default POINT (0.5 0.5)]}.
 * {@code javaindex} builds a {@link SpatialIndexOperator} and queries it with a
 * {@link SpatialIndexKNNSearchOperator}; the others run a {@link SpatialKNNSearchOperator}.
 * Lines are parsed by {@link SpatialInput}.
 */
public class SpatialKNN {

    public static void main(String[] args) {
        System.out.println("Running Spatial KNN Benchmark with args " + Arrays.toString(args));

        final String fileUrl = args[1];
        final String platform = args[2];
        final int k = Integer.parseInt(args[3]);
        final SpatialGeometry reference = WayangGeometry.fromStringInput(args.length > 4 ? args[4] : "POINT (0.5 0.5)");

        final WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Spatial.plugin());

        final TextFileSource source = new TextFileSource(fileUrl);
        final OutputSlot<String> nearest;
        if (platform.equals("javaindex")) {
            final SpatialIndexOperator<String> index =
                    new SpatialIndexOperator<>(SpatialInput.KEY, DataSetType.createDefault(String.class));
            final SpatialIndexKNNSearchOperator<String> knn =
                    new SpatialIndexKNNSearchOperator<>(k, reference, DataSetType.createDefault(String.class));
            index.addTargetPlatform(Java.platform());
            knn.addTargetPlatform(Java.platform());
            source.connectTo(0, index, 0);
            index.connectTo(0, knn, 0);
            nearest = knn.getOutput();
        } else {
            final SpatialKNNSearchOperator<String> knn = new SpatialKNNSearchOperator<>(
                    k, SpatialInput.KEY, DataSetType.createDefault(String.class), reference);
            knn.addTargetPlatform(platform.equals("spark") ? Spark.platform() : Java.platform());
            source.connectTo(0, knn, 0);
            nearest = knn.getOutput();
        }

        final CountOperator<String> count = new CountOperator<>(String.class);
        nearest.connectTo(count.getInput());
        final Collection<Long> outputcount = new ArrayList<>();
        final LocalCallbackSink<Long> sink = LocalCallbackSink.createCollectingSink(outputcount, Long.class);
        count.connectTo(0, sink, 0);

        wayangContext.execute("Benchmark spatial_knn", new WayangPlan(sink), ReflectionUtils.getDeclaringJar(SpatialKNN.class));

        System.out.println("Spatial KNN Result Size: " + outputcount);
    }
}
