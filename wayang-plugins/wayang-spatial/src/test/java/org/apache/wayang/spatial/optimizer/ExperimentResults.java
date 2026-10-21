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

package org.apache.wayang.spatial.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes what a demonstration test measured to {@code target/experiment-results}, so the numbers
 * outlive the console. {@code experiments/plot_crs_ordering.py} reads them from there.
 *
 * The directory is resolved against the working directory, which Surefire sets to the module's
 * own base directory. Writing is best-effort: a test that has proven its point must not go red
 * because a filesystem was read-only.
 */
final class ExperimentResults {

    private static final Path OUTPUT_DIRECTORY = Paths.get("target", "experiment-results");

    private ExperimentResults() {
    }

    /**
     * Writes the machine-readable form of a result, for the plotting script.
     *
     * @param name the file name, without the {@code .json} extension
     * @param json the contents
     */
    static void writeJson(String name, String json) {
        write(name + ".json", json);
    }

    /**
     * Writes the human-readable form of a result. This is where a run's findings go: a test
     * reports to its file, not to the console, so the numbers survive the scrollback.
     *
     * @param name the file name, without the {@code .txt} extension
     * @param text the report
     */
    static void writeText(String name, String text) {
        write(name + ".txt", text);
    }

    /**
     * Writes a result as a table, to be imported into a spreadsheet and charted there.
     *
     * @param name the file name, without the {@code .csv} extension
     * @param csv  the contents, with a header row
     */
    static void writeCsv(String name, String csv) {
        write(name + ".csv", csv);
    }

    /**
     * Writes a result as a GeoJSON FeatureCollection, to be opened in a GIS such as QGIS.
     *
     * @param name    the file name, without the {@code .geojson} extension
     * @param geoJson the FeatureCollection, with coordinates in EPSG:4326
     */
    static void writeGeoJson(String name, String geoJson) {
        write(name + ".geojson", geoJson);
    }

    private static void write(String fileName, String contents) {
        final Path file = OUTPUT_DIRECTORY.resolve(fileName);
        try {
            Files.createDirectories(OUTPUT_DIRECTORY);
            Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
            System.out.printf("  -> %s%n", file.toAbsolutePath());
        } catch (IOException e) {
            System.out.printf("  could not write %s: %s%n", file, e.getMessage());
        }
    }
}
