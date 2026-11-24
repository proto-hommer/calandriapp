<p align="center"><img src="docs/img/banner.png" alt="CalandriApp: mesh, LoRa, offline" width="100%"/></p>

**Una app Android chica y propia para usar nodos Meshtastic en el campo: mensajes, mapa offline y
seguimiento de un grupo cerrado, sin cuentas, sin nube y sin internet.**

Versión **1.5.2** | Android 8 o más nuevo | *powered by Meshtastic®*

[![Descargar APK](https://img.shields.io/github/v/release/proto-hommer/calandriapp?label=descargar%20APK&color=2ea44f)](https://github.com/proto-hommer/calandriapp/releases/latest)

**Bajás el APK, lo instalás, emparejás tu nodo por Bluetooth y tenés a tu grupo en el mapa.**

<p align="center">
  <img src="docs/img/intro-nodos.png" alt="Intro: se escanea el relieve y aparecen los nodos" width="23%"/>
  &nbsp;
  <img src="docs/img/intro-calandria.png" alt="Intro: los nodos forman la calandria" width="23%"/>
  &nbsp;
  <img src="docs/img/ajustes.png" alt="Ajustes del nodo conectado" width="23%"/>
  &nbsp;
  <img src="docs/img/acerca.png" alt="Acerca de y licencias" width="23%"/>
</p>
<p align="center"><i>La intro (se escanea el relieve, los nodos aparecen en el mapa y se juntan formando la calandria), los ajustes del nodo y el "acerca de".</i></p>

* * *

<p><img src="docs/img/titulos/que-es-esto.png" alt="Qué es esto" width="100%"/></p>

CalandriApp habla con un nodo [Meshtastic](https://meshtastic.org) por Bluetooth y usa la API pública
de Meshtastic. **No es un fork de la app oficial**: es una app más simple, hecha para un uso concreto,
trabajo de campo con un grupo cerrado:

Las capturas usan un **grupo de ejemplo inventado** (una expedición en el valle de Calamuchita): ni los
nodos ni las posiciones son de nadie real.

<p><img src="docs/img/titulos/s-el-mapa-y-los-nodos.png" alt="El mapa y los nodos" height="26"/></p>

<p align="center">
  <img src="docs/img/mapa-noche.png" alt="Mapa con el grupo, los repetidores, rastros y waypoints" width="32%"/>
  &nbsp;
  <img src="docs/img/mapa-detalle.png" alt="Detalle de los íconos de los nodos" width="62%"/>
</p>

**Mapa offline** con los nodos del grupo, su rastro (la línea que dejan al moverse) y los waypoints.
Cada nodo se lee de un vistazo por su **color**, su **forma** y su **borde**:

| Cómo se ve | Qué es | En el ejemplo |
|---|---|---|
| Círculo **verde** | Del grupo, se lo oye directo o a 1 salto | ANA |
| Círculo **amarillo** | Del grupo, a 2 o 3 saltos | JUAN |
| Círculo **naranja** | Del grupo, a 4 o 5 saltos | SOFI |
| Borde **rojo punteado** | Del grupo, perdido: no se lo oye hace rato | TOMI |
| **Rombo azul** | Fijo del grupo (router, base), con el borde de la señal por fuera | BASE |
| Nombre **violeta** | Ajeno (no es del grupo): solo sirve de repetidor | SRC2, CHMP |
| **Rombo** | Nodo fijo (router, base, repetidor), del grupo o ajeno | CHMP, VGB1 |
| Retícula **cian** | Vos: el teléfono y el nodo conectado | EX1 |
| Emoji con un palito | Waypoint (campamento, peligro, auto…) | ⛺ ⚠️ 🚗 |

Los ajenos que no se oyen hace más de 30 minutos se ocultan del mapa hasta que vuelven a oírse. La
leyenda de abajo a la izquierda se puede achicar.

<p><img src="docs/img/titulos/s-la-ficha-de-un-nodo.png" alt="La ficha de un nodo" height="26"/></p>

<p align="center">
  <img src="docs/img/ficha-nodo.png" alt="Ficha de un nodo del grupo" width="32%"/>
  &nbsp;
  <img src="docs/img/nodos.png" alt="Lista de nodos" width="32%"/>
  &nbsp;
  <img src="docs/img/waypoint.png" alt="Ficha de un waypoint" width="32%"/>
</p>

Tocás un nodo y aparece su ficha: hace cuánto se lo oyó, el enlace (saltos o SNR), la batería, a qué
distancia y en qué dirección está, su rastro y el modelo de placa. Desde ahí le escribís, vas hacia él
(**IR**), ves el perfil del terreno o cargás los datos de su antena. En la pestaña **Nodos** está la
misma información en lista: primero los del grupo y después los repetidores y otros. Mantené apretado
un nodo de la lista para verlo en el mapa. Los waypoints tienen su propia ficha (quién lo puso, si vence,
a qué distancia está) y también se puede ir hacia ellos; se crean con un toque largo en el mapa.

<p><img src="docs/img/titulos/s-ir-a-un-nodo-o-waypoint.png" alt="Ir a un nodo o waypoint" height="26"/></p>

<p align="center">
  <img src="docs/img/ir-a.png" alt="Ruta a pie hasta un nodo, por senderos" width="32%"/>
  &nbsp;
  <img src="docs/img/rutas-ajustes.png" alt="Ajustes: mapas y rutas sin conexión" width="32%"/>
</p>

Sin salir de la app: tocás **IR** en la ficha de un nodo o de un waypoint y la app te marca el camino
**por calles y senderos**, a pie, en bici o en auto, con lo que falta, el tiempo estimado, el rumbo y
cuánto sube. Si te salís del camino recalcula, y si el destino es un nodo que se mueve, lo sigue. Al
llegar te avisa.

Funciona **sin señal**: las rutas se calculan en el teléfono con [BRouter](https://github.com/abrensch/brouter).
Los datos de cada zona se bajan una sola vez (Córdoba y alrededores son unos 11 MB) desde el mismo panel
o desde Ajustes → Rutas sin conexión. Sin esos datos, muestra la línea recta con la distancia y el
rumbo. **GUARDAR EL CAMINO** baja además el relieve (y el mapa, si la fuente lo permite) de los
cuadrantes por donde pasa la ruta.

<p><img src="docs/img/titulos/s-alcance-y-perfil-del-terreno.png" alt="Alcance y perfil del terreno" height="26"/></p>

<p align="center">
  <img src="docs/img/alcance.png" alt="Alcance estimado de un nodo fijo" width="32%"/>
  &nbsp;
  <img src="docs/img/perfil-pasa.png" alt="Perfil del terreno: el enlace pasa" width="32%"/>
  &nbsp;
  <img src="docs/img/perfil-no-pasa.png" alt="Perfil del terreno: el enlace no pasa" width="32%"/>
</p>

- **Alcance estimado**: con un toque largo sobre un nodo al que le cargaste los datos de la antena,
  se dibujan dos círculos: el alcance en ciudad (lleno) y a campo abierto (punteado). La altura de la
  antena puede salir sola del GPS del nodo (su altitud menos el terreno), si la manda.
- **Perfil del terreno** entre vos y cualquier nodo: el corte del relieve, la línea de vista, la zona de
  Fresnel (las líneas punteadas) y si el enlace **pasa, está al límite o no pasa**, con el margen en
  dB. En el ejemplo, la base pasa con +17 dB aunque un cerro roza la línea de vista; JUAN, del otro lado
  de las sierras, no pasa. Usa el relieve del SRTM (≈30 m), que se baja por zonas y queda guardado.

<p><img src="docs/img/titulos/s-mensajes.png" alt="Mensajes" height="26"/></p>

<p align="center">
  <img src="docs/img/chat.png" alt="Chat del grupo con respuestas y saltos" width="32%"/>
</p>

Al grupo, al canal público y directos, con confirmación de entrega y reintento. Cada mensaje dice
cuántos **saltos** dio ("directo", "2 saltos"…). Mantené apretado un mensaje para **responderlo**: la
respuesta lleva la cita arriba. Llegan **avisos** dentro de la app y notificaciones con la app cerrada
o la pantalla apagada: la conexión sigue en segundo plano y se reconecta sola si se corta.

<p><img src="docs/img/titulos/s-grupo-cerrado.png" alt="Grupo cerrado" height="26"/></p>

<p align="center">
  <img src="docs/img/grupo.png" alt="Pestaña Grupo con el QR para sumar a otros" width="32%"/>
</p>

En dos toques crea un canal privado con clave AES-256 y lo comparte por **QR**, en el formato estándar
de Meshtastic (sirve también con la app oficial). El nombre del grupo se puede cambiar después: los
demás vuelven a escanear el QR. El QR de la captura es del grupo inventado.

<p><img src="docs/img/titulos/s-mapas-sin-conexion.png" alt="Mapas sin conexión" height="26"/></p>

<p align="center">
  <img src="docs/img/cuadricula.png" alt="Cuadrícula de mapas sin conexión" width="32%"/>
</p>

En Ajustes → Mapas sin conexión hay una cuadrícula sobre el mapa (1-A, 2-B…): **verde** lo bajado,
**amarillo** lo que se está bajando, **naranja** lo que está a medias y **rojo** lo que falta. Tocás
los cuadrantes que querés (quedan con borde cian) y se baja todo junto: el relieve y, si la fuente lo
permite, el mapa.

<p><img src="docs/img/titulos/s-modo-dia.png" alt="Modo día" height="26"/></p>

<p align="center">
  <img src="docs/img/modo-dia.png" alt="Modo día: mapa" width="32%"/>
  &nbsp;
  <img src="docs/img/modo-dia-nodos.png" alt="Modo día: lista de nodos" width="32%"/>
</p>

En Ajustes → Apariencia: **NOCHE** (el HUD oscuro de siempre), **DÍA** ("neón de día": vidrio claro
translúcido y bordes que brillan, para leer al sol) o **AUTO** (según el tema del teléfono).

<p><img src="docs/img/titulos/s-y-ademas.png" alt="Y además" height="26"/></p>

- **Posición del celular** al grupo a través del nodo, para nodos sin GPS.
- **Ajustes del nodo conectado**: nombre, rol, posición, radio, canales y reinicio. Avisa si algo
  quedó mal configurado (por ejemplo, la radio en modo manual, que deja de ver a los nodos ajenos).
- **Se actualiza sola** desde los releases de este repo, verificando la firma.

La calandria es un pájaro cantor de acá. En el logo está dibujada como una malla de nodos
conectados, y el ojo es el nodo principal.

* * *

<p><img src="docs/img/titulos/por-que-se-hizo.png" alt="Por qué se hizo" width="100%"/></p>

Arrancó con el [Mesh Paper 1.54](https://github.com/proto-hommer/mesh-paper-154), un nodo
Meshtastic de bolsillo con pantalla e-paper. Ese nodo **no tiene GPS**: su posición la tiene que
mandar el celular. Y en el campo, lo que hacía falta era poco y concreto:

- saber **dónde está cada uno del grupo** y si se lo está oyendo bien;
- **mandarse mensajes** sin depender de la señal del celular;
- tener el **mapa sin internet**;
- que los nodos de afuera sirvan **solo de repetidores**, sin mezclarse con el grupo.

La app oficial hace muchísimo más, pero para esto sobraba. CalandriApp hace eso, de forma simple y
sin cuentas, sin nube ni servicios de Google: todo queda en el teléfono.

**Es libre y gratis: la puede bajar y usar el que quiera**, con su propio nodo y su propio grupo.

* * *

<p><img src="docs/img/titulos/descargar-e-instalar.png" alt="Descargar e instalar" width="100%"/></p>

1. Bajá el `CalandriApp-1.5.2.apk` de la [última versión](https://github.com/proto-hommer/calandriapp/releases/latest).
2. Abrilo en el celular. Android va a pedir permiso para **instalar apps de origen desconocido**:
   dáselo al navegador o al gestor de archivos con el que lo abriste.
3. Abrí CalandriApp, dale los permisos de **Bluetooth** y **ubicación**, tocá **BUSCAR** y
   **EMPAREJAR** tu nodo. El PIN por defecto es `123456`, o el que muestre la pantalla del nodo.
4. En **Grupo**, creá un grupo nuevo o escaneá el QR de alguien que ya lo tenga.

La próxima vez se conecta sola al último nodo.

**Si no aparece el nodo:** en Android 8 a 11 hay que tener **prendida la ubicación** del teléfono para
buscar por Bluetooth (la app avisa). Y el nodo no se anuncia mientras está conectado a otra app, como
la oficial de Meshtastic: cerrala o desconectalo de ahí. Si ya lo emparejaste desde los ajustes de
Android, aparece igual en la lista con el botón CONECTAR.

<p><img src="docs/img/titulos/s-con-que-nodos-anda.png" alt="Con qué nodos anda" height="26"/></p>

Con **cualquier nodo Meshtastic que tenga Bluetooth**: Heltec, LilyGO (T-Beam, T-Echo, T-Deck),
RAK, Seeed, ThinkNode, Station G2 y el resto de los 154 modelos que conoce Meshtastic, en
cualquier región. Conviene firmware **2.5 o más nuevo**: la app avisa si el del nodo es más viejo.

Probada en un Motorola moto e22 (Android 12) conectada a un nodo Seeed XIAO ESP32-S3 + Wio-SX1262,
con un Mesh Paper 1.54 en el grupo, en la región ANZ (Argentina).

Por ahora **solo por Bluetooth**: los nodos que solo se conectan por WiFi o USB (por ejemplo, con
RP2040 o `meshtasticd` en Linux) todavía no.

* * *

<p><img src="docs/img/titulos/el-grupo-cerrado.png" alt="El grupo cerrado" width="100%"/></p>

La app arma el grupo como lo recomienda la documentación de Meshtastic:

- **Canal principal = privado del grupo**, con clave AES-256 aleatoria. La posición y la telemetría
  salen por ahí, cifradas.
- **Secundario = canal público** (LongFast), para hablar con gente de afuera.
- **Slot de frecuencia fijo** (el 20 en ANZ con LongFast), para que los nodos públicos nos sigan
  oyendo y repitiendo aunque el canal principal tenga otro nombre.
- Se comparte con la **URL / QR estándar de Meshtastic**: cada miembro puede usar esta app o la oficial.

Un nodo cuenta como **del grupo** cuando alguna vez se lo oyó por el canal privado, es decir, cuando
tiene la clave. Los demás se muestran como repetidores.

* * *

<p><img src="docs/img/titulos/como-se-hizo.png" alt="Cómo se hizo" width="100%"/></p>

Por etapas, compilando y probando cada una en un teléfono real antes de pasar a la siguiente:

1. **Bluetooth**: conectar, emparejar con PIN, leer la configuración y listar los nodos.
2. **Grupo**: crear el canal privado con el slot fijado, compartirlo y unirse por QR.
3. **Mensajes** de canal y directos, con confirmación de entrega y reintento.
4. **Posición del celular** al nodo, periódica y por el canal privado.
5. **Mapa offline** con osmdroid: nodos del grupo con su rastro y waypoints.

Después vinieron:

- la interfaz tipo HUD, con el mapa como pantalla principal;
- los colores por saltos y el anillo de señal de los nodos ajenos;
- la pestaña de ajustes del nodo y el estimador de alcance;
- el soporte de todas las regiones y placas;
- la marca, con el ícono adaptativo y la intro animada;
- el aspecto neón, con paneles de vidrio esmerilado sobre el mapa.

Algunas cosas que salieron en el camino:

- **El canal 0 no siempre es "el grupo".** El nodo deja en canal 0 a los nodos de los que nunca
  recibió el nombre, y los mensajes directos cifrados con clave pública también llegan con canal 0.
  Por eso la app solo cuenta como del grupo a quien se oyó por el canal privado de verdad.
- **El mapa respeta las reglas de OpenStreetMap**: muestra su atribución, respeta la caché de sus
  servidores y no baja zonas enteras de ellos. Para usarlo sin conexión, se importa un `.mbtiles` o
  se pone un servidor de mapas propio.
- **La marca se genera sola**: el ícono en todas las densidades, el monocromo de Android 13, los
  logos y la geometría de la intro salen de un único `marca.svg`, con un script
  (ver [`assets/brand/README.md`](assets/brand/README.md)).

* * *

<p><img src="docs/img/titulos/privacidad.png" alt="Privacidad" width="100%"/></p>

- Sin cuentas, sin nube, sin Google Play Services, sin analíticas.
- Los mensajes, rastros, waypoints y datos de antenas quedan **solo en el teléfono**.
- La ubicación del celular se usa para el mapa y, si lo dejás prendido, para pasársela al nodo, que
  la manda **cifrada por el canal privado del grupo**.
- Lo único que sale a internet son los tiles del mapa y del relieve, si tenés "usar internet" prendido,
  los datos de rutas de una zona cuando los pedís (de brouter.de: el servidor solo sabe qué cuadro de
  5° bajaste), y, al abrir la app, una consulta a GitHub para ver si hay una versión nueva (se apaga en
  Ajustes → Actualizaciones). Las actualizaciones se bajan de los releases de este repo y solo se
  instalan si están firmadas con el mismo certificado y coinciden con su SHA-256.

* * *

<p><img src="docs/img/titulos/lo-que-viene.png" alt="Lo que viene" width="100%"/></p>

- Conexión por **WiFi** y por **USB**, para nodos sin Bluetooth.
- Paleta de la marca (cobre y verde) en toda la interfaz.
- Alcance según el terreno: en vez de un círculo, la zona que cubre un nodo y la que queda en
  sombra detrás de los cerros.

* * *

<p><img src="docs/img/titulos/compilar-desde-el-codigo.png" alt="Compilar desde el código" width="100%"/></p>

Hace falta Android Studio, o su JDK, y el submódulo de los protobufs de Meshtastic:

```
git clone --recurse-submodules https://github.com/proto-hommer/calandriapp.git
cd calandriapp
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`. Para una versión de publicación firmada con tu propia
clave, ver el comentario sobre la firma en `app/build.gradle.kts`.

* * *

<p><img src="docs/img/titulos/creditos-marcas-y-licencias.png" alt="Créditos, marcas y licencias" width="100%"/></p>

- **powered by Meshtastic®.** Meshtastic® es una marca registrada de Meshtastic LLC. Los componentes de
  software de Meshtastic se publican bajo distintas licencias; ver [GitHub](https://github.com/meshtastic)
  para más detalles. CalandriApp es una app independiente: **no es la app oficial ni está patrocinada
  ni respaldada por el proyecto Meshtastic**. Sin garantía: se usa bajo tu propio riesgo.
- Datos del mapa © colaboradores de [OpenStreetMap](https://www.openstreetmap.org/copyright),
  disponibles bajo la licencia ODbL.
- Rutas calculadas en el teléfono con [BRouter](https://github.com/abrensch/brouter) (MIT), con datos
  de OpenStreetMap preparados por brouter.de.
- Relieve: SRTM, cortesía del U.S. Geological Survey, a través de
  [Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) (AWS Open Data, Mapzen / Tilezen).
- Usa los [protobufs de Meshtastic](https://github.com/meshtastic/protobufs) (GPL-3.0),
  [osmdroid](https://github.com/osmdroid/osmdroid), [ZXing](https://github.com/zxing/zxing) y
  [zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache-2.0),
  [Haze](https://github.com/chrisbanes/haze) (Apache-2.0),
  Protocol Buffers (BSD-3-Clause), AndroidX / Jetpack Compose y Kotlin (Apache-2.0). El logo usa
  la tipografía Space Grotesk (OFL-1.1).

<p><img src="docs/img/titulos/licencia.png" alt="Licencia" width="100%"/></p>

CalandriApp es software libre bajo la **[GNU GPL v3](LICENSE)**: la podés usar, estudiar, compartir
y modificar, siempre que lo que distribuyas también sea libre y con su código.
