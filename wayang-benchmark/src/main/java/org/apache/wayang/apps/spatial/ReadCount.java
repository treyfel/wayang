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

import org.apache.wayang.basic.data.Record;
import org.apache.wayang.basic.operators.CountOperator;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.TextFileSource;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.platform.Platform;
import org.apache.wayang.java.Java;
import org.apache.wayang.postgres.Postgres;
import org.apache.wayang.postgres.operators.PostgresTableSource;
import org.apache.wayang.spark.Spark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

/**
 * Baseline for the spatial benchmarks: reads an input and counts it, without any spatial operator.
 * Its runs pin down the load profiles of the sources and of the count, so that calibration attributes
 * the rest of a spatial job's time to the spatial operator.
 * <p>
 * Args: {@code <ignored> <file-url> <java|spark>} or {@code <ignored> <table> postgres <host> <database>}.
 * Postgres reads {@code ST_AsText(geom)}, like the spatial apps.
 */
public class ReadCount {

    public static void main(String[] args) {
        System.out.println("Running Read Count Benchmark with args " + Arrays.toString(args));

        final String input = args[1];
        final String platform = args[2];
        final Configuration configuration = new Configuration();
        final Operator source;
        final Platform countPlatform;
        if (platform.equals("postgres")) {
            configuration.setProperty("wayang.postgres.jdbc.url", "jdbc:postgresql://" + args[3] + ":5432/" + args[4]);
            configuration.setProperty("wayang.postgres.jdbc.user", "wayang_user");
            configuration.setProperty("wayang.postgres.jdbc.password", "wayang");
            source = new PostgresTableSource(input, "ST_AsText(geom)");
            countPlatform = Java.platform();
        } else {
            source = new TextFileSource(input);
            countPlatform = platform.equals("spark") ? Spark.platform() : Java.platform();
            source.addTargetPlatform(countPlatform);
        }

        final WayangContext wayangContext = new WayangContext(configuration)
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Postgres.plugin());

        final CountOperator<?> count = platform.equals("postgres")
                ? new CountOperator<>(Record.class)
                : new CountOperator<>(String.class);
        count.addTargetPlatform(countPlatform);
        source.connectTo(0, count, 0);
        final Collection<Long> outputcount = new ArrayList<>();
        final LocalCallbackSink<Long> sink = LocalCallbackSink.createCollectingSink(outputcount, Long.class);
        count.connectTo(0, sink, 0);

        wayangContext.execute("Benchmark read_count", new WayangPlan(sink));

        System.out.println("Read Count Result Size: " + outputcount);
    }
}
