"""
Cobertura del suelo para el modelo de radio de CalandriApp: ESA WorldCover 2021 (clase, 10 m) y,
si se da, la altura de copas de ETH (Lang et al., 10 m, misma grilla), llevadas a tiles de 256x256
en Web Mercator zoom 12 (la grilla del relieve de la app, ~33 m por píxel en Córdoba).

Cada píxel es un byte:  (índice de clase << 4) | (altura de copas en pasos de 2 m, 0..15)
  índice de clase: 0 sin dato, 1 árboles (10), 2 arbustos (20), 3 pastizal (30), 4 cultivo (40),
  5 construido (50), 6 suelo desnudo (60), 7 nieve (70), 8 agua (80), 9 humedal (90),
  10 manglar (95), 11 musgo (100)  [entre paréntesis, el código de WorldCover]
  altura: el promedio de las copas dentro del píxel / 2, redondeado (15 = 30 m o más). 0 si no hay
  dato de copas.
La clase es la que más aparece entre 4x4 muestras dentro del píxel.

Uso: python wc_tiles.py <salida> <worldcover.tif>[:<copas.tif>] [...] [--bbox oeste,sur,este,norte]
Los dos archivos de cada par tienen que ser del mismo cuadro de 3x3 grados (misma grilla).
Los tiles que ya existen se combinan: los píxeles sin dato se completan con el archivo nuevo.

Fuentes (CC BY 4.0):
  WorldCover: https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_<S33W066>_Map.tif
  Copas ETH:  ETH_GlobalCanopyHeight_10m_2020_<S33W066>_Map.tif (libdrive.ethz.ch, carpeta 3deg_cogs)
"""
import math
import os
import sys

import numpy as np
import tifffile
from PIL import Image

Z = 12
N = 1 << Z
SIZE = 256
SUB = 4  # muestras por lado dentro de cada píxel
CODES = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100]


def tile_x(lon):
    return (lon + 180.0) / 360.0 * N


def tile_y(lat):
    r = math.radians(lat)
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * N


def read(path):
    with tifffile.TiffFile(path) as t:
        page = t.pages[0]
        sx, sy = page.tags['ModelPixelScaleTag'].value[:2]
        tp = page.tags['ModelTiepointTag'].value
        return page.asarray(), tp[3], tp[4], sx, sy


def main():
    args = sys.argv[1:]
    bbox = None
    if '--bbox' in args:
        i = args.index('--bbox')
        bbox = [float(v) for v in args[i + 1].split(',')]
        del args[i:i + 2]
    out, pairs = args[0], args[1:]
    lut = np.zeros(256, dtype=np.int64)
    for i, c in enumerate(CODES):
        lut[c] = i
    for pair in pairs:
        cover_path, _, canopy_path = pair.partition('::')
        print(cover_path, 'leyendo...', flush=True)
        data, lon0, lat0, sx, sy = read(cover_path)
        canopy = None
        if canopy_path:
            print(canopy_path, 'leyendo...', flush=True)
            canopy, clon0, clat0, csx, csy = read(canopy_path)
            assert (clon0, clat0, csx, csy) == (lon0, lat0, sx, sy) and canopy.shape == data.shape, 'grillas distintas'
        h, w = data.shape
        west, east = lon0, lon0 + w * sx
        north, south = lat0, lat0 - h * sy
        if bbox:
            west, south, east, north = max(west, bbox[0]), max(south, bbox[1]), min(east, bbox[2]), min(north, bbox[3])
            if west >= east or south >= north:
                continue
        x0, x1 = int(math.floor(tile_x(west))), int(math.floor(tile_x(east - 1e-9)))
        y0, y1 = int(math.floor(tile_y(north))), int(math.floor(tile_y(south + 1e-9)))
        print(f'tiles x {x0}..{x1}, y {y0}..{y1}', flush=True)
        f = (np.arange(SIZE * SUB) + 0.5) / (SIZE * SUB)
        done = 0
        for ty in range(y0, y1 + 1):
            yy = (ty + f) / N
            lats = np.degrees(np.arctan(np.sinh(np.pi * (1 - 2 * yy))))
            rows = np.floor((lat0 - lats) / sy).astype(np.int64)
            rok = (rows >= 0) & (rows < h)
            if not rok.any():
                continue
            rr = np.clip(rows, 0, h - 1)
            for tx in range(x0, x1 + 1):
                lons = (tx + f) / N * 360.0 - 180.0
                cols = np.floor((lons - lon0) / sx).astype(np.int64)
                cok = (cols >= 0) & (cols < w)
                if not cok.any():
                    continue
                cc = np.clip(cols, 0, w - 1)
                ok = rok[:, None] & cok[None, :]
                samp = np.where(ok, data[rr[:, None], cc[None, :]], 0)
                idx = lut[samp].reshape(SIZE, SUB, SIZE, SUB).transpose(0, 2, 1, 3).reshape(SIZE, SIZE, SUB * SUB)
                counts = np.zeros((SIZE, SIZE, len(CODES)), dtype=np.int16)
                for k in range(1, len(CODES)):  # el 0 (sin dato) no compite
                    counts[:, :, k] = (idx == k).sum(axis=2)
                cls = np.where(counts.max(axis=2) > 0, counts.argmax(axis=2), 0).astype(np.uint8)
                hgt = np.zeros((SIZE, SIZE), dtype=np.uint8)
                if canopy is not None:
                    ch = np.where(ok, canopy[rr[:, None], cc[None, :]], 255).astype(np.float32)
                    ch = ch.reshape(SIZE, SUB, SIZE, SUB).transpose(0, 2, 1, 3).reshape(SIZE, SIZE, SUB * SUB)
                    valid = ch < 255
                    n_ok = valid.sum(axis=2)
                    mean = np.where(n_ok > 0, np.where(valid, ch, 0).sum(axis=2) / np.maximum(n_ok, 1), 0)
                    hgt = np.clip(np.rint(mean / 2), 0, 15).astype(np.uint8)
                tile = (cls << 4) | hgt
                tile[cls == 0] = 0
                if not tile.any():
                    continue
                path = os.path.join(out, str(Z), str(tx), f'{ty}.png')
                os.makedirs(os.path.dirname(path), exist_ok=True)
                if os.path.exists(path):
                    old = np.array(Image.open(path))
                    tile = np.where(old > 0, old, tile)
                Image.fromarray(tile, mode='L').save(path, optimize=True)
                done += 1
            print(f'  fila {ty}: {done} tiles', flush=True)
        del data, canopy


if __name__ == '__main__':
    main()
