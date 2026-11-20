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

package org.apache.wayang.spatial.index;

import org.apache.wayang.core.api.exception.WayangException;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialIndex;
import org.apache.wayang.core.util.Tuple;
import org.apache.wayang.spatial.data.WayangGeometry;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/**
 * A {@link SpatialIndex} backed by a JTS {@link STRtree}.
 * <p>
 * The tree is built once on creation and only read afterwards, so it can be queried concurrently and
 * serialized to other machines.
 *
 * @param <T> the type of the indexed elements
 */
public class JtsSpatialIndex<T> implements SpatialIndex<T> {

    /**
     * An indexed element together with its geometry, so that queries need not extract the geometry again.
     */
    private static final class Entry<T> implements Serializable {
        final Geometry geometry;
        final T element;

        Entry(Geometry geometry, T element) {
            this.geometry = geometry;
            this.element = element;
        }
    }

    /**
     * Distance between the geometries of two entries. Never smaller than the distance between their
     * envelopes, which the tree's branch-and-bound search relies on.
     */
    private static final ItemDistance ENTRY_DISTANCE =
            (a, b) -> ((Entry<?>) a.getItem()).geometry.distance(((Entry<?>) b.getItem()).geometry);

    private final STRtree tree;
    private final int size;
    private final int srid;

    private JtsSpatialIndex(STRtree tree, int size, int srid) {
        this.tree = tree;
        this.size = size;
        this.srid = srid;
    }

    /**
     * Builds an index over some elements. Elements whose geometry is {@code null} or empty are skipped.
     *
     * @param elements     the elements to index
     * @param keyExtractor extracts the geometry of an element
     * @param <T>          the type of the elements
     * @return the index
     * @throws WayangException if the geometries have different known SRIDs
     */
    public static <T> JtsSpatialIndex<T> build(Iterator<T> elements,
                                               Function<T, ? extends SpatialGeometry> keyExtractor) {
        final STRtree tree = new STRtree();
        int size = 0;
        int srid = SpatialGeometry.SRID_UNKNOWN;
        while (elements.hasNext()) {
            final T element = elements.next();
            final SpatialGeometry key = keyExtractor.apply(element);
            if (key == null) {
                continue;
            }
            final Geometry geometry = ((WayangGeometry) key).getGeometry();
            if (geometry == null || geometry.isEmpty()) {
                continue; // JTS reports distance 0 to an empty geometry, which would rank it first
            }
            srid = mergeSrid(srid, geometry.getSRID());
            tree.insert(geometry.getEnvelopeInternal(), new Entry<>(geometry, element));
            size++;
        }
        tree.build();
        return new JtsSpatialIndex<>(tree, size, srid);
    }

    private static int mergeSrid(int indexSrid, int geometrySrid) {
        if (geometrySrid == SpatialGeometry.SRID_UNKNOWN) {
            return indexSrid;
        }
        if (indexSrid != SpatialGeometry.SRID_UNKNOWN && indexSrid != geometrySrid) {
            throw new WayangException(String.format(
                    "Cannot index geometries with SRIDs %d and %d together; transform them to one SRID first.",
                    indexSrid, geometrySrid));
        }
        return geometrySrid;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public int getSrid() {
        return this.srid;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Tuple<Double, T>> nearestNeighbours(SpatialGeometry reference, int k) {
        final Geometry referenceGeometry = ((WayangGeometry) reference).getGeometry();
        final int referenceSrid = referenceGeometry.getSRID();
        if (this.srid != SpatialGeometry.SRID_UNKNOWN && referenceSrid != SpatialGeometry.SRID_UNKNOWN
                && this.srid != referenceSrid) {
            throw new WayangException(String.format(
                    "Spatial kNN search cannot compare SRID %d with reference SRID %d; transform one of them first.",
                    this.srid, referenceSrid));
        }
        if (k <= 0 || this.size == 0 || referenceGeometry.isEmpty()) {
            return Collections.emptyList();
        }

        final Object[] nearest = this.tree.nearestNeighbour(
                referenceGeometry.getEnvelopeInternal(),
                new Entry<T>(referenceGeometry, null),
                ENTRY_DISTANCE,
                Math.min(k, this.size));

        // The tree does not return its result in order.
        final List<Tuple<Double, T>> result = new ArrayList<>(nearest.length);
        for (Object item : nearest) {
            final Entry<T> entry = (Entry<T>) item;
            result.add(new Tuple<>(referenceGeometry.distance(entry.geometry), entry.element));
        }
        result.sort(Comparator.comparingDouble(t -> t.field0));
        return result;
    }
}
