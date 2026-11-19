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

package org.apache.wayang.spatial.operators.spark;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.Job;
import org.apache.wayang.core.api.WayangContext;
import org.apache.wayang.core.optimizer.DefaultOptimizationContext;
import org.apache.wayang.core.optimizer.OptimizationContext;
import org.apache.wayang.core.plan.wayangplan.Operator;
import org.apache.wayang.core.plan.wayangplan.WayangPlan;
import org.apache.wayang.core.platform.ChannelInstance;
import org.apache.wayang.core.platform.CrossPlatformExecutor;
import org.apache.wayang.core.profiling.FullInstrumentationStrategy;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.core.util.WayangCollections;
import org.apache.wayang.java.channels.CollectionChannel;
import org.apache.wayang.spark.channels.RddChannel;
import org.apache.wayang.spark.execution.SparkExecutor;
import org.apache.wayang.spark.platform.SparkPlatform;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Test suite for {@link SparkSpatialKNNSearchOperator}.
 */
class SparkSpatialKNNSearchOperatorTest {

    private Configuration configuration;
    private SparkExecutor sparkExecutor;
    private Job job;

    @BeforeEach
    void setUp() {
        WayangContext context = new WayangContext(new Configuration());
        this.job = context.createJob("spark-spatial-knn-test", new WayangPlan());
        this.configuration = this.job.getConfiguration();
        this.ensureCrossPlatformExecutor();
        this.sparkExecutor = (SparkExecutor) SparkPlatform.getInstance().getExecutorFactory().create(this.job);
    }

    private void ensureCrossPlatformExecutor() {
        try {
            Field field = Job.class.getDeclaredField("crossPlatformExecutor");
            field.setAccessible(true);
            if (field.get(this.job) == null) {
                CrossPlatformExecutor executor = new CrossPlatformExecutor(this.job, new FullInstrumentationStrategy());
                field.set(this.job, executor);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to initialize CrossPlatformExecutor for tests.", e);
        }
    }

    private OptimizationContext.OperatorContext createOperatorContext(Operator operator) {
        OptimizationContext optimizationContext = new DefaultOptimizationContext(this.job);
        return optimizationContext.addOneTimeOperator(operator);
    }

    private RddChannel.Instance createRddChannelInstance(Collection<?> collection, int numPartitions) {
        RddChannel.Instance instance = (RddChannel.Instance) RddChannel.UNCACHED_DESCRIPTOR
                .createChannel(null, this.configuration)
                .createInstance(mock(SparkExecutor.class), null, -1);
        instance.accept(this.sparkExecutor.sc.parallelize(WayangCollections.asList(collection), numPartitions), this.sparkExecutor);
        return instance;
    }

    private CollectionChannel.Instance createCollectionChannelInstance() {
        return (CollectionChannel.Instance) CollectionChannel.DESCRIPTOR
                .createChannel(null, this.configuration)
                .createInstance(this.sparkExecutor, null, -1);
    }

    private <T> List<T> run(SparkSpatialKNNSearchOperator<T> knnOp, List<T> input, int numPartitions) {
        RddChannel.Instance inputChannel = this.createRddChannelInstance(input, numPartitions);
        CollectionChannel.Instance outputChannel = this.createCollectionChannelInstance();

        knnOp.evaluate(new ChannelInstance[]{inputChannel},
                new ChannelInstance[]{outputChannel},
                this.sparkExecutor,
                this.createOperatorContext(knnOp));

        return new ArrayList<>(outputChannel.<T>provideCollection());
    }

    private SparkSpatialKNNSearchOperator<WayangGeometry> knnOnGeometries(int k, String reference) {
        return new SparkSpatialKNNSearchOperator<>(
                k,
                w -> w,
                DataSetType.createDefault(WayangGeometry.class),
                new WayangGeometry(reference)
        );
    }

    private static List<WayangGeometry> points(String... wkts) {
        List<WayangGeometry> geometries = new ArrayList<>();
        for (String wkt : wkts) {
            geometries.add(new WayangGeometry(wkt));
        }
        return geometries;
    }

    @Test
    void testReturnsKNearestNearestFirst() {
        List<WayangGeometry> input = points("POINT (5 0)", "POINT (1 0)", "POINT (10 0)", "POINT (3 0)");

        List<WayangGeometry> result = this.run(this.knnOnGeometries(2, "POINT (0 0)"), input, 2);

        assertEquals(points("POINT (1 0)", "POINT (3 0)"), result);
    }

    @Test
    void testKLargerThanAPartitionStillFindsAllNearest() {
        List<WayangGeometry> input = points(
                "POINT (1 0)", "POINT (9 0)",
                "POINT (3 0)", "POINT (8 0)",
                "POINT (5 0)", "POINT (2 0)");

        List<WayangGeometry> result = this.run(this.knnOnGeometries(4, "POINT (0 0)"), input, 3);

        assertEquals(points("POINT (1 0)", "POINT (2 0)", "POINT (3 0)", "POINT (5 0)"), result);
    }

    @Test
    void testKLargerThanInputWithEmptyPartitionsReturnsEverything() {
        List<WayangGeometry> input = points("POINT (4 0)", "POINT (1 0)", "POINT (2 0)");

        List<WayangGeometry> result = this.run(this.knnOnGeometries(10, "POINT (0 0)"), input, 5);

        assertEquals(points("POINT (1 0)", "POINT (2 0)", "POINT (4 0)"), result);
    }
}
