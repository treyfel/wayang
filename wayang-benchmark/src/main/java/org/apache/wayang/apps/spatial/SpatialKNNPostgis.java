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
import org.apache.wayang.basic.operators.SpatialKNNSearchOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.java.Java;
import org.apache.wayang.postgres.Postgres;
import org.apache.wayang.postgres.operators.PostgresTableSource;
import org.apache.wayang.spatial.Spatial;
import org.apache.wayang.spatial.data.WayangGeometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

/**
 * k-nearest-neighbour search in PostGIS ({@code ORDER BY geom <-> reference LIMIT k}).
 * <p>
 * Args: {@code <ignored> <table> <host> <database> <k> [reference EWKT, default SRID=4326;POINT (0.5 0.5)]}.
 * The reference needs the column's SRID.
 */
public class SpatialKNNPostgis {

    public static void main(String[] args) {
        System.out.println("Running Spatial KNN Benchmark with args " + Arrays.toString(args) + " on Postgres");

        final String tableName = args[1];
        final String host = args[2];
        final String database = args[3];
        final int k = Integer.parseInt(args[4]);
        final String reference = args.length > 5 ? args[5] : "SRID=4326;POINT (0.5 0.5)";

        final Configuration configuration = new Configuration();
        configuration.setProperty("wayang.postgres.jdbc.url", "jdbc:postgresql://" + host + ":5432/" + database);
        configuration.setProperty("wayang.postgres.jdbc.user", "wayang_user");
        configuration.setProperty("wayang.postgres.jdbc.password", "wayang");

        final WayangContext wayangContext = new WayangContext(configuration)
                .withPlugin(Java.basicPlugin())
                .withPlugin(Postgres.plugin())
                .withPlugin(Spatial.plugin());

        final PostgresTableSource source = new PostgresTableSource(tableName, "ST_AsText(geom)");
        final SpatialKNNSearchOperator<Record> knn = new SpatialKNNSearchOperator<>(
                k,
                record -> WayangGeometry.fromStringInput(record.getString(0)),
                DataSetType.createDefault(Record.class),
                WayangGeometry.fromStringInput(reference));
        knn.getKeyDescriptor().withSqlImplementation(tableName, "geom");
        knn.addTargetPlatform(Postgres.platform());
        source.connectTo(0, knn, 0);

        final CountOperator<Record> count = new CountOperator<>(Record.class);
        knn.connectTo(0, count, 0);
        final Collection<Long> outputcount = new ArrayList<>();
        final LocalCallbackSink<Long> sink = LocalCallbackSink.createCollectingSink(outputcount, Long.class);
        count.connectTo(0, sink, 0);

        wayangContext.execute("Benchmark spatial_knn_postgis", new WayangPlan(sink));

        System.out.println("Spatial KNN Postgres Result Size: " + outputcount);
    }
}
