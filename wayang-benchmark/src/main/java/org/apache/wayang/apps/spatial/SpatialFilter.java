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

import org.apache.wayang.api.JavaPlanBuilder;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.spark.Spark;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.java.Java;
import org.apache.wayang.spatial.Spatial;

import java.util.Arrays;
import java.util.Collection;

public class SpatialFilter {
    public static void main(String[] args) {
        System.out.println("Running Spatial Filter Benchmark with args " + Arrays.toString(args));

        String fileUrl = args[1];
        String platform = args[2];
        String selectivity = args[3];

        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Spatial.plugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("filter test")
                .withUdfJarOf(SpatialFilter.class);

        // The optional last argument replaces the (0,0)-(s,s) box with a query geometry (inline or @file).
        SpatialGeometry queryGeometry = args.length > 4
                ? SpatialInput.queryGeometry(args[4])
                : SpatialInput.originBox(selectivity);

        // Pin source, filter and count to one platform, so that every run measures the same plan.
        final Platform target = platform.equals("spark") ? Spark.platform() : Java.platform();
        Collection<Long> outputcount =
                planBuilder.readTextFile(fileUrl).withTargetPlatform(target)
                        .spatialFilter(
                                SpatialInput.KEY,
                                SpatialPredicate.INTERSECTS,
                                queryGeometry
                        ).withTargetPlatform(target)
                        .count().withTargetPlatform(target)
                        .collect();

        System.out.println("Spatial Filter Result Size: " + outputcount);
    }
}