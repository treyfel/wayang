<!--
  - Licensed to the Apache Software Foundation (ASF) under one
  - or more contributor license agreements.  See the NOTICE file
  - distributed with this work for additional information
  - regarding copyright ownership.  The ASF licenses this file
  - to you under the Apache License, Version 2.0 (the
  - "License"); you may not use this file except in compliance
  - with the License.  You may obtain a copy of the License at
  -
  -   http://www.apache.org/licenses/LICENSE-2.0
  -
  - Unless required by applicable law or agreed to in writing,
  - software distributed under the License is distributed on an
  - "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  - KIND, either express or implied.  See the License for the
  - specific language governing permissions and limitations
  - under the License.
  -->

# TODO: take the spatial reference geometry as a string literal

## Why

`wayang-spatial` must stay an optional plugin — it drags in JTS and `sedona-spark-shaded`, so it is
deliberately **not** a compile-time dependency of `wayang-api-json`. That module therefore has two
reflective touchpoints into the plugin:

1. `org.apache.wayang.spatial.Spatial.plugin()` — to register the plugin's mappings.
2. `org.apache.wayang.spatial.data.WayangGeometry.fromStringInput(String)` — to turn the plan's
   `spatialFilter.geometry` literal into a `SpatialGeometry`.

The second one exists only because `SpatialFilterOperator` insists on a `SpatialGeometry`. If the
operator accepted the **literal** instead, the JSON API would just pass the string straight
through and touchpoint 2 would disappear.

Touchpoint 1 is irreducible: you need the plugin instance to register mappings, and it lives in
the plugin jar. That is a discovery problem, not an abstraction problem — no new abstraction layer
removes it. It currently goes through `ReflectionUtils.evaluate`, the same idiom as
`Configuration` (basic-plugin lookup) and `Platform.load`.

## Scope — do this

Change `SpatialFilterOperator.referenceGeometry` from `SpatialGeometry` to a `String` holding a
WKT / GeoJSON / hex-WKB literal, and parse it inside each execution operator.

Additional wins beyond deleting the reflection:

- **Safer.** `JavaSpatialFilterOperator` currently hard-casts
  `(WayangGeometry) this.referenceGeometry`, which would `ClassCastException` on any other
  `SpatialGeometry` implementation. Parsing a literal cannot fail that way.
- **Cheaper serialization.** `WayangGeometry` is `Serializable` and ships a whole JTS object graph
  to executors (its internal map may hold the `Geometry`). A literal is far smaller and more
  robust.
- **No layering change.** `SpatialFilterOperator` lives in `wayang-basic` and is JTS-free today; it
  stays JTS-free with a `String`.

## Scope — do NOT do this

Do **not** relax the key descriptors
(`TransformationDescriptor<Type, SpatialGeometry>` on `SpatialFilterOperator`, and the two on
`SpatialJoinOperator`) to strings:

- It forces a parse per tuple on the hot path, and throws away work already done —
  `JavaGeoJsonFileSource` already produces `WayangGeometry` objects, and
  `JavaSpatialJoinOperator` builds a JTS `STRtree` over them.
- `String` communicates nothing about what the UDF must return. This is where the type earns its
  keep.

## Files to touch

- `wayang-commons/wayang-basic/.../basic/operators/SpatialFilterOperator.java` — field and
  constructors.
- `wayang-plugins/wayang-spatial/.../operators/java/JavaSpatialFilterOperator.java` — replace the
  hard cast with `WayangGeometry.fromStringInput(...)`; parse once, outside the stream lambda.
- `wayang-plugins/wayang-spatial/.../operators/spark/SparkSpatialFilterOperator.java` — same.
- `wayang-plugins/wayang-spatial/.../operators/jdbc/JdbcSpatialFilterOperator.java` — `createSqlClause`
  currently gets WKT free via `referenceGeometry.toWKT()`. It must now normalise the literal to WKT
  first (parse via `WayangGeometry`), since the literal may be GeoJSON or WKB-hex. **This is the one
  place the change costs something:** parsing moves from one site to three.
- `wayang-api/wayang-api-scala-java/.../api/DataQuanta.scala` — `spatialFilter` / `spatialFilterJava`.
  Keep a `SpatialGeometry` overload for Java/Scala callers who already hold one (forward it with
  `.toWKT()`) and add a `String` overload. Do not force fluent-API users to stringify.
- `wayang-api/wayang-api-json/.../builder/JsonPlanBuilder.scala` — delete `getSpatialGeometry` and
  pass `operator.data.geometry` through unchanged. The `SpatialPackage` constant and
  `requireSpatial` stay, for the plugin lookup.
- Tests: `JavaApiSpatialTest`, `JavaSpatialFilterOperatorTest`, `SparkSpatialFilterOperatorTest`,
  `JdbcSpatialFilterOperatorTest`, `PostgresSpatialIntegrationTest`.

## Caveats

- **Base.** This branch sits on top of `spatial-json-api` at `7ab34615c` ("clean up the spatial
  json api"), so the pieces this work builds on are already in place: `getSpatialGeometry` /
  `requireSpatial` / `SpatialPackage` in `JsonPlanBuilder`, the `geometry` JSON field (renamed from
  `geometryUdf`), and the deleted `SerializableExpression`. If `spatial-json-api` gets squashed or
  reworked before merge, re-cut this branch rather than rebasing.
- WKT loses SRID and can lose precision. `WayangGeometry.cleanSRID` already strips an `SRID=`
  prefix, and `fromStringInput` sniffs WKT / GeoJSON / WKB-hex — so preserving the caller's
  original literal verbatim (rather than normalising on the way in) keeps the most information.
- `JdbcSpatialFilterOperator` hardcodes `SRID = 4326` with a pre-existing `TODO` in the source.
  Worth resolving while in there, but it is a separate concern.
