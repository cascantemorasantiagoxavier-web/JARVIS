#!/usr/bin/env python3
"""Genera backend/public/data/globe.json (puntos de tierra y capitales) para el globo de la web.

Usa la misma máscara de tierra que la app Android (Natural Earth, dominio público, 0,5°) y la lista
de capitales. Puntos repartidos con una espiral de Fibonacci para que la densidad sea uniforme.
Uso: python3 -I backend/scripts/build-globe-data.py
"""
import gzip
import json
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "app/Android/core/src/main/resources"
raw = gzip.decompress((RES / "landmask.bin.gz").read_bytes())
header, _, bits = raw.partition(b"\n")
_, cols, rows = header.decode().split()
cols, rows = int(cols), int(rows)


def is_land(lat, lon):
    r = min(rows - 1, max(0, int((90.0 - lat) / 180.0 * rows)))
    c = min(cols - 1, max(0, int(((lon + 180.0) % 360.0) / 360.0 * cols)))
    i = r * cols + c
    return bool(bits[i >> 3] & (0x80 >> (i & 7)))


N = 16000
golden = math.pi * (3 - math.sqrt(5))
land = []
for k in range(N):
    y = 1 - (k + 0.5) / N * 2
    lat = math.degrees(math.asin(y))
    lon = math.degrees((k * golden) % (2 * math.pi)) - 180
    if is_land(lat, lon):
        land.append([round(lat, 2), round(lon, 2)])
cities = []
for line in (RES / "capitals.txt").read_text(encoding="utf-8").splitlines():
    if line.strip() and not line.startswith("#"):
        p = line.split(";")
        cities.append([round(float(p[0]), 3), round(float(p[1]), 3), p[2]])
out = ROOT / "backend/public/data/globe.json"
out.write_text(json.dumps({"source": "Natural Earth (dominio público), máscara 0,5°", "land": land, "cities": cities}, separators=(",", ":")), encoding="utf-8")
print(f"{len(land)} puntos de tierra, {len(cities)} capitales → {out} ({out.stat().st_size // 1024} KB)")
