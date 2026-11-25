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

package org.apache.wayang.jdbc.test;

import org.apache.wayang.basic.data.Record;
import org.apache.wayang.basic.operators.SpatialKNNSearchOperator;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.types.DataSetType;
import org.apache.wayang.jdbc.compiler.FunctionCompiler;
import org.apache.wayang.jdbc.operators.JdbcExecutionOperator;

import java.sql.Connection;

/**
 * Test implementation of a JDBC {@link SpatialKNNSearchOperator}. The real one lives in the spatial plugin;
 * this one orders by a plain column, which is enough to check how the executor composes the query.
 */
public class HsqldbSpatialKNNSearchOperator extends SpatialKNNSearchOperator<Record> implements JdbcExecutionOperator {

    private final String orderColumn;

    public HsqldbSpatialKNNSearchOperator(int k, String orderColumn, SpatialGeometry reference) {
        super(k, record -> reference, DataSetType.createDefault(Record.class), reference);
        this.orderColumn = orderColumn;
    }

    @Override
    public String createSqlClause(Connection connection, FunctionCompiler compiler) {
        return String.format(" ORDER BY %s LIMIT %d", this.orderColumn, this.k);
    }

    @Override
    public HsqldbPlatform getPlatform() {
        return HsqldbPlatform.getInstance();
    }
}
