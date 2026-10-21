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
import org.apache.wayang.basic.data.Tuple2;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Berlin parks that intersect a lake, with lakes in EPSG:4326, parks in EPSG:25833 and the Berlin
 * boundary in EPSG:3857, run with the CRS transformations placed at different points of the plan.
 *
 * <p>Placement must not change which park and lake overlap. It can change pairs that only touch:
 * shared OSM vertices stay identical only if both sides took the same transformation path.</p>
 *
 * <p>Each plan is a sequential Java API pipeline written out in its own test, with the Berlin
 * filter as part of the plan. {@link #transformLakesIntoParkCrs()} runs first and its pairs are
 * the reference the other plans are checked against; {@link #writeReport()} tabulates whichever
 * plans ran.</p>
 *
 * <p>Needs {@code OSM2015_lakes.csv}, {@code OSM2015_parks.csv} and {@code berlin.csv} in
 * {@code ~/wayang/data}; skipped otherwise. {@code berlin.csv} is the boundary fetched by
 * {@code src/test/scripts/fetch_osm_berlin.py}, in the layout of the other two files.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CrsTransformTest {

    private static final int LAKES_SRID = 4326;
    private static final int PARKS_SRID = 25833;
    private static final int BERLIN_SRID = 3857;

    private static final int ID_FIELD = 0;
    private static final int GEOMETRY_FIELD = 1;

    private static final int TIMED_REPETITIONS = 5;

    private static final double CONTACT_TOLERANCE_METRES = 0.001;

    private static final Pattern CSV_ROW = Pattern.compile("^\"(.*)\",\\d+,.*$");

    private enum Plan {
        TRANSFORM_LAKES_INTO_PARK_CRS("berlin->25833, lakes->25833, filter parks, join in 25833"),
        TRANSFORM_PARKS_INTO_LAKE_CRS_BEFORE_BERLIN_FILTER("berlin->4326, parks->4326, filter parks, join in 4326"),
        TRANSFORM_PARKS_INTO_LAKE_CRS_AFTER_BERLIN_FILTER("berlin->25833, filter parks, parks->4326, join in 4326"),
        TRANSFORM_BOTH_INTO_BERLIN_CRS("lakes->3857, parks->3857, filter parks, join in 3857");

        final String description;

        Plan(String description) {
            this.description = description;
        }
    }

    private static final AtomicLong TRANSFORMED_GEOMETRIES = new AtomicLong();
    private static final AtomicLong TRANSFORMED_COORDINATES = new AtomicLong();

    private static List<Record> lakes;
    private static List<Record> parks;
    private static List<Record> berlin;

    private static final Map<Plan, Outcome> OUTCOMES = new EnumMap<>(Plan.class);
    private static SortedSet<String> expectedPairs;

    @BeforeAll
    static void loadData() {
        final Path dataDirectory = Paths.get(System.getProperty("user.home"), "wayang", "data");
        assumeTrue(Files.isRegularFile(dataDirectory.resolve("OSM2015_lakes.csv"))
                        && Files.isRegularFile(dataDirectory.resolve("OSM2015_parks.csv"))
                        && Files.isRegularFile(dataDirectory.resolve("berlin.csv")),
                "Missing OSM data in " + dataDirectory);

        lakes = readCsv(dataDirectory.resolve("OSM2015_lakes.csv"), LAKES_SRID);
        parks = readCsv(dataDirectory.resolve("OSM2015_parks.csv"), PARKS_SRID);
        berlin = readCsv(dataDirectory.resolve("berlin.csv"), BERLIN_SRID);
    }

    @Test
    @Order(1)
    void transformLakesIntoParkCrs() {
        final Plan plan = Plan.TRANSFORM_LAKES_INTO_PARK_CRS;
        final long[] timings = new long[TIMED_REPETITIONS];
        SortedSet<String> pairs = null;
        // The first execution warms up and is not timed.
        for (int execution = -1; execution < TIMED_REPETITIONS; execution++) {
            TRANSFORMED_GEOMETRIES.set(0);
            TRANSFORMED_COORDINATES.set(0);
            final JavaPlanBuilder planBuilder = new JavaPlanBuilder(new WayangContext(new Configuration())
                    .withPlugin(Java.basicPlugin())
                    .withPlugin(Spatial.javaPlugin()))
                    .withJobName(plan.description);
            final long start = System.nanoTime();

            final DataQuantaBuilder<?, Record> lakesIn25833 = planBuilder.loadCollection(lakes)
                    .crsTransform(CrsTransformTest::countedGeometry,         // read and count the geometry
                            CrsTransformTest::withGeometry,                  // write the reprojected one back
                            PARKS_SRID);
            final DataQuantaBuilder<?, Record> parksInBerlin = planBuilder.loadCollection(parks)
                    .spatialFilter(CrsTransformTest::geometry, SpatialPredicate.WITHIN, berlinIn(planBuilder, PARKS_SRID));
            final Collection<String> collected = lakesIn25833
                    .spatialJoin(CrsTransformTest::geometry, parksInBerlin, CrsTransformTest::geometry,
                            SpatialPredicate.INTERSECTS)
                    .map(pair -> pairOf(pair, PARKS_SRID))
                    .collect();

            if (execution >= 0) {
                timings[execution] = System.nanoTime() - start;
            }
            pairs = new TreeSet<>(collected);
        }
        Arrays.sort(timings);
        OUTCOMES.put(plan, new Outcome(pairs, TRANSFORMED_GEOMETRIES.get(), TRANSFORMED_COORDINATES.get(),
                timings[TIMED_REPETITIONS / 2]));
        writeGeoJson(plan, "pairs", pairs);

        assertFalse(pairs.isEmpty());
        expectedPairs = pairs;
    }

    @Test
    void transformParksIntoLakeCrsBeforeBerlinFilter() {
        final Plan plan = Plan.TRANSFORM_PARKS_INTO_LAKE_CRS_BEFORE_BERLIN_FILTER;
        final long[] timings = new long[TIMED_REPETITIONS];
        SortedSet<String> pairs = null;
        // The first execution warms up and is not timed.
        for (int execution = -1; execution < TIMED_REPETITIONS; execution++) {
            TRANSFORMED_GEOMETRIES.set(0);
            TRANSFORMED_COORDINATES.set(0);
            final JavaPlanBuilder planBuilder = new JavaPlanBuilder(new WayangContext(new Configuration())
                    .withPlugin(Java.basicPlugin())
                    .withPlugin(Spatial.javaPlugin()))
                    .withJobName(plan.description);
            final long start = System.nanoTime();

            final DataQuantaBuilder<?, Record> lakesIn4326 = planBuilder.loadCollection(lakes);
            final DataQuantaBuilder<?, Record> parksInBerlin = planBuilder.loadCollection(parks)
                    .crsTransform(CrsTransformTest::countedGeometry, CrsTransformTest::withGeometry, LAKES_SRID)
                    .spatialFilter(CrsTransformTest::geometry, SpatialPredicate.WITHIN, berlinIn(planBuilder, LAKES_SRID));
            final Collection<String> collected = lakesIn4326
                    .spatialJoin(CrsTransformTest::geometry, parksInBerlin, CrsTransformTest::geometry,
                            SpatialPredicate.INTERSECTS)
                    .map(pair -> pairOf(pair, LAKES_SRID))
                    .collect();

            if (execution >= 0) {
                timings[execution] = System.nanoTime() - start;
            }
            pairs = new TreeSet<>(collected);
        }
        Arrays.sort(timings);
        OUTCOMES.put(plan, new Outcome(pairs, TRANSFORMED_GEOMETRIES.get(), TRANSFORMED_COORDINATES.get(),
                timings[TIMED_REPETITIONS / 2]));
        writeGeoJson(plan, "pairs", pairs);

        assumeTrue(expectedPairs != null, "transformLakesIntoParkCrs has not run, nothing to compare against");
        final SortedSet<String> missing = new TreeSet<>(expectedPairs);
        missing.removeAll(pairs);
        writeGeoJson(plan, "missing", missing);
        assertTrue(expectedPairs.containsAll(pairs), plan.description + " found pairs that do not intersect");
        for (String pair : expectedPairs) {
            assertTrue(pairs.contains(pair) || touchesOnly(pair),
                    plan.description + " lost the overlapping pair " + pair);
        }
    }

    @Test
    void transformParksIntoLakeCrsAfterBerlinFilter() {
        final Plan plan = Plan.TRANSFORM_PARKS_INTO_LAKE_CRS_AFTER_BERLIN_FILTER;
        final long[] timings = new long[TIMED_REPETITIONS];
        SortedSet<String> pairs = null;
        // The first execution warms up and is not timed.
        for (int execution = -1; execution < TIMED_REPETITIONS; execution++) {
            TRANSFORMED_GEOMETRIES.set(0);
            TRANSFORMED_COORDINATES.set(0);
            final JavaPlanBuilder planBuilder = new JavaPlanBuilder(new WayangContext(new Configuration())
                    .withPlugin(Java.basicPlugin())
                    .withPlugin(Spatial.javaPlugin()))
                    .withJobName(plan.description);
            final long start = System.nanoTime();

            final DataQuantaBuilder<?, Record> lakesIn4326 = planBuilder.loadCollection(lakes);
            final DataQuantaBuilder<?, Record> parksInBerlin = planBuilder.loadCollection(parks)
                    .spatialFilter(CrsTransformTest::geometry, SpatialPredicate.WITHIN, berlinIn(planBuilder, PARKS_SRID))
                    .crsTransform(CrsTransformTest::countedGeometry, CrsTransformTest::withGeometry, LAKES_SRID);
            final Collection<String> collected = lakesIn4326
                    .spatialJoin(CrsTransformTest::geometry, parksInBerlin, CrsTransformTest::geometry,
                            SpatialPredicate.INTERSECTS)
                    .map(pair -> pairOf(pair, LAKES_SRID))
                    .collect();

            if (execution >= 0) {
                timings[execution] = System.nanoTime() - start;
            }
            pairs = new TreeSet<>(collected);
        }
        Arrays.sort(timings);
        OUTCOMES.put(plan, new Outcome(pairs, TRANSFORMED_GEOMETRIES.get(), TRANSFORMED_COORDINATES.get(),
                timings[TIMED_REPETITIONS / 2]));
        writeGeoJson(plan, "pairs", pairs);

        assumeTrue(expectedPairs != null, "transformLakesIntoParkCrs has not run, nothing to compare against");
        final SortedSet<String> missing = new TreeSet<>(expectedPairs);
        missing.removeAll(pairs);
        writeGeoJson(plan, "missing", missing);
        assertTrue(expectedPairs.containsAll(pairs), plan.description + " found pairs that do not intersect");
        for (String pair : expectedPairs) {
            assertTrue(pairs.contains(pair) || touchesOnly(pair),
                    plan.description + " lost the overlapping pair " + pair);
        }
    }

    @Test
    void transformBothIntoBerlinCrs() {
        final Plan plan = Plan.TRANSFORM_BOTH_INTO_BERLIN_CRS;
        final long[] timings = new long[TIMED_REPETITIONS];
        SortedSet<String> pairs = null;
        // The first execution warms up and is not timed.
        for (int execution = -1; execution < TIMED_REPETITIONS; execution++) {
            TRANSFORMED_GEOMETRIES.set(0);
            TRANSFORMED_COORDINATES.set(0);
            final JavaPlanBuilder planBuilder = new JavaPlanBuilder(new WayangContext(new Configuration())
                    .withPlugin(Java.basicPlugin())
                    .withPlugin(Spatial.javaPlugin()))
                    .withJobName(plan.description);
            final long start = System.nanoTime();

            final DataQuantaBuilder<?, Record> lakesIn3857 = planBuilder.loadCollection(lakes)
                    .crsTransform(CrsTransformTest::countedGeometry, CrsTransformTest::withGeometry, BERLIN_SRID);
            final DataQuantaBuilder<?, Record> parksInBerlin = planBuilder.loadCollection(parks)
                    .crsTransform(CrsTransformTest::countedGeometry, CrsTransformTest::withGeometry, BERLIN_SRID)
                    .spatialFilter(CrsTransformTest::geometry, SpatialPredicate.WITHIN, geometry(berlin.get(0)));
            final Collection<String> collected = lakesIn3857
                    .spatialJoin(CrsTransformTest::geometry, parksInBerlin, CrsTransformTest::geometry,
                            SpatialPredicate.INTERSECTS)
                    .map(pair -> pairOf(pair, BERLIN_SRID))
                    .collect();

            if (execution >= 0) {
                timings[execution] = System.nanoTime() - start;
            }
            pairs = new TreeSet<>(collected);
        }
        Arrays.sort(timings);
        OUTCOMES.put(plan, new Outcome(pairs, TRANSFORMED_GEOMETRIES.get(), TRANSFORMED_COORDINATES.get(),
                timings[TIMED_REPETITIONS / 2]));
        writeGeoJson(plan, "pairs", pairs);

        assumeTrue(expectedPairs != null, "transformLakesIntoParkCrs has not run, nothing to compare against");
        final SortedSet<String> missing = new TreeSet<>(expectedPairs);
        missing.removeAll(pairs);
        writeGeoJson(plan, "missing", missing);
        assertTrue(expectedPairs.containsAll(pairs), plan.description + " found pairs that do not intersect");
        for (String pair : expectedPairs) {
            assertTrue(pairs.contains(pair) || touchesOnly(pair),
                    plan.description + " lost the overlapping pair " + pair);
        }
    }

    @AfterAll
    static void writeReport() {
        if (!OUTCOMES.isEmpty()) {
            report(OUTCOMES);
        }
    }

    /**
     * Reprojects the Berlin boundary from {@link #BERLIN_SRID} into {@code srid} with a CRS transformation,
     * so that the reprojection of the filter geometry is part of the plan's work and timing.
     */
    private static SpatialGeometry berlinIn(JavaPlanBuilder planBuilder, int srid) {
        final Collection<Record> reprojected = planBuilder.loadCollection(berlin)
                .crsTransform(CrsTransformTest::countedGeometry, CrsTransformTest::withGeometry, srid)
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

    private static String pairOf(Tuple2<Record, Record> pair, int joinSrid) {
        final Record lake = pair.field0;
        final Record park = pair.field1;
        assertEquals(joinSrid, geometry(lake).getSrid());
        assertEquals(joinSrid, geometry(park).getSrid());
        return park.getField(ID_FIELD) + " ~ " + lake.getField(ID_FIELD);
    }

    /**
     * Writes the park and the lake of every pair as GeoJSON features in EPSG:4326, so each plan's
     * result can be loaded into QGIS. Every feature carries {@code park}, {@code lake} and
     * {@code role}; styling by {@code role} tells parks and lakes apart.
     */
    private static void writeGeoJson(Plan plan, String layer, SortedSet<String> pairs) {
        final GeoJsonWriter writer = new GeoJsonWriter();
        writer.setEncodeCRS(false);
        final StringBuilder features = new StringBuilder();
        for (String pair : pairs) {
            final String[] ids = pair.split(" ~ ");
            final Geometry park = CrsTransforms.transform(geometry(parks, ids[0]), PARKS_SRID, LAKES_SRID);
            final Geometry lake = geometry(lakes, ids[1]);
            for (Object[] feature : new Object[][]{{"park", park}, {"lake", lake}}) {
                if (features.length() > 0) {
                    features.append(",\n");
                }
                features.append(String.format(
                        "{\"type\":\"Feature\",\"properties\":{\"park\":%s,\"lake\":%s,\"role\":\"%s\"},\"geometry\":%s}",
                        ids[0], ids[1], feature[0], writer.write((Geometry) feature[1])));
            }
        }
        ExperimentResults.writeGeoJson("crs_" + plan.name().toLowerCase() + "_" + layer,
                "{\"type\":\"FeatureCollection\",\"features\":[\n" + features + "\n]}\n");
    }

    private static boolean touchesOnly(String pair) {
        final String[] ids = pair.split(" ~ ");
        final Geometry park = geometry(parks, ids[0]);
        final Geometry lake = CrsTransforms.transform(geometry(lakes, ids[1]), LAKES_SRID, PARKS_SRID);
        return !shrink(park).intersects(shrink(lake));
    }

    private static Geometry geometry(List<Record> records, String id) {
        return geometry(records.get(Integer.parseInt(id))).getGeometry();
    }

    private static Geometry shrink(Geometry geometry) {
        return geometry.getDimension() == 2 ? geometry.buffer(-CONTACT_TOLERANCE_METRES) : geometry;
    }

    private static List<Record> readCsv(Path file, int srid) {
        final List<String> lines = lines(file);
        final List<Record> records = new ArrayList<>();
        for (int row = 1; row < lines.size(); row++) {
            if (lines.get(row).isEmpty()) {
                continue;
            }
            final Matcher matcher = CSV_ROW.matcher(lines.get(row));
            if (!matcher.matches()) {
                throw new IllegalStateException(file + ":" + (row + 1) + " is not a geometry row");
            }
            final Geometry geometry = CrsTransforms.transform(parse(matcher.group(1)), 4326, srid);
            records.add(new Record(records.size(), WayangGeometry.fromGeometry(geometry)));
        }
        return records;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Geometry parse(String wkt) {
        return WayangGeometry.fromStringInput("SRID=4326;" + wkt).getGeometry();
    }

    private static final class Outcome {
        final SortedSet<String> pairs;
        final long geometries;
        final long coordinates;
        final long nanos;

        Outcome(SortedSet<String> pairs, long geometries, long coordinates, long nanos) {
            this.pairs = pairs;
            this.geometries = geometries;
            this.coordinates = coordinates;
            this.nanos = nanos;
        }
    }

    private static void report(Map<Plan, Outcome> outcomes) {
        final StringBuilder report = new StringBuilder(String.format(
                "CRS transform placement - Berlin parks intersecting a lake, Java platform%n%n"
                        + "  lakes %,6d  EPSG:%d%n  parks %,6d  EPSG:%d%n  Berlin     1  EPSG:%d%n%n"
                        + "  %-52s %-60s %10s %12s %7s %7s%n",
                lakes.size(), LAKES_SRID, parks.size(), PARKS_SRID, BERLIN_SRID,
                "", "", "geometries", "coordinates", "pairs", "ms"));
        for (Map.Entry<Plan, Outcome> entry : outcomes.entrySet()) {
            final Plan plan = entry.getKey();
            final Outcome outcome = entry.getValue();
            report.append(String.format("  %-52s %-60s %,10d %,12d %,7d %7.0f%n",
                    plan.name().toLowerCase(), plan.description, outcome.geometries, outcome.coordinates,
                    outcome.pairs.size(), outcome.nanos / 1e6));
        }
        report.append(String.format("%n  Pairs missing against transform_lakes_into_park_crs only touch; median of %d warmed executions.%n",
                TIMED_REPETITIONS));
        System.out.print(report);
        ExperimentResults.writeText("crs_transform", report.toString());
    }
}
