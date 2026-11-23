# Marca CalandriApp

Una calandria dibujada como una malla de nodos conectados. El ojo es el nodo principal, en verde.

## Fuente de verdad

`marca.svg` es la única fuente. De ahí el script lee el contorno, las conexiones, los nodos y el
ojo. **No cambiar coordenadas, nodos ni conexiones**: todo lo demás se genera escalándola.
Entre los niveles de detalle cambian solo los grosores de línea y el radio del ojo.

## Paleta

| Uso | Color |
|---|---|
| Fondo azul marino | `#14223A` |
| Red / nodos (cobre) | `#C98245` |
| Ojo / nodo principal | `#268647` |
| Texto | `#F3F5F6` |

En la app están como `BrandNavy`, `BrandCopper`, `BrandGreen` y `BrandText`
(`ui/theme/Color.kt`) y como `@color/brand_*` (`res/values/colors.xml`).

## Tipografía

"CalandriApp" en **Space Grotesk SemiBold** (Google Fonts, licencia OFL), inclinada 12° y con
interletrado de −0,02 em. Space Grotesk no tiene cursiva propia: la inclinación la hace el script.
En los SVG el texto va **convertido a trazos**, así que no hace falta tener la fuente instalada.

## Niveles de detalle

| Archivo | Qué tiene | Para |
|---|---|---|
| `marca.svg` | todos los nodos y conexiones | 128 px o más |
| `marca-simplificada.svg` | contorno, 7 conexiones internas y el ojo | ícono de la app, de 48 a 128 px |
| `marca-minima.svg` | solo el contorno y el ojo | menos de 48 px |

## Archivos

- Marca: `marca.svg`, `marca-simplificada.svg`, `marca-minima.svg`
- Logos (marca + nombre): `logo-horizontal.svg`, `logo-vertical.svg`
- Monocromos, en `#F3F5F6` (`-mono-claro`) y en `#14223A` (`-mono-oscuro`), sobre transparente:
  `marca-mono-*.svg`, `marca-simplificada-mono-*.svg`, `logo-horizontal-mono-*.svg`, `logo-vertical-mono-*.svg`.
  En los monocromos el ojo se separa de las líneas con un hueco, en lugar del aro azul.
- `png/`: marca en 128, 256, 512 y 1024; simplificada en 48, 64 y 96; mínima en 16, 24 y 32;
  logos en 1600 de ancho (con fondo y transparentes); `icono-tienda-512.png` (sin transparencia,
  para Google Play) y las vistas previas del ícono (`vista-previa-*.png`).

En Android (`app/src/main/res`) el script genera:

- `drawable/ic_launcher_foreground.xml`: la marca simplificada dentro de la zona segura (66 de 108 dp).
- `drawable/ic_launcher_background.xml`: fondo `#14223A`.
- `drawable/ic_launcher_monochrome.xml`: para los íconos temáticos de Android 13+.
- `drawable/ic_splash.xml`: la marca completa para la pantalla de inicio.
- `mipmap-anydpi/ic_launcher*.xml`: el ícono adaptativo.
- `mipmap-*dpi/ic_launcher*.png`: de 48 a 192 px, cuadrado redondeado y redondo.

**Esos archivos no se editan a mano**: se regeneran con el script.

## Regenerar todo (Windows 11)

Hace falta Node.js 18 o más nuevo.

```powershell
cd assets\brand\tools
npm install      # la primera vez: resvg, opentype.js y la fuente Space Grotesk
npm run brand
```

El script (`tools/build.mjs`) usa:

- `@resvg/resvg-js` para pasar SVG a PNG; los PNG salen sin metadatos.
- `opentype.js` para convertir el texto en trazos.
- `@fontsource/space-grotesk` para la fuente.

Las versiones quedan fijas en `tools/package.json` y en `package-lock.json`. Después de
regenerar, compilá la app (`./gradlew assembleDebug`) para ver el ícono y el splash.

## Pantalla de inicio

Usa `androidx.core:core-splashscreen`, que la hace igual en Android 8 a 16: fondo `#14223A` y la
calandria completa centrada (tema `Theme.CalandriApp.Arranque` en `res/values/themes.xml`).
