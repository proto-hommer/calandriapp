// Genera toda la marca CalandriApp a partir de assets/brand/marca.svg.
//
//   cd assets/brand/tools && npm install && npm run brand
//
// Salidas:
//   assets/brand/*.svg              variantes de la marca, logos y monocromos (texto en trazos)
//   assets/brand/png/*.png          PNG de referencia y vistas previas del ícono
//   app/src/main/res/...            ícono adaptativo, monocromo, mipmaps y splash de Android
//
// La geometría (contorno, conexiones, nodos, ojo) se lee de marca.svg y no se toca: solo se escala.
// Cambian únicamente grosores y radios entre la versión completa, la simplificada y la mínima.

import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { Resvg } from '@resvg/resvg-js';
import opentype from 'opentype.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const BRAND = resolve(HERE, '..');
const ROOT = resolve(BRAND, '../..');
const RES = join(ROOT, 'app', 'src', 'main', 'res');
const PNG_DIR = join(BRAND, 'png');
const require = createRequire(import.meta.url);

// ---------- paleta (exacta) ----------
const C = { navy: '#14223A', copper: '#C98245', green: '#268647', text: '#F3F5F6' };

// ---------- tipografía ----------
// Space Grotesk no tiene cursiva: se usa SemiBold (600) con una inclinación de 12° hecha a mano,
// y un interletrado de -0,02 em. El texto sale convertido a trazos.
const FONT_FILE = join(dirname(require.resolve('@fontsource/space-grotesk/package.json')),
  'files', 'space-grotesk-latin-600-normal.woff');
const SLANT_DEG = 12;
const TRACKING_EM = -0.02;
const NAME = 'CalandriApp';

// ---------- 1. geometría de la fuente de verdad ----------
const src = readFileSync(join(BRAND, 'marca.svg'), 'utf8');
const attr = (tag, name) => {
  const m = tag.match(new RegExp(`\\s${name}="([^"]*)"`));
  return m ? m[1] : null;
};
const firstPath = src.match(/<path\b[^>]*>/)[0];
const outline = [...attr(firstPath, 'd').matchAll(/[ML]\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)/g)]
  .map(m => [Number(m[1]), Number(m[2])]);
const fillOpacity = Number(attr(firstPath, 'fill-opacity') ?? 0);
const lineWidth = Number(src.match(/<g\b[^>]*stroke-width="([\d.]+)"/)[1]);
const edges = [...src.matchAll(/<line\b[^>]*>/g)].map(m =>
  ['x1', 'y1', 'x2', 'y2'].map(k => Number(attr(m[0], k))));
const circles = [...src.matchAll(/<circle\b[^>]*>/g)].map(m => ({
  cx: Number(attr(m[0], 'cx')), cy: Number(attr(m[0], 'cy')), r: Number(attr(m[0], 'r')),
  fill: attr(m[0], 'fill'), strokeWidth: Number(attr(m[0], 'stroke-width') ?? 0),
}));
const eye = circles.find(c => (c.fill ?? '').toUpperCase() === C.green);
const nodes = circles.filter(c => c !== eye);
if (!eye || outline.length < 3 || edges.length === 0) throw new Error('marca.svg: no encontré contorno, conexiones u ojo');

// Versión simplificada (como calandriapp-icono.png): contorno + estas conexiones internas + ojo.
// Cada una tiene que existir en marca.svg: no se inventa ninguna.
const SIMPLE = [[98, 74, 136, 66], [98, 74, 108, 96], [108, 96, 72, 106], [108, 96, 128, 92],
  [136, 66, 128, 92], [128, 92, 120, 120], [108, 96, 84, 124]];
const sameEdge = (a, b) => (a[0] === b[0] && a[1] === b[1] && a[2] === b[2] && a[3] === b[3]) ||
  (a[0] === b[2] && a[1] === b[3] && a[2] === b[0] && a[3] === b[1]);
for (const e of SIMPLE) {
  if (!edges.some(s => sameEdge(s, e))) throw new Error(`conexión simplificada ${e} no está en marca.svg`);
}

// Niveles de detalle: misma geometría, distinto grosor.
const VARIANTS = {
  completa: { stroke: lineWidth, fill: fillOpacity, edges, nodes: true, eyeR: eye.r, ring: eye.strokeWidth },
  simplificada: { stroke: 3.4, fill: 0, edges: SIMPLE, nodes: false, eyeR: 10, ring: 0 },
  minima: { stroke: 4.4, fill: 0, edges: [], nodes: false, eyeR: 12, ring: 0 },
};

// ---------- utilidades ----------
const f = n => Number(n.toFixed(3)).toString();
const outlineD = (T = p => p) => 'M' + outline.map(p => T(p).map(f).join(',')).join(' L') + ' Z';

/** Puntos con su "radio" de dibujo, para calcular cajas y encuadres. */
function extents(v) {
  const pts = [];
  const sw = v.stroke / 2;
  for (const p of outline) pts.push([p[0], p[1], v.nodes ? Math.max(sw, 3.2) : sw]);
  for (const e of v.edges) { pts.push([e[0], e[1], sw]); pts.push([e[2], e[3], sw]); }
  pts.push([eye.cx, eye.cy, v.eyeR + v.ring / 2]);
  return pts;
}
function bbox(v) {
  const p = extents(v);
  const x0 = Math.min(...p.map(q => q[0] - q[2])), x1 = Math.max(...p.map(q => q[0] + q[2]));
  const y0 = Math.min(...p.map(q => q[1] - q[2])), y1 = Math.max(...p.map(q => q[1] + q[2]));
  return { x: x0, y: y0, w: x1 - x0, h: y1 - y0, cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 };
}
/** Escala para que todo entre en un círculo de radio R alrededor del centro de la caja. */
function fitScale(v, R) {
  const b = bbox(v);
  const far = Math.max(...extents(v).map(([x, y, r]) => Math.hypot(x - b.cx, y - b.cy) + r));
  return { s: R / far, cx: b.cx, cy: b.cy };
}
const circleD = (cx, cy, r) => `M${f(cx - r)},${f(cy)} a${f(r)},${f(r)} 0 1,0 ${f(2 * r)},0 a${f(r)},${f(r)} 0 1,0 ${f(-2 * r)},0 Z`;

// ---------- 2. SVG de la marca ----------
let maskId = 0;
/**
 * Elementos de la marca en coordenadas de marca.svg.
 * mono = color único (monocromo): el ojo se separa de las líneas con un hueco en vez del aro.
 */
function markElements(v, mono = null) {
  const copper = mono ?? C.copper;
  const green = mono ?? C.green;
  const out = [];
  if (v.fill) out.push(`<path d="${outlineD()}" fill="${copper}" fill-opacity="${v.fill}"/>`);
  const lines = `<path d="${outlineD()}"/>` + v.edges.map(e =>
    `<line x1="${e[0]}" y1="${e[1]}" x2="${e[2]}" y2="${e[3]}"/>`).join('');
  const strokes = `<g stroke="${copper}" stroke-width="${v.stroke}" stroke-linecap="round" stroke-linejoin="round" fill="none">${lines}</g>`;
  const dots = v.nodes ? `<g fill="${copper}">` + nodes.map(n =>
    `<circle cx="${n.cx}" cy="${n.cy}" r="${n.r}"/>`).join('') + '</g>' : '';
  if (mono) {
    const id = `ojo${++maskId}`;
    const gap = v.ring ? v.eyeR + v.ring / 2 : v.eyeR + 1.6;
    out.push(`<mask id="${id}" maskUnits="userSpaceOnUse" x="-1000" y="-1000" width="3000" height="3000">` +
      `<rect x="-1000" y="-1000" width="3000" height="3000" fill="#fff"/>` +
      `<circle cx="${eye.cx}" cy="${eye.cy}" r="${gap}" fill="#000"/></mask>`);
    out.push(`<g mask="url(#${id})">${strokes}${dots}</g>`);
    out.push(`<circle cx="${eye.cx}" cy="${eye.cy}" r="${v.ring ? v.eyeR - v.ring / 2 : v.eyeR}" fill="${green}"/>`);
  } else {
    out.push(strokes, dots);
    out.push(v.ring
      ? `<circle cx="${eye.cx}" cy="${eye.cy}" r="${v.eyeR}" fill="${green}" stroke="${C.navy}" stroke-width="${v.ring}"/>`
      : `<circle cx="${eye.cx}" cy="${eye.cy}" r="${v.eyeR}" fill="${green}"/>`);
  }
  return out.filter(Boolean).join('\n  ');
}

function svgDoc({ x, y, w, h }, body, { title, bg = null } = {}) {
  const vb = [x, y, w, h].map(f).join(' ');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}" width="${f(w)}" height="${f(h)}">\n` +
    (title ? `  <title>${title}</title>\n` : '') +
    (bg ? `  <rect x="${f(x)}" y="${f(y)}" width="${f(w)}" height="${f(h)}" fill="${bg}"/>\n` : '') +
    `  ${body}\n</svg>\n`;
}
const padBox = (b, p) => ({ x: b.x - p, y: b.y - p, w: b.w + 2 * p, h: b.h + 2 * p });

// ---------- 3. texto en trazos ----------
const fontBuf = readFileSync(FONT_FILE);
const font = opentype.parse(fontBuf.buffer.slice(fontBuf.byteOffset, fontBuf.byteOffset + fontBuf.byteLength));
const capRatio = (font.tables.os2?.sCapHeight || font.unitsPerEm * 0.7) / font.unitsPerEm;

function textPath(str, size, x, baseline) {
  const glyphs = font.stringToGlyphs(str);
  const scale = size / font.unitsPerEm;
  const t = Math.tan(SLANT_DEG * Math.PI / 180);
  const cmds = [];
  let pen = x;
  glyphs.forEach((g, i) => {
    cmds.push(...g.getPath(pen, baseline, size).commands);
    pen += g.advanceWidth * scale + TRACKING_EM * size;
    if (i < glyphs.length - 1) pen += font.getKerningValue(g, glyphs[i + 1]) * scale;
  });
  // Inclinación: lo que está arriba de la línea de base se corre a la derecha.
  const sk = (X, Y) => X + (baseline - Y) * t;
  for (const c of cmds) {
    if ('x' in c) c.x = sk(c.x, c.y);
    if ('x1' in c) c.x1 = sk(c.x1, c.y1);
    if ('x2' in c) c.x2 = sk(c.x2, c.y2);
  }
  const p = new opentype.Path();
  p.commands = cmds;
  const bb = p.getBoundingBox();
  return { d: p.toPathData(2), box: { x: bb.x1, y: bb.y1, w: bb.x2 - bb.x1, h: bb.y2 - bb.y1 } };
}
const union = (a, b) => {
  const x = Math.min(a.x, b.x), y = Math.min(a.y, b.y);
  return { x, y, w: Math.max(a.x + a.w, b.x + b.w) - x, h: Math.max(a.y + a.h, b.y + b.h) - y };
};

function logo(layout, mono = null) {
  const v = VARIANTS.completa;
  const mb = bbox(v);
  let text;
  if (layout === 'horizontal') {
    const size = (0.4 * mb.h) / capRatio;
    text = textPath(NAME, size, mb.x + mb.w + 0.14 * mb.h, mb.cy + (0.4 * mb.h) / 2);
  } else {
    const probe = textPath(NAME, 100, 0, 0).box.w;
    const size = 100 * (1.15 * mb.w) / probe;
    const w = textPath(NAME, size, 0, 0).box.w;
    text = textPath(NAME, size, mb.cx - w / 2, mb.y + mb.h + 0.16 * mb.h + capRatio * size);
  }
  const box = padBox(union(mb, text.box), 0.08 * mb.h);
  const body = markElements(v, mono) + `\n  <path d="${text.d}" fill="${mono ?? C.text}"/>`;
  return { box, body };
}

// ---------- 4. vector drawables de Android ----------
function vectorDrawable({ variant, mono = false, size, R, color = null }) {
  const v = VARIANTS[variant];
  const { s, cx, cy } = fitScale(v, R);
  const c0 = size / 2;
  const T = ([x, y]) => [(x - cx) * s + c0, (y - cy) * s + c0];
  const copper = color ?? C.copper;
  const green = color ?? C.green;
  const strokeD = outlineD(T) + v.edges.map(e => {
    const a = T([e[0], e[1]]), b = T([e[2], e[3]]);
    return ` M${f(a[0])},${f(a[1])} L${f(b[0])},${f(b[1])}`;
  }).join('');
  const [ex, ey] = T([eye.cx, eye.cy]);
  const parts = [];
  if (v.fill && !mono) parts.push(`<path android:fillColor="${copper}" android:fillAlpha="${v.fill}" android:pathData="${outlineD(T)}"/>`);
  let net = `<path android:strokeColor="${copper}" android:strokeWidth="${f(v.stroke * s)}" ` +
    `android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="${strokeD}"/>`;
  if (v.nodes) net += nodes.map(n => {
    const [x, y] = T([n.cx, n.cy]);
    return `\n    <path android:fillColor="${copper}" android:pathData="${circleD(x, y, n.r * s)}"/>`;
  }).join('');
  if (mono) {
    // Hueco alrededor del ojo: recorte con un rectángulo horario y un círculo antihorario
    // (regla nonzero: el círculo queda afuera).
    const gap = (v.ring ? v.eyeR + v.ring / 2 : v.eyeR + 1.6) * s;
    const hole = `M0,0 H${size} V${size} H0 Z M${f(ex + gap)},${f(ey)} A${f(gap)},${f(gap)} 0 1,0 ${f(ex - gap)},${f(ey)} A${f(gap)},${f(gap)} 0 1,0 ${f(ex + gap)},${f(ey)} Z`;
    parts.push(`<group>\n    <clip-path android:pathData="${hole}"/>\n    ${net}\n  </group>`);
    parts.push(`<path android:fillColor="${green}" android:pathData="${circleD(ex, ey, (v.ring ? v.eyeR - v.ring / 2 : v.eyeR) * s)}"/>`);
  } else {
    parts.push(net);
    parts.push(`<path android:fillColor="${green}" android:pathData="${circleD(ex, ey, v.eyeR * s)}"/>`);
    if (v.ring) parts.push(`<path android:strokeColor="${C.navy}" android:strokeWidth="${f(v.ring * s)}" android:pathData="${circleD(ex, ey, v.eyeR * s)}"/>`);
  }
  return `<?xml version="1.0" encoding="utf-8"?>\n<!-- Generado por assets/brand/tools/build.mjs: no editar a mano. -->\n` +
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n` +
    `    android:width="${size}dp" android:height="${size}dp"\n` +
    `    android:viewportWidth="${size}" android:viewportHeight="${size}">\n  ${parts.join('\n  ')}\n</vector>\n`;
}

// ---------- 5. composiciones del ícono (lienzo adaptativo de 108) ----------
const SAFE_R = 32; // zona segura: círculo de 66 dp de diámetro (radio 33), con 1 dp de aire
function iconSvg(px, shape) {
  const variant = px < 48 ? 'minima' : 'simplificada';
  const v = VARIANTS[variant];
  const { s, cx, cy } = fitScale(v, SAFE_R);
  // Lo que muestra el launcher es el centro de 72×72 del lienzo de 108.
  const clip = shape === 'circle'
    ? '<clipPath id="forma"><circle cx="54" cy="54" r="36"/></clipPath>'
    : '<clipPath id="forma"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath>';
  const body = `<defs>${clip}</defs><g clip-path="url(#forma)">` +
    `<rect x="0" y="0" width="108" height="108" fill="${C.navy}"/>` +
    `<g transform="translate(54 54) scale(${f(s)}) translate(${f(-cx)} ${f(-cy)})">${markElements(v)}</g></g>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="${px}" height="${px}">${body}</svg>`;
}

function png(svg, width) {
  return new Resvg(svg, { fitTo: { mode: 'width', value: width }, font: { loadSystemFonts: false } }).render().asPng();
}

// ---------- 6. escribir todo ----------
const written = [];
const put = (path, data) => { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, data); written.push(path); };

// SVG de la marca
const vbMark = v => padBox(bbox(v), 6);
put(join(BRAND, 'marca-simplificada.svg'), svgDoc(vbMark(VARIANTS.simplificada), markElements(VARIANTS.simplificada), { title: 'CalandriApp — marca simplificada (ícono, < 128 px)' }));
put(join(BRAND, 'marca-minima.svg'), svgDoc(vbMark(VARIANTS.minima), markElements(VARIANTS.minima), { title: 'CalandriApp — marca mínima (< 48 px)' }));
for (const [tone, color] of [['claro', C.text], ['oscuro', C.navy]]) {
  put(join(BRAND, `marca-mono-${tone}.svg`), svgDoc(vbMark(VARIANTS.completa), markElements(VARIANTS.completa, color), { title: `CalandriApp — marca monocromo ${color}` }));
  put(join(BRAND, `marca-simplificada-mono-${tone}.svg`), svgDoc(vbMark(VARIANTS.simplificada), markElements(VARIANTS.simplificada, color), { title: `CalandriApp — marca simplificada monocromo ${color}` }));
}
// Logos
for (const layout of ['horizontal', 'vertical']) {
  const l = logo(layout);
  put(join(BRAND, `logo-${layout}.svg`), svgDoc(l.box, l.body, { title: `CalandriApp — logo ${layout}` }));
  for (const [tone, color] of [['claro', C.text], ['oscuro', C.navy]]) {
    const m = logo(layout, color);
    put(join(BRAND, `logo-${layout}-mono-${tone}.svg`), svgDoc(m.box, m.body, { title: `CalandriApp — logo ${layout} monocromo ${color}` }));
  }
  // PNG con fondo, para presentaciones
  put(join(PNG_DIR, `logo-${layout}-1600.png`), png(svgDoc(l.box, l.body, { bg: C.navy }), 1600));
  put(join(PNG_DIR, `logo-${layout}-transparente-1600.png`), png(svgDoc(l.box, l.body), 1600));
}
// PNG de la marca
for (const w of [128, 256, 512, 1024]) put(join(PNG_DIR, `marca-${w}.png`), png(svgDoc(vbMark(VARIANTS.completa), markElements(VARIANTS.completa)), w));
for (const w of [48, 64, 96]) put(join(PNG_DIR, `marca-simplificada-${w}.png`), png(svgDoc(vbMark(VARIANTS.simplificada), markElements(VARIANTS.simplificada)), w));
for (const w of [16, 24, 32]) put(join(PNG_DIR, `marca-minima-${w}.png`), png(svgDoc(vbMark(VARIANTS.minima), markElements(VARIANTS.minima)), w));
// Ícono: tienda (512, sin transparencia) y vistas previas
put(join(PNG_DIR, 'icono-tienda-512.png'), png(iconSvg(512, 'square').replace('rx="16"', 'rx="0"'), 512));
for (const px of [48, 96, 192]) put(join(PNG_DIR, `vista-previa-${px}.png`), png(iconSvg(px, 'circle'), px));

// Hoja de vista previa: 48, 96 y 192 px a tamaño real, sobre fondo oscuro y claro.
{
  const sizes = [48, 96, 192];
  const gap = 32, w = sizes.reduce((a, b) => a + b, 0) + gap * (sizes.length + 1), rowH = 192 + 2 * gap;
  let x = gap, imgs = '';
  for (const px of sizes) {
    const data = png(iconSvg(px, 'circle'), px).toString('base64');
    for (const row of [0, 1]) imgs += `<image href="data:image/png;base64,${data}" x="${x}" y="${row * rowH + gap + (192 - px) / 2}" width="${px}" height="${px}"/>`;
    x += px + gap;
  }
  const sheet = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${2 * rowH}" width="${w}" height="${2 * rowH}">` +
    `<rect width="${w}" height="${rowH}" fill="#202124"/><rect y="${rowH}" width="${w}" height="${rowH}" fill="#E8EAED"/>${imgs}</svg>`;
  put(join(PNG_DIR, 'vista-previa-hoja.png'), png(sheet, w));
}

// Android: ícono adaptativo, monocromo, splash, mipmaps
put(join(RES, 'drawable', 'ic_launcher_foreground.xml'), vectorDrawable({ variant: 'simplificada', size: 108, R: SAFE_R }));
put(join(RES, 'drawable', 'ic_launcher_monochrome.xml'), vectorDrawable({ variant: 'simplificada', size: 108, R: SAFE_R, mono: true, color: '#FFFFFF' }));
put(join(RES, 'drawable', 'ic_launcher_background.xml'),
  `<?xml version="1.0" encoding="utf-8"?>\n<!-- Generado por assets/brand/tools/build.mjs: no editar a mano. -->\n` +
  `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp" android:height="108dp"\n` +
  `    android:viewportWidth="108" android:viewportHeight="108">\n  <path android:fillColor="${C.navy}" android:pathData="M0,0h108v108h-108z"/>\n</vector>\n`);
// Splash: ícono sin fondo de 288 dp que tiene que entrar en un círculo de 192 dp. Versión completa.
put(join(RES, 'drawable', 'ic_splash.xml'), vectorDrawable({ variant: 'completa', size: 288, R: 92 }));
const adaptive = `<?xml version="1.0" encoding="utf-8"?>\n<!-- Generado por assets/brand/tools/build.mjs: no editar a mano. -->\n` +
  `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n` +
  `    <background android:drawable="@drawable/ic_launcher_background" />\n` +
  `    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n` +
  `    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n`;
put(join(RES, 'mipmap-anydpi', 'ic_launcher.xml'), adaptive);
put(join(RES, 'mipmap-anydpi', 'ic_launcher_round.xml'), adaptive);
const DENSITIES = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [d, px] of Object.entries(DENSITIES)) {
  for (const old of ['ic_launcher.webp', 'ic_launcher_round.webp']) {
    const p = join(RES, `mipmap-${d}`, old);
    if (existsSync(p)) rmSync(p);
  }
  put(join(RES, `mipmap-${d}`, 'ic_launcher.png'), png(iconSvg(px, 'square'), px));
  put(join(RES, `mipmap-${d}`, 'ic_launcher_round.png'), png(iconSvg(px, 'circle'), px));
}

// Intro animada de la app: geometría en Kotlin y el nombre como vector.
{
  const pts = [...nodes.map(n => [n.cx, n.cy]), [eye.cx, eye.cy]];
  const idx = ([x, y]) => {
    const i = pts.findIndex(p => p[0] === x && p[1] === y);
    if (i < 0) throw new Error(`punto ${x},${y} no es un nodo`);
    return i;
  };
  const b = bbox(VARIANTS.completa);
  const kt = `// Generado por assets/brand/tools/build.mjs desde assets/brand/marca.svg: no editar a mano.
package io.github.protohommer.meshpaper.ui.brand

/** Geometría de la marca (coordenadas de marca.svg) para animarla. */
internal object BrandGeometry {
    /** Caja de la marca completa: x, y, ancho, alto. */
    val box = floatArrayOf(${[b.x, b.y, b.w, b.h].map(v => f(v) + 'f').join(', ')})

    /** Nodos (x, y); el último es el ojo. */
    val points = floatArrayOf(${pts.map(p => `${p[0]}f, ${p[1]}f`).join(', ')})
    const val EYE = ${pts.length - 1}

    /** Contorno, como índices de [points]. */
    val outline = intArrayOf(${outline.map(p => idx(p)).join(', ')})

    /** Conexiones internas, de a pares de índices. */
    val edges = intArrayOf(${edges.map(e => `${idx([e[0], e[1]])}, ${idx([e[2], e[3]])}`).join(', ')})

    const val STROKE = ${lineWidth}f
    const val NODE_R = ${nodes[0].r}f
    const val EYE_R = ${eye.r}f
    const val EYE_RING = ${eye.strokeWidth}f
    const val FILL_ALPHA = ${fillOpacity}f
}
`;
  put(join(ROOT, 'app', 'src', 'main', 'java', 'io', 'github', 'protohommer', 'meshpaper', 'ui', 'brand', 'BrandGeometry.kt'), kt);

  const name = textPath(NAME, 100, 0, 100);
  const nb = padBox(name.box, 2);
  const tx = -nb.x, ty = -nb.y;
  const nameVector = `<?xml version="1.0" encoding="utf-8"?>\n<!-- Generado por assets/brand/tools/build.mjs: no editar a mano. -->\n` +
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"\n` +
    `    android:width="${f(nb.w)}dp" android:height="${f(nb.h)}dp"\n` +
    `    android:viewportWidth="${f(nb.w)}" android:viewportHeight="${f(nb.h)}">\n` +
    `  <group android:translateX="${f(tx)}" android:translateY="${f(ty)}">\n` +
    `    <path android:fillColor="${C.text}" android:pathData="${name.d}"/>\n  </group>\n</vector>\n`;
  put(join(RES, 'drawable', 'brand_nombre.xml'), nameVector);
}

console.log(`Listo: ${written.length} archivos.`);
for (const p of written) console.log('  ' + p.slice(ROOT.length + 1).replaceAll('\\', '/'));
