/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.wayang.basic.operators;

import org.apache.wayang.basic.data.Record;
import org.apache.wayang.core.plan.wayangplan.UnarySink;
import org.apache.wayang.core.types.DataSetType;

/**
 * Logical operator representing a GeoJSON FeatureCollection file sink consuming {@link Record} elements.
 * Each Record must have at least two fields: the feature geometry at
 * {@link #GEOMETRY_FIELD_INDEX} and a {@link java.util.Map} of feature properties at
 * {@link #PROPERTIES_FIELD_INDEX}. This is the shape produced by {@link GeoJsonFileSource}, so a
 * source and a sink round-trip without an intermediate transformation.
 */
public class GeoJsonFileSink extends UnarySink<Record> {

    /**
     * Index of the field holding the feature geometry.
     */
    public static final int GEOMETRY_FIELD_INDEX = 0;

    /**
     * Index of the field holding the feature properties.
     */
    public static final int PROPERTIES_FIELD_INDEX = 1;

    private final String outputUrl;

    public GeoJsonFileSink(String outputUrl) {
        super(DataSetType.createDefault(Record.class));
        this.outputUrl = outputUrl;
    }

    public GeoJsonFileSink(GeoJsonFileSink that) {
        super(that);
        this.outputUrl = that.outputUrl;
    }

    public String getOutputUrl() {
        return outputUrl;
    }
}
