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

import org.apache.wayang.basic.data.Record;
import org.apache.wayang.basic.data.Tuple2;
import org.apache.wayang.basic.operators.CRSTransformationOperator;
import org.apache.wayang.basic.operators.CollectionSource;
import org.apache.wayang.basic.operators.LocalCallbackSink;
import org.apache.wayang.basic.operators.MapOperator;
import org.apache.wayang.basic.operators.SpatialFilterOperator;
import org.apache.wayang.basic.operators.SpatialJoinOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.java.Java;
import org.apache.wayang.spatial.Spatial;
import org.apache.wayang.spatial.crs.CrsTransforms;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shows that where CRS transformations go in a multi-input spatial plan is a real optimization
 * decision, using real OpenStreetMap data rather than a synthetic grid.
 *
 * <p>The query is <em>"Berlin parks within 100 m of a lake"</em>, over three inputs that arrive in
 * three different CRSs:</p>
 * <ul>
 *   <li>lakes in EPSG:4326 — as OSM delivers them,</li>
 *   <li>parks in EPSG:25833 (ETRS89 / UTM 33N) — the CRS of Berlin's own geodata,</li>
 *   <li>the Berlin boundary in EPSG:3857 — as a web map hands it over,</li>
 * </ul>
 * <p>and the result is wanted in EPSG:4326 for a GeoJSON sink. The inputs cover Berlin and a wide
 * ring of Brandenburg, so the Berlin filter is selective — and very differently so for the two
 * sides: it keeps about a fifth of the lakes but three quarters of the parks.</p>
 *
 * <p>Wayang has no distance predicate, so "within 100 m" is expressed as <em>buffer one side by
 * 100 m, then INTERSECTS</em>. A buffer only means metres in a metric CRS, which takes EPSG:4326 out
 * of the running for the buffer, and buffering multiplies vertex counts, which makes transforming
 * <em>after</em> a buffer expensive. Together with the filter that makes the placement non-trivial:
 * there is no single "transform at the source" or "transform as late as possible" answer, and the
 * cheapest plans need rewrites a single-operator mapping cannot express:</p>
 * <ul>
 *   <li>pushing the Berlin filter below the join (the park side),</li>
 *   <li>deriving a <em>new</em> filter for the lake side — Berlin grown by 100 m — and moving it
 *       below the lake transform, which needs the boundary moved into EPSG:4326 (operand swap),</li>
 *   <li>choosing which side to buffer and reproject, and therefore which CRS the join runs in.</li>
 * </ul>
 *
 * <p>The data is ODbL-licensed and therefore not committed: run
 * {@code src/test/scripts/fetch_osm_berlin.py} first. The test is skipped without it. The location
 * defaults to {@code ~/.cache/wayang-spatial/osm-berlin} and can be overridden with the
 * {@code wayang.spatial.osmBerlinDir} system property.</p>
 */
public class CrsTransformPlacementTest {

    private static final int LAKES_SRID = 4326;

    private static final int PARKS_SRID = 25833;

    private static final int BOUNDARY_SRID = 3857;

    private static final int OUTPUT_SRID = 4326;

    private static final double DISTANCE_METRES = 100.0;

    /**
     * The lake-side filter only has to be a necessary condition, so it may be generous. The slack
     * absorbs the small differences between deciding "within 100 m of Berlin" in EPSG:4326 and in
     * EPSG:25833, which keeps the rewrite exactly equivalent rather than approximately so.
     */
    private static final double DERIVED_FILTER_SLACK_METRES = 10.0;

    private static final int ID_FIELD = 0;

    private static final int GEOMETRY_FIELD = 1;

    /** Where a plan that keeps the unbuffered geometry puts the buffered one. */
    private static final int BUFFER_FIELD = 2;

    /** Timed executions per plan. One sample drifts by tens of percent; the median does not. */
    private static final int TIMED_REPETITIONS = 5;

    /** Which input a CRS transformation works on, so its work can be attributed. */
    private enum Stage {
        LAKES, PARKS, OUTPUT
    }

    private static final Map<Stage, AtomicLong> TRANSFORMED_GEOMETRIES = counters();

    private static final Map<Stage, AtomicLong> TRANSFORMED_COORDINATES = counters();

    private enum Plan {
        AS_WRITTEN("as written",
                "lakes->25833 at the source, buffer, join, filter Berlin, result->4326"),
        PARK_FILTER_PUSHED("park filter pushed",
                "Berlin filter moved below the join, on the park side"),
        BOTH_FILTERS_PUSHED("both filters pushed",
                "plus a derived lake filter (Berlin + 100 m, swapped into 4326) below the lake transform"),
        JOIN_IN_4326("join in 4326",
                "buffer the filtered parks instead and reproject them; lakes are never reprojected"),
        JOIN_IN_3857("join in 3857",
                "everything into the boundary's CRS; no boundary transform, but 100 units != 100 m");

        final String label;

        final String description;

        Plan(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    // ------------------------------------------------------------------ data, loaded once

    private static List<Record> lakes;

    private static List<Record> parks;

    private static SpatialGeometry berlinIn3857;

    private static SpatialGeometry berlinIn25833;

    /** Berlin grown by 100 m (+ slack) in metres, then moved into the lakes' CRS. */
    private static SpatialGeometry berlinPlusDistanceIn4326;

    /** Both inputs in EPSG:25833, for measuring the true distance of pairs the plans disagree on. */
    private static Map<String, Geometry> lakesInMetres;

    private static Map<String, Geometry> parksInMetres;

    @BeforeAll
    static void loadOsmData() {
        final Path directory = Paths.get(System.getProperty("wayang.spatial.osmBerlinDir",
                Paths.get(System.getProperty("user.home"), ".cache", "wayang-spatial", "osm-berlin").toString()));
        assumeTrue(Files.isRegularFile(directory.resolve("lakes.tsv"))
                        && Files.isRegularFile(directory.resolve("parks.tsv"))
                        && Files.isRegularFile(directory.resolve("berlin.tsv")),
                "No OSM data in " + directory + "; run src/test/scripts/fetch_osm_berlin.py to fetch it.");

        // Every file is in EPSG:4326; here each input is put into the CRS it "arrives" in.
        lakes = new ArrayList<>();
        lakesInMetres = new HashMap<>();
        for (String[] row : readTsv(directory.resolve("lakes.tsv"))) {
            final Geometry geometry = parse(row[1]);
            lakes.add(new Record(row[0], WayangGeometry.fromGeometry(geometry)));
            lakesInMetres.put(row[0], CrsTransforms.transform(geometry, 4326, PARKS_SRID));
        }

        parks = new ArrayList<>();
        parksInMetres = new HashMap<>();
        for (String[] row : readTsv(directory.resolve("parks.tsv"))) {
            final Geometry geometry = CrsTransforms.transform(parse(row[1]), 4326, PARKS_SRID);
            parks.add(new Record(row[0], WayangGeometry.fromGeometry(geometry)));
            parksInMetres.put(row[0], geometry);
        }

        final Geometry berlin = CrsTransforms.transform(
                parse(readTsv(directory.resolve("berlin.tsv")).get(0)[1]), 4326, BOUNDARY_SRID);
        berlinIn3857 = WayangGeometry.fromGeometry(berlin);

        // The reference geometries the rewritten plans need. Each is one geometry transformed once
        // per plan, against thousands of data geometries. The boundary's edges are a few hundred
        // metres at most, so unlike the 1-degree box of CrsTransformOrderingTest it needs no
        // densifying: an edge's curvature between these CRSs is far below a millimetre.
        final Geometry berlinMetres = CrsTransforms.transform(berlin, BOUNDARY_SRID, PARKS_SRID);
        berlinIn25833 = WayangGeometry.fromGeometry(berlinMetres);
        final Geometry grown = berlinMetres.buffer(DISTANCE_METRES + DERIVED_FILTER_SLACK_METRES);
        grown.setSRID(PARKS_SRID);
        berlinPlusDistanceIn4326 = WayangGeometry.fromGeometry(
                CrsTransforms.transform(grown, PARKS_SRID, LAKES_SRID));
    }

    @Test
    void transformPlacementDecidesCostAndCorrectness() {
        final Map<Plan, Outcome> outcomes = new EnumMap<>(Plan.class);
        for (Plan plan : Plan.values()) {
            run(plan); // warm-up, see CrsTransformOrderingTest
        }
        for (Plan plan : Plan.values()) {
            outcomes.put(plan, measure(plan));
        }

        final Outcome reference = outcomes.get(Plan.AS_WRITTEN);
        final Outcome pushed = outcomes.get(Plan.BOTH_FILTERS_PUSHED);
        final Outcome in4326 = outcomes.get(Plan.JOIN_IN_4326);
        final Outcome in3857 = outcomes.get(Plan.JOIN_IN_3857);

        report(outcomes);

        // 1. Filter pushdown, including the derived lake filter, is an exact rewrite.
        assertFalse(reference.pairs.isEmpty(), "The demo needs a non-empty result to be meaningful.");
        assertEquals(reference.pairs, outcomes.get(Plan.PARK_FILTER_PUSHED).pairs);
        assertEquals(reference.pairs, pushed.pairs);

        // 2. ...and it spares most of the lake reprojections.
        assertEquals(lakes.size(), reference.geometries(Stage.LAKES),
                "As written, every lake is reprojected.");
        assertTrue(pushed.geometries(Stage.LAKES) < lakes.size() / 3,
                String.format("The derived filter should spare most lake reprojections; got %d of %d.",
                        pushed.geometries(Stage.LAKES), lakes.size()));

        // 3. Buffering the other side avoids lake reprojection entirely. It is only *nearly* the
        //    same query: the buffer approximates the 100 m circle with chords, and buffering parks
        //    instead of lakes moves that approximation error. Every pair it disagrees on sits at
        //    the 100 m boundary.
        assertEquals(0, in4326.geometries(Stage.LAKES));
        final SortedSet<String> differing = symmetricDifference(reference.pairs, in4326.pairs);
        assertTrue(differing.size() <= reference.pairs.size() / 100,
                String.format("Buffering the parks instead should change at most 1%% of the pairs; got %d of %d.",
                        differing.size(), reference.pairs.size()));
        for (String pair : differing) {
            final double distance = trueDistance(pair);
            assertTrue(distance > 90 && distance < 110,
                    String.format("Pair %s differs although it is %.1f m apart, not ~100 m.", pair, distance));
        }

        // 4. Web Mercator is the one CRS that needs no boundary transform — and is wrong: at
        //    Berlin's latitude a Mercator unit is ~0.61 m, so the 100-unit buffer is ~61 m and
        //    pairs between ~61 m and 100 m apart go missing.
        assertTrue(reference.pairs.containsAll(in3857.pairs),
                "A 61 m buffer must not find pairs that a 100 m buffer misses.");
        assertTrue(in3857.pairs.size() < reference.pairs.size(),
                "Joining in EPSG:3857 should lose the pairs between ~61 m and 100 m apart.");
        for (String pair : symmetricDifference(reference.pairs, in3857.pairs)) {
            assertTrue(trueDistance(pair) > 55,
                    String.format("Pair %s is missing from the 3857 plan but only %.1f m apart.",
                            pair, trueDistance(pair)));
        }
    }

    // ------------------------------------------------------------------ plans

    private Outcome measure(Plan plan) {
        final long[] timings = new long[TIMED_REPETITIONS];
        Outcome outcome = null;
        for (int repetition = 0; repetition < TIMED_REPETITIONS; repetition++) {
            outcome = run(plan);
            timings[repetition] = outcome.timingsNanos[0];
        }
        return outcome.withTimings(timings);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Outcome run(Plan plan) {
        TRANSFORMED_GEOMETRIES.values().forEach(counter -> counter.set(0));
        TRANSFORMED_COORDINATES.values().forEach(counter -> counter.set(0));

        final DataSetType<Record> recordType = DataSetType.createDefault(Record.class);
        final CollectionSource<Record> lakeSource = new CollectionSource<>(lakes, recordType);
        final CollectionSource<Record> parkSource = new CollectionSource<>(parks, recordType);

        // The result: (park id, lake id, park geometry), with the geometry still in the join's CRS.
        final MapOperator<Tuple2, Record> toResult = new MapOperator<>(
                pair -> {
                    final Record lake = (Record) pair.field0;
                    final Record park = (Record) pair.field1;
                    return new Record(park.getField(ID_FIELD), lake.getField(ID_FIELD),
                            park.getField(GEOMETRY_FIELD));
                },
                Tuple2.class, Record.class);

        final List<Record> collected = new ArrayList<>();
        final LocalCallbackSink<Record> sink = LocalCallbackSink.createCollectingSink(collected, recordType);

        final SpatialJoinOperator<Record, Record> join;
        int joinSrid;

        switch (plan) {
            case AS_WRITTEN: {
                final CRSTransformationOperator<Record> lakesToMetres =
                        transform(GEOMETRY_FIELD, LAKES_SRID, PARKS_SRID, Stage.LAKES);
                final MapOperator<Record, Record> bufferLakes = buffer(GEOMETRY_FIELD);
                join = join(GEOMETRY_FIELD, GEOMETRY_FIELD);
                final SpatialFilterOperator<Tuple2> inBerlin = new SpatialFilterOperator<>(
                        SpatialPredicate.WITHIN,
                        pair -> (WayangGeometry) ((Record) pair.field1).getField(GEOMETRY_FIELD),
                        DataSetType.createDefaultUnchecked(Tuple2.class),
                        berlinIn25833);
                lakeSource.connectTo(0, lakesToMetres, 0);
                lakesToMetres.connectTo(0, bufferLakes, 0);
                bufferLakes.connectTo(0, join, 0);
                parkSource.connectTo(0, join, 1);
                join.connectTo(0, inBerlin, 0);
                inBerlin.connectTo(0, toResult, 0);
                joinSrid = PARKS_SRID;
                break;
            }
            case PARK_FILTER_PUSHED:
            case BOTH_FILTERS_PUSHED: {
                final CRSTransformationOperator<Record> lakesToMetres =
                        transform(GEOMETRY_FIELD, LAKES_SRID, PARKS_SRID, Stage.LAKES);
                final MapOperator<Record, Record> bufferLakes = buffer(GEOMETRY_FIELD);
                final SpatialFilterOperator<Record> parksInBerlin =
                        filter(SpatialPredicate.WITHIN, GEOMETRY_FIELD, berlinIn25833);
                join = join(GEOMETRY_FIELD, GEOMETRY_FIELD);
                if (plan == Plan.BOTH_FILTERS_PUSHED) {
                    final SpatialFilterOperator<Record> lakesNearBerlin =
                            filter(SpatialPredicate.INTERSECTS, GEOMETRY_FIELD, berlinPlusDistanceIn4326);
                    lakeSource.connectTo(0, lakesNearBerlin, 0);
                    lakesNearBerlin.connectTo(0, lakesToMetres, 0);
                } else {
                    lakeSource.connectTo(0, lakesToMetres, 0);
                }
                lakesToMetres.connectTo(0, bufferLakes, 0);
                bufferLakes.connectTo(0, join, 0);
                parkSource.connectTo(0, parksInBerlin, 0);
                parksInBerlin.connectTo(0, join, 1);
                join.connectTo(0, toResult, 0);
                joinSrid = PARKS_SRID;
                break;
            }
            case JOIN_IN_4326: {
                // The parks keep their unbuffered geometry for the result and gain a buffered one,
                // which alone is reprojected. Buffering first is forced: 4326 has no metres.
                final SpatialFilterOperator<Record> lakesNearBerlin =
                        filter(SpatialPredicate.INTERSECTS, GEOMETRY_FIELD, berlinPlusDistanceIn4326);
                final SpatialFilterOperator<Record> parksInBerlin =
                        filter(SpatialPredicate.WITHIN, GEOMETRY_FIELD, berlinIn25833);
                final MapOperator<Record, Record> bufferParks = buffer(BUFFER_FIELD);
                final CRSTransformationOperator<Record> buffersToDegrees =
                        transform(BUFFER_FIELD, PARKS_SRID, LAKES_SRID, Stage.PARKS);
                join = join(GEOMETRY_FIELD, BUFFER_FIELD);
                lakeSource.connectTo(0, lakesNearBerlin, 0);
                lakesNearBerlin.connectTo(0, join, 0);
                parkSource.connectTo(0, parksInBerlin, 0);
                parksInBerlin.connectTo(0, bufferParks, 0);
                bufferParks.connectTo(0, buffersToDegrees, 0);
                buffersToDegrees.connectTo(0, join, 1);
                join.connectTo(0, toResult, 0);
                joinSrid = PARKS_SRID; // the result carries the unbuffered park geometry
                break;
            }
            case JOIN_IN_3857: {
                final CRSTransformationOperator<Record> lakesToMercator =
                        transform(GEOMETRY_FIELD, LAKES_SRID, BOUNDARY_SRID, Stage.LAKES);
                final MapOperator<Record, Record> bufferLakes = buffer(GEOMETRY_FIELD);
                final CRSTransformationOperator<Record> parksToMercator =
                        transform(GEOMETRY_FIELD, PARKS_SRID, BOUNDARY_SRID, Stage.PARKS);
                final SpatialFilterOperator<Record> parksInBerlin =
                        filter(SpatialPredicate.WITHIN, GEOMETRY_FIELD, berlinIn3857);
                join = join(GEOMETRY_FIELD, GEOMETRY_FIELD);
                lakeSource.connectTo(0, lakesToMercator, 0);
                lakesToMercator.connectTo(0, bufferLakes, 0);
                bufferLakes.connectTo(0, join, 0);
                parkSource.connectTo(0, parksToMercator, 0);
                parksToMercator.connectTo(0, parksInBerlin, 0);
                parksInBerlin.connectTo(0, join, 1);
                join.connectTo(0, toResult, 0);
                joinSrid = BOUNDARY_SRID;
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown plan " + plan);
        }

        final CRSTransformationOperator<Record> resultToOutput =
                transform(2, joinSrid, OUTPUT_SRID, Stage.OUTPUT);
        toResult.connectTo(0, resultToOutput, 0);
        resultToOutput.connectTo(0, sink, 0);

        final WayangContext context = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        final long start = System.nanoTime();
        context.execute(new WayangPlan(sink));
        final long elapsedNanos = System.nanoTime() - start;

        final SortedSet<String> pairs = new TreeSet<>();
        for (Record result : collected) {
            assertEquals(OUTPUT_SRID, ((WayangGeometry) result.getField(2)).getGeometry().getSRID(),
                    "The sink must receive every geometry in the output CRS.");
            pairs.add(result.getField(0) + " ~ " + result.getField(1));
        }

        final Map<Stage, long[]> work = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            work.put(stage, new long[]{
                    TRANSFORMED_GEOMETRIES.get(stage).get(), TRANSFORMED_COORDINATES.get(stage).get()});
        }
        return new Outcome(pairs, work, new long[]{elapsedNanos});
    }

    private static CRSTransformationOperator<Record> transform(int field, int sourceSrid, int targetSrid,
                                                               Stage stage) {
        return new CRSTransformationOperator<>(
                record -> readAndCount(record.getField(field), stage),
                (record, geometry) -> {
                    final Record transformed = record.copy();
                    transformed.setField(field, geometry);
                    return transformed;
                },
                DataSetType.createDefault(Record.class), sourceSrid, targetSrid);
    }

    /** Instrumented geometry reader: counts what a transform is handed. */
    private static SpatialGeometry readAndCount(Object field, Stage stage) {
        final WayangGeometry geometry = (WayangGeometry) field;
        TRANSFORMED_GEOMETRIES.get(stage).incrementAndGet();
        TRANSFORMED_COORDINATES.get(stage).addAndGet(geometry.getGeometry().getNumPoints());
        return geometry;
    }

    /**
     * Buffers the geometry field by {@link #DISTANCE_METRES} in whatever CRS it is in — which is
     * only metres if that CRS is metric — and writes the result to {@code targetField}.
     */
    private static MapOperator<Record, Record> buffer(int targetField) {
        return new MapOperator<>(
                record -> {
                    final Geometry geometry = ((WayangGeometry) record.getField(GEOMETRY_FIELD)).getGeometry();
                    final Geometry buffered = geometry.buffer(DISTANCE_METRES);
                    buffered.setSRID(geometry.getSRID());
                    final Object[] fields = new Object[Math.max(record.size(), targetField + 1)];
                    for (int i = 0; i < record.size(); i++) {
                        fields[i] = record.getField(i);
                    }
                    fields[targetField] = WayangGeometry.fromGeometry(buffered);
                    return new Record(fields);
                },
                Record.class, Record.class);
    }

    private static SpatialFilterOperator<Record> filter(SpatialPredicate predicate, int field,
                                                        SpatialGeometry reference) {
        return new SpatialFilterOperator<>(predicate,
                record -> (WayangGeometry) record.getField(field),
                DataSetType.createDefault(Record.class),
                reference);
    }

    /** Lakes on the left, parks on the right. */
    private static SpatialJoinOperator<Record, Record> join(int lakeField, int parkField) {
        return new SpatialJoinOperator<>(
                (FunctionDescriptor.SerializableFunction<Record, SpatialGeometry>)
                        record -> (WayangGeometry) record.getField(lakeField),
                (FunctionDescriptor.SerializableFunction<Record, SpatialGeometry>)
                        record -> (WayangGeometry) record.getField(parkField),
                Record.class, Record.class,
                SpatialPredicate.INTERSECTS);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<Stage, AtomicLong> counters() {
        final Map<Stage, AtomicLong> counters = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            counters.put(stage, new AtomicLong());
        }
        return counters;
    }

    private static List<String[]> readTsv(Path file) {
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            final List<String[]> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) { // skip the header
                if (!line.isEmpty()) {
                    rows.add(line.split("\t", 2));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Geometry parse(String wkt) {
        return WayangGeometry.fromStringInput("SRID=4326;" + wkt).getGeometry();
    }

    private static SortedSet<String> symmetricDifference(SortedSet<String> a, SortedSet<String> b) {
        final SortedSet<String> difference = new TreeSet<>(a);
        difference.addAll(b);
        final SortedSet<String> both = new TreeSet<>(a);
        both.retainAll(b);
        difference.removeAll(both);
        return difference;
    }

    /** The exact distance of a "park ~ lake" pair, in metres, measured in EPSG:25833. */
    private static double trueDistance(String pair) {
        final String[] ids = pair.split(" ~ ");
        return parksInMetres.get(ids[0]).distance(lakesInMetres.get(ids[1]));
    }

    private static class Outcome {
        final SortedSet<String> pairs;

        /** Per stage: {geometries, coordinates} reprojected. */
        final Map<Stage, long[]> work;

        /** Every timed run of this plan, ascending. */
        final long[] timingsNanos;

        Outcome(SortedSet<String> pairs, Map<Stage, long[]> work, long[] timingsNanos) {
            this.pairs = pairs;
            this.work = work;
            this.timingsNanos = timingsNanos.clone();
            Arrays.sort(this.timingsNanos);
        }

        Outcome withTimings(long[] timingsNanos) {
            return new Outcome(this.pairs, this.work, timingsNanos);
        }

        long geometries(Stage stage) {
            return this.work.get(stage)[0];
        }

        long coordinates(Stage stage) {
            return this.work.get(stage)[1];
        }

        long totalGeometries() {
            return this.work.values().stream().mapToLong(w -> w[0]).sum();
        }

        long totalCoordinates() {
            return this.work.values().stream().mapToLong(w -> w[1]).sum();
        }

        double medianMillis() {
            return this.timingsNanos[this.timingsNanos.length / 2] / 1_000_000.0;
        }
    }

    private static void report(Map<Plan, Outcome> outcomes) {
        final Outcome reference = outcomes.get(Plan.AS_WRITTEN);
        final StringBuilder report = new StringBuilder();
        report.append(String.format("CRS transform placement - Berlin parks within %.0f m of a lake, Java platform%n%n",
                DISTANCE_METRES));
        report.append(String.format("  lakes  %,6d  EPSG:%d%n", lakes.size(), LAKES_SRID));
        report.append(String.format("  parks  %,6d  EPSG:%d%n", parks.size(), PARKS_SRID));
        report.append(String.format("  Berlin      1  EPSG:%d (%,d vertices)%n",
                BOUNDARY_SRID, ((WayangGeometry) berlinIn3857).getGeometry().getNumPoints()));
        report.append(String.format("  output         EPSG:%d%n%n", OUTPUT_SRID));

        for (Plan plan : Plan.values()) {
            report.append(String.format("  %-20s %s%n", plan.label, plan.description));
        }
        report.append(String.format("%n  %-20s %9s %9s %9s %11s %11s %9s %8s%n", "",
                "lakes", "parks", "results", "geometries", "coordinates", "pairs", "median"));
        report.append(String.format("  %-20s %9s %9s %9s %11s %11s %9s %8s%n", "",
                "reproj.", "reproj.", "reproj.", "total", "total", "", "ms"));
        for (Plan plan : Plan.values()) {
            final Outcome outcome = outcomes.get(plan);
            report.append(String.format("  %-20s %,9d %,9d %,9d %,11d %,11d %,9d %8.0f%n", plan.label,
                    outcome.geometries(Stage.LAKES), outcome.geometries(Stage.PARKS),
                    outcome.geometries(Stage.OUTPUT), outcome.totalGeometries(),
                    outcome.totalCoordinates(), outcome.pairs.size(), outcome.medianMillis()));
        }

        report.append(String.format("%n  Agreement with the as-written plan:%n"));
        for (Plan plan : Plan.values()) {
            if (plan == Plan.AS_WRITTEN) continue;
            final SortedSet<String> missing = new TreeSet<>(reference.pairs);
            missing.removeAll(outcomes.get(plan).pairs);
            final SortedSet<String> extra = new TreeSet<>(outcomes.get(plan).pairs);
            extra.removeAll(reference.pairs);
            report.append(String.format("  %-20s %s%n", plan.label,
                    missing.isEmpty() && extra.isEmpty()
                            ? "identical"
                            : String.format("%d missing, %d extra; true distance of those pairs %s",
                            missing.size(), extra.size(), distanceRange(symmetricDifference(
                                    reference.pairs, outcomes.get(plan).pairs)))));
        }
        report.append(String.format(
                "%n  Each rewritten plan also transforms one reference geometry (the boundary, or the%n"
                        + "  boundary grown by %.0f m) per execution; that is not counted above.%n"
                        + "  Median of %d warmed executions of the whole plan (enumeration and execution).%n",
                DISTANCE_METRES + DERIVED_FILTER_SLACK_METRES, TIMED_REPETITIONS));

        ExperimentResults.writeText("crs_placement", report.toString());

        final StringBuilder json = new StringBuilder();
        json.append(String.format("{%n  \"lakes\": %d,%n  \"parks\": %d,%n  \"distance_m\": %.1f,%n"
                        + "  \"timed_repetitions\": %d,%n  \"plans\": {%n",
                lakes.size(), parks.size(), DISTANCE_METRES, TIMED_REPETITIONS));
        final Plan[] plans = Plan.values();
        for (int i = 0; i < plans.length; i++) {
            final Outcome outcome = outcomes.get(plans[i]);
            json.append(String.format(
                    "    \"%s\": {\"pairs\": %d, \"pairs_differing_from_as_written\": %d,%n"
                            + "      \"reprojected\": {\"lakes\": [%d, %d], \"parks\": [%d, %d], \"output\": [%d, %d]},%n"
                            + "      \"runtime_ms_median\": %.2f}%s%n",
                    plans[i].name().toLowerCase(), outcome.pairs.size(),
                    symmetricDifference(reference.pairs, outcome.pairs).size(),
                    outcome.geometries(Stage.LAKES), outcome.coordinates(Stage.LAKES),
                    outcome.geometries(Stage.PARKS), outcome.coordinates(Stage.PARKS),
                    outcome.geometries(Stage.OUTPUT), outcome.coordinates(Stage.OUTPUT),
                    outcome.medianMillis(), i < plans.length - 1 ? "," : ""));
        }
        json.append(String.format("  }%n}%n"));
        ExperimentResults.writeJson("crs_placement", json.toString());
    }

    private static String distanceRange(SortedSet<String> pairs) {
        double min = Double.MAX_VALUE, max = 0;
        for (String pair : pairs) {
            final double distance = trueDistance(pair);
            min = Math.min(min, distance);
            max = Math.max(max, distance);
        }
        return String.format("%.1f-%.1f m", min, max);
    }
}
