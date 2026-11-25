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

import org.apache.sedona.core.spatialRDD.SpatialRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.wayang.core.util.ReflectionUtils;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.locationtech.jts.io.geojson.GeoJsonReader;

/**
 * Ships the JARs that the Spark spatial operators' tasks need to the executors. On a non-local Spark context,
 * Wayang only ships wayang-spark, wayang-basic, wayang-core and the UDF JARs; without this, executors fail to
 * deserialize the tasks with {@link NoClassDefFoundError}s.
 */
final class SparkSpatialJars {

    /**
     * One class per JAR: Sedona (which includes jts-core), this plugin ({@link WayangGeometry} and the operators'
     * own functions), and jts-io-common, which {@link WayangGeometry} needs for GeoJSON.
     */
    private static final Class<?>[] CLASSES = {SpatialRDD.class, WayangGeometry.class, GeoJsonReader.class};

    /**
     * jts-io-common's own dependency for GeoJSON; looked up by name, as this plugin does not depend on it directly.
     */
    private static final String JSON_SIMPLE_CLASS = "org.json.simple.parser.JSONParser";

    private SparkSpatialJars() {
    }

    static void register(JavaSparkContext sparkContext) {
        if (sparkContext.isLocal()) {
            return;
        }
        for (Class<?> cls : CLASSES) {
            addJarOf(sparkContext, cls);
        }
        try {
            addJarOf(sparkContext, Class.forName(JSON_SIMPLE_CLASS));
        } catch (ClassNotFoundException e) {
            // Without it, only GeoJSON parsing on the executors fails.
        }
    }

    private static void addJarOf(JavaSparkContext sparkContext, Class<?> cls) {
        final String jar = ReflectionUtils.getDeclaringJar(cls);
        if (jar != null) {
            sparkContext.addJar(jar);
        }
    }
}
