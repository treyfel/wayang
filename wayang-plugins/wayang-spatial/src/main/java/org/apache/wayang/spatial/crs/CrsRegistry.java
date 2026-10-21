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

package org.apache.wayang.spatial.crs;

import org.apache.wayang.core.api.exception.WayangException;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an SRID (interpreted as an EPSG code) to a proj4j
 * {@link CoordinateReferenceSystem}.
 *
 * <p>The proj4j jar on the classpath ships classes but no EPSG parameter database, so
 * {@code CRSFactory.createFromName} has nothing to read. This registry therefore carries a
 * curated table of PROJ parameter strings instead, which {@code createFromParameters} accepts
 * with the core jar alone and which raises no licensing question.</p>
 *
 * <p>Coverage is deliberately small and limited to the CRSs that a Wayang plan is likely to mix.
 * An unknown SRID fails with a message listing the supported ones, rather than silently assuming
 * a CRS.</p>
 */
public class CrsRegistry {

    /** Explicitly listed CRSs. UTM zones are generated on top of these (see {@link #lookUpProjString}). */
    private static final Map<Integer, String> BUILT_IN;

    static {
        Map<Integer, String> crs = new LinkedHashMap<>();
        // Geographic
        crs.put(4326, "+proj=longlat +datum=WGS84 +no_defs");                              // WGS 84
        crs.put(4258, "+proj=longlat +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +no_defs");       // ETRS89
        // Projected
        crs.put(3857, "+proj=merc +a=6378137 +b=6378137 +lat_ts=0.0 +lon_0=0.0 +x_0=0.0 "
                + "+y_0=0 +k=1.0 +units=m +nadgrids=@null +no_defs");                      // Web Mercator
        crs.put(3035, "+proj=laea +lat_0=52 +lon_0=10 +x_0=4321000 +y_0=3210000 "
                + "+ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs");                // ETRS89-LAEA Europe
        BUILT_IN = Collections.unmodifiableMap(crs);
    }

    /**
     * Parsing a PROJ string is not free, and the result is immutable and thread-safe, so it is
     * shared. The {@link org.locationtech.proj4j.CoordinateTransform}s built from these are
     * <em>not</em> shared (see {@link CrsTransforms}).
     */
    private static final Map<Integer, CoordinateReferenceSystem> CACHE = new ConcurrentHashMap<>();

    private static final CRSFactory CRS_FACTORY = new CRSFactory();

    private CrsRegistry() {
    }

    /**
     * @param srid an SRID, interpreted as an EPSG code
     * @return the CRS for {@code srid}
     * @throws WayangException if the SRID is not covered by this registry
     */
    public static CoordinateReferenceSystem get(int srid) {
        return CACHE.computeIfAbsent(srid, code ->
                CRS_FACTORY.createFromParameters("EPSG:" + code, lookUpProjString(code)));
    }

    private static String lookUpProjString(int srid) {
        String builtIn = BUILT_IN.get(srid);
        if (builtIn != null) {
            return builtIn;
        }
        // UTM zones, generated rather than enumerated: WGS 84 north/south and ETRS89 north.
        if (srid >= 32601 && srid <= 32660) {
            return String.format("+proj=utm +zone=%d +datum=WGS84 +units=m +no_defs", srid - 32600);
        }
        if (srid >= 32701 && srid <= 32760) {
            return String.format("+proj=utm +zone=%d +south +datum=WGS84 +units=m +no_defs", srid - 32700);
        }
        if (srid >= 25828 && srid <= 25838) {
            return String.format("+proj=utm +zone=%d +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 "
                    + "+units=m +no_defs", srid - 25800);
        }
        throw new WayangException(String.format(
                "Unsupported SRID %d. This build knows %s, WGS 84 UTM zones (32601-32660, 32701-32760) "
                        + "and ETRS89 UTM zones (25828-25838).",
                srid, supportedBuiltIns()));
    }

    private static String supportedBuiltIns() {
        SortedSet<Integer> sorted = new TreeSet<>(BUILT_IN.keySet());
        StringBuilder sb = new StringBuilder();
        for (Integer srid : sorted) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("EPSG:").append(srid);
        }
        return sb.toString();
    }
}
