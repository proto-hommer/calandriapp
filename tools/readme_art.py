"""Banner y títulos de sección de los README de la familia Calandria, con la identidad 2.0.

La misma en los tres repos (calandriapp, calandria-mk1-epaper, calandria-flasher), para que se lean
como un solo proyecto:

- Fondo noche (#07090C) con grilla fina de 40 px y curvas de nivel apagadas.
- La calandria: la malla en gris, los nodos en naranja y el ojo en cian ("vos").
- El nombre en Barlow, blanco y espaciado; la bajada en monoespaciada gris.
- Títulos de sección en PNG transparente, en un gris medio que se lee en el tema claro y en el
  oscuro de GitHub: un punto naranja, el texto y una línea fina que termina en un anillo cian.

Los títulos salen del mismo README: cada <img src=".../titulos/<slug>.png" alt="Texto"> se dibuja con
su alt ("s-..." = subtítulo). Así, para sumar una sección alcanza con agregarla al README y correr esto.

    python readme_art.py --barlow barlow_regular.ttf --readme ../README.md --banner ../docs/img/banner.png \\
        --name "CALANDRIA FLASHER" --sub "FIRMWARE · USB · DESDE EL NAVEGADOR" --tag "WEB SERIAL · CHROME O EDGE" \\
        --footer "para la familia Calandria · powered by Meshtastic®"

Barlow (SIL OFL) no está en el repo: viene de Google Fonts o de res/font de CalandriApp.
"""

import argparse
import math
import os
import re

from PIL import Image, ImageDraw, ImageFont

MONO = r"C:\Windows\Fonts\consola.ttf"

# La marca (marca.svg): nodos, contorno y malla; el nodo 13 es el ojo
P = [168, 62, 138, 47, 124, 53, 98, 74, 72, 106, 28, 156, 36, 168, 84, 124, 120, 120, 146, 98, 152, 70, 108, 96, 128, 92, 136, 66]
PTS = [(P[i], P[i + 1]) for i in range(0, len(P), 2)]
EYE = 13
OUTLINE = list(range(11))
E = [0, 13, 1, 13, 2, 13, 10, 13, 3, 13, 3, 11, 13, 12, 10, 12, 9, 12, 8, 12, 11, 12, 11, 4, 11, 7, 11, 8, 4, 7, 7, 5, 11, 13]
EDGES = [(E[i], E[i + 1]) for i in range(0, len(E), 2)]
BOX = (24.8, 43.8, 146.4, 127.4)

# Identidad 2.0
NIGHT = (7, 9, 12)
GRID = (16, 21, 27)
CONTOUR = (28, 35, 44)
MESH = (58, 68, 80)
OUTLINE_C = (138, 147, 157)
ORANGE = (255, 122, 47)
CYAN = (92, 225, 230)
TEXT = (241, 243, 245)
DIM = (138, 147, 157)
CONTROL = (42, 50, 60)
TITLE = (110, 119, 129)  # gris medio: se lee sobre blanco y sobre el oscuro de GitHub


def spaced(draw, text, font, x, y, fill, spacing, anchor="lm"):
    for c in text:
        draw.text((x, y), c, font=font, fill=fill, anchor=anchor)
        x += font.getlength(c) + spacing
    return x


def spaced_width(text, font, spacing):
    return sum(font.getlength(c) for c in text) + spacing * (len(text) - 1)


def draw_mark(d, cx, cy, s):
    bx, by, bw, bh = BOX
    pts = [(cx + (x - (bx + bw / 2)) * s, cy + (y - (by + bh / 2)) * s) for x, y in PTS]
    for a, b in EDGES:
        d.line([pts[a], pts[b]], fill=MESH, width=max(2, int(s * 0.5)))
    ol = [pts[i] for i in OUTLINE] + [pts[OUTLINE[0]]]
    for i in range(len(ol) - 1):
        d.line([ol[i], ol[i + 1]], fill=OUTLINE_C, width=max(2, int(s * 0.7)))
    for i, p in enumerate(pts):
        if i == EYE:
            continue
        r = s * 1.6
        d.ellipse((p[0] - r, p[1] - r, p[0] + r, p[1] + r), fill=ORANGE)
    e = pts[EYE]
    R = s * 3.4
    d.ellipse((e[0] - R, e[1] - R, e[0] + R, e[1] + R), outline=CYAN, width=max(2, int(s * 0.9)))
    r = R * 0.45
    d.ellipse((e[0] - r, e[1] - r, e[0] + r, e[1] + r), fill=CYAN)


def banner(font, out, name, sub, tag, footer, tag_warn, peaks):
    W, H = 1760, 560
    im = Image.new("RGB", (W, H), NIGHT)
    d = ImageDraw.Draw(im)
    for x in range(0, W, 40):
        d.line([(x, 0), (x, H)], fill=GRID, width=1)
    for y in range(0, H, 40):
        d.line([(0, y), (W, y)], fill=GRID, width=1)
    for px, py, n, ph in peaks:
        for k in range(1, n + 1):
            r = 58 * k
            pts = []
            for a_ in range(0, 361, 3):
                a = math.radians(a_)
                rr = r * (1 + 0.10 * math.sin(3 * a + ph + k * 0.3) + 0.05 * math.sin(5 * a + ph * 2))
                pts.append((px + rr * math.cos(a), py + rr * math.sin(a) * 0.62))
            d.line(pts, fill=CONTOUR, width=2)
    draw_mark(d, 330, 280, 2.3)

    x0 = 620
    size, sp = 96, 22
    while True:
        f = ImageFont.truetype(font, size)
        if spaced_width(name, f, sp) <= W - x0 - 70:
            break
        size -= 2
        sp = max(10, sp - 1)
    spaced(d, name, f, x0, 222, TEXT, sp)
    fs = ImageFont.truetype(MONO, 28)
    spaced(d, sub, fs, x0 + 4, 306, DIM, 9)
    if tag:
        ft = ImageFont.truetype(MONO, 24)
        tw = spaced_width(tag, ft, 6)
        tx, ty = x0 + 4, 362
        col = ORANGE if tag_warn else (194, 201, 209)
        d.rounded_rectangle((tx, ty, tx + tw + 36, ty + 48), radius=12, outline=ORANGE if tag_warn else CONTROL, width=2)
        spaced(d, tag, ft, tx + 18, ty + 24, col, 6)
    if footer:
        fp = ImageFont.truetype(font, 26)
        d.text((W - 60, H - 42), footer, font=fp, fill=DIM, anchor="rm")
    for x, y, sx, sy in [(28, 28, 1, 1), (W - 28, 28, -1, 1), (28, H - 28, 1, -1), (W - 28, H - 28, -1, -1)]:
        d.line([(x, y), (x + sx * 40, y)], fill=CONTROL, width=3)
        d.line([(x, y), (x, y + sy * 40)], fill=CONTROL, width=3)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    im.save(out, optimize=True)


def h2(font, text, out):
    W, H = 1760, 130
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(font, 70)
    d.ellipse((8, 54, 30, 76), fill=ORANGE + (255,))
    end = spaced(d, text.upper(), f, 58, 66, TITLE + (255,), 10)
    d.line([(end + 28, 66), (W - 34, 66)], fill=TITLE + (110,), width=3)
    d.ellipse((W - 32, 56, W - 12, 76), outline=CYAN + (255,), width=4)
    im.save(out, optimize=True)


def h3(font, text, out):
    f = ImageFont.truetype(font, 52)
    w = int(spaced_width(text.upper(), f, 6)) + 64
    im = Image.new("RGBA", (w, 84), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((4, 34, 20, 50), outline=ORANGE + (255,), width=4)
    spaced(d, text.upper(), f, 40, 44, TITLE + (255,), 6)
    im.save(out, optimize=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--barlow", required=True)
    ap.add_argument("--readme", required=True)
    ap.add_argument("--banner", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--sub", required=True)
    ap.add_argument("--tag", default="")
    ap.add_argument("--tag-warn", action="store_true", help="etiqueta en naranja (p. ej. alpha)")
    ap.add_argument("--footer", default="")
    ap.add_argument("--peaks", default="260,90,8,0.6;1500,470,9,1.7;1000,-60,5,2.9",
                    help="cerros de fondo: x,y,curvas,fase;…")
    a = ap.parse_args()
    peaks = [tuple(float(v) if i == 3 else int(float(v)) for i, v in enumerate(p.split(","))) for p in a.peaks.split(";")]
    banner(a.barlow, a.banner, a.name, a.sub, a.tag, a.footer, a.tag_warn, peaks)
    root = os.path.dirname(os.path.abspath(a.readme))
    md = open(a.readme, encoding="utf-8").read()
    n = 0
    for src, alt in re.findall(r'<img src="([^"]*titulos/[^"]+\.png)" alt="([^"]+)"', md):
        out = os.path.join(root, src)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        (h3 if os.path.basename(src).startswith("s-") else h2)(a.barlow, alt, out)
        n += 1
    print(f"banner + {n} títulos")


if __name__ == "__main__":
    main()
