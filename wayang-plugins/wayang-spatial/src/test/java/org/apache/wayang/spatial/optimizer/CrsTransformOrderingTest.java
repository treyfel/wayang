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
import org.apache.wayang.basic.operators.SpatialFilterOperator;
import org.apache.wayang.basic.operators.SpatialJoinOperator;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.java.Java;
import org.apache.wayang.spatial.Spatial;
import org.apache.wayang.spatial.crs.CrsTransforms;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates that Wayang's optimizer does not reorder a CRS transformation with respect to a
 * downstream filter, and therefore reprojects far more geometries than the plan needs.
 *
 * <p>The pipeline is <em>source &rarr; crsTransform &rarr; spatialFilter &rarr; spatialJoin</em>.
 * Written that way, every input geometry is reprojected and only the survivors of the filter are
 * ever used. The equivalent plan that filters first reprojects only the survivors — but a filter
 * expressed in the <em>target</em> CRS cannot simply be moved below the transform: its reference
 * geometry has to be moved into the source CRS with it. That pair of rewrites (reorder + operand
 * swap) is what {@code docs/design/crs-transform-operator.md} and the CRS optimization doc call
 * for, and what this test shows is absent.</p>
 *
 * <p>Both plans are executed and asserted to return <em>the same result</em>, which is what makes
 * the missing rewrite a genuine optimization rather than a change of meaning.</p>
 */
public class CrsTransformOrderingTest {

    private static final int SOURCE_SRID = 4326;

    private static final int TARGET_SRID = 32632;

    private static final int ID_FIELD = 0;

    private static final int GEOMETRY_FIELD = 1;

    /** Counts the elements that reach the CRS transformation, i.e. the work it is asked to do. */
    private static final AtomicLong TRANSFORMED_ELEMENTS = new AtomicLong();

    /** Counts the individual coordinates reprojected, which is what the work actually costs. */
    private static final AtomicLong TRANSFORMED_COORDINATES = new AtomicLong();

    /** Vertices per feature. Higher makes the transform's share of the runtime clearer. */
    private static final int VERTICES_PER_FEATURE = 40;

    /** Timed executions per plan. One sample drifts by tens of percent; the median does not. */
    private static final int TIMED_REPETITIONS = 7;

    // The region of interest, defined once in lon/lat. The "written" plan states it in the target
    // CRS (below), the better plan states it in the source CRS; they are the same region.
    private static final double ROI_MIN_LON = 8.0;
    private static final double ROI_MAX_LON = 9.0;
    private static final double ROI_MIN_LAT = 50.0;
    private static final double ROI_MAX_LAT = 50.5;

    /**
     * A straight edge in one CRS is not straight in the other, so transforming a box by its four
     * corners alone yields a subtly different region — {@link OperandSwapEquivalenceTest} measures
     * the gap at ~111 m for this region. Densifying the edges before transforming removes it, and
     * is what makes the two plans below exactly equivalent rather than approximately so.
     */
    private static final double ROI_DENSIFY_DEGREES = 0.05;

    @Test
    void optimizerDoesNotMoveTheTransformBelowTheFilter() {
        final List<Record> features = buildFeatures();
        final List<Record> joinPartners = buildJoinPartners();

        // Warm up both shapes first: whichever plan ran second would otherwise look faster than it
        // is, purely from JIT. The reprojection counts are exact either way; only the clock needs it.
        run(Plan.TRANSFORM_THEN_FILTER, features, joinPartners);
        run(Plan.FILTER_THEN_TRANSFORM, features, joinPartners);

        final Outcome asWritten = measure(Plan.TRANSFORM_THEN_FILTER, features, joinPartners);
        final Outcome better = measure(Plan.FILTER_THEN_TRANSFORM, features, joinPartners);

        report(features.size(), asWritten, better);

        // 1. The two plans are equivalent: same join result, so the rewrite would be sound.
        assertEquals(asWritten.joinedIds, better.joinedIds,
                "The two orderings must agree, otherwise this is not an optimization at all.");
        assertTrue(!asWritten.joinedIds.isEmpty(), "The demo needs a non-empty result to be meaningful.");

        // 2. As written, the transform sees every input element...
        assertEquals(features.size(), asWritten.transformedElements,
                "The optimizer left the transform above the filter, so it reprojected the whole input.");

        // 3. ...while the better ordering only reprojects what survives the filter.
        assertTrue(better.transformedElements < asWritten.transformedElements / 10,
                String.format("Expected the filter-first plan to reproject far less; got %d vs %d.",
                        better.transformedElements, asWritten.transformedElements));

        // 4. And the results being equal means the extra work bought nothing.
        assertEquals(better.joinedIds.size(), asWritten.joinedIds.size());
    }

    // ------------------------------------------------------------------ plans

    private enum Plan {
        /** What a user writes, and what Wayang executes verbatim. */
        TRANSFORM_THEN_FILTER,
        /** What an optimizer that knew about CRSs would execute instead. */
        FILTER_THEN_TRANSFORM
    }

    /**
     * Executes one plan shape {@link #TIMED_REPETITIONS} times and keeps every timing. The
     * counts and the join result are deterministic, so the last run speaks for all of them;
     * total runtime is not, which is the whole reason for repeating.
     */
    private Outcome measure(Plan shape, List<Record> features, List<Record> joinPartners) {
        final long[] timings = new long[TIMED_REPETITIONS];
        Outcome outcome = null;
        for (int repetition = 0; repetition < TIMED_REPETITIONS; repetition++) {
            outcome = run(shape, features, joinPartners);
            timings[repetition] = outcome.elapsedNanos;
        }
        return outcome.withTimings(timings);
    }

    private Outcome run(Plan shape, List<Record> features, List<Record> joinPartners) {
        TRANSFORMED_ELEMENTS.set(0);
        TRANSFORMED_COORDINATES.set(0);

        final DataSetType<Record> recordType = DataSetType.createDefault(Record.class);
        final CollectionSource<Record> source = new CollectionSource<>(features, recordType);
        final CollectionSource<Record> partners = new CollectionSource<>(joinPartners, recordType);

        final CRSTransformationOperator<Record> transform = new CRSTransformationOperator<>(
                record -> readAndCount(record.getField(GEOMETRY_FIELD)),
                (record, geometry) -> {
                    final Record transformed = record.copy();
                    transformed.setField(GEOMETRY_FIELD, geometry);
                    return transformed;
                },
                recordType, SOURCE_SRID, TARGET_SRID);

        // The filter is written against the *target* CRS, because that is the CRS the user is
        // thinking in once the transform is in the plan. Moving it below the transform therefore
        // also means moving its reference geometry into the source CRS — the "operand swap".
        final SpatialFilterOperator<Record> filter = new SpatialFilterOperator<>(
                SpatialPredicate.INTERSECTS,
                record -> (WayangGeometry) record.getField(GEOMETRY_FIELD),
                recordType,
                shape == Plan.TRANSFORM_THEN_FILTER ? roiInTargetCrs() : roiInSourceCrs());

        final SpatialJoinOperator<Record, Record> join = new SpatialJoinOperator<>(
                (org.apache.wayang.core.function.FunctionDescriptor.SerializableFunction<Record, SpatialGeometry>)
                        record -> (WayangGeometry) record.getField(GEOMETRY_FIELD),
                (org.apache.wayang.core.function.FunctionDescriptor.SerializableFunction<Record, SpatialGeometry>)
                        record -> (WayangGeometry) record.getField(GEOMETRY_FIELD),
                Record.class, Record.class,
                SpatialPredicate.INTERSECTS);

        final List<Tuple2> collected = new ArrayList<>();
        final LocalCallbackSink<Tuple2> sink = LocalCallbackSink.createCollectingSink(
                collected, DataSetType.createDefaultUnchecked(Tuple2.class));

        // Only the order of these two connections differs between the plans.
        final Operator first = shape == Plan.TRANSFORM_THEN_FILTER ? transform : filter;
        final Operator second = shape == Plan.TRANSFORM_THEN_FILTER ? filter : transform;
        source.connectTo(0, first, 0);
        first.connectTo(0, second, 0);
        second.connectTo(0, join, 0);
        partners.connectTo(0, join, 1);
        join.connectTo(0, sink, 0);

        final WayangContext context = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        // The whole of execute(): plan enumeration and execution, i.e. what the user waits for.
        final long start = System.nanoTime();
        context.execute(new WayangPlan(sink));
        final long elapsedNanos = System.nanoTime() - start;

        final SortedSet<Integer> ids = new TreeSet<>();
        for (Tuple2 tuple : collected) {
            ids.add((Integer) ((Record) tuple.field0).getField(ID_FIELD));
        }

        return new Outcome(ids, TRANSFORMED_ELEMENTS.get(), TRANSFORMED_COORDINATES.get(),
                elapsedNanos, new long[]{elapsedNanos});
    }

    // ------------------------------------------------------------------ data

    /** Instrumented geometry reader: counts what the transform is handed. */
    private static SpatialGeometry readAndCount(Object field) {
        final WayangGeometry geometry = (WayangGeometry) field;
        TRANSFORMED_ELEMENTS.incrementAndGet();
        TRANSFORMED_COORDINATES.addAndGet(geometry.getGeometry().getNumPoints());
        return geometry;
    }

/** A grid of small polygons across western Germany, in EPSG:4326. */
    private static List<Record> buildFeatures() {
        final List<Record> features = new ArrayList<>();
        int id = 0;
        for (double lon = 6.0; lon <= 12.0; lon += 0.08) {
            for (double lat = 48.0; lat <= 53.0; lat += 0.08) {
                features.add(new Record(id++, ring(lon, lat, 0.01)));
            }
        }
        return features;
    }

    /** A handful of polygons in EPSG:32632 covering the region, as the join's right-hand side. */
    private static List<Record> buildJoinPartners() {
        final List<Record> partners = new ArrayList<>();
        final Geometry roi = ((WayangGeometry) roiInTargetCrs()).getGeometry();
        final org.locationtech.jts.geom.Envelope box = roi.getEnvelopeInternal();
        int id = 0;
        final double stepX = box.getWidth() / 3.0;
        final double stepY = box.getHeight() / 3.0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final double minX = box.getMinX() + i * stepX;
                final double minY = box.getMinY() + j * stepY;
                partners.add(new Record(id++, WayangGeometry.fromStringInput(String.format(
                        "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                        minX, minY, minX + stepX, minY, minX + stepX, minY + stepY,
                        minX, minY + stepY, minX, minY))));
            }
        }
        return partners;
    }

    /** A closed ring of {@link #VERTICES_PER_FEATURE} vertices around a centre. */
    private static WayangGeometry ring(double centreLon, double centreLat, double radius) {
        final StringBuilder wkt = new StringBuilder("POLYGON((");
        for (int i = 0; i < VERTICES_PER_FEATURE; i++) {
            final double angle = 2.0 * Math.PI * i / VERTICES_PER_FEATURE;
            if (i > 0) wkt.append(", ");
            wkt.append(String.format("%f %f",
                    centreLon + radius * Math.cos(angle), centreLat + radius * Math.sin(angle)));
        }
        wkt.append(String.format(", %f %f))", centreLon + radius, centreLat));
        return WayangGeometry.fromStringInput(wkt.toString());
    }

    /** The region of interest as the user states it once the data is in metres. */
    private static SpatialGeometry roiInTargetCrs() {
        final Geometry inDegrees = org.locationtech.jts.densify.Densifier.densify(
                ((WayangGeometry) roiInSourceCrs()).getGeometry(), ROI_DENSIFY_DEGREES);
        return WayangGeometry.fromGeometry(CrsTransforms.transform(inDegrees, SOURCE_SRID, TARGET_SRID));
    }

    /** The same region, stated in the CRS the data arrives in — what the operand swap produces. */
    private static SpatialGeometry roiInSourceCrs() {
        return WayangGeometry.fromStringInput(String.format(
                "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                ROI_MIN_LON, ROI_MIN_LAT, ROI_MAX_LON, ROI_MIN_LAT, ROI_MAX_LON, ROI_MAX_LAT,
                ROI_MIN_LON, ROI_MAX_LAT, ROI_MIN_LON, ROI_MIN_LAT));
    }

    private static class Outcome {
        final SortedSet<Integer> joinedIds;
        final long transformedElements;
        final long transformedCoordinates;
        final long elapsedNanos;

        /** Every timed run of this plan, ascending. */
        final long[] timingsNanos;

        Outcome(SortedSet<Integer> joinedIds, long transformedElements,
                long transformedCoordinates, long elapsedNanos, long[] timingsNanos) {
            this.joinedIds = joinedIds;
            this.transformedElements = transformedElements;
            this.transformedCoordinates = transformedCoordinates;
            this.elapsedNanos = elapsedNanos;
            this.timingsNanos = timingsNanos.clone();
            Arrays.sort(this.timingsNanos);
        }

        Outcome withTimings(long[] timingsNanos) {
            return new Outcome(this.joinedIds, this.transformedElements,
                    this.transformedCoordinates, this.elapsedNanos, timingsNanos);
        }

        double medianMillis() {
            return millis(this.timingsNanos[this.timingsNanos.length / 2]);
        }

        double fastestMillis() {
            return millis(this.timingsNanos[0]);
        }

        double slowestMillis() {
            return millis(this.timingsNanos[this.timingsNanos.length - 1]);
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    private static void report(int inputSize, Outcome asWritten, Outcome better) {
        final long wasted = asWritten.transformedElements - better.transformedElements;
        final double wastedPercent = 100.0 * wasted / asWritten.transformedElements;

        final StringBuilder report = new StringBuilder();
        report.append(String.format(
                "CRS transform ordering - source -> [transform|filter] -> join, Java platform%n%n"));
        report.append(String.format("  input features                     %,d (%d vertices each)%n",
                inputSize, VERTICES_PER_FEATURE));
        report.append(String.format("  join result (both plans)           %,d features%n%n",
                asWritten.joinedIds.size()));
        report.append(String.format("  %-34s %14s %14s%n", "", "as written", "filter first"));
        report.append(String.format("  %-34s %14s %14s%n", "plan order",
                "transform,filter", "filter,transform"));
        report.append(String.format("  %-34s %,14d %,14d%n", "geometries reprojected",
                asWritten.transformedElements, better.transformedElements));
        report.append(String.format("  %-34s %,14d %,14d%n", "coordinates reprojected",
                asWritten.transformedCoordinates, better.transformedCoordinates));
        report.append(String.format("  %-34s %14s %14s%n", "total runtime, median (ms)",
                String.format("%.1f", asWritten.medianMillis()),
                String.format("%.1f", better.medianMillis())));
        report.append(String.format("  %-34s %14s %14s%n", "total runtime, range (ms)",
                String.format("%.1f-%.1f", asWritten.fastestMillis(), asWritten.slowestMillis()),
                String.format("%.1f-%.1f", better.fastestMillis(), better.slowestMillis())));
        report.append(String.format("%n  wasted reprojections: %,d of %,d (%.1f%%)"
                        + " - identical result either way%n%n",
                wasted, asWritten.transformedElements, wastedPercent));
        report.append(String.format(
                "  Wayang executed each plan exactly as written: every mapping is a%n"
                        + "  single-operator pattern, so there is no rewrite phase that could%n"
                        + "  reorder the transform past the filter or swap the filter's operand.%n%n"));
        report.append(String.format(
                "  Total runtime is the median of %d warmed executions of the whole plan%n"
                        + "  (enumeration and execution) on one machine; the reprojection counts%n"
                        + "  are exact and identical on every run.%n%n", TIMED_REPETITIONS));
        report.append(String.format(
                "  Reprojecting %.0fx fewer geometries makes the whole plan %.1fx faster: the%n"
                        + "  spatial join and the plan enumeration are the rest of the bill, and%n"
                        + "  reordering does not touch them.%n",
                (double) asWritten.transformedElements / better.transformedElements,
                asWritten.medianMillis() / better.medianMillis()));

        ExperimentResults.writeText("crs_ordering", report.toString());

        ExperimentResults.writeJson("crs_ordering", String.format(
                "{%n"
                        + "  \"features_in\": %d,%n"
                        + "  \"vertices_per_feature\": %d,%n"
                        + "  \"join_result\": %d,%n"
                        + "  \"source_srid\": %d,%n"
                        + "  \"target_srid\": %d,%n"
                        + "  \"timed_repetitions\": %d,%n"
                        + "  \"as_written\": {\"geometries\": %d, \"coordinates\": %d,%n"
                        + "    \"runtime_ms\": "
                        + "{\"median\": %.2f, \"fastest\": %.2f, \"slowest\": %.2f}},%n"
                        + "  \"filter_first\": {\"geometries\": %d, \"coordinates\": %d,%n"
                        + "    \"runtime_ms\": "
                        + "{\"median\": %.2f, \"fastest\": %.2f, \"slowest\": %.2f}}%n"
                        + "}%n",
                inputSize, VERTICES_PER_FEATURE, asWritten.joinedIds.size(),
                SOURCE_SRID, TARGET_SRID, TIMED_REPETITIONS,
                asWritten.transformedElements, asWritten.transformedCoordinates,
                asWritten.medianMillis(), asWritten.fastestMillis(), asWritten.slowestMillis(),
                better.transformedElements, better.transformedCoordinates,
                better.medianMillis(), better.fastestMillis(), better.slowestMillis()));
    }
}
