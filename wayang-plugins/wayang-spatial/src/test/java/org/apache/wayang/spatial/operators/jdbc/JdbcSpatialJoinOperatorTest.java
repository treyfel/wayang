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

package org.apache.wayang.spatial.operators.jdbc;

import org.apache.wayang.basic.data.Record;
import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.Job;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.function.TransformationDescriptor;
import org.apache.wayang.core.optimizer.DefaultOptimizationContext;
import org.apache.wayang.core.plan.executionplan.ExecutionStage;
import org.apache.wayang.core.plan.executionplan.ExecutionTask;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.core.platform.CrossPlatformExecutor;
import org.apache.wayang.core.profiling.NoInstrumentationStrategy;
import org.apache.wayang.jdbc.channels.SqlQueryChannel;
import org.apache.wayang.jdbc.execution.JdbcExecutor;
import org.apache.wayang.jdbc.operators.JdbcProjectionOperator;
import org.apache.wayang.jdbc.operators.JdbcTableSource;
import org.apache.wayang.jdbc.operators.SqlToStreamOperator;
import org.apache.wayang.jdbc.platform.JdbcPlatformTemplate;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.apache.wayang.spatial.test.HsqldbPlatform;
import org.apache.wayang.spatial.test.HsqldbTableSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test suite for {@link JdbcSpatialJoinOperator}.
 * Verifies that the generated SQL contains the expected spatial JOIN clause.
 */
class JdbcSpatialJoinOperatorTest {

    /**
     * Concrete projection for testing against HSQLDB, standing in for the one
     * {@code jdbcRemoteInput}'s projectRecords builds on each input.
     */
    private static class TestJdbcProjectionOperator extends JdbcProjectionOperator {

        TestJdbcProjectionOperator(String... fieldNames) {
            super(fieldNames);
        }

        @Override
        public JdbcPlatformTemplate getPlatform() {
            return HsqldbPlatform.getInstance();
        }
    }

    /**
     * Concrete subclass for testing against HSQLDB.
     */
    private static class TestJdbcSpatialJoinOperator extends JdbcSpatialJoinOperator {

        TestJdbcSpatialJoinOperator(
                TransformationDescriptor<Record, ? extends SpatialGeometry> keyDescriptor0,
                TransformationDescriptor<Record, ? extends SpatialGeometry> keyDescriptor1,
                SpatialPredicate predicateType) {
            super(keyDescriptor0, keyDescriptor1, predicateType);
        }

        @Override
        public JdbcPlatformTemplate getPlatform() {
            return HsqldbPlatform.getInstance();
        }
    }

    @Test
    void testSpatialJoinIntersectsGeneratesCorrectSql() throws SQLException {
        String sql = buildSpatialJoinSql(SpatialPredicate.INTERSECTS);

        assertEquals(
                "SELECT * FROM testA JOIN testB ON ST_Intersects(testA.geom, testB.geom)",
                sql
        );
    }

    @Test
    void testSpatialJoinContainsGeneratesCorrectSql() throws SQLException {
        String sql = buildSpatialJoinSql(SpatialPredicate.CONTAINS);

        assertEquals(
                "SELECT * FROM testA JOIN testB ON ST_Contains(testA.geom, testB.geom)",
                sql
        );
    }

    @Test
    void testSpatialJoinWithinGeneratesCorrectSql() throws SQLException {
        String sql = buildSpatialJoinSql(SpatialPredicate.WITHIN);

        assertEquals(
                "SELECT * FROM testA JOIN testB ON ST_Within(testA.geom, testB.geom)",
                sql
        );
    }

    @Test
    void testSpatialJoinProjectsBothSidesQualifiedByTable() throws SQLException {
        String sql = buildSpatialJoinSql(SpatialPredicate.INTERSECTS, true);

        // Both branches contribute. Only the left one lies on the walk from the stage's
        // start task, so without this the right side's columns vanish from the result.
        // Qualifying matters too: both tables have a geom column.
        assertEquals(
                "SELECT testA.id, testA.geom, testB.geom FROM testA "
                        + "JOIN testB ON ST_Intersects(testA.geom, testB.geom)",
                sql
        );
    }

    /**
     * Sets up a JDBC execution pipeline (two table sources -> spatial join -> SqlToStream)
     * and returns the generated SQL query string.
     */
    private String buildSpatialJoinSql(SpatialPredicate predicateType) throws SQLException {
        return buildSpatialJoinSql(predicateType, false);
    }

    private String buildSpatialJoinSql(SpatialPredicate predicateType, boolean withProjections)
            throws SQLException {
        Configuration configuration = new Configuration();

        Job job = mock(Job.class);
        when(job.getConfiguration()).thenReturn(configuration);
        when(job.getCrossPlatformExecutor())
                .thenReturn(new CrossPlatformExecutor(job, new NoInstrumentationStrategy()));

        HsqldbPlatform hsqldbPlatform = new HsqldbPlatform();
        SqlQueryChannel.Descriptor sqlChannelDescriptor =
                HsqldbPlatform.getInstance().getSqlQueryChannelDescriptor();

        ExecutionStage sqlStage = mock(ExecutionStage.class);

        // Create two test tables.
        try (Connection jdbcConnection =
                     hsqldbPlatform.createDatabaseDescriptor(configuration).createJdbcConnection()) {
            final Statement statement = jdbcConnection.createStatement();
            statement.execute("DROP TABLE testA IF EXISTS;");
            statement.execute("DROP TABLE testB IF EXISTS;");
            statement.execute("CREATE TABLE testA (id INT, geom VARCHAR(255));");
            statement.execute("INSERT INTO testA VALUES (0, 'POINT (0 0)');");
            statement.execute("CREATE TABLE testB (id INT, geom VARCHAR(255));");
            statement.execute("INSERT INTO testB VALUES (0, 'POINT (0 0)');");
        }

        JdbcTableSource tableSourceA = new HsqldbTableSource("testA");
        JdbcTableSource tableSourceB = new HsqldbTableSource("testB");

        ExecutionTask tableSourceATask = new ExecutionTask(tableSourceA);
        tableSourceATask.setOutputChannel(0,
                new SqlQueryChannel(sqlChannelDescriptor, tableSourceA.getOutput(0)));
        tableSourceATask.setStage(sqlStage);

        ExecutionTask tableSourceBTask = new ExecutionTask(tableSourceB);
        tableSourceBTask.setOutputChannel(0,
                new SqlQueryChannel(sqlChannelDescriptor, tableSourceB.getOutput(0)));
        tableSourceBTask.setStage(sqlStage);

        // Key descriptors with SQL implementation.
        TransformationDescriptor<Record, WayangGeometry> leftKey =
                new TransformationDescriptor<>(
                        record -> WayangGeometry.fromStringInput((String) record.getField(1)),
                        Record.class,
                        WayangGeometry.class
                ).withSqlImplementation("testA", "geom");

        TransformationDescriptor<Record, WayangGeometry> rightKey =
                new TransformationDescriptor<>(
                        record -> WayangGeometry.fromStringInput((String) record.getField(1)),
                        Record.class,
                        WayangGeometry.class
                ).withSqlImplementation("testB", "geom");

        final ExecutionOperator joinOp = new TestJdbcSpatialJoinOperator(
                leftKey, rightKey, predicateType
        );

        ExecutionTask joinTask = new ExecutionTask(joinOp);
        Set<ExecutionTask> allTasks = new LinkedHashSet<>(
                Arrays.asList(tableSourceATask, tableSourceBTask, joinTask));
        if (withProjections) {
            ExecutionTask projectionA =
                    projectionTask(sqlStage, sqlChannelDescriptor, tableSourceATask, "id", "geom");
            ExecutionTask projectionB =
                    projectionTask(sqlStage, sqlChannelDescriptor, tableSourceBTask, "geom");
            projectionA.getOutputChannel(0).addConsumer(joinTask, 0);
            projectionB.getOutputChannel(0).addConsumer(joinTask, 1);
            allTasks.add(projectionA);
            allTasks.add(projectionB);
        } else {
            tableSourceATask.getOutputChannel(0).addConsumer(joinTask, 0);
            tableSourceBTask.getOutputChannel(0).addConsumer(joinTask, 1);
        }
        when(sqlStage.getAllTasks()).thenReturn(allTasks);
        joinTask.setOutputChannel(0,
                new SqlQueryChannel(sqlChannelDescriptor, joinOp.getOutput(0)));
        joinTask.setStage(sqlStage);

        // Both sources start the stage; selectStartTask picks the left one by the join's
        // own left table name, which is why start tasks are a set rather than a pair.
        when(sqlStage.getStartTasks())
                .thenReturn(new LinkedHashSet<>(Arrays.asList(tableSourceATask, tableSourceBTask)));
        when(sqlStage.getTerminalTasks()).thenReturn(Collections.singleton(joinTask));

        // Next stage.
        ExecutionStage nextStage = mock(ExecutionStage.class);
        SqlToStreamOperator sqlToStreamOperator = new SqlToStreamOperator(HsqldbPlatform.getInstance());
        ExecutionTask sqlToStreamTask = new ExecutionTask(sqlToStreamOperator);
        joinTask.getOutputChannel(0).addConsumer(sqlToStreamTask, 0);
        sqlToStreamTask.setStage(nextStage);

        // Execute to build the SQL string.
        JdbcExecutor executor = new JdbcExecutor(HsqldbPlatform.getInstance(), job);
        executor.execute(sqlStage, new DefaultOptimizationContext(job), job.getCrossPlatformExecutor());

        SqlQueryChannel.Instance sqlQueryChannelInstance =
                (SqlQueryChannel.Instance) job.getCrossPlatformExecutor()
                        .getChannelInstance(sqlToStreamTask.getInputChannel(0));

        return sqlQueryChannelInstance.getSqlQuery();
    }

    private ExecutionTask projectionTask(ExecutionStage stage, SqlQueryChannel.Descriptor descriptor,
                                         ExecutionTask source, String... fieldNames) {
        ExecutionOperator projection = new TestJdbcProjectionOperator(fieldNames);
        ExecutionTask task = new ExecutionTask(projection);
        source.getOutputChannel(0).addConsumer(task, 0);
        task.setOutputChannel(0, new SqlQueryChannel(descriptor, projection.getOutput(0)));
        task.setStage(stage);
        return task;
    }
}
