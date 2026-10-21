#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["shapely>=2.0"]
# ///
"""
Fetches lakes, parks and the Berlin boundary from OpenStreetMap for CrsTransformPlacementTest.

Usage:
    uv run fetch_osm_berlin.py [output-directory]
"""

import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from shapely.geometry import LineString, MultiPolygon, Polygon
from shapely.ops import polygonize, unary_union
from shapely.validation import make_valid

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
USER_AGENT = "apache-wayang-spatial-test-data/0.1"

# south, west, north, east. Berlin itself spans roughly 52.34-52.68 N, 13.09-13.76 E.
REGION = (52.10, 12.70, 52.90, 14.20)

# Water areas that are not lakes. Rivers in particular are long polygons that would touch most
# riverside parks and swamp the join result.
NON_LAKE_WATER = "river|canal|stream|ditch|drain|wastewater|moat|fountain|lock|basin"

QUERIES = {
    "lakes": f'nwr["natural"="water"]["water"!~"^({NON_LAKE_WATER})$"]({{bbox}});',
    "parks": 'nwr["leisure"="park"]({bbox});',
    "berlin": 'relation["boundary"="administrative"]["admin_level"="4"]["name"="Berlin"];',
}


def overpass(statement: str) -> list:
    bbox = ",".join(str(c) for c in REGION)
    query = f"[out:json][timeout:300];{statement.format(bbox=bbox)}out geom;"
    data = urllib.parse.urlencode({"data": query}).encode()
    request = urllib.request.Request(OVERPASS_URL, data=data, headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=360) as response:
                return json.load(response)["elements"]
        except urllib.error.HTTPError as e:
            # 429/504 mean "busy, come back later" on the public instance.
            if e.code not in (429, 504) or attempt == 3:
                raise
            time.sleep(30 * (attempt + 1))
    raise AssertionError("unreachable")


def to_polygon(element: dict):
    """Turns a closed way or a multipolygon relation into a valid (Multi)Polygon, or None."""
    if element["type"] == "way":
        coords = [(p["lon"], p["lat"]) for p in element.get("geometry", [])]
        if len(coords) < 4 or coords[0] != coords[-1]:
            return None
        return make_valid(Polygon(coords))

    if element["type"] == "relation":
        # A relation's rings may be split over several member ways; polygonize stitches them.
        outer, inner = [], []
        for member in element.get("members", []):
            if member["type"] != "way" or "geometry" not in member:
                continue
            line = LineString([(p["lon"], p["lat"]) for p in member["geometry"]])
            (inner if member.get("role") == "inner" else outer).append(line)
        shell = unary_union(list(polygonize(outer)))
        if shell.is_empty:
            return None
        holes = unary_union(list(polygonize(inner)))
        return make_valid(shell.difference(holes) if not holes.is_empty else shell)

    return None  # nodes carry no area


def polygonal(geometry):
    """make_valid may return a GeometryCollection with stray lines; keep only the area."""
    if geometry is None or geometry.is_empty:
        return None
    if isinstance(geometry, (Polygon, MultiPolygon)):
        return geometry
    parts = [g for g in getattr(geometry, "geoms", []) if isinstance(g, (Polygon, MultiPolygon))]
    return unary_union(parts) if parts else None


def write(directory: Path, name: str, elements: list) -> int:
    rows = []
    for element in elements:
        geometry = polygonal(to_polygon(element))
        if geometry is not None:
            rows.append(f"{element['type']}/{element['id']}\t{geometry.wkt}")
    rows.sort()
    path = directory / f"{name}.tsv"
    path.write_text("osm_id\twkt_epsg4326\n" + "\n".join(rows) + "\n", encoding="utf-8")
    print(f"  {name:7s} {len(rows):6d} polygons -> {path}")
    return len(rows)


def main() -> None:
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 \
        else Path.home() / ".cache" / "wayang-spatial" / "osm-berlin"
    directory.mkdir(parents=True, exist_ok=True)
    print(f"Fetching OSM data for region {REGION} (S, W, N, E)")
    for name, statement in QUERIES.items():
        write(directory, name, overpass(statement))
    (directory / "ATTRIBUTION").write_text(
        "Data (c) OpenStreetMap contributors, licensed under the Open Database License (ODbL).\n"
        "https://www.openstreetmap.org/copyright\n"
        f"Fetched {time.strftime('%Y-%m-%d')} from {OVERPASS_URL}, region {REGION}.\n",
        encoding="utf-8")


if __name__ == "__main__":
    main()
