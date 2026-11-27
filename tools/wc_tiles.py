"""
ESA WorldCover 2021 (10 m, lat/lon) -> tiles de 256x256 en Web Mercator, zoom 12 (la grilla del
relieve de CalandriApp, ~33 m por píxel en Córdoba). Cada píxel guarda el código de clase de
WorldCover (10 árboles, 20 arbustos, 30 pastizal, 40 cultivo, 50 construido, 60 suelo desnudo,
70 nieve, 80 agua, 90 humedal, 95 manglar, 100 musgo/líquen; 0 = sin dato): la clase que más
aparece entre 4x4 muestras dentro del píxel.

Uso: python wc_tiles.py <salida> <archivo.tif> [<archivo.tif> ...]
Los tiles que ya existen se combinan (los píxeles sin dato se completan con el archivo nuevo).
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
CLASSES = np.array([0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100], dtype=np.uint8)


def tile_x(lon):
    return (lon + 180.0) / 360.0 * N


def tile_y(lat):
    r = math.radians(lat)
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * N


def main():
    out, srcs = sys.argv[1], sys.argv[2:]
    for src in srcs:
        with tifffile.TiffFile(src) as t:
            page = t.pages[0]
            sx, sy = page.tags['ModelPixelScaleTag'].value[:2]
            tp = page.tags['ModelTiepointTag'].value
            lon0, lat0 = tp[3], tp[4]  # esquina de arriba a la izquierda
            print(src, 'leyendo...', flush=True)
            data = page.asarray()
        h, w = data.shape
        west, east = lon0, lon0 + w * sx
        north, south = lat0, lat0 - h * sy
        x0, x1 = int(math.floor(tile_x(west))), int(math.floor(tile_x(east - 1e-9)))
        y0, y1 = int(math.floor(tile_y(north))), int(math.floor(tile_y(south + 1e-9)))
        print(f'{src}: tiles x {x0}..{x1}, y {y0}..{y1}', flush=True)
        # Posiciones de las muestras dentro de un tile (en fracción de tile)
        f = (np.arange(SIZE * SUB) + 0.5) / (SIZE * SUB)
        lut = np.zeros(256, dtype=np.int64)
        for i, c in enumerate(CLASSES):
            lut[c] = i
        done = 0
        for ty in range(y0, y1 + 1):
            # latitudes de las filas de muestras (Mercator -> lat)
            yy = (ty + f) / N
            lats = np.degrees(np.arctan(np.sinh(np.pi * (1 - 2 * yy))))
            rows = np.floor((lat0 - lats) / sy).astype(np.int64)
            rok = (rows >= 0) & (rows < h)
            for tx in range(x0, x1 + 1):
                lons = (tx + f) / N * 360.0 - 180.0
                cols = np.floor((lons - lon0) / sx).astype(np.int64)
                cok = (cols >= 0) & (cols < w)
                if not rok.any() or not cok.any():
                    continue
                samp = np.zeros((SIZE * SUB, SIZE * SUB), dtype=np.uint8)
                rr = np.clip(rows, 0, h - 1); cc = np.clip(cols, 0, w - 1)
                samp[:, :] = data[rr[:, None], cc[None, :]]
                samp[~rok, :] = 0
                samp[:, ~cok] = 0
                idx = lut[samp].reshape(SIZE, SUB, SIZE, SUB).transpose(0, 2, 1, 3).reshape(SIZE, SIZE, SUB * SUB)
                counts = np.zeros((SIZE, SIZE, len(CLASSES)), dtype=np.int16)
                for k in range(1, len(CLASSES)):  # el 0 (sin dato) no compite
                    counts[:, :, k] = (idx == k).sum(axis=2)
                best = counts.argmax(axis=2)
                tile = np.where(counts.max(axis=2) > 0, CLASSES[best], 0).astype(np.uint8)
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
        del data


if __name__ == '__main__':
    main()
