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
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
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
 * Berlin parks that intersect a lake, out of all OSM2015 lakes and parks worldwide, with lakes in
 * EPSG:4326 and parks in EPSG:3857, run with the CRS transformation placed at different points of
 * the plan.
 *
 * <p>Parks are in EPSG:3857 rather than the EPSG:25833 of the smaller test because a UTM zone
 * cannot hold the whole world (see {@link CrsTransformFilterOrderGlobalTest}). They are
 * reprojected on load, which every plan and {@link #loadLakesAndParks()} share.</p>
 *
 * <p>Placement must not change which park and lake overlap. It can change pairs that only touch:
 * shared OSM vertices stay identical only if both sides took the same transformation path.
 * {@link #transformLakesIntoParkCrs()} runs first and its pairs are the reference the other plans
 * are checked against; {@link #writeReport()} tabulates whichever plans ran.</p>
 *
 * <p>Every plan runs once, timed, after {@link #warmUp()} has run the reference plan once untimed.</p>
 *
 * <p>Needs the unpacked {@code OSM2015_lakes.csv} and {@code OSM2015_parks.csv} in
 * {@code ~/wayang/data/osm2015-full} and {@code berlin.csv} in {@code ~/wayang/data}; skipped
 * otherwise.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CrsTransformJoinGlobalTest {

    private static final int LAKES_SRID = 4326;
    private static final int PARKS_SRID = 3857;

    // Pairs only touch or overlap in Berlin, where EPSG:25833 measures true metres.
    private static final int CONTACT_SRID = 25833;

    // The CRS the Berlin boundary arrives in for the plan that reprojects it itself.
    private static final int BERLIN_SOURCE_SRID = 25833;
    private static final double CONTACT_TOLERANCE_METRES = 0.001;

    private static final int ID_FIELD = 0;
    private static final int GEOMETRY_FIELD = 1;

    // The WKT is quoted unless it has no comma (a POINT). DOTALL: OSM tags can contain U+2028 and
    // similar, which end a line for '.' but not for Files.lines.
    private static final Pattern CSV_ROW = Pattern.compile("^(?:\"(.*)\"|([^\",]*)),(\\d+),.*$", Pattern.DOTALL);

    private static final String CSV_HEADER = "geometry,$1,$2";

    private enum Plan {
        TRANSFORM_LAKES_INTO_PARK_CRS("lakes->3857, filter parks, join in 3857"),
        TRANSFORM_PARKS_INTO_LAKE_CRS_BEFORE_BERLIN_FILTER("parks->4326, filter parks, join in 4326"),
        TRANSFORM_PARKS_INTO_LAKE_CRS_AFTER_BERLIN_FILTER("filter parks, parks->4326, join in 4326"),
        TRANSFORM_PARKS_INTO_LAKE_CRS_AFTER_25833_BERLIN_FILTER(
                "berlin 25833->3857, filter parks, parks->4326, join in 4326");

        final String description;

        Plan(String description) {
            this.description = description;
        }
    }

    private static final AtomicLong READ_LAKES = new AtomicLong();
    private static final AtomicLong READ_PARKS = new AtomicLong();
    private static final AtomicLong TRANSFORMED_GEOMETRIES = new AtomicLong();
    private static final AtomicLong TRANSFORMED_COORDINATES = new AtomicLong();

    private static String lakesUrl;
    private static String parksUrl;
    private static Map<Integer, SpatialGeometry> berlin;
    // The Berlin boundary as it arrives for the plan that reprojects it itself.
    private static List<Record> berlinIn25833;

    private static final Map<Plan, Outcome> OUTCOMES = new EnumMap<>(Plan.class);
    private static Long loadNanos;
    private static long loadedLakes;
    private static long loadedParks;
    // park ~ lake -> {park, lake}, in EPSG:4326
    private static SortedMap<String, Geometry[]> expectedPairs;

    @BeforeAll
    static void loadData() {
        final Path dataDirectory = Paths.get(System.getProperty("user.home"), "wayang", "data");
        final Path lakesFile = dataDirectory.resolve(Paths.get("osm2015-full", "OSM2015_lakes.csv"));
        final Path parksFile = dataDirectory.resolve(Paths.get("osm2015-full", "OSM2015_parks.csv"));
        final Path berlinFile = dataDirectory.resolve("berlin.csv");
        assumeTrue(Files.isRegularFile(lakesFile) && Files.isRegularFile(parksFile) && Files.isRegularFile(berlinFile),
                "Missing " + lakesFile + ", " + parksFile + " or " + berlinFile);

        lakesUrl = lakesFile.toUri().toString();
        parksUrl = parksFile.toUri().toString();

        final Geometry berlinIn4326 = parseRow(lines(berlinFile).get(1)).getValue();
        berlin = Map.of(
                LAKES_SRID, WayangGeometry.fromGeometry(berlinIn4326),
                PARKS_SRID, WayangGeometry.fromGeometry(CrsTransforms.transform(berlinIn4326, LAKES_SRID, PARKS_SRID)));
        berlinIn25833 = List.of(new Record("berlin",
                WayangGeometry.fromGeometry(CrsTransforms.transform(berlinIn4326, LAKES_SRID, BERLIN_SOURCE_SRID))));
    }

    /**
     * Runs the reference plan once, untimed and unchecked, so that the timed runs start with a
     * warm JVM.
     */
    @Test
    @Order(0)
    void warmUp() {
        joinLakesInParkCrs(newPlanBuilder("warm-up"));
    }

    /**
     * Reads and parses every lake and park, reprojects the parks into their CRS and does nothing
     * else, so the plans' times can be reported without the loading they share.
     */
    @Test
    @Order(1)
    void loadLakesAndParks() {
        READ_LAKES.set(0);
        READ_PARKS.set(0);
        final JavaPlanBuilder planBuilder = newPlanBuilder("load lakes and parks");
        final long start = System.nanoTime();

        final Collection<Long> lakeCount = lakes(planBuilder).count().collect();
        final Collection<Long> parkCount = parks(planBuilder).count().collect();

        loadNanos = System.nanoTime() - start;
        assertEquals(READ_LAKES.get(), (long) lakeCount.iterator().next());
        assertEquals(READ_PARKS.get(), (long) parkCount.iterator().next());
        loadedLakes = READ_LAKES.get();
        loadedParks = READ_PARKS.get();
    }

    @Test
    @Order(2)
    void transformLakesIntoParkCrs() {
        final Plan plan = Plan.TRANSFORM_LAKES_INTO_PARK_CRS;
        resetCounters();
        final JavaPlanBuilder planBuilder = newPlanBuilder(plan.description);
        final long start = System.nanoTime();

        final Collection<Tuple2<Record, Record>> collected = joinLakesInParkCrs(planBuilder);

        final long nanos = System.nanoTime() - start;
        final SortedMap<String, Geometry[]> pairs = byPair(collected, PARKS_SRID);
        OUTCOMES.put(plan, outcome(pairs, nanos));
        writeGeoJson(plan, "pairs", pairs);

        assertFalse(pairs.isEmpty());
        expectedPairs = pairs;
    }

    @Test
    @Order(3)
    void transformParksIntoLakeCrsBeforeBerlinFilter() {
        final Plan plan = Plan.TRANSFORM_PARKS_INTO_LAKE_CRS_BEFORE_BERLIN_FILTER;
        resetCounters();
        final JavaPlanBuilder planBuilder = newPlanBuilder(plan.description);
        final long start = System.nanoTime();

        final DataQuantaBuilder<?, Record> lakesIn4326 = lakes(planBuilder);
        final DataQuantaBuilder<?, Record> parksInBerlin = parks(planBuilder)
                .crsTransform(CrsTransformJoinGlobalTest::countedGeometry,
                        CrsTransformJoinGlobalTest::withGeometry, LAKES_SRID)
                .withSourceSrid(PARKS_SRID)
                .spatialFilter(CrsTransformJoinGlobalTest::geometry, SpatialPredicate.WITHIN,
                        berlin.get(LAKES_SRID));
        final Collection<Tuple2<Record, Record>> collected = lakesIn4326
                .spatialJoin(CrsTransformJoinGlobalTest::geometry, parksInBerlin,
                        CrsTransformJoinGlobalTest::geometry, SpatialPredicate.INTERSECTS)
                .collect();

        final long nanos = System.nanoTime() - start;
        final SortedMap<String, Geometry[]> pairs = byPair(collected, LAKES_SRID);
        OUTCOMES.put(plan, outcome(pairs, nanos));
        checkAgainstReference(plan, pairs);
    }

    @Test
    @Order(4)
    void transformParksIntoLakeCrsAfterBerlinFilter() {
        final Plan plan = Plan.TRANSFORM_PARKS_INTO_LAKE_CRS_AFTER_BERLIN_FILTER;
        resetCounters();
        final JavaPlanBuilder planBuilder = newPlanBuilder(plan.description);
        final long start = System.nanoTime();

        final DataQuantaBuilder<?, Record> lakesIn4326 = lakes(planBuilder);
        final DataQuantaBuilder<?, Record> parksInBerlin = parks(planBuilder)
                .spatialFilter(CrsTransformJoinGlobalTest::geometry, SpatialPredicate.WITHIN,
                        berlin.get(PARKS_SRID))
                .crsTransform(CrsTransformJoinGlobalTest::countedGeometry,
                        CrsTransformJoinGlobalTest::withGeometry, LAKES_SRID)
                .withSourceSrid(PARKS_SRID);
        final Collection<Tuple2<Record, Record>> collected = lakesIn4326
                .spatialJoin(CrsTransformJoinGlobalTest::geometry, parksInBerlin,
                        CrsTransformJoinGlobalTest::geometry, SpatialPredicate.INTERSECTS)
                .collect();

        final long nanos = System.nanoTime() - start;
        final SortedMap<String, Geometry[]> pairs = byPair(collected, LAKES_SRID);
        OUTCOMES.put(plan, outcome(pairs, nanos));
        checkAgainstReference(plan, pairs);
    }

    /**
     * Exactly {@link #transformParksIntoLakeCrsAfterBerlinFilter()}, except that the Berlin
     * boundary arrives in EPSG:25833 and the plan reprojects it into the parks' EPSG:3857 before
     * filtering with it.
     */
    @Test
    @Order(5)
    void transformParksIntoLakeCrsAfter25833BerlinFilter() {
        final Plan plan = Plan.TRANSFORM_PARKS_INTO_LAKE_CRS_AFTER_25833_BERLIN_FILTER;
        resetCounters();
        final JavaPlanBuilder planBuilder = newPlanBuilder(plan.description);
        final long start = System.nanoTime();

        final DataQuantaBuilder<?, Record> lakesIn4326 = lakes(planBuilder);
        final DataQuantaBuilder<?, Record> parksInBerlin = parks(planBuilder)
                .spatialFilter(CrsTransformJoinGlobalTest::geometry, SpatialPredicate.WITHIN,
                        berlinIn(planBuilder, PARKS_SRID))
                .crsTransform(CrsTransformJoinGlobalTest::countedGeometry,
                        CrsTransformJoinGlobalTest::withGeometry, LAKES_SRID)
                .withSourceSrid(PARKS_SRID);
        final Collection<Tuple2<Record, Record>> collected = lakesIn4326
                .spatialJoin(CrsTransformJoinGlobalTest::geometry, parksInBerlin,
                        CrsTransformJoinGlobalTest::geometry, SpatialPredicate.INTERSECTS)
                .collect();

        final long nanos = System.nanoTime() - start;
        final SortedMap<String, Geometry[]> pairs = byPair(collected, LAKES_SRID);
        OUTCOMES.put(plan, outcome(pairs, nanos));
        checkAgainstReference(plan, pairs);
    }

    @AfterAll
    static void writeReport() {
        if (!OUTCOMES.isEmpty()) {
            report(OUTCOMES);
        }
    }

    private static JavaPlanBuilder newPlanBuilder(String jobName) {
        return new JavaPlanBuilder(new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin()))
                .withJobName(jobName);
    }

    /**
     * The reference plan: lakes into the parks' CRS, parks filtered for Berlin, join in EPSG:3857.
     */
    private static Collection<Tuple2<Record, Record>> joinLakesInParkCrs(JavaPlanBuilder planBuilder) {
        final DataQuantaBuilder<?, Record> lakesIn3857 = lakes(planBuilder)
                .crsTransform(CrsTransformJoinGlobalTest::countedGeometry,
                        CrsTransformJoinGlobalTest::withGeometry, PARKS_SRID)
                .withSourceSrid(LAKES_SRID);
        final DataQuantaBuilder<?, Record> parksInBerlin = parks(planBuilder)
                .spatialFilter(CrsTransformJoinGlobalTest::geometry, SpatialPredicate.WITHIN,
                        berlin.get(PARKS_SRID));
        return lakesIn3857
                .spatialJoin(CrsTransformJoinGlobalTest::geometry, parksInBerlin,
                        CrsTransformJoinGlobalTest::geometry, SpatialPredicate.INTERSECTS)
                .collect();
    }

    /**
     * Reprojects the Berlin boundary from {@link #BERLIN_SOURCE_SRID} into {@code srid} with a CRS
     * transformation, so that the reprojection of the filter geometry is part of the plan's work and timing.
     */
    private static SpatialGeometry berlinIn(JavaPlanBuilder planBuilder, int srid) {
        final Collection<Record> reprojected = planBuilder.loadCollection(berlinIn25833)
                .crsTransform(CrsTransformJoinGlobalTest::countedGeometry,
                        CrsTransformJoinGlobalTest::withGeometry, srid)
                .collect();
        return geometry(reprojected.iterator().next());
    }

    private static DataQuantaBuilder<?, Record> lakes(JavaPlanBuilder planBuilder) {
        return planBuilder.readTextFile(lakesUrl)
                .filter(line -> !line.equals(CSV_HEADER))
                .map(CrsTransformJoinGlobalTest::readLake);
    }

    private static DataQuantaBuilder<?, Record> parks(JavaPlanBuilder planBuilder) {
        return planBuilder.readTextFile(parksUrl)
                .filter(line -> !line.equals(CSV_HEADER))
                .map(CrsTransformJoinGlobalTest::readPark);
    }

    private static void resetCounters() {
        READ_LAKES.set(0);
        READ_PARKS.set(0);
        TRANSFORMED_GEOMETRIES.set(0);
        TRANSFORMED_COORDINATES.set(0);
    }

    private static Outcome outcome(SortedMap<String, Geometry[]> pairs, long nanos) {
        return new Outcome(pairs.size(), READ_LAKES.get(), READ_PARKS.get(), TRANSFORMED_GEOMETRIES.get(),
                TRANSFORMED_COORDINATES.get(), nanos);
    }

    private static void checkAgainstReference(Plan plan, SortedMap<String, Geometry[]> pairs) {
        assumeTrue(expectedPairs != null, "transformLakesIntoParkCrs has not run, nothing to compare against");
        final SortedMap<String, Geometry[]> extra = new TreeMap<>(pairs);
        extra.keySet().removeAll(expectedPairs.keySet());
        final SortedMap<String, Geometry[]> missing = new TreeMap<>(expectedPairs);
        missing.keySet().removeAll(pairs.keySet());
        OUTCOMES.get(plan).extra = extra.size();
        OUTCOMES.get(plan).missing = missing.size();
        writeGeoJson(plan, "extra", extra);
        writeGeoJson(plan, "missing", missing);

        for (Map.Entry<String, Geometry[]> pair : extra.entrySet()) {
            assertTrue(touchesOnly(pair.getValue()), plan.description + " found the overlapping pair "
                    + pair.getKey() + " the reference plan did not");
        }
        for (Map.Entry<String, Geometry[]> pair : missing.entrySet()) {
            assertTrue(touchesOnly(pair.getValue()), plan.description + " lost the overlapping pair " + pair.getKey());
        }
    }

    private static Record readLake(String line) {
        READ_LAKES.incrementAndGet();
        final Map.Entry<String, Geometry> row = parseRow(line);
        return new Record(row.getKey(), WayangGeometry.fromGeometry(row.getValue()));
    }

    private static Record readPark(String line) {
        READ_PARKS.incrementAndGet();
        final Map.Entry<String, Geometry> row = parseRow(line);
        return new Record(row.getKey(),
                WayangGeometry.fromGeometry(CrsTransforms.transform(row.getValue(), LAKES_SRID, PARKS_SRID)));
    }

    /**
     * @return the row's id and geometry, in EPSG:4326. The OSM id alone does not identify a
     * feature: the dataset splits some multipolygon relations into several rows that share it, so
     * the id is the OSM id plus a hash of the row's geometry.
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
     * @return the joined pairs as {@code park ~ lake}, with their park and lake in EPSG:4326
     */
    private static SortedMap<String, Geometry[]> byPair(Collection<Tuple2<Record, Record>> joined, int joinSrid) {
        final SortedMap<String, Geometry[]> byPair = new TreeMap<>();
        for (Tuple2<Record, Record> pair : joined) {
            final Record lake = pair.field0;
            final Record park = pair.field1;
            assertEquals(joinSrid, geometry(lake).getSrid());
            assertEquals(joinSrid, geometry(park).getSrid());
            final String key = park.getField(ID_FIELD) + " ~ " + lake.getField(ID_FIELD);
            assertNull(byPair.put(key, new Geometry[]{
                            in4326(geometry(park).getGeometry(), joinSrid),
                            in4326(geometry(lake).getGeometry(), joinSrid)}),
                    "Pair " + key + " was joined twice");
        }
        return byPair;
    }

    private static Geometry in4326(Geometry geometry, int srid) {
        return srid == LAKES_SRID ? geometry : CrsTransforms.transform(geometry, srid, LAKES_SRID);
    }

    /**
     * @return whether the park and lake, both in EPSG:4326, only touch: they stop intersecting
     * once each shrinks by {@link #CONTACT_TOLERANCE_METRES}
     */
    private static boolean touchesOnly(Geometry[] parkAndLake) {
        final Geometry park = CrsTransforms.transform(parkAndLake[0], LAKES_SRID, CONTACT_SRID);
        final Geometry lake = CrsTransforms.transform(parkAndLake[1], LAKES_SRID, CONTACT_SRID);
        return !shrink(park).intersects(shrink(lake));
    }

    private static Geometry shrink(Geometry geometry) {
        return geometry.getDimension() == 2 ? geometry.buffer(-CONTACT_TOLERANCE_METRES) : geometry;
    }

    /**
     * Writes the park and the lake of every pair as GeoJSON features in EPSG:4326, so each plan's
     * result can be loaded into QGIS. Every feature carries {@code park}, {@code lake} and
     * {@code role}; styling by {@code role} tells parks and lakes apart.
     */
    private static void writeGeoJson(Plan plan, String layer, SortedMap<String, Geometry[]> pairs) {
        final GeoJsonWriter writer = new GeoJsonWriter();
        writer.setEncodeCRS(false);
        final StringBuilder features = new StringBuilder();
        for (Map.Entry<String, Geometry[]> pair : pairs.entrySet()) {
            final String[] ids = pair.getKey().split(" ~ ");
            final String[] roles = {"park", "lake"};
            for (int i = 0; i < roles.length; i++) {
                if (features.length() > 0) {
                    features.append(",\n");
                }
                features.append(String.format(
                        "{\"type\":\"Feature\",\"properties\":{\"park\":\"%s\",\"lake\":\"%s\",\"role\":\"%s\"},\"geometry\":%s}",
                        ids[0], ids[1], roles[i], writer.write(pair.getValue()[i])));
            }
        }
        ExperimentResults.writeGeoJson("crs_join_global_" + plan.name().toLowerCase() + "_" + layer,
                "{\"type\":\"FeatureCollection\",\"features\":[\n" + features + "\n]}\n");
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    private static final class Outcome {
        final int pairs;
        final long readLakes;
        final long readParks;
        final long geometries;
        final long coordinates;
        final long nanos;
        Integer extra;
        Integer missing;

        Outcome(int pairs, long readLakes, long readParks, long geometries, long coordinates, long nanos) {
            this.pairs = pairs;
            this.readLakes = readLakes;
            this.readParks = readParks;
            this.geometries = geometries;
            this.coordinates = coordinates;
            this.nanos = nanos;
        }
    }

    private static void report(Map<Plan, Outcome> outcomes) {
        final StringBuilder report = new StringBuilder(String.format(
                "CRS transform placement - Berlin parks intersecting a lake, out of all OSM2015 lakes and parks, Java platform%n%n"
                        + "  lakes  EPSG:%d, streamed from %s%n"
                        + "  parks  EPSG:%d (reprojected from 4326 on load), streamed from %s%n"
                        + "  Berlin EPSG:%d and EPSG:%d, and EPSG:%d for the plan that reprojects it itself%n%n"
                        + "  %-56s %-60s %11s %11s %11s %14s %6s %6s %8s %9s %9s%n",
                LAKES_SRID, lakesUrl, PARKS_SRID, parksUrl, LAKES_SRID, PARKS_SRID, BERLIN_SOURCE_SRID,
                "", "", "lakes read", "parks read", "geometries", "coordinates", "pairs", "+/-", "", "total s",
                "net s"));
        for (Map.Entry<Plan, Outcome> entry : outcomes.entrySet()) {
            final Plan plan = entry.getKey();
            final Outcome outcome = entry.getValue();
            report.append(String.format("  %-56s %-60s %,11d %,11d %,11d %,14d %,6d %6s %8s %9.1f %9s%n",
                    plan.name().toLowerCase(), plan.description, outcome.readLakes, outcome.readParks,
                    outcome.geometries, outcome.coordinates, outcome.pairs,
                    outcome.extra == null ? "-" : "+" + outcome.extra,
                    outcome.missing == null ? "-" : "-" + outcome.missing,
                    seconds(outcome.nanos),
                    loadNanos == null ? "-"
                            : String.format("%.1f", seconds(outcome.nanos) - seconds(loadNanos))));
        }
        if (loadNanos != null) {
            report.append(String.format("%n  Loading alone (read, parse, reproject parks, count %,d lakes and %,d parks):"
                            + " %.1f s; net s is total s minus it.",
                    loadedLakes, loadedParks, seconds(loadNanos)));
        }
        report.append(String.format("%n  +/- are pairs found or lost against transform_lakes_into_park_crs; all of them only touch."
                + "%n  Times are from a single run each, after one untimed warm-up run of transform_lakes_into_park_crs.%n"));
        System.out.print(report);
        ExperimentResults.writeText("crs_join_global", report.toString());
    }
}
