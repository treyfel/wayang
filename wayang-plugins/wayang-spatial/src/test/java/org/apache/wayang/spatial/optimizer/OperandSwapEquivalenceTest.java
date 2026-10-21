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

import org.apache.wayang.spatial.crs.CrsTransforms;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down a precondition of the rewrite that {@link CrsTransformOrderingTest} argues for.
 *
 * <p>Moving a spatial filter below a CRS transformation means restating its reference geometry in
 * the source CRS — the "operand swap". That is <em>not</em> a pure change of representation: a
 * straight edge in one CRS is a curve in the other, so transforming a polygon by its vertices
 * alone produces a subtly different region, and elements near an edge fall on different sides of
 * it in the two plans.</p>
 *
 * <p>This test measures the resulting disagreement band and shows that densifying the reference
 * geometry's edges before transforming closes it. An optimizer performing the operand swap has to
 * densify; one that does not would silently change results near region boundaries.</p>
 */
public class OperandSwapEquivalenceTest {

    private static final GeometryFactory GF = new GeometryFactory();

    private static final int SOURCE_SRID = 4326;

    private static final int TARGET_SRID = 32632;

    /** A 1° x 0.5° region — a long enough edge for the curvature to matter. */
    private static final String ROI_WKT = "POLYGON((8.0 50.0, 9.0 50.0, 9.0 50.5, 8.0 50.5, 8.0 50.0))";

    /** Colorado as defined by statute: 37-41 N, 102.05-109.05 W. */
    private static final String COLORADO_WKT =
            "POLYGON((-109.05 37.0, -102.05 37.0, -102.05 41.0, -109.05 41.0, -109.05 37.0))";

    /** WGS 84 / UTM zone 13N, whose central meridian (105 W) runs through Colorado. */
    private static final int COLORADO_UTM_SRID = 32613;

    private static final String COLORADO_FEATURES_RESOURCE = "/geonames-colorado-border.csv";

    /** Roughly metres per degree of latitude, for turning offsets into a readable distance. */
    private static final double METRES_PER_DEGREE = 111_000.0;

    @Test
    void naiveOperandSwapDisagreesNearEdges() throws ParseException {
        final Geometry roiSource = new WKTReader().read(ROI_WKT);

        final double naive = worstDisagreementOffset(
                roiSource, CrsTransforms.transform(roiSource, SOURCE_SRID, TARGET_SRID));

        ExperimentResults.writeText("operand_swap_naive", String.format(
                "Operand swap, reference geometry transformed by its vertices alone%n%n"
                        + "  region of interest     %s%n"
                        + "  EPSG:%d -> EPSG:%d%n%n"
                        + "  worst disagreement     %.4f deg (~%.0f m) from an edge%n%n"
                        + "  Points within that band fall on different sides of the region in the%n"
                        + "  two formulations, so the swap is not a pure change of representation.%n",
                ROI_WKT, SOURCE_SRID, TARGET_SRID, naive, naive * METRES_PER_DEGREE));

        assertTrue(naive > 0.0,
                "Transforming only the corners should measurably change the region; if this ever "
                        + "stops being true, the densification below is no longer needed.");

        ExperimentResults.writeJson("operand_swap_naive", String.format(
                "{%n"
                        + "  \"source_srid\": %d,%n"
                        + "  \"target_srid\": %d,%n"
                        + "  \"roi_wkt\": \"%s\",%n"
                        + "  \"metres_per_degree\": %.1f,%n"
                        + "  \"worst_offset_degrees\": %.6f,%n"
                        + "  \"worst_offset_metres\": %.1f%n"
                        + "}%n",
                SOURCE_SRID, TARGET_SRID, ROI_WKT, METRES_PER_DEGREE,
                naive, naive * METRES_PER_DEGREE));
    }

    @Test
    void densifyingTheReferenceGeometryRestoresEquivalence() throws ParseException {
        final Geometry roiSource = new WKTReader().read(ROI_WKT);

        final StringBuilder measured = new StringBuilder();
        final List<double[]> results = new ArrayList<>();

        // Even a coarse densification is enough: the edges only have to follow the curve.
        for (double step : new double[]{0.1, 0.01, 0.001}) {
            final Geometry densified = CrsTransforms.transform(
                    Densifier.densify(roiSource, step), SOURCE_SRID, TARGET_SRID);
            final double offset = worstDisagreementOffset(roiSource, densified);
            assertEquals(0.0, offset, 0.0,
                    String.format("Densifying at %.3f deg should make the two formulations agree.", step));

            results.add(new double[]{step, offset});
            if (measured.length() > 0) measured.append(String.format(",%n"));
            measured.append(String.format(
                    "    {\"step_degrees\": %.3f, \"worst_offset_degrees\": %.6f}", step, offset));
        }

        final StringBuilder report = new StringBuilder();
        report.append(String.format(
                "Operand swap, reference geometry densified before transforming%n%n"
                        + "  EPSG:%d -> EPSG:%d%n%n"
                        + "  %-22s %22s%n", SOURCE_SRID, TARGET_SRID,
                "densify step (deg)", "worst disagreement (deg)"));
        for (double[] row : results) {
            report.append(String.format("  %-22.3f %22.6f%n", row[0], row[1]));
        }
        report.append(String.format(
                "%n  Every step size closes the gap exactly. An optimizer performing the%n"
                        + "  operand swap has to densify; one that does not silently changes%n"
                        + "  results near region boundaries.%n"));
        ExperimentResults.writeText("operand_swap_densified", report.toString());

        ExperimentResults.writeJson("operand_swap_densified", String.format(
                "{%n"
                        + "  \"source_srid\": %d,%n"
                        + "  \"target_srid\": %d,%n"
                        + "  \"metres_per_degree\": %.1f,%n"
                        + "  \"steps\": [%n%s%n  ]%n"
                        + "}%n",
                SOURCE_SRID, TARGET_SRID, METRES_PER_DEGREE, measured));
    }

    /**
     * The same effect on real data, at a realistic scale. Colorado's statutory boundary is the
     * four-corner box below, so a filter "features in Colorado" written in EPSG:4326 has exactly
     * four vertices. Swapped onto features stored in UTM zone 13N, its 600 km edges move by up to
     * ~6 km, and named towns fall on the wrong side.
     */
    @Test
    void naiveOperandSwapMisclassifiesRealFeaturesNearColoradosBoundary() throws Exception {
        final Geometry colorado = new WKTReader().read(COLORADO_WKT);
        final List<Feature> features = loadColoradoBorderFeatures();

        final Geometry naiveTarget = CrsTransforms.transform(colorado, SOURCE_SRID, COLORADO_UTM_SRID);
        final Disagreement naive = classify(features, colorado, naiveTarget);
        final Disagreement densified = classify(features, colorado,
                CrsTransforms.transform(Densifier.densify(colorado, 0.1), SOURCE_SRID, COLORADO_UTM_SRID));

        ExperimentResults.writeText("operand_swap_colorado", String.format(
                "Operand swap on real data: GeoNames features near Colorado's boundary%n%n"
                        + "  region of interest     %s%n"
                        + "  EPSG:%d -> EPSG:%d%n"
                        + "  features               %d, all within 0.1 deg of the boundary%n%n"
                        + "  %-22s %12s %12s%n"
                        + "  %-22s %12d %12d%n"
                        + "  %-22s %12d %12d%n%n"
                        + "  Populated places wrongly included by the naive swap:%n%s%n"
                        + "  Populated places wrongly excluded by the naive swap:%n%s",
                COLORADO_WKT, SOURCE_SRID, COLORADO_UTM_SRID, features.size(),
                "reference geometry", "wrongly in", "wrongly out",
                "vertices only", naive.wronglyIn.size(), naive.wronglyOut.size(),
                "densified at 0.1 deg", densified.wronglyIn.size(), densified.wronglyOut.size(),
                listPopulatedPlaces(naive.wronglyIn), listPopulatedPlaces(naive.wronglyOut)));

        ExperimentResults.writeJson("operand_swap_colorado", String.format(
                "{%n"
                        + "  \"source_srid\": %d,%n"
                        + "  \"target_srid\": %d,%n"
                        + "  \"roi_wkt\": \"%s\",%n"
                        + "  \"features\": %d,%n"
                        + "  \"vertices_only\": {\"wrongly_in\": %d, \"wrongly_out\": %d},%n"
                        + "  \"densified_0_1_deg\": {\"wrongly_in\": %d, \"wrongly_out\": %d},%n"
                        + "  \"wrongly_in_ids\": [%s],%n"
                        + "  \"wrongly_out_ids\": [%s],%n"
                        + "  \"naive_boundary_in_source_crs\": [%s]%n"
                        + "}%n",
                SOURCE_SRID, COLORADO_UTM_SRID, COLORADO_WKT, features.size(),
                naive.wronglyIn.size(), naive.wronglyOut.size(),
                densified.wronglyIn.size(), densified.wronglyOut.size(),
                joinIds(naive.wronglyIn), joinIds(naive.wronglyOut),
                joinCoordinates(regionSelectedBy(naiveTarget))));

        // Hundreds of features, not a handful at the floating-point edge.
        assertTrue(naive.wronglyIn.size() > 100 && naive.wronglyOut.size() > 100,
                "The naive swap should misclassify hundreds of features near the boundary.");
        // Arboles, CO (pop. 280) sits 3 km north of the 37th parallel, inside the bulge.
        assertTrue(naive.wronglyOut.stream().anyMatch(f -> f.name.equals("Arboles")),
                "Arboles, CO should be lost by the naive swap.");
        assertEquals(0, densified.wronglyIn.size() + densified.wronglyOut.size(),
                "Densifying at 0.1 deg should classify every feature as the source-CRS filter does.");
    }

    /** A GeoNames feature: a named point in EPSG:4326. */
    private static final class Feature {
        final long id;
        final String name;
        final String featureClass;
        final String admin1;
        final Point location;

        Feature(long id, String name, String featureClass, String admin1, Point location) {
            this.id = id;
            this.name = name;
            this.featureClass = featureClass;
            this.admin1 = admin1;
            this.location = location;
        }
    }

    /** The features each formulation of the filter classifies differently from the source CRS. */
    private static final class Disagreement {
        final List<Feature> wronglyIn = new ArrayList<>();
        final List<Feature> wronglyOut = new ArrayList<>();
    }

    private static List<Feature> loadColoradoBorderFeatures() throws IOException {
        final List<Feature> features = new ArrayList<>();
        try (InputStream in = OperandSwapEquivalenceTest.class.getResourceAsStream(COLORADO_FEATURES_RESOURCE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     Objects.requireNonNull(in, COLORADO_FEATURES_RESOURCE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                // geonameid, lon, lat, feature class, admin1, name (which may contain commas)
                final String[] fields = line.split(",", 6);
                final Point location = GF.createPoint(new Coordinate(
                        Double.parseDouble(fields[1]), Double.parseDouble(fields[2])));
                features.add(new Feature(Long.parseLong(fields[0]), fields[5], fields[3], fields[4], location));
            }
        }
        return features;
    }

    /**
     * Classifies every feature against the filter in its source CRS and against the swapped filter
     * in the target CRS, and collects the features the two disagree on.
     */
    private static Disagreement classify(List<Feature> features, Geometry roiSource, Geometry roiTarget) {
        final Disagreement disagreement = new Disagreement();
        for (Feature feature : features) {
            final boolean inSource = roiSource.contains(feature.location);
            final boolean inTarget = roiTarget.contains(
                    CrsTransforms.transform(feature.location, SOURCE_SRID, COLORADO_UTM_SRID));
            if (inTarget && !inSource) disagreement.wronglyIn.add(feature);
            if (inSource && !inTarget) disagreement.wronglyOut.add(feature);
        }
        return disagreement;
    }

    /**
     * Draws the region a target-CRS filter actually selects back in the source CRS, for plotting.
     * Its straight target-CRS edges are densified first (every 5 km) so that they come back as
     * the curves they are in the source CRS.
     */
    private static Geometry regionSelectedBy(Geometry roiTarget) {
        return CrsTransforms.transform(Densifier.densify(roiTarget, 5_000), COLORADO_UTM_SRID, SOURCE_SRID);
    }

    private static String joinIds(List<Feature> features) {
        final StringBuilder sb = new StringBuilder();
        for (Feature feature : features) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(feature.id);
        }
        return sb.toString();
    }

    private static String joinCoordinates(Geometry geometry) {
        final StringBuilder sb = new StringBuilder();
        for (Coordinate c : geometry.getCoordinates()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("[%.6f, %.6f]", c.x, c.y));
        }
        return sb.toString();
    }

    /** Lists the populated places (GeoNames feature class P) among {@code features}. */
    private static String listPopulatedPlaces(List<Feature> features) {
        final StringBuilder sb = new StringBuilder();
        for (Feature feature : features) {
            if (!feature.featureClass.equals("P")) continue;
            final Coordinate c = feature.location.getCoordinate();
            sb.append(String.format("    %-28s %-3s %9.4f %10.4f%n", feature.name, feature.admin1, c.y, c.x));
        }
        return sb.length() == 0 ? String.format("    (none)%n") : sb.toString();
    }

    /**
     * Samples points on both sides of the region's horizontal edges, at a range of offsets, and
     * returns the largest offset at which "inside the source-CRS region" and "inside the
     * target-CRS region" disagree. Points exactly on an edge are never sampled: whether a boundary
     * point counts as inside is a floating-point coin flip, not the effect being measured.
     *
     * @return the widest disagreement, in degrees of latitude; 0 if the two always agree
     */
    private static double worstDisagreementOffset(Geometry roiSource, Geometry roiTarget) {
        double worst = 0.0;
        for (double lon = 8.005; lon <= 8.995; lon += 0.005) {
            for (double edgeLat : new double[]{50.0, 50.5}) {
                for (double offset : new double[]{
                        0.2, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002, 0.001, 0.0005, 0.0002, 0.0001}) {
                    if (offset <= worst) continue;
                    for (int sign : new int[]{1, -1}) {
                        final Point p = GF.createPoint(new Coordinate(lon, edgeLat + sign * offset));
                        final boolean inSource = roiSource.contains(p);
                        final boolean inTarget =
                                roiTarget.contains(CrsTransforms.transform(p, SOURCE_SRID, TARGET_SRID));
                        if (inSource != inTarget) {
                            worst = offset;
                        }
                    }
                }
            }
        }
        return worst;
    }
}
