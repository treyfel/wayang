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

import org.apache.wayang.api.DataQuantaBuilder;
import org.apache.wayang.api.JavaPlanBuilder;
import org.apache.wayang.basic.data.Record;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.java.Java;
import org.apache.wayang.spatial.Spatial;
import org.apache.wayang.spatial.crs.CrsTransforms;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Berlin lakes in EPSG:3857, out of all OSM2015 lakes worldwide in EPSG:4326, with the CRS
 * transformation placed before or after the "inside Berlin" filter.
 *
 * <p>The large-data counterpart of {@link CrsTransformFilterOrderTest}: the lakes are streamed
 * from the CSV file instead of being loaded into memory, and each plan runs {@link #RUNS} times. Transforming
 * first reprojects every lake in the world; filtering first reprojects only Berlin's.</p>
 *
 * <p>The target is EPSG:3857 rather than the EPSG:25833 of the smaller test because a UTM zone
 * cannot hold the whole world: transforming every lake into EPSG:25833 fails on the first one with
 * no finite coordinate there (one on Borneo, 109.3E 1.8N).</p>
 *
 * <p>Each plan is a sequential Java API pipeline written out in its own test.
 * {@link #transformLakesThenFilterForBerlin()} runs first and its lakes are the reference the
 * other plan is checked against; {@link #writeReport()} tabulates whichever plans ran.</p>
 *
 * <p>Needs the unpacked {@code OSM2015_lakes.csv} in {@code ~/wayang/data/osm2015-full} and
 * {@code berlin.csv} in {@code ~/wayang/data}; skipped otherwise.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CrsTransformFilterOrderGlobalTest {

    private static final int LAKES_SRID = 4326;
    private static final int TARGET_SRID = 3857;

    private static final int ID_FIELD = 0;
    private static final int GEOMETRY_FIELD = 1;

    private static final double BORDER_TOLERANCE_METRES = 1.0;

    private static final int RUNS = 3;

    // The WKT is quoted unless it has no comma (a POINT). DOTALL: OSM tags can contain U+2028 and
    // similar, which end a line for '.' but not for Files.lines.
    private static final Pattern CSV_ROW = Pattern.compile("^(?:\"(.*)\"|([^\",]*)),(\\d+),.*$", Pattern.DOTALL);

    private static final String CSV_HEADER = "geometry,$1,$2";

    private enum Plan {
        TRANSFORM_LAKES_THEN_FILTER_FOR_BERLIN("Transform then filter",
                "berlin->3857, lakes->3857, filter lakes in 3857"),
        FILTER_LAKES_FOR_BERLIN_THEN_TRANSFORM("Filter then transform",
                "filter lakes in 4326, lakes->3857");

        final String label;

        final String description;

        Plan(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    private static final AtomicLong TRANSFORMED_GEOMETRIES = new AtomicLong();
    private static final AtomicLong TRANSFORMED_COORDINATES = new AtomicLong();

    private static String lakesUrl;
    private static List<Record> berlin;
    /** Only for checking results, outside the plans: the plans reproject the boundary themselves. */
    private static Geometry berlinIn3857;

    private static final Map<Plan, Outcome> OUTCOMES = new EnumMap<>(Plan.class);
    private static long[] loadNanos;
    private static Long lakeCount;
    private static SortedMap<String, Geometry> expectedLakes;
    private static SortedMap<String, Geometry> differingLakes;

    @BeforeAll
    static void loadData() {
        final Path lakesFile = Paths.get(System.getProperty("user.home"),
                "wayang", "data", "osm2015-full", "OSM2015_lakes.csv");
        final Path berlinFile = Paths.get(System.getProperty("user.home"), "wayang", "data", "berlin.csv");
        assumeTrue(Files.isRegularFile(lakesFile) && Files.isRegularFile(berlinFile),
                "Missing " + lakesFile + " or " + berlinFile);

        lakesUrl = lakesFile.toUri().toString();

        final Map.Entry<String, Geometry> berlinRow = parseRow(lines(berlinFile).get(1));
        berlin = List.of(new Record(berlinRow.getKey(), WayangGeometry.fromGeometry(berlinRow.getValue())));
        berlinIn3857 = CrsTransforms.transform(berlinRow.getValue(), LAKES_SRID, TARGET_SRID);
    }

    /**
     * Reads and parses every lake and does nothing else, so the plans' times can be reported
     * without the loading they share: the file is too large to load into memory up front.
     */
    @Test
    @Order(0)
    void loadLakes() {
        final long[] nanos = new long[RUNS];
        for (int run = 0; run < RUNS; run++) {
            final JavaPlanBuilder planBuilder = new JavaPlanBuilder(new WayangContext(new Configuration())
                    .withPlugin(Java.basicPlugin())
                    .withPlugin(Spatial.javaPlugin()))
                    .withJobName("load lakes");
            final long start = System.nanoTime();

            final Collection<Long> count = planBuilder.readTextFile(lakesUrl)
                    .filter(line -> !line.equals(CSV_HEADER))
                    .map(CrsTransformFilterOrderGlobalTest::readLake)
                    .count()
                    .collect();

            nanos[run] = System.nanoTime() - start;
            lakeCount = count.iterator().next();
        }
        loadNanos = nanos;
    }

    @Test
    @Order(1)
    void transformLakesThenFilterForBerlin() {
        final Plan plan = Plan.TRANSFORM_LAKES_THEN_FILTER_FOR_BERLIN;
        final long[] nanos = new long[RUNS];
        SortedMap<String, Geometry> berlinLakes = null;
        for (int run = 0; run < RUNS; run++) {
            TRANSFORMED_GEOMETRIES.set(0);
            TRANSFORMED_COORDINATES.set(0);
            final JavaPlanBuilder planBuilder = new JavaPlanBuilder(new WayangContext(new Configuration())
                    .withPlugin(Java.basicPlugin())
                    .withPlugin(Spatial.javaPlugin()))
                    .withJobName(plan.description);
            final long start = System.nanoTime();

            final DataQuantaBuilder<?, Record> lakesInBerlin = planBuilder.readTextFile(lakesUrl)
                    .filter(line -> !line.equals(CSV_HEADER))
                    .map(CrsTransformFilterOrderGlobalTest::readLake)
                    .crsTransform(CrsTransformFilterOrderGlobalTest::countedGeometry,
                            CrsTransformFilterOrderGlobalTest::withGeometry, TARGET_SRID)
                    .spatialFilter(CrsTransformFilterOrderGlobalTest::geometry, SpatialPredicate.WITHIN,
                            berlinIn(planBuilder, TARGET_SRID));
            final Collection<Record> collected = lakesInBerlin.collect();

            nanos[run] = System.nanoTime() - start;
            final SortedMap<String, Geometry> runLakes = byId(collected);
            if (berlinLakes != null) {
                assertEquals(berlinLakes.keySet(), runLakes.keySet(), plan.description + " differs between runs");
            }
            berlinLakes = runLakes;
        }
        OUTCOMES.put(plan, new Outcome(berlinLakes.size(), TRANSFORMED_GEOMETRIES.get(),
                TRANSFORMED_COORDINATES.get(), nanos));
        writeGeoJson(plan, "lakes", berlinLakes);

        assertFalse(berlinLakes.isEmpty());
        expectedLakes = berlinLakes;
    }

    @Test
    @Order(2)
    void filterLakesForBerlinThenTransform() {
        final Plan plan = Plan.FILTER_LAKES_FOR_BERLIN_THEN_TRANSFORM;
        final long[] nanos = new long[RUNS];
        SortedMap<String, Geometry> berlinLakes = null;
        for (int run = 0; run < RUNS; run++) {
            TRANSFORMED_GEOMETRIES.set(0);
            TRANSFORMED_COORDINATES.set(0);
            final JavaPlanBuilder planBuilder = new JavaPlanBuilder(new WayangContext(new Configuration())
                    .withPlugin(Java.basicPlugin())
                    .withPlugin(Spatial.javaPlugin()))
                    .withJobName(plan.description);
            final long start = System.nanoTime();

            final DataQuantaBuilder<?, Record> lakesInBerlin = planBuilder.readTextFile(lakesUrl)
                    .filter(line -> !line.equals(CSV_HEADER))
                    .map(CrsTransformFilterOrderGlobalTest::readLake)
                    .spatialFilter(CrsTransformFilterOrderGlobalTest::geometry, SpatialPredicate.WITHIN,
                            geometry(berlin.get(0)))
                    .crsTransform(CrsTransformFilterOrderGlobalTest::countedGeometry,
                            CrsTransformFilterOrderGlobalTest::withGeometry, TARGET_SRID);
            final Collection<Record> collected = lakesInBerlin.collect();

            nanos[run] = System.nanoTime() - start;
            final SortedMap<String, Geometry> runLakes = byId(collected);
            if (berlinLakes != null) {
                assertEquals(berlinLakes.keySet(), runLakes.keySet(), plan.description + " differs between runs");
            }
            berlinLakes = runLakes;
        }
        OUTCOMES.put(plan, new Outcome(berlinLakes.size(), TRANSFORMED_GEOMETRIES.get(),
                TRANSFORMED_COORDINATES.get(), nanos));
        writeGeoJson(plan, "lakes", berlinLakes);

        assumeTrue(expectedLakes != null, "transformLakesThenFilterForBerlin has not run, nothing to compare against");
        differingLakes = new TreeMap<>(expectedLakes);
        berlinLakes.forEach((id, geometry) -> {
            if (differingLakes.remove(id) == null) {
                differingLakes.put(id, geometry);
            }
        });
        writeGeoJson(plan, "differing", differingLakes);
        for (Map.Entry<String, Geometry> lake : differingLakes.entrySet()) {
            assertTrue(borderDistance(lake.getValue()) <= BORDER_TOLERANCE_METRES,
                    plan.description + " disagrees on lake " + lake.getKey() + ", "
                            + borderDistance(lake.getValue()) + " m from the Berlin border");
        }
    }

    @AfterAll
    static void writeReport() {
        if (!OUTCOMES.isEmpty()) {
            report(OUTCOMES);
        }
    }

    private static double meanSeconds(long[] nanos) {
        return Arrays.stream(nanos).average().getAsDouble() / 1e9;
    }

    private static String rangeSeconds(long[] nanos) {
        return String.format("%.1f-%.1f", Arrays.stream(nanos).min().getAsLong() / 1e9,
                Arrays.stream(nanos).max().getAsLong() / 1e9);
    }

    private static Record readLake(String line) {
        final Map.Entry<String, Geometry> row = parseRow(line);
        return new Record(row.getKey(), WayangGeometry.fromGeometry(row.getValue()));
    }

    /**
     * @return the row's lake id and geometry. The OSM id alone does not identify a lake: the
     * dataset splits some multipolygon relations into several rows that share it, so the id is
     * the OSM id plus a hash of the row's geometry.
     */
    private static Map.Entry<String, Geometry> parseRow(String line) {
        final Matcher matcher = CSV_ROW.matcher(line);
        if (!matcher.matches()) {
            throw new IllegalStateException("Not a geometry row: " + line.substring(0, Math.min(line.length(), 200)));
        }
        final String wkt = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Map.entry(matcher.group(3) + "/" + Integer.toHexString(wkt.hashCode()),
                WayangGeometry.fromStringInput("SRID=4326;" + wkt).getGeometry());
    }

    /**
     * Reprojects the Berlin boundary from {@link #LAKES_SRID} into {@code srid} with a CRS transformation,
     * so that the reprojection of the filter geometry is part of the plan's work and timing.
     */
    private static SpatialGeometry berlinIn(JavaPlanBuilder planBuilder, int srid) {
        final Collection<Record> reprojected = planBuilder.loadCollection(berlin)
                .crsTransform(CrsTransformFilterOrderGlobalTest::countedGeometry,
                        CrsTransformFilterOrderGlobalTest::withGeometry, srid)
                .collect();
        return geometry(reprojected.iterator().next());
    }

    private static WayangGeometry geometry(Record record) {
        return (WayangGeometry) record.getField(GEOMETRY_FIELD);
    }

    private static WayangGeometry countedGeometry(Record record) {
        final WayangGeometry geometry = geometry(record);
        TRANSFORMED_GEOMETRIES.incrementAndGet();
        TRANSFORMED_COORDINATES.addAndGet(geometry.getGeometry().getNumPoints());
        return geometry;
    }

    private static Record withGeometry(Record record, SpatialGeometry geometry) {
        final Object[] fields = new Object[record.size()];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = record.getField(i);
        }
        fields[GEOMETRY_FIELD] = geometry;
        return new Record(fields);
    }

    /**
     * @return the collected lakes by id, with their EPSG:3857 geometry
     */
    private static SortedMap<String, Geometry> byId(Collection<Record> lakes) {
        final SortedMap<String, Geometry> byId = new TreeMap<>();
        for (Record lake : lakes) {
            assertEquals(TARGET_SRID, geometry(lake).getSrid());
            assertNull(byId.put((String) lake.getField(ID_FIELD), geometry(lake).getGeometry()),
                    "Lake id " + lake.getField(ID_FIELD) + " names two lakes");
        }
        return byId;
    }

    /**
     * @return how far, in EPSG:3857 metres, the lake comes to the Berlin border
     */
    private static double borderDistance(Geometry lakeIn3857) {
        return berlinIn3857.getBoundary().distance(lakeIn3857);
    }

    /**
     * Writes the given lakes as GeoJSON features in EPSG:4326, so each plan's result can be loaded
     * into QGIS. Every feature carries {@code lake}, its id (OSM id/geometry hash).
     */
    private static void writeGeoJson(Plan plan, String layer, SortedMap<String, Geometry> lakes) {
        final GeoJsonWriter writer = new GeoJsonWriter();
        writer.setEncodeCRS(false);
        final StringBuilder features = new StringBuilder();
        for (Map.Entry<String, Geometry> lake : lakes.entrySet()) {
            if (features.length() > 0) {
                features.append(",\n");
            }
            features.append(String.format("{\"type\":\"Feature\",\"properties\":{\"lake\":\"%s\"},\"geometry\":%s}",
                    lake.getKey(), writer.write(CrsTransforms.transform(lake.getValue(), TARGET_SRID, LAKES_SRID))));
        }
        ExperimentResults.writeGeoJson("crs_global_" + plan.name().toLowerCase() + "_" + layer,
                "{\"type\":\"FeatureCollection\",\"features\":[\n" + features + "\n]}\n");
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Outcome {
        final int berlinLakes;
        final long geometries;
        final long coordinates;
        final long[] nanos;

        Outcome(int berlinLakes, long geometries, long coordinates, long[] nanos) {
            this.berlinLakes = berlinLakes;
            this.geometries = geometries;
            this.coordinates = coordinates;
            this.nanos = nanos;
        }
    }

    private static void report(Map<Plan, Outcome> outcomes) {
        final StringBuilder report = new StringBuilder(String.format(
                "CRS transform order - Berlin lakes out of all OSM2015 lakes, Java platform%n%n"
                        + "  lakes  %s  EPSG:%d -> EPSG:%d, streamed from %s%n  Berlin  EPSG:%d%n%n"
                        + "  %-40s %-50s %12s %14s %8s %9s %13s %9s%n",
                lakeCount == null ? "-" : String.format("%,d", lakeCount), LAKES_SRID, TARGET_SRID, lakesUrl,
                LAKES_SRID, "", "", "geometries", "coordinates", "Berlin", "total s", "range s", "net s"));
        for (Map.Entry<Plan, Outcome> entry : outcomes.entrySet()) {
            final Plan plan = entry.getKey();
            final Outcome outcome = entry.getValue();
            report.append(String.format("  %-40s %-50s %,12d %,14d %,8d %9.1f %13s %9s%n",
                    plan.name().toLowerCase(), plan.description, outcome.geometries,
                    outcome.coordinates, outcome.berlinLakes, meanSeconds(outcome.nanos), rangeSeconds(outcome.nanos),
                    loadNanos == null ? "-"
                            : String.format("%.1f", meanSeconds(outcome.nanos) - meanSeconds(loadNanos))));
        }
        if (loadNanos != null) {
            report.append(String.format("%n  Loading alone (read, parse, count): %.1f s (%s s); net s is total s minus it.",
                    meanSeconds(loadNanos), rangeSeconds(loadNanos)));
        }
        if (differingLakes != null) {
            report.append(String.format("%n  Lakes the plans disagree on: %d", differingLakes.size()));
            if (!differingLakes.isEmpty()) {
                final double farthest = differingLakes.values().stream()
                        .mapToDouble(CrsTransformFilterOrderGlobalTest::borderDistance).max().getAsDouble();
                report.append(String.format(", at most %.6f m from the Berlin border", farthest));
            }
            report.append('.');
        }
        report.append(String.format("%n  Times are the mean of %d runs each.%n", RUNS));
        System.out.print(report);
        ExperimentResults.writeText("crs_filter_order_global", report.toString());
        ExperimentResults.writeCsv("crs_filter_order_global", csv(outcomes));
    }

    /**
     * The report as a table for a spreadsheet chart: one row per plan, with its mean time split
     * into the loading that every plan shares and the rest. The load columns stay empty if
     * {@link #loadLakes()} did not run.
     */
    private static String csv(Map<Plan, Outcome> outcomes) {
        final StringBuilder csv = new StringBuilder(
                "plan,description,load_s,net_s,total_s,min_s,max_s,geometries,coordinates\n");
        for (Map.Entry<Plan, Outcome> entry : outcomes.entrySet()) {
            final Plan plan = entry.getKey();
            final Outcome outcome = entry.getValue();
            final double total = meanSeconds(outcome.nanos);
            csv.append(String.format(Locale.ROOT, "%s,\"%s\",%s,%s,%.2f,%.2f,%.2f,%d,%d%n",
                    plan.label, plan.description,
                    loadNanos == null ? "" : String.format(Locale.ROOT, "%.2f", meanSeconds(loadNanos)),
                    loadNanos == null ? "" : String.format(Locale.ROOT, "%.2f", total - meanSeconds(loadNanos)),
                    total,
                    Arrays.stream(outcome.nanos).min().getAsLong() / 1e9,
                    Arrays.stream(outcome.nanos).max().getAsLong() / 1e9,
                    outcome.geometries, outcome.coordinates));
        }
        return csv.toString();
    }
}
