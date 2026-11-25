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

import org.apache.wayang.core.api.exception.WayangException;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.spatial.data.WayangGeometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses an input line of the spatial benchmarks: a plain WKT line, or an OSM2015 CSV row
 * ({@code "WKT",id,[tags]}, where POINT rows come without quotes).
 */
final class SpatialInput {

    /**
     * {@link #parse} as a serializable key extractor for the spatial operators. Use this instead of a lambda in
     * an app: Spark executors deserialize a lambda through the class that defines it, and the apps reference the
     * Java platform, which executors do not have (NoClassDefFoundError: JavaPlatform). This class does not.
     */
    static final FunctionDescriptor.SerializableFunction<String, WayangGeometry> KEY = SpatialInput::parse;

    private SpatialInput() {
    }

    static WayangGeometry parse(String line) {
        if (line.startsWith("\"")) {
            final int end = line.indexOf("\",", 1);
            return WayangGeometry.fromStringInput(end < 0 ? line.replace("\"", "") : line.substring(1, end));
        }
        if (line.startsWith("POINT")) {
            final int end = line.indexOf(',');
            return WayangGeometry.fromStringInput(end < 0 ? line : line.substring(0, end));
        }
        return WayangGeometry.fromStringInput(line);
    }

    /**
     * Reads a query geometry given inline (WKT, EWKT, GeoJSON or WKB hex) or as {@code @<path>} to a file
     * holding one.
     */
    static WayangGeometry queryGeometry(String argument) {
        if (!argument.startsWith("@")) {
            return WayangGeometry.fromStringInput(argument);
        }
        try {
            return WayangGeometry.fromStringInput(Files.readString(Path.of(argument.substring(1))).trim());
        } catch (IOException e) {
            throw new WayangException("Could not read the query geometry from " + argument.substring(1), e);
        }
    }

    /**
     * The query box {@code (0,0)-(s,s)} of the original benchmarks.
     */
    static WayangGeometry originBox(String s) {
        return WayangGeometry.fromStringInput(
                "POLYGON((0.0 0.0, " + s + " 0.0, " + s + " " + s + ", 0.0 " + s + ", 0.0 0.0))");
    }
}
