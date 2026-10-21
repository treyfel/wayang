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

package org.apache.wayang.spatial.data;

import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.*;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;

import java.util.HashMap;

public class WayangGeometry implements SpatialGeometry {

    private static final String EWKT_SRID_PREFIX = "SRID=";

    private final HashMap<String, Object> data;

    public WayangGeometry() {
        this.data = new HashMap<>();
    }

    /**
     * Backwards-compatible constructor, treats input as WKT.
     */
    public WayangGeometry(String wkt) {
        this();
        this.data.put("wkt", wkt);
    }
    /**
     * Create WayangGeometry from string input.
     * Detects WKT, WKB-hex, or GeoJSON and stores only that
     * representation initially. Other conversions are done lazily.
     *
     * @param input geometry string (WKT / WKB-hex / GeoJSON)
     * @return WayangGeometry instance
     */
    public static WayangGeometry fromStringInput(String input) {
        String trimmed = input.trim();
        WayangGeometry wg = new WayangGeometry();

        if (wg.looksLikeWKT(trimmed)) {
            wg.data.put("wkt", trimmed);
        } else if (wg.looksLikeGeoJSON(trimmed)) {
            wg.data.put("geojson", trimmed);
        } else {
            // Assume WKB hex string
            wg.data.put("wkb", trimmed);
        }

        return wg;
    }

    /**
     * Create WayangGeometry from an existing JTS Geometry object.
     * The geometry is stored, and all other formats (WKT/WKB/GeoJSON)
     * are generated lazily when their getters are called.
     *
     * @param geometry JTS Geometry instance
     * @return WayangGeometry wrapper
     */
    public static WayangGeometry fromGeometry(Geometry geometry) {
        if (geometry == null) {
            throw new IllegalArgumentException("Geometry must not be null.");
        }
        WayangGeometry wg = new WayangGeometry();
        wg.data.put("geometry", geometry);
        return wg;
    }

    public static WayangGeometry fromGeoJson(String geoJson) {
        WayangGeometry wg = new WayangGeometry();
        wg.data.put("geojson", geoJson);
        // could directly create the respective geometry with jts
        return wg;
    }

    /**
     * Get the geometry as WKT. If WKT is not yet available, it is
     * generated from another stored representation and cached.
     *
     * @return WKT string
     */
    @Override
    public String toWKT() {
        return getWKT();
    }

    /**
     * Get the geometry as WKB hex string. If WKB is not yet available,
     * it is generated from another stored representation and cached.
     *
     * @return WKB hex string
     */
    @Override
    public String toWKB() {
        return getWKB();
    }

    /**
     * EWKT and EWKB carry their SRID, GeoJSON defaults to 4326 (RFC 7946) unless it declares a
     * {@code crs}, plain WKT has none.
     */
    @Override
    public int getSrid() {
        return getGeometry().getSRID();
    }

    /**
     * Get the geometry as WKT. If WKT is not yet available, it is
     * generated from another stored representation and cached.
     *
     * @return WKT string
     */
    public String getWKT() {
        Object wktObj = this.data.get("wkt");
        if (wktObj != null) {
            return wktObj.toString();
        }

        Geometry geometry = getGeometry();
        WKTWriter writer = new WKTWriter();
        String wkt = writer.write(geometry);
        if (geometry.getSRID() != 0) {
            wkt = EWKT_SRID_PREFIX + geometry.getSRID() + ";" + wkt;
        }
        this.data.put("wkt", wkt);
        return wkt;
    }

    /**
     * Get the geometry as WKB hex string. If WKB is not yet available,
     * it is generated from another stored representation and cached.
     *
     * @return WKB hex string
     */
    public String getWKB() {
        Object wkbObj = this.data.get("wkb");
        if (wkbObj != null) {
            return wkbObj.toString();
        }

        Geometry geometry = getGeometry();
        WKBWriter writer = new WKBWriter(2, geometry.getSRID() != 0);
        byte[] wkbBytes = writer.write(geometry);
        String wkbHex = WKBWriter.toHex(wkbBytes);
        this.data.put("wkb", wkbHex);
        return wkbHex;
    }

    /**
     * Get the geometry as GeoJSON string. If GeoJSON is not yet
     * available, it is generated from another stored representation
     * and cached.
     *
     * @return GeoJSON string
     */
    public String getGeoJSON() {
        Object geoJsonObj = this.data.get("geojson");
        if (geoJsonObj != null) {
            return geoJsonObj.toString();
        }

        Geometry geometry = getGeometry();
        GeoJsonWriter writer = new GeoJsonWriter();
        String geoJson = writer.write(geometry);
        this.data.put("geojson", geoJson);
        return geoJson;
    }

    /**
     * Convert one of the stored geometry representations (WKT, WKB-hex,
     * or GeoJSON) into a JTS Geometry object.
     *
     * The first available representation is used in this order:
     * WKT -> WKB-hex -> GeoJSON
     *
     * The resulting Geometry carries the SRID of the representation it was
     * parsed from (see {@link #getSrid()}) and is cached in the data map
     * under "geometry".
     *
     * @return JTS Geometry instance
     */
    public Geometry getGeometry() {
        Object geomObj = this.data.get("geometry");
        if (geomObj instanceof Geometry) {
            return (Geometry) geomObj;
        }

        Geometry geometry;

        try {
            if (this.data.containsKey("wkt")) {
                geometry = readWKT(this.data.get("wkt").toString().trim());

            } else if (this.data.containsKey("wkb")) {
                String wkbHex = this.data.get("wkb").toString().trim();
                byte[] wkbBytes = WKBReader.hexToBytes(wkbHex);
                WKBReader reader = new WKBReader();
                geometry = reader.read(wkbBytes);

            } else if (this.data.containsKey("geojson")) {
                String geoJson = this.data.get("geojson").toString().trim();
                GeoJsonReader reader = new GeoJsonReader();
                geometry = reader.read(geoJson);

            } else {
                throw new IllegalStateException("No geometry representation available in WayangGeometry.");
            }
        } catch (ParseException e) {
            throw new RuntimeException("Failed to parse geometry from stored representations.", e);
        }

        this.data.put("geometry", geometry);
        return geometry;
    }

    // ---------- Helpers ---------- //

    private boolean looksLikeWKT(String s) {
        return s.startsWith(EWKT_SRID_PREFIX) ||
                s.startsWith("POINT") ||
                s.startsWith("LINESTRING") ||
                s.startsWith("POLYGON") ||
                s.startsWith("MULTI") ||
                s.startsWith("GEOMETRYCOLLECTION");
    }

    private boolean looksLikeGeoJSON(String s) {
        return s.startsWith("{") && s.contains("\"type\"");
    }

    private static Geometry readWKT(String wkt) throws ParseException {
        int srid = 0;
        if (wkt.startsWith(EWKT_SRID_PREFIX)) {
            int separator = wkt.indexOf(';');
            if (separator < 0) {
                throw new ParseException("EWKT is missing the ';' after its SRID: " + wkt);
            }
            try {
                srid = Integer.parseInt(wkt.substring(EWKT_SRID_PREFIX.length(), separator).trim());
            } catch (NumberFormatException e) {
                throw new ParseException("EWKT has a non-numeric SRID: " + wkt);
            }
            wkt = wkt.substring(separator + 1);
        }
        return new WKTReader(new GeometryFactory(new PrecisionModel(), srid)).read(wkt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WayangGeometry)) return false;

        WayangGeometry that = (WayangGeometry) o;

        Geometry g1 = this.getGeometry();
        Geometry g2 = that.getGeometry();

        if (g1 == null || g2 == null) {
            return g1 == g2;
        }

        // Delegate to JTS Geometry equality (structural / topological, depending on JTS version).
        return g1.getSRID() == g2.getSRID() && g1.equals(g2);
    }

    @Override
    public int hashCode() {
        Geometry geometry = this.getGeometry();
        return geometry != null ? geometry.hashCode() : 0;
    }

}
