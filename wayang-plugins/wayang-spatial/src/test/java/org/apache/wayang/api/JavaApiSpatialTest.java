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

package org.apache.wayang.api;

import org.apache.wayang.basic.data.Record;
import org.apache.wayang.basic.data.Tuple2;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.java.Java;
import org.apache.wayang.postgres.Postgres;
import org.apache.wayang.postgres.operators.PostgresTableSource;
import org.apache.wayang.spark.Spark;
import org.apache.wayang.spatial.Spatial;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the fluent spatial API on DataQuantaBuilder.
 */
public class JavaApiSpatialTest {

    private static final String POSTGRES_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String SPATIALDB_URL = "jdbc:postgresql://localhost:5432/spatialdb";
    private static final String POSTGRES_USER = "postgres";
    private static final String POSTGRES_PASSWORD = "postgres";

    private static boolean postgresAvailable;
    private static boolean spatialDbAvailable;

    @BeforeAll
    static void probeDatabases() {
        postgresAvailable = canConnect(POSTGRES_URL);
        spatialDbAvailable = canConnect(SPATIALDB_URL);
    }

    private static boolean canConnect(String url) {
        try (java.sql.Connection ignored = DriverManager.getConnection(url, POSTGRES_USER, POSTGRES_PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Java Platform Tests ====================

    @Test
    void testSpatialFilter() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Filter Test");

        List<String> testData = Arrays.asList(
                "0.0,0.0,1.0,1.0",      // Box at origin
                "0.5,0.5,1.5,1.5",      // Overlapping box
                "2.0,2.0,3.0,3.0",      // Non-overlapping box
                "0.25,0.25,0.75,0.75"   // Box inside first
        );

        WayangGeometry queryGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"
        );

        Collection<Long> result = planBuilder.loadCollection(testData)
                .spatialFilter(
                        (input -> {
                            String[] parts = input.split(",");
                            double xmin = Double.parseDouble(parts[0]);
                            double ymin = Double.parseDouble(parts[1]);
                            double xmax = Double.parseDouble(parts[2]);
                            double ymax = Double.parseDouble(parts[3]);
                            String wkt = String.format(
                                    "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                                    xmin, ymin, xmax, ymin, xmax, ymax, xmin, ymax, xmin, ymin
                            );
                            return WayangGeometry.fromStringInput(wkt);
                        }),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry
                )
                .count()
                .collect();

        // Should match 3 boxes (first overlaps, second overlaps, fourth is inside)
        assertEquals(1, result.size());
        Long count = result.iterator().next();
        assertEquals(3L, count);
    }

    @Test
    void testSpatialJoin() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Join Test");

        List<String> leftData = Arrays.asList(
                "POINT(0.5 0.5)",  // Inside first box
                "POINT(1.5 1.5)",  // Inside second box
                "POINT(0.25 0.75)" // Inside first box
        );

        List<String> rightData = Arrays.asList(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",  // Contains first and third points
                "POLYGON((1 1, 2 1, 2 2, 1 2, 1 1))"   // Contains second point
        );

        Collection<Long> result = planBuilder.loadCollection(leftData)
                .spatialJoin(
                        (WayangGeometry::fromStringInput),
                        planBuilder.loadCollection(rightData),
                        (WayangGeometry::fromStringInput),
                        SpatialPredicate.INTERSECTS
                )
                .count()
                .collect();

        // Should have 3 matches:
        // - POINT(0.5 0.5) with first box
        // - POINT(1.5 1.5) with second box
        // - POINT(0.25 0.75) with first box
        assertEquals(1, result.size());
        Long count = result.iterator().next();
        assertEquals(3L, count);
    }

    @Test
    void testChainedOperations() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Chained Operations Test");

        // Data: "id,xmin,ymin,xmax,ymax"
        List<String> testData = Arrays.asList(
                "1,0.0,0.0,1.0,1.0",
                "2,0.5,0.5,1.5,1.5",
                "3,2.0,2.0,3.0,3.0",
                "4,0.25,0.25,0.75,0.75"
        );

        WayangGeometry queryGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"
        );

        // Chain: spatialFilter -> map (extract id) -> filter (id > 1) -> collect
        Collection<Integer> result = planBuilder.loadCollection(testData)
                .spatialFilter(
                        (input -> {
                            String[] parts = input.split(",");
                            double xmin = Double.parseDouble(parts[1]);
                            double ymin = Double.parseDouble(parts[2]);
                            double xmax = Double.parseDouble(parts[3]);
                            double ymax = Double.parseDouble(parts[4]);
                            String wkt = String.format(
                                    "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                                    xmin, ymin, xmax, ymin, xmax, ymax, xmin, ymax, xmin, ymin
                            );
                            return WayangGeometry.fromStringInput(wkt);
                        }),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry
                )
                .map(line -> Integer.parseInt(line.split(",")[0]))  // Extract ID
                .filter(id -> id > 1)  // Keep only IDs > 1
                .collect();

        // Should match boxes 1, 2, 4 (intersect), then filter to IDs > 1 -> 2, 4
        assertEquals(2, result.size());
        assertTrue(result.contains(2));
        assertTrue(result.contains(4));
    }

    @Test
    void testSpatialJoinChainedWithMapAndReduce() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Join Chained Test");

        // Left data: points with values "wkt;value"
        List<String> leftData = Arrays.asList(
                "POINT(0.5 0.5);10",
                "POINT(1.5 1.5);20",
                "POINT(0.25 0.75);30"
        );

        // Right data: boxes with multipliers "wkt;multiplier"
        List<String> rightData = Arrays.asList(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0));2",
                "POLYGON((1 1, 2 1, 2 2, 1 2, 1 1));3"
        );

        // Chain: spatialJoin -> map (multiply values) -> reduce (sum)
        Collection<Integer> result = planBuilder.loadCollection(leftData)
                .spatialJoin(
                        (input -> WayangGeometry.fromStringInput(input.split(";")[0])),
                        planBuilder.loadCollection(rightData),
                        (input -> WayangGeometry.fromStringInput(input.split(";")[0])),
                        SpatialPredicate.INTERSECTS
                )
                .map(tuple -> {
                    int leftValue = Integer.parseInt(tuple.field0.split(";")[1]);
                    int rightMultiplier = Integer.parseInt(tuple.field1.split(";")[1]);
                    return leftValue * rightMultiplier;
                })
                .reduce((a, b) -> a + b)
                .collect();

        // Matches:
        // - POINT(0.5 0.5);10 with box;2 -> 10*2 = 20
        // - POINT(1.5 1.5);20 with box;3 -> 20*3 = 60
        // - POINT(0.25 0.75);30 with box;2 -> 30*2 = 60
        // Sum = 20 + 60 + 60 = 140
        assertEquals(1, result.size());
        assertEquals(140, result.iterator().next());
    }

    @Test
    void testChainedSpatialFilters() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Chained Spatial Filters Test");

        List<String> testData = Arrays.asList(
                "POLYGON((0.1 0.1, 0.3 0.1, 0.3 0.3, 0.1 0.3, 0.1 0.1))",  // Inside both query geometries
                "POLYGON((0.6 0.6, 0.8 0.6, 0.8 0.8, 0.6 0.8, 0.6 0.6))",  // Inside first, outside second
                "POLYGON((2 2, 3 2, 3 3, 2 3, 2 2))"                        // Outside both
        );

        WayangGeometry queryGeometry1 = WayangGeometry.fromStringInput(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"  // Unit square
        );
        WayangGeometry queryGeometry2 = WayangGeometry.fromStringInput(
                "POLYGON((0 0, 0.5 0, 0.5 0.5, 0 0.5, 0 0))"  // Smaller square (0-0.5 range)
        );

        // Chain two spatial filters
        Collection<Long> result = planBuilder.loadCollection(testData)
                .spatialFilter(
                        (WayangGeometry::fromStringInput),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry1
                )
                .map(x -> x).withOutputClass(String.class)  // Preserve type for chaining
                .spatialFilter(
                        (WayangGeometry::fromStringInput),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry2
                )
                .count()
                .collect();

        // Only the first box (0.1-0.3) should pass both filters
        assertEquals(1, result.size());
        assertEquals(1L, result.iterator().next());
    }

    @Test
    void testSpatialFilterFollowedBySpatialJoin() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spatial.javaPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Filter then Join Test");

        List<String> leftData = Arrays.asList(
                "POINT(0.5 0.5)",
                "POINT(1.5 1.5)",
                "POINT(0.25 0.25)"
        );

        List<String> rightData = Arrays.asList(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                "POLYGON((1 1, 2 1, 2 2, 1 2, 1 1))"
        );

        WayangGeometry preFilterGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"
        );

        // Filter left data, then join with right
        var filteredLeft = planBuilder.loadCollection(leftData)
                .spatialFilter(
                        (input -> WayangGeometry.fromStringInput(input)),
                        SpatialPredicate.INTERSECTS,
                        preFilterGeometry
                )
                .map(x -> x).withOutputClass(String.class);

        Collection<Long> result = filteredLeft
                .spatialJoin(
                        (input -> WayangGeometry.fromStringInput(input)),
                        planBuilder.loadCollection(rightData),
                        (input -> WayangGeometry.fromStringInput(input)),
                        SpatialPredicate.INTERSECTS
                )
                .count()
                .collect();

        // After filter: POINT(0.5 0.5) and POINT(0.25 0.25) remain
        // Join matches: both with first box = 2 matches
        assertEquals(1, result.size());
        assertEquals(2L, result.iterator().next());
    }

    // ==================== Spark Platform Tests ====================

    @Test
    void testSpatialFilterWithSpark() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Spatial.sparkPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Filter Spark Test");

        List<String> testData = Arrays.asList(
                "0.0,0.0,1.0,1.0",
                "0.5,0.5,1.5,1.5",
                "2.0,2.0,3.0,3.0",
                "0.25,0.25,0.75,0.75"
        );

        WayangGeometry queryGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"
        );

        Collection<Long> result = planBuilder.loadCollection(testData)
                .spatialFilter(
                        (input -> {
                            String[] parts = input.split(",");
                            double xmin = Double.parseDouble(parts[0]);
                            double ymin = Double.parseDouble(parts[1]);
                            double xmax = Double.parseDouble(parts[2]);
                            double ymax = Double.parseDouble(parts[3]);
                            String wkt = String.format(
                                    "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                                    xmin, ymin, xmax, ymin, xmax, ymax, xmin, ymax, xmin, ymin
                            );
                            return WayangGeometry.fromStringInput(wkt);
                        }),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry
                )
                .withTargetPlatform(Spark.platform())
                .count()
                .collect();

        assertEquals(1, result.size());
        assertEquals(3L, result.iterator().next());
    }

    @Test
    void testSpatialJoinWithSpark() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Spatial.sparkPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Join Spark Test");

        List<String> leftData = Arrays.asList(
                "POINT(0.5 0.5)",
                "POINT(1.5 1.5)",
                "POINT(0.25 0.75)"
        );

        List<String> rightData = Arrays.asList(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))",
                "POLYGON((1 1, 2 1, 2 2, 1 2, 1 1))"
        );

        Collection<Long> result = planBuilder.loadCollection(leftData)
                .spatialJoin(
                        (input -> WayangGeometry.fromStringInput(input)),
                        planBuilder.loadCollection(rightData),
                        (input -> WayangGeometry.fromStringInput(input)),
                        SpatialPredicate.INTERSECTS
                )
                .withTargetPlatform(Spark.platform())
                .count()
                .collect();

        assertEquals(1, result.size());
        assertEquals(3L, result.iterator().next());
    }

    @Test
    void testSpatialFilterWithJavaAndSpark() {
        WayangContext wayangContext = new WayangContext(new Configuration())
                .withPlugin(Java.basicPlugin())
                .withPlugin(Spark.basicPlugin())
                .withPlugin(Spatial.plugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Filter Java+Spark Test");

        List<String> testData = Arrays.asList(
                "0.0,0.0,1.0,1.0",
                "0.5,0.5,1.5,1.5",
                "2.0,2.0,3.0,3.0",
                "0.25,0.25,0.75,0.75"
        );

        WayangGeometry queryGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"
        );

        // Let Wayang choose the platform
        Collection<Long> result = planBuilder.loadCollection(testData)
                .spatialFilter(
                        (input -> {
                            String[] parts = input.split(",");
                            double xmin = Double.parseDouble(parts[0]);
                            double ymin = Double.parseDouble(parts[1]);
                            double xmax = Double.parseDouble(parts[2]);
                            double ymax = Double.parseDouble(parts[3]);
                            String wkt = String.format(
                                    "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                                    xmin, ymin, xmax, ymin, xmax, ymax, xmin, ymax, xmin, ymin
                            );
                            return WayangGeometry.fromStringInput(wkt);
                        }),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry
                )
                .count()
                .collect();

        assertEquals(1, result.size());
        assertEquals(3L, result.iterator().next());
    }

    // ==================== PostgreSQL Platform Tests ====================
    // These tests use PostgreSQL spatial operators with ST_Intersects pushdown.

    /**
     * Helper method to create a PostgreSQL-configured Configuration.
     */
    private Configuration getPostgresConfiguration() {
        Configuration configuration = new Configuration();
        configuration.setProperty("wayang.postgres.jdbc.url", POSTGRES_URL);
        configuration.setProperty("wayang.postgres.jdbc.user", POSTGRES_USER);
        configuration.setProperty("wayang.postgres.jdbc.password", POSTGRES_PASSWORD);
        return configuration;
    }

    @Test
    void testSpatialFilterWithPostgres() {
        Assumptions.assumeTrue(postgresAvailable, "Postgres not reachable");
        Configuration configuration = getPostgresConfiguration();

        WayangContext wayangContext = new WayangContext(configuration)
                .withPlugin(Java.basicPlugin())
                .withPlugin(Postgres.plugin())
                .withPlugin(Spatial.postgresPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Filter with Postgres Test");

        // Query geometry: a box in the lower-left quadrant (0,0) to (0.4, 0.4)
        WayangGeometry queryGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0.0 0.0, 0.4 0.0, 0.4 0.4, 0.0 0.4, 0.0 0.0))"
        );

        // Read from spider_boxes table and apply spatial filter using PostgreSQL ST_Intersects
        Collection<Long> result = planBuilder
                .readTable(new PostgresTableSource("spider_boxes", "x_min", "y_min", "x_max", "y_max", "geom"))
                .spatialFilter(
                        (Record record) -> WayangGeometry.fromStringInput(record.getString(4)),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry,
                        "geom"  // SQL geometry column name for PostgreSQL pushdown
                )
                .withTargetPlatform(Postgres.platform())
                .count()
                .collect();

        // Verify we got results (exact count depends on data in spider_boxes)
        assertEquals(1, result.size());
        Long count = result.iterator().next();
        assertTrue(count > 0, "Expected at least one box intersecting the query geometry");
        System.out.println("PostgreSQL Spatial Filter (ST_Intersects): " + count + " boxes intersect the query geometry");
    }

    @Test
    void testSpatialFilterWithPostgresAndMapping() {
        Assumptions.assumeTrue(postgresAvailable, "Postgres not reachable");
        Configuration configuration = getPostgresConfiguration();

        WayangContext wayangContext = new WayangContext(configuration)
                .withPlugin(Java.basicPlugin())
                .withPlugin(Postgres.plugin())
                .withPlugin(Spatial.postgresPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Filter with Postgres and Mapping Test");

        // Query geometry covering center area
        WayangGeometry queryGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0.3 0.3, 0.7 0.3, 0.7 0.7, 0.3 0.7, 0.3 0.3))"
        );

        // Read from spider_boxes, filter spatially with PostgreSQL, then map to extract bounds
        Collection<String> result = planBuilder
                .readTable(new PostgresTableSource("spider_boxes", "x_min", "y_min", "x_max", "y_max", "geom"))
                .spatialFilter(
                        (Record record) -> WayangGeometry.fromStringInput(record.getString(4)),
                        SpatialPredicate.INTERSECTS,
                        queryGeometry,
                        "geom"  // SQL geometry column name for PostgreSQL pushdown
                )
                .withTargetPlatform(Postgres.platform())
                .map((Record record) -> String.format("Box: (%.2f,%.2f)-(%.2f,%.2f)",
                        record.getDouble(0), record.getDouble(1),
                        record.getDouble(2), record.getDouble(3)))
                .collect();

        assertTrue(result.size() > 0, "Expected at least one box intersecting the query geometry");
        System.out.println("PostgreSQL Spatial Filter + Mapping: " + result.size() + " results");
        result.stream().limit(5).forEach(System.out::println);
    }

    @Test
    void testSpatialFilterWithPostgresContains() {
        Assumptions.assumeTrue(postgresAvailable, "Postgres not reachable");
        Configuration configuration = getPostgresConfiguration();

        WayangContext wayangContext = new WayangContext(configuration)
                .withPlugin(Java.basicPlugin())
                .withPlugin(Postgres.plugin())
                .withPlugin(Spatial.postgresPlugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Spatial Filter with Postgres Contains Test");

        // Query geometry: full unit square - should contain all boxes that are fully inside
        WayangGeometry queryGeometry = WayangGeometry.fromStringInput(
                "POLYGON((0.0 0.0, 1.0 0.0, 1.0 1.0, 0.0 1.0, 0.0 0.0))"
        );

        // Test WITHIN predicate - find boxes that are completely within the query geometry
        Collection<Long> result = planBuilder
                .readTable(new PostgresTableSource("spider_boxes", "x_min", "y_min", "x_max", "y_max", "geom"))
                .spatialFilter(
                        (Record record) -> WayangGeometry.fromStringInput(record.getString(4)),
                        SpatialPredicate.WITHIN,
                        queryGeometry,
                        "geom"  // SQL geometry column name for PostgreSQL pushdown
                )
                .withTargetPlatform(Postgres.platform())
                .count()
                .collect();

        assertEquals(1, result.size());
        Long count = result.iterator().next();
        System.out.println("PostgreSQL Spatial Filter (ST_Within): " + count + " boxes within the query geometry");
    }

    // ==================== Cross-Platform Spatial + Non-Spatial Query ====================

    @Test
    void testAverageWarmRentPerPostalCode() throws Exception {
        Assumptions.assumeTrue(spatialDbAvailable, "Postgres spatialdb not reachable");
        Configuration configuration = new Configuration();
        configuration.setProperty("wayang.postgres.jdbc.url", SPATIALDB_URL);
        configuration.setProperty("wayang.postgres.jdbc.user", POSTGRES_USER);
        configuration.setProperty("wayang.postgres.jdbc.password", POSTGRES_PASSWORD);

        WayangContext wayangContext = new WayangContext(configuration)
                .withPlugin(Java.basicPlugin())
                .withPlugin(Postgres.plugin())
                .withPlugin(Spatial.plugin());

        JavaPlanBuilder planBuilder = new JavaPlanBuilder(wayangContext)
                .withJobName("Average Warm Rent Per Postal Code");

        String plzPath = "file:" + JavaApiSpatialTest.class
                .getResource("/data/plz_geometries.geojson").getPath();

        String outputPath = "file:/tmp/plz_rent_stats.geojson";
        Files.deleteIfExists(Paths.get("/tmp/plz_rent_stats.geojson"));

        planBuilder
                .readTable(new PostgresTableSource("apartments", "warm_price", "size_m2", "poi_lat", "poi_lon"))
                .asRecords()
                .projectRecords(new String[]{"warm_price", "size_m2", "poi_lat", "poi_lon"})
                .spatialJoin(
                        (Record apt) -> WayangGeometry.fromStringInput(
                                String.format("POINT(%f %f)", apt.getDouble(3), apt.getDouble(2))),
                        planBuilder.readGeoJsonFile(plzPath),
                        (Record plz) -> (WayangGeometry) plz.getField(0),
                        SpatialPredicate.INTERSECTS
                )
                .map((Tuple2<Record, Record> pair) -> {
                    double warmPrice = ((Number) pair.field0.getField(0)).doubleValue();
                    double sizeSqm = ((Number) pair.field0.getField(1)).doubleValue();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> props = (Map<String, Object>) pair.field1.getField(1);
                    WayangGeometry geom = (WayangGeometry) pair.field1.getField(0);
                    return new Record(props.get("plz").toString(), warmPrice, warmPrice / sizeSqm, 1.0, geom);
                })
                .reduceByKey(
                        (Record r) -> r.getString(0),
                        (Record a, Record b) -> new Record(
                                a.getString(0),
                                ((Number) a.getField(1)).doubleValue() + ((Number) b.getField(1)).doubleValue(),
                                ((Number) a.getField(2)).doubleValue() + ((Number) b.getField(2)).doubleValue(),
                                ((Number) a.getField(3)).doubleValue() + ((Number) b.getField(3)).doubleValue(),
                                a.getField(4)
                        )
                )
                .map((Record r) -> {
                    double count = ((Number) r.getField(3)).doubleValue();
                    double avgPrice = ((Number) r.getField(1)).doubleValue() / count;
                    double avgPerSqm = ((Number) r.getField(2)).doubleValue() / count;
                    String plzCode = r.getString(0);
                    WayangGeometry geom = (WayangGeometry) r.getField(4);

                    Map<String, Object> props = new HashMap<>();
                    props.put("plz", plzCode);
                    props.put("avg_warm_price", avgPrice);
                    props.put("avg_price_per_sqm", avgPerSqm);

                    return new Record(geom, props);
                })
                .writeGeoJsonFile(outputPath);

        assertTrue(Files.exists(Paths.get("/tmp/plz_rent_stats.geojson")),
                "Expected GeoJSON output file to be created");
        String content = Files.readString(Paths.get("/tmp/plz_rent_stats.geojson"));
        assertTrue(content.startsWith("{\"type\":\"FeatureCollection\""),
                "Expected valid GeoJSON FeatureCollection");
        System.out.println("Written " + content.length() + " bytes to " + outputPath);
    }
}
