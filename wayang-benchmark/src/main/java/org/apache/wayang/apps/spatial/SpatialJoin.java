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
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.java.Java;
import org.apache.wayang.spark.Spark;
import org.apache.wayang.spatial.Spatial;

import java.util.Arrays;
import java.util.Collection;

public class SpatialJoin {

    public static void main(String[] args) {
        System.out.println("Running Spatial Join Benchmark with args " + Arrays.toString(args));

        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Spatial.plugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext).withUdfJarOf(SpatialJoin.class);

        String file1Url = args[1];
        String file2Url = args[2];
        String platform = args[3];
        // Pin sources, join and count to one platform, so that every run measures the same plan.
        final Platform target = platform.equals("spark") ? Spark.platform() : Java.platform();
        var table1 = planBuilder.readTextFile(file1Url).withTargetPlatform(target);
        var table2 = planBuilder.readTextFile(file2Url).withTargetPlatform(target);

        Collection<Long> outputcount = table1
                .spatialJoin(
                        SpatialInput.KEY,
                        table2,
                        SpatialInput.KEY,
                        SpatialPredicate.INTERSECTS
                ).withTargetPlatform(target)
                .count().withTargetPlatform(target)
                .collect();
        System.out.println("Spatial Join Result Size: " + outputcount);
    }
}
