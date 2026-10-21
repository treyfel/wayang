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

package org.apache.wayang.jdbc.execution;

import org.apache.wayang.basic.operators.JoinOperator;
import org.apache.wayang.basic.operators.SpatialJoinOperator;
import org.apache.wayang.core.api.exception.WayangException;
import org.apache.wayang.core.plan.executionplan.Channel;
import org.apache.wayang.core.plan.executionplan.ExecutionStage;
import org.apache.wayang.core.plan.executionplan.ExecutionTask;
import org.apache.wayang.core.plan.wayangplan.ExecutionOperator;
import org.apache.wayang.jdbc.operators.JdbcJoinOperator;
import org.apache.wayang.jdbc.operators.JdbcProjectionOperator;
import org.apache.wayang.jdbc.operators.JdbcTableSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The input branches of a pushed-down join, read off an {@link ExecutionStage}'s topology.
 *
 * Both of this class's callers need the same walk: {@link #selectStartTask} to find the table
 * source belonging on the left-hand side, and {@link #composeJoinSelectClause} to find what
 * every branch projects.
 */
class JoinBranches {

    private JoinBranches() {
    }

    /**
     * One input branch of a join: the {@link JdbcTableSource} task that starts it and the
     * projection it delivers, or {@code null} for a branch that delivers whole records.
     */
    private record Branch(ExecutionTask source, JdbcProjectionOperator projection) {

        String tableName() {
            return ((JdbcTableSource) this.source.getOperator()).getTableName();
        }
    }

    /**
     * Retrieves one input branch of a join by walking backwards from the task feeding the
     * join to the {@link JdbcTableSource} that starts it. Backwards because the forward
     * walk through a stage follows a single output channel to a single consumer and so
     * cannot fork into the two branches of a join.
     *
     * @param task  the producer feeding one input of the join
     * @param stage in which the branch should lie
     * @return the said branch or {@code null} if it does not bottom out in a single named
     *         table, i.e. a nested join or a producer outside the stage
     */
    private static Branch walkBranch(ExecutionTask task, final ExecutionStage stage) {
        JdbcProjectionOperator projection = null;
        while (task != null && task.getStage() == stage) {
            final ExecutionOperator operator = task.getOperator();
            if (operator instanceof JdbcTableSource) {
                return new Branch(task, projection);
            }
            if (operator instanceof final JdbcProjectionOperator projectionOperator && projection == null) {
                // The projection nearest the join wins: it re-projects whatever an
                // inner one produced, so it is what the branch delivers.
                projection = projectionOperator;
            }
            // More than one input means another join, whose output is not a single
            // named table and so cannot be qualified.
            task = task.getNumInputChannels() == 1 && task.getInputChannel(0) != null
                    ? task.getInputChannel(0).getProducer()
                    : null;
        }
        return null;
    }

    /**
     * Retrieves the join tasks of the given {@link ExecutionStage}.
     *
     * @param stage whose join tasks are requested
     * @return the said tasks, in {@link ExecutionStage#getAllTasks()} order
     */
    private static List<ExecutionTask> findJoinTasks(final ExecutionStage stage) {
        final List<ExecutionTask> joinTasks = new ArrayList<>(2);
        for (final ExecutionTask task : stage.getAllTasks()) {
            final ExecutionOperator operator = task.getOperator();
            if (operator instanceof JoinOperator || operator instanceof SpatialJoinOperator) {
                joinTasks.add(task);
            }
        }
        return joinTasks;
    }

    /**
     * Composes the {@code SELECT} clause of a join stage: what every input branch
     * projects, each column qualified by the table it came from. Every branch is needed
     * because a forward walk from the stage's start task reaches only the left one, and
     * qualifying is needed because two joined tables routinely share a column name, such
     * as an id or a geometry column.
     *
     * Branches are taken in the join's own input order, so the left side's columns come
     * first, and a branch that projects nothing contributes {@code <table>.*}.
     *
     * @param stage whose {@code SELECT} clause is requested
     * @return the said clause or {@code null} if no branch projects, leaving the plain
     *         {@code *} that a join without projections has always produced
     */
    static String composeJoinSelectClause(final ExecutionStage stage) {
        final List<ExecutionTask> joinTasks = findJoinTasks(stage);
        if (joinTasks.size() != 1) {
            return null;
        }
        final ExecutionTask joinTask = joinTasks.get(0);
        final List<String> columns = new ArrayList<>();
        boolean anyProjection = false;

        for (int input = 0; input < joinTask.getNumInputChannels(); input++) {
            final Channel inputChannel = joinTask.getInputChannel(input);
            final Branch branch = inputChannel == null ? null : walkBranch(inputChannel.getProducer(), stage);
            if (branch == null) {
                return null;
            }
            if (branch.projection() == null) {
                columns.add(branch.tableName() + ".*");
                continue;
            }
            anyProjection = true;
            for (final String field : branch.projection().getFunctionDescriptor().getFieldNames()) {
                columns.add(branch.tableName() + "." + field);
            }
        }
        return anyProjection ? String.join(", ", columns) : null;
    }

    /**
     * Selects the table source that belongs on the left-hand side of a JDBC join, i.e.
     * the one starting the join's first input branch. Stage start tasks are not ordered,
     * but {@link JdbcJoinOperator#createSqlClause} assumes its first key descriptor's
     * table is used in the {@code FROM} clause.
     *
     * @param startTasks the start tasks of {@code stage}
     * @param stage      whose left-hand table source is requested
     * @return the said start task
     */
    static ExecutionTask selectStartTask(final Collection<?> startTasks, final ExecutionStage stage) {
        if (startTasks.size() == 1) {
            return (ExecutionTask) startTasks.iterator().next();
        }

        final List<ExecutionTask> joinTasks = findJoinTasks(stage);
        if (!joinTasks.isEmpty() && joinTasks.get(0).getNumInputChannels() > 0) {
            final Channel leftInput = joinTasks.get(0).getInputChannel(0);
            final Branch left = leftInput == null ? null : walkBranch(leftInput.getProducer(), stage);
            if (left != null) {
                return left.source();
            }
        }

        throw new WayangException("Could not determine the left table source for JDBC stage.");
    }
}
