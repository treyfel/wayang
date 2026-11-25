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

package org.apache.wayang.core.api.spatial;

import org.apache.wayang.core.util.Tuple;

import java.io.Serializable;
import java.util.List;

/**
 * A spatial index over some elements, keyed by a geometry of each element.
 * <p>
 * A dataset of indexes describes one indexed dataset: each index covers a part of the elements (one part on
 * a single-machine platform, one per partition on a distributed one). Operators that read the dataset query
 * every part and merge the answers, so an index built on one platform can be read on another.
 * Implementations (e.g. in the spatial plugin) provide the actual index structure.
 *
 * @param <T> the type of the indexed elements
 */
public interface SpatialIndex<T> extends Serializable {

    /**
     * Returns the number of indexed elements. Elements without a geometry, or with an empty one, are not indexed.
     *
     * @return the number of indexed elements
     */
    int size();

    /**
     * Returns the spatial reference identifier shared by the indexed geometries.
     *
     * @return the SRID, or {@link SpatialGeometry#SRID_UNKNOWN} if unknown
     */
    default int getSrid() {
        return SpatialGeometry.SRID_UNKNOWN;
    }

    /**
     * Finds the {@code k} indexed elements nearest to a reference geometry.
     *
     * @param reference the geometry to measure distances from
     * @param k         the maximum number of elements to return
     * @return up to {@code k} pairs of distance and element, nearest first
     */
    List<Tuple<Double, T>> nearestNeighbours(SpatialGeometry reference, int k);
}
